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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.datastore.HarnessSnapshot
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.companion.HarnessManager
import me.rerere.rikkahub.data.sync.companion.HarnessRecoveryScheduler

data class HarnessUiState(
    val snapshot: HarnessSnapshot = HarnessSnapshot(),
    val autoKeepRunning: Boolean = true,
    val busyAction: String? = null,
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
    private val _state = MutableStateFlow(
        HarnessUiState(
            snapshot = harnessManager.snapshot.value,
            autoKeepRunning = settingsStore.settingsFlow.value.harnessSetting.autoKeepRunning,
        )
    )
    val state: StateFlow<HarnessUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = operate("刷新状态") { harnessManager.inspect() }

    fun install() = operate("安装或修复") { harnessManager.install() }

    fun start() = operate("启动") { harnessManager.start() }

    fun stop() = operate("停止") { harnessManager.stop() }

    fun restart() = operate("重启") { harnessManager.restart() }

    fun setAutoKeepRunning(enabled: Boolean) = operate(
        action = if (enabled) "开启自动复活" else "关闭自动复活",
    ) {
        harnessManager.setAutoKeepRunning(enabled)
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

    private fun operate(
        action: String,
        operation: suspend () -> HarnessSnapshot,
    ) {
        viewModelScope.launch {
            operationMutex.withLock {
                _state.value = _state.value.copy(busyAction = action, message = null, error = null)
                runCatching {
                    val snapshot = operation()
                    val logs = runCatching { harnessManager.redactedLogTail() }.getOrDefault(snapshot.logTail)
                    val setting = settingsStore.settingsFlow.value.harnessSetting
                    HarnessRecoveryScheduler.sync(context, setting)
                    snapshot.copy(logTail = logs) to setting.autoKeepRunning
                }.onSuccess { (snapshot, autoKeepRunning) ->
                    _state.value = _state.value.copy(
                        snapshot = snapshot,
                        autoKeepRunning = autoKeepRunning,
                        busyAction = null,
                        message = "${action}完成。",
                    )
                }.onFailure { error ->
                    _state.value = _state.value.copy(
                        busyAction = null,
                        error = error.message ?: "${action}失败。",
                    )
                }
            }
        }
    }
}
