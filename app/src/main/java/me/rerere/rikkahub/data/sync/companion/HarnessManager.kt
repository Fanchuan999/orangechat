/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync.companion

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.rikkahub.data.datastore.HarnessSnapshot
import me.rerere.rikkahub.data.datastore.HarnessStatus
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.OkHttpClient
import okhttp3.Request

internal fun classifyHarness(
    installed: Boolean,
    processRunning: Boolean,
    httpHealthy: Boolean,
    detail: String,
): HarnessStatus = when {
    !installed -> HarnessStatus.NOT_INSTALLED
    processRunning && httpHealthy -> HarnessStatus.RUNNING
    processRunning || httpHealthy -> HarnessStatus.ERROR
    detail.startsWith("error:", ignoreCase = true) -> HarnessStatus.ERROR
    else -> HarnessStatus.STOPPED
}

internal fun shouldRecover(
    autoKeepRunning: Boolean,
    manuallyStopped: Boolean,
    running: Boolean,
): Boolean = decideHarnessRecovery(
    autoKeepRunning = autoKeepRunning,
    manuallyStopped = manuallyStopped,
    running = running,
) == HarnessRecoveryDecision.RECOVER

internal fun redactHarnessLog(text: String): String {
    val patterns = listOf(
        Regex("(?im)^(\\s*Authorization\\s*:).*$"),
        Regex("(?im)^(\\s*Cookie\\s*:).*$"),
        Regex("(?im)^(\\s*API_KEY\\s*=).*$"),
        Regex("(?im)^(\\s*apiKey\\s*:).*$"),
        Regex("(?im)^(\\s*token\\s*:).*$"),
    )
    return patterns.fold(text) { redacted, pattern ->
        pattern.replace(redacted) { match -> "${match.groupValues[1]} [REDACTED]" }
    }
}

internal fun boundedHarnessLog(text: String): String {
    var bounded = text.lineSequence().toList().takeLast(MAX_LOG_LINES).joinToString("\n")
    while (bounded.toByteArray(Charsets.UTF_8).size > MAX_LOG_BYTES && bounded.isNotEmpty()) {
        bounded = bounded.drop((bounded.length / 16).coerceAtLeast(1))
    }
    return bounded
}

class HarnessManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val termuxConfigBridge: TermuxConfigBridge,
    sharedHttpClient: OkHttpClient,
) {
    private val healthClient = sharedHttpClient.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .build()
    private val initialSetting = settingsStore.settingsFlow.value.harnessSetting
    private val _snapshot = MutableStateFlow(
        HarnessSnapshot(
            status = if (initialSetting.installedVersion.isBlank()) {
                HarnessStatus.NOT_INSTALLED
            } else {
                HarnessStatus.STOPPED
            },
            installedVersion = initialSetting.installedVersion,
        )
    )
    val snapshot: StateFlow<HarnessSnapshot> = _snapshot.asStateFlow()

    suspend fun inspect(): HarnessSnapshot {
        val resultFile = resultFile("inspect")
        resultFile.delete()
        val probeFailure = runCatching {
            termuxConfigBridge.executeCommandsAndWait(
                commands = listOf(probeCommand(resultFile)),
                completionFile = resultFile,
                timeoutMessage = "Harness 状态检查没有在 15 秒内完成。",
                waitAttempts = QUICK_WAIT_ATTEMPTS,
            )
        }.exceptionOrNull()

        val probe = if (probeFailure == null) {
            parseProbe(resultFile.readText())
        } else {
            HarnessProbe(
                installed = settingsStore.settingsFlow.value.harnessSetting.installedVersion.isNotBlank(),
                detail = probeFailure.message.orEmpty(),
            )
        }
        resultFile.delete()

        val httpHealthy = isHttpHealthy()
        val status = if (probeFailure != null && probe.installed) {
            HarnessStatus.ERROR
        } else {
            classifyHarness(
                installed = probe.installed,
                processRunning = probe.processRunning,
                httpHealthy = httpHealthy,
                detail = probe.detail,
            )
        }
        val next = HarnessSnapshot(
            status = status,
            installedVersion = probe.version.ifBlank {
                settingsStore.settingsFlow.value.harnessSetting.installedVersion
            },
            detail = probe.detail,
            logTail = boundedHarnessLog(redactHarnessLog(probe.detail)),
        )
        _snapshot.value = next
        return next
    }

    suspend fun install(): HarnessSnapshot {
        val resultFile = resultFile("setup")
        resultFile.delete()
        try {
            termuxConfigBridge.executeCommandsAndWait(
                commands = HarnessScripts.bootstrapCommands(resultFile.absolutePath),
                completionFile = resultFile,
                timeoutMessage = "Harness 在三分钟内没有准备好，请查看 ~/daddy-harness/setup.log。",
                waitAttempts = HARNESS_INSTALL_WAIT_ATTEMPTS,
            )
            require(resultFile.readText().trim() == READY_MARKER) {
                setupFailureMessage(resultFile.readText())
            }
            settingsStore.update { settings ->
                settings.copy(
                    harnessSetting = settings.harnessSetting.copy(
                        autoKeepRunning = true,
                        manuallyStopped = false,
                        installedVersion = HarnessScripts.VERSION,
                    )
                )
            }
        } finally {
            resultFile.delete()
        }
        return inspect()
    }

    suspend fun start(): HarnessSnapshot = runLifecycle(
        command = HarnessScripts.startCommand(),
        actionName = "启动",
    ) { setting ->
        setting.copy(autoKeepRunning = true, manuallyStopped = false)
    }

    suspend fun stop(): HarnessSnapshot = runLifecycle(
        command = HarnessScripts.stopCommand(),
        actionName = "停止",
    ) { setting ->
        setting.copy(autoKeepRunning = false, manuallyStopped = true)
    }

    suspend fun restart(): HarnessSnapshot = runLifecycle(
        command = HarnessScripts.restartCommand(),
        actionName = "重启",
    ) { setting ->
        setting.copy(autoKeepRunning = true, manuallyStopped = false)
    }

    suspend fun setAutoKeepRunning(enabled: Boolean): HarnessSnapshot = runLifecycle(
        command = HarnessScripts.setAutoKeepRunningCommand(enabled),
        actionName = if (enabled) "开启自动复活" else "关闭自动复活",
    ) { setting ->
        setting.copy(autoKeepRunning = enabled)
    }

    suspend fun recoverIfNeeded(): Boolean {
        val setting = settingsStore.settingsFlow.value.harnessSetting
        val current = inspect()
        val installed = current.status != HarnessStatus.NOT_INSTALLED || current.installedVersion.isNotBlank()
        if (
            installed && shouldRecover(
                autoKeepRunning = setting.autoKeepRunning,
                manuallyStopped = setting.manuallyStopped,
                running = current.status == HarnessStatus.RUNNING,
            )
        ) {
            start()
            return true
        }
        return false
    }

    fun fallbackInstallCommand(): String {
        val result = resultFile("manual_setup")
        return HarnessScripts.bootstrapCommands(result.absolutePath)
            .joinToString(separator = " && ") { command -> "($command)" }
    }

    suspend fun redactedLogTail(): String {
        val resultFile = resultFile("logs")
        resultFile.delete()
        try {
            termuxConfigBridge.executeCommandsAndWait(
                commands = listOf(logTailCommand(resultFile)),
                completionFile = resultFile,
                timeoutMessage = "Harness 日志没有在 15 秒内返回。",
                waitAttempts = QUICK_WAIT_ATTEMPTS,
            )
            return boundedHarnessLog(redactHarnessLog(resultFile.readText()))
        } finally {
            resultFile.delete()
        }
    }

    private suspend fun runLifecycle(
        command: String,
        actionName: String,
        updateSetting: (me.rerere.rikkahub.data.datastore.HarnessSetting) ->
            me.rerere.rikkahub.data.datastore.HarnessSetting,
    ): HarnessSnapshot {
        val resultFile = resultFile("action")
        resultFile.delete()
        try {
            termuxConfigBridge.executeCommandsAndWait(
                commands = listOf(
                    command,
                    "printf '%s' '$READY_MARKER' > ${shellQuote(resultFile.absolutePath)}",
                ),
                completionFile = resultFile,
                timeoutMessage = "Harness $actionName 没有在 20 秒内完成。",
                waitAttempts = ACTION_WAIT_ATTEMPTS,
            )
            require(resultFile.readText().trim() == READY_MARKER) { "Harness $actionName 没有成功。" }
            settingsStore.update { settings ->
                settings.copy(harnessSetting = updateSetting(settings.harnessSetting))
            }
        } finally {
            resultFile.delete()
        }
        return inspect()
    }

    private fun isHttpHealthy(): Boolean = runCatching {
        val request = Request.Builder().url(HarnessScripts.WEB_URL).get().build()
        healthClient.newCall(request).execute().use { response -> response.isSuccessful }
    }.getOrDefault(false)

    private fun resultFile(purpose: String): File {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "OrangeChat/companion",
        )
        check(directory.exists() || directory.mkdirs()) { "无法创建 Daddy 的联动配置目录。" }
        return File(directory, "harness_${purpose}_${UUID.randomUUID()}.result")
    }

    private fun probeCommand(resultFile: File): String = """
        base="${'$'}HOME/daddy-harness"
        installed=0
        process=0
        version=""
        [ -x "${'$'}base/runtime/node_modules/.bin/dsh" ] && installed=1
        [ -f "${'$'}base/VERSION" ] && version="${'$'}(cat "${'$'}base/VERSION" 2>/dev/null || true)"
        pid="${'$'}(cat "${'$'}base/harness.pid" 2>/dev/null || true)"
        if [ -n "${'$'}pid" ] && kill -0 "${'$'}pid" 2>/dev/null; then process=1; fi
        detail="${'$'}(tail -n 1 "${'$'}base/harness.log" 2>/dev/null || tail -n 1 "${'$'}base/setup.log" 2>/dev/null || true)"
        detail="${'$'}(printf '%s' "${'$'}detail" | tr '\r\n' '  ')"
        printf 'installed=%s\nprocess=%s\nversion=%s\ndetail=%s\n' \
          "${'$'}installed" "${'$'}process" "${'$'}version" "${'$'}detail" > ${shellQuote(resultFile.absolutePath)}
    """.trimIndent()

    private fun logTailCommand(resultFile: File): String = """
        base="${'$'}HOME/daddy-harness"
        { tail -n $MAX_LOG_LINES "${'$'}base/setup.log" 2>/dev/null || true; \
          tail -n $MAX_LOG_LINES "${'$'}base/watchdog.log" 2>/dev/null || true; \
          tail -n $MAX_LOG_LINES "${'$'}base/harness.log" 2>/dev/null || true; } | \
          tail -n $MAX_LOG_LINES > ${shellQuote(resultFile.absolutePath)}
        [ -s ${shellQuote(resultFile.absolutePath)} ] || printf '%s' '暂无 Harness 日志。' > ${shellQuote(resultFile.absolutePath)}
    """.trimIndent()

    private fun parseProbe(text: String): HarnessProbe {
        val values = text.lineSequence()
            .filter { it.contains('=') }
            .associate { line -> line.substringBefore('=') to line.substringAfter('=') }
        return HarnessProbe(
            installed = values["installed"] == "1",
            processRunning = values["process"] == "1",
            version = values["version"].orEmpty().trim(),
            detail = values["detail"].orEmpty().trim(),
        )
    }

    private fun setupFailureMessage(result: String): String {
        val trimmed = result.trim()
        return if (trimmed.startsWith("error:")) {
            "Harness 没有启动：${trimmed.removePrefix("error:").trim()}"
        } else {
            "Harness 安装没有正确完成，请查看 ~/daddy-harness/setup.log。"
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    private data class HarnessProbe(
        val installed: Boolean = false,
        val processRunning: Boolean = false,
        val version: String = "",
        val detail: String = "",
    )

    private companion object {
        const val READY_MARKER = "ready"
        const val QUICK_WAIT_ATTEMPTS = 20
        const val ACTION_WAIT_ATTEMPTS = 28
    }
}

internal const val HARNESS_INSTALL_WAIT_ATTEMPTS = 1_200

internal fun harnessInstallWaitDurationMillis(pollIntervalMillis: Long): Long =
    HARNESS_INSTALL_WAIT_ATTEMPTS * pollIntervalMillis

private const val MAX_LOG_LINES = 200
private const val MAX_LOG_BYTES = 24 * 1024
