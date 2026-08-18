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
import me.rerere.rikkahub.data.datastore.HarnessInstallStage
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

internal data class HarnessProbe(
    val runtimeMarker: String = "",
    val installStage: HarnessInstallStage = HarnessInstallStage.UNKNOWN,
    val processRunning: Boolean = false,
    val setupRunning: Boolean = false,
    val legacyRuntimeFound: Boolean = false,
    val manuallyStopped: Boolean = false,
    val backoffUntilEpochSeconds: Long = 0,
    val version: String = "",
    val detail: String = "",
)

internal fun harnessInstallStageProgress(stage: HarnessInstallStage): Int = when (stage) {
    HarnessInstallStage.UNKNOWN -> 0
    HarnessInstallStage.PRECHECK -> 5
    HarnessInstallStage.INSTALL_PROOT -> 15
    HarnessInstallStage.INSTALL_DEBIAN -> 35
    HarnessInstallStage.INSTALL_NODE -> 55
    HarnessInstallStage.INSTALL_HARNESS -> 75
    HarnessInstallStage.WRITE_SCRIPTS -> 85
    HarnessInstallStage.START_AND_HEALTHCHECK -> 95
    HarnessInstallStage.READY -> 100
    HarnessInstallStage.FAILED -> 0
}

internal fun parseHarnessProbe(text: String): HarnessProbe {
    val values = text.lineSequence()
        .filter { it.contains('=') }
        .associate { line -> line.substringBefore('=') to line.substringAfter('=') }
    return HarnessProbe(
        runtimeMarker = values["runtime"].orEmpty().trim(),
        installStage = parseHarnessInstallStage(values["stage"].orEmpty()),
        processRunning = values["process"] == "1",
        setupRunning = values["setup"] == "1",
        legacyRuntimeFound = values["legacy"] == "1",
        manuallyStopped = values["manual_stop"] == "1",
        backoffUntilEpochSeconds = values["backoff_until"]?.toLongOrNull() ?: 0,
        version = values["version"].orEmpty().trim(),
        detail = values["detail"].orEmpty().trim(),
    )
}

internal fun classifyLinuxHarness(
    probe: HarnessProbe,
    httpHealthy: Boolean,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1_000,
): HarnessStatus {
    if (probe.installStage == HarnessInstallStage.FAILED) return HarnessStatus.ERROR
    if (probe.setupRunning) {
        return if (probe.installStage == HarnessInstallStage.START_AND_HEALTHCHECK) {
            HarnessStatus.STARTING
        } else {
            HarnessStatus.INSTALLING
        }
    }

    val linuxReady = isLinuxHarnessRuntime(probe.runtimeMarker) &&
        probe.installStage == HarnessInstallStage.READY &&
        probe.version.isNotBlank()
    if (!linuxReady) {
        return if (probe.installStage !in setOf(HarnessInstallStage.UNKNOWN, HarnessInstallStage.READY)) {
            HarnessStatus.REPAIRING
        } else {
            HarnessStatus.NOT_INSTALLED
        }
    }
    if (probe.manuallyStopped) return HarnessStatus.MANUALLY_STOPPED
    if (probe.backoffUntilEpochSeconds > nowEpochSeconds) return HarnessStatus.BACKING_OFF
    return when {
        probe.processRunning && httpHealthy -> HarnessStatus.RUNNING
        probe.processRunning || httpHealthy -> HarnessStatus.ERROR
        else -> HarnessStatus.STOPPED
    }
}

internal fun shouldSubmitHarnessInstall(setupRunning: Boolean): Boolean = !setupRunning

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
        Regex("(?i)(\\bAuthorization\\s*[:=]\\s*)(?:Bearer\\s+)?[^\\s,;]+"),
        Regex("(?im)(^\\s*(?:Cookie|Set-Cookie)\\s*:\\s*)[^\\r\\n]+"),
        Regex(
            "(?i)([\\\"']?(?:api[_-]?key|apiKey|token|access[_-]?token|password|secret)" +
                "[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\\"'\\s,}\\]]+",
        ),
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
            parseHarnessProbe(resultFile.readText())
        } else {
            HarnessProbe(
                version = settingsStore.settingsFlow.value.harnessSetting.installedVersion,
                detail = probeFailure.message.orEmpty(),
            )
        }
        resultFile.delete()

        val httpHealthy = isHttpHealthy()
        val status = if (probeFailure != null && probe.version.isNotBlank()) {
            HarnessStatus.ERROR
        } else {
            classifyLinuxHarness(probe = probe, httpHealthy = httpHealthy)
        }
        val next = HarnessSnapshot(
            status = status,
            installedVersion = probe.version.ifBlank {
                settingsStore.settingsFlow.value.harnessSetting.installedVersion
            },
            detail = probe.detail,
            logTail = boundedHarnessLog(redactHarnessLog(probe.detail)),
            installStage = probe.installStage,
            legacyRuntimeFound = probe.legacyRuntimeFound,
            installProgressPercent = harnessInstallStageProgress(probe.installStage),
        )
        _snapshot.value = next
        return next
    }

    suspend fun install(): HarnessSnapshot {
        val before = inspect()
        if (before.status in setOf(HarnessStatus.INSTALLING, HarnessStatus.STARTING)) return before

        val resultFile = resultFile("setup")
        resultFile.delete()
        try {
            termuxConfigBridge.executeCommandsAndWait(
                commands = HarnessScripts.bootstrapCommands(resultFile.absolutePath),
                completionFile = resultFile,
                timeoutMessage = "Harness 在三十分钟内没有准备好，请查看 ~/daddy-linux/services/harness/logs/setup.log。",
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
        } catch (failure: Throwable) {
            val current = inspect()
            if (current.status in setOf(HarnessStatus.INSTALLING, HarnessStatus.STARTING)) return current
            throw failure
        } finally {
            resultFile.delete()
        }
        return inspect()
    }

    suspend fun start(): HarnessSnapshot = runLifecycle(
        command = HarnessScripts.startCommand(),
        actionName = "启动",
        waitForHttpHealthy = true,
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
        waitForHttpHealthy = true,
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
        val installed = current.installStage == HarnessInstallStage.READY && current.installedVersion.isNotBlank()
        if (
            installed && current.status == HarnessStatus.STOPPED && shouldRecover(
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
        waitForHttpHealthy: Boolean = false,
        updateSetting: (me.rerere.rikkahub.data.datastore.HarnessSetting) ->
            me.rerere.rikkahub.data.datastore.HarnessSetting,
    ): HarnessSnapshot {
        val resultFile = resultFile("action")
        resultFile.delete()
        try {
            termuxConfigBridge.executeCommandsAndWait(
                commands = buildList {
                    add(command)
                    if (waitForHttpHealthy) add(waitForHarnessHealthCommand())
                    add("printf '%s' '$READY_MARKER' > ${shellQuote(resultFile.absolutePath)}")
                },
                completionFile = resultFile,
                timeoutMessage = "Harness $actionName 没有在 90 秒内完成。",
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

    private fun waitForHarnessHealthCommand(): String = """
        ready=0
        for attempt in ${'$'}(seq 1 90); do
          if command -v curl >/dev/null 2>&1 && curl -fsS '${HarnessScripts.WEB_URL}' >/dev/null 2>&1; then
            ready=1
            break
          fi
          if (echo > /dev/tcp/127.0.0.1/3080) >/dev/null 2>&1; then
            ready=1
            break
          fi
          sleep 1
        done
        [ "${'$'}ready" = 1 ] || exit 48
    """.trimIndent()

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
        base="${'$'}HOME/daddy-linux"
        services="${'$'}base/services/harness"
        run="${'$'}services/run"
        process=0
        setup=0
        legacy=0
        manual_stop=0
        version=""
        runtime="${'$'}(cat "${'$'}services/runtime.marker" 2>/dev/null || true)"
        stage="${'$'}(sed -n 's/^stage=//p' "${'$'}services/install.state" 2>/dev/null | head -n 1)"
        detail="${'$'}(sed -n 's/^detail=//p' "${'$'}services/install.state" 2>/dev/null | head -n 1)"
        [ -f "${'$'}services/runtime/harness/VERSION" ] && version="${'$'}(cat "${'$'}services/runtime/harness/VERSION" 2>/dev/null || true)"
        [ -d "${'$'}HOME/daddy-harness" ] && legacy=1
        [ -f "${'$'}run/.manual-stop" ] && manual_stop=1
        backoff_until="${'$'}(cat "${'$'}run/next-restart-at" 2>/dev/null || echo 0)"

        pid="${'$'}(cat "${'$'}run/harness.pid" 2>/dev/null || true)"
        expected="${'$'}(cat "${'$'}run/process-start-ticks" 2>/dev/null || true)"
        actual="${'$'}(awk '{print ${'$'}22}' "/proc/${'$'}pid/stat" 2>/dev/null || true)"
        if [ -n "${'$'}pid" ] && [ -n "${'$'}expected" ] && [ "${'$'}actual" = "${'$'}expected" ] &&
           kill -0 "${'$'}pid" 2>/dev/null; then process=1; fi

        setup_pid="${'$'}(cat "${'$'}run/setup.pid" 2>/dev/null || true)"
        setup_expected="${'$'}(cat "${'$'}run/setup-start-ticks" 2>/dev/null || true)"
        setup_actual="${'$'}(awk '{print ${'$'}22}' "/proc/${'$'}setup_pid/stat" 2>/dev/null || true)"
        if [ -n "${'$'}setup_pid" ] && [ -n "${'$'}setup_expected" ] &&
           [ "${'$'}setup_actual" = "${'$'}setup_expected" ] && kill -0 "${'$'}setup_pid" 2>/dev/null; then setup=1; fi

        [ -n "${'$'}detail" ] || detail="${'$'}(tail -n 1 "${'$'}services/logs/harness.log" 2>/dev/null || true)"
        detail="${'$'}(printf '%s' "${'$'}detail" | tr '\r\n' '  ')"
        printf 'runtime=%s\nstage=%s\nprocess=%s\nsetup=%s\nlegacy=%s\nmanual_stop=%s\nbackoff_until=%s\nversion=%s\ndetail=%s\n' \
          "${'$'}runtime" "${'$'}stage" "${'$'}process" "${'$'}setup" "${'$'}legacy" "${'$'}manual_stop" \
          "${'$'}backoff_until" "${'$'}version" "${'$'}detail" > ${shellQuote(resultFile.absolutePath)}
    """.trimIndent()

    private fun logTailCommand(resultFile: File): String = """
        logs="${'$'}HOME/daddy-linux/services/harness/logs"
        { tail -n $MAX_LOG_LINES "${'$'}logs/setup.log" 2>/dev/null || true; \
          tail -n $MAX_LOG_LINES "${'$'}logs/watchdog.log" 2>/dev/null || true; \
          tail -n $MAX_LOG_LINES "${'$'}logs/harness.log" 2>/dev/null || true; } | \
          tail -n $MAX_LOG_LINES > ${shellQuote(resultFile.absolutePath)}
        [ -s ${shellQuote(resultFile.absolutePath)} ] || printf '%s' '暂无 Harness 日志。' > ${shellQuote(resultFile.absolutePath)}
    """.trimIndent()

    private fun setupFailureMessage(result: String): String {
        val trimmed = result.trim()
        return if (trimmed.startsWith("error:")) {
            "Harness 没有启动：${trimmed.removePrefix("error:").trim()}"
        } else {
            "Harness 安装没有正确完成，请查看 ~/daddy-linux/services/harness/logs/setup.log。"
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    private companion object {
        const val READY_MARKER = "ready"
        const val QUICK_WAIT_ATTEMPTS = 20
        const val ACTION_WAIT_ATTEMPTS = 120
    }
}

internal const val HARNESS_INSTALL_WAIT_ATTEMPTS = 2_400

internal fun harnessInstallWaitDurationMillis(pollIntervalMillis: Long): Long =
    HARNESS_INSTALL_WAIT_ATTEMPTS * pollIntervalMillis

internal fun harnessActionWaitDurationMillis(pollIntervalMillis: Long): Long =
    120 * pollIntervalMillis

private const val MAX_LOG_LINES = 200
private const val MAX_LOG_BYTES = 24 * 1024
