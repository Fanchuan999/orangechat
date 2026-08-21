/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.harness

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.datastore.CodeHutApprovalMode
import me.rerere.rikkahub.data.datastore.HarnessSnapshot
import me.rerere.rikkahub.data.datastore.HarnessStatus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.activeApprovalMode
import me.rerere.rikkahub.data.sync.companion.HarnessManager
import me.rerere.rikkahub.data.sync.companion.HarnessRecoveryScheduler

data class HarnessUiState(
    val snapshot: HarnessSnapshot = HarnessSnapshot(),
    val autoKeepRunning: Boolean = true,
    val approvalMode: CodeHutApprovalMode = CodeHutApprovalMode.ASK_EVERY_TIME,
    val busyAction: String? = null,
    val displayedInstallProgress: Int = snapshot.installProgressPercent.coerceIn(0, 100),
    val message: String? = null,
    val error: String? = null,
) {
    val isBusy: Boolean get() = busyAction != null
}

class HarnessVM(
    private val context: Context,
    private val harnessManager: HarnessManager,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val operationMutex = Mutex()
    private var helpLeaseExpiryJob: Job? = null
    private val _state = MutableStateFlow(
        HarnessUiState(
            snapshot = redactHarnessSnapshot(harnessManager.snapshot.value),
            autoKeepRunning = settingsStore.settingsFlow.value.harnessSetting.autoKeepRunning,
            approvalMode = settingsStore.settingsFlow.value.codeHutSetting.activeApprovalMode(),
        )
    )
    val state: StateFlow<HarnessUiState> = _state.asStateFlow()

    init {
        scheduleApprovalLeaseExpiry()
        refresh()
    }

    fun refresh() = operate("刷新状态") { harnessManager.inspect() }

    fun install() = operate(action = "安装或修复", monitorInstall = true) { harnessManager.install() }

    fun start() = operate("启动") { harnessManager.start() }

    fun stop() = operate("停止") { harnessManager.stop() }

    fun restart() = operate("重启") { harnessManager.restart() }

    fun setAutoKeepRunning(enabled: Boolean) = operate(
        action = if (enabled) "开启自动复活" else "关闭自动复活",
    ) {
        harnessManager.setAutoKeepRunning(enabled)
    }

    fun setApprovalMode(mode: CodeHutApprovalMode) {
        viewModelScope.launch {
            operationMutex.withLock {
                _state.value = _state.value.copy(busyAction = "同步权限预设", message = null, error = null)
                runCatching {
                    // The remote gate is the authority. Persist and display HELP only after it
                    // acknowledges the exact revisioned lease that was written under its lock.
                    val lease = harnessManager.applyApprovalMode(mode)
                    settingsStore.update { settings ->
                        settings.copy(
                            codeHutSetting = settings.codeHutSetting.copy(
                                approvalMode = lease.mode,
                                approvalRevision = lease.revision,
                                helpApprovalExpiresAtEpochMillis = lease.expiresAtEpochMillis,
                            ),
                        )
                    }
                    lease
                }.onSuccess { lease ->
                    scheduleApprovalLeaseExpiry()
                    _state.value = _state.value.copy(
                        approvalMode = lease.mode,
                        busyAction = null,
                        message = if (lease.mode == CodeHutApprovalMode.HELP_ME_APPROVE) {
                            "“帮我批准”已生效 30 分钟；仅严格只读操作会连续放行。"
                        } else {
                            "代码小屋权限预设已写入工作台：普通操作也会逐次确认。"
                        },
                        error = null,
                    )
                }.onFailure { error ->
                    val conservativeLease = runCatching {
                        harnessManager.forceConservativeApprovalMode()
                    }.getOrNull()
                    if (conservativeLease != null) {
                        runCatching {
                            settingsStore.update { settings ->
                                settings.copy(
                                    codeHutSetting = settings.codeHutSetting.copy(
                                        approvalMode = conservativeLease.mode,
                                        approvalRevision = conservativeLease.revision,
                                        helpApprovalExpiresAtEpochMillis = conservativeLease.expiresAtEpochMillis,
                                    ),
                                )
                            }
                        }
                    } else {
                        // Do not invent an acknowledged revision.  The remaining remote HELP
                        // lease expires by itself; the Manager has also attempted to stop it.
                        runCatching {
                            settingsStore.update { settings ->
                                settings.copy(
                                    codeHutSetting = settings.codeHutSetting.copy(
                                        approvalMode = CodeHutApprovalMode.ASK_EVERY_TIME,
                                        helpApprovalExpiresAtEpochMillis = 0,
                                    ),
                                )
                            }
                        }
                    }
                    scheduleApprovalLeaseExpiry()
                    _state.value = _state.value.copy(
                        approvalMode = CodeHutApprovalMode.ASK_EVERY_TIME,
                        busyAction = null,
                        message = if (conservativeLease != null) {
                            "代码小屋权限预设未切换，已确认恢复为“每次询问”。"
                        } else {
                            "权限同步失败；无法确认工作台状态，已请求停止。"
                        },
                        error = redactHarnessUiNotice(error.message ?: "权限同步失败。"),
                    )
                }
            }
        }
    }

    fun copyFallbackCommand() {
        val command = harnessManager.fallbackInstallCommand()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("Daddy Harness 安装命令", command))
        _state.value = _state.value.copy(message = "备用安装命令已复制。", error = null)
    }

    fun clearNotice() {
        _state.value = _state.value.copy(message = null, error = null)
    }

    private fun scheduleApprovalLeaseExpiry() {
        helpLeaseExpiryJob?.cancel()
        val setting = settingsStore.settingsFlow.value.codeHutSetting
        val expiresAt = setting.helpApprovalExpiresAtEpochMillis
        if (setting.activeApprovalMode() != CodeHutApprovalMode.HELP_ME_APPROVE) return
        helpLeaseExpiryJob = viewModelScope.launch {
            delay((expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
            if (settingsStore.settingsFlow.value.codeHutSetting.activeApprovalMode() ==
                CodeHutApprovalMode.ASK_EVERY_TIME
            ) {
                _state.value = _state.value.copy(
                    approvalMode = CodeHutApprovalMode.ASK_EVERY_TIME,
                    message = "“帮我批准”已到期，代码小屋已恢复为逐次确认。",
                )
            }
        }
    }

    private fun operate(
        action: String,
        monitorInstall: Boolean = false,
        operation: suspend () -> HarnessSnapshot,
    ) {
        viewModelScope.launch {
            operationMutex.withLock {
                val initialProgress = if (monitorInstall) 0 else _state.value.displayedInstallProgress
                _state.value = _state.value.copy(
                    busyAction = action,
                    displayedInstallProgress = initialProgress,
                    message = null,
                    error = null,
                )
                val operationResult = if (monitorInstall) {
                    runWithInstallMonitoring(operation)
                } else {
                    runCatching { operation() }
                }
                operationResult.mapCatching { snapshot ->
                    val logs = runCatching { harnessManager.redactedLogTail() }.getOrDefault(snapshot.logTail)
                    val setting = settingsStore.settingsFlow.value.harnessSetting
                    HarnessRecoveryScheduler.sync(context, setting)
                    snapshot.copy(logTail = logs) to setting.autoKeepRunning
                }.onSuccess { (snapshot, autoKeepRunning) ->
                    publishSnapshot(snapshot, installationAttemptActive = monitorInstall)
                    _state.value = _state.value.copy(
                        snapshot = snapshot,
                        autoKeepRunning = autoKeepRunning,
                        approvalMode = settingsStore.settingsFlow.value.codeHutSetting.activeApprovalMode(),
                        busyAction = null,
                        message = if (snapshot.status in setOf(
                                HarnessStatus.INSTALLING,
                                HarnessStatus.STARTING,
                            )
                        ) {
                            "安装已在后台继续，可稍后刷新查看阶段。"
                        } else {
                            "${action}完成。"
                        },
                    )
                }.onFailure { error ->
                    publishSnapshot(
                        snapshot = harnessManager.snapshot.value,
                        installationAttemptActive = monitorInstall,
                    )
                    _state.value = _state.value.copy(
                        busyAction = null,
                        error = redactHarnessUiNotice(error.message ?: "${action}失败。"),
                    )
                }
            }
        }
    }

    private suspend fun runWithInstallMonitoring(
        operation: suspend () -> HarnessSnapshot,
    ): Result<HarnessSnapshot> = coroutineScope {
        val monitor = launch {
            while (isActive) {
                delay(INSTALL_MONITOR_INTERVAL_MS)
                runCatching { harnessManager.inspect() }
                    .onSuccess { snapshot -> publishSnapshot(snapshot, installationAttemptActive = true) }
            }
        }
        try {
            runCatching { operation() }
        } finally {
            monitor.cancelAndJoin()
        }
    }

    private fun publishSnapshot(
        snapshot: HarnessSnapshot,
        installationAttemptActive: Boolean,
    ) {
        val current = _state.value
        val redactedSnapshot = redactHarnessSnapshot(snapshot)
        _state.value = current.copy(
            snapshot = redactedSnapshot,
            displayedInstallProgress = nextHarnessInstallProgress(
                previous = current.displayedInstallProgress,
                snapshot = redactedSnapshot,
                installationAttemptActive = installationAttemptActive,
            ),
        )
    }

    private fun redactHarnessSnapshot(snapshot: HarnessSnapshot): HarnessSnapshot = snapshot.copy(
        detail = redactHarnessUiNotice(snapshot.detail),
        logTail = redactHarnessUiNotice(snapshot.logTail),
    )

    private companion object {
        const val INSTALL_MONITOR_INTERVAL_MS = 2_000L
    }
}
