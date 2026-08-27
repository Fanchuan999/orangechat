/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.codehut

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException
import me.rerere.rikkahub.data.codehut.HarnessInboxClient
import me.rerere.rikkahub.data.codehut.HarnessInboxLoadResult
import me.rerere.rikkahub.data.codehut.HarnessInboxTask
import me.rerere.rikkahub.data.codehut.HarnessImageAttachment
import me.rerere.rikkahub.data.codehut.HarnessImageSendResult
import me.rerere.rikkahub.data.codehut.redactCodeHutUiText
import me.rerere.rikkahub.data.datastore.HarnessSnapshot
import me.rerere.rikkahub.data.pcbridge.PcBridgeUiActions
import me.rerere.rikkahub.data.pcbridge.PcBridgeUiPolicy
import me.rerere.rikkahub.data.pcbridge.PcBridgeUiState
import me.rerere.rikkahub.data.sync.companion.HarnessManager

data class CodeHutUiState(
    val harnessSnapshot: HarnessSnapshot,
    val activeInboxTasks: List<HarnessInboxTask> = emptyList(),
    val pcBridge: PcBridgeUiState = PcBridgeUiState.Unpaired,
    val inboxNotice: String? = null,
    val error: String? = null,
) {
    val environment: CodeHutEnvironmentPresentation
        get() = codeHutEnvironmentPresentation(harnessSnapshot)

    val workbench: CodeHutWorkbenchPresentation
        get() = codeHutWorkbenchPresentation(harnessSnapshot.status)

    val pcBridgePolicy: PcBridgeUiPolicy
        get() = PcBridgeUiPolicy.from(pcBridge)
}

class CodeHutVM(
    initialHarnessSnapshot: HarnessSnapshot,
    harnessSnapshots: Flow<HarnessSnapshot>,
    private val inspectHarness: suspend () -> Unit,
    private val loadInbox: suspend () -> HarnessInboxLoadResult,
    private val sendImage: suspend (HarnessImageAttachment) -> HarnessImageSendResult,
    private val pairingService: PcBridgeUiActions,
    actionScope: CoroutineScope? = null,
    private val refreshInboxOnStart: Boolean = true,
) : ViewModel() {
    private val launchScope = actionScope ?: viewModelScope
    private val _state = MutableStateFlow(
        CodeHutUiState(
            harnessSnapshot = initialHarnessSnapshot,
        )
    )
    val state: StateFlow<CodeHutUiState> = _state.asStateFlow()

    constructor(
        harnessManager: HarnessManager,
        inboxClient: HarnessInboxClient,
        pairingService: PcBridgeUiActions,
    ) : this(
        initialHarnessSnapshot = harnessManager.snapshot.value,
        harnessSnapshots = harnessManager.snapshot,
        inspectHarness = harnessManager::inspect,
        loadInbox = inboxClient::loadActiveTasks,
        sendImage = inboxClient::sendImageToInbox,
        pairingService = pairingService,
    )

    init {
        launchScope.launch {
            harnessSnapshots.collect { snapshot ->
                _state.value = _state.value.copy(harnessSnapshot = snapshot)
            }
        }
        launchScope.launch(start = CoroutineStart.UNDISPATCHED) {
            pairingService.state.collect { pcBridge ->
                _state.value = _state.value.copy(pcBridge = pcBridge)
            }
        }
        refreshPcBridge()
        if (refreshInboxOnStart) refreshInbox()
    }

    fun refreshHarness() {
        launchScope.launch {
            try {
                inspectHarness()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    error = redactCodeHutUiText(error.message ?: "刷新 Harness 状态失败"),
                )
            }
            refreshInbox()
        }
    }

    fun updatePcInvitation(invitation: String) {
        try {
            pairingService.updateInvitationCode(invitation)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            publishPcBridgeError("无法读取电脑邀请，请重新粘贴。")
        }
    }

    fun confirmPcPairing(): Job = launchScope.launch {
        try {
            pairingService.confirmPairing()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            publishPcBridgeError("安全连接电脑失败，请重试。")
        }
    }

    fun refreshPcBridge(): Job = launchScope.launch {
        try {
            pairingService.refreshStatus()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            publishPcBridgeError("无法刷新电脑状态，请重试。")
        }
    }

    fun unlinkPcBridge(): Job = launchScope.launch {
        try {
            pairingService.unlink()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            publishPcBridgeError("无法解除电脑配对，请重试。")
        }
    }

    fun abandonPendingPcBridge(): Job = launchScope.launch {
        if (_state.value.pcBridge !is PcBridgeUiState.PendingRecovery) return@launch
        try {
            pairingService.abandonPendingPairing()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            publishPcBridgeError("无法放弃本机待恢复配对，请重试。")
        }
    }

    fun forgetUnavailableConfirmedPcBridge(): Job = launchScope.launch {
        if (_state.value.pcBridge !is PcBridgeUiState.ConfirmedRecovery) return@launch
        try {
            pairingService.forgetUnavailableConfirmedPairing()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            publishPcBridgeError("无法忘记本机电脑配对，请重试。")
        }
    }

    fun refreshInbox() {
        launchScope.launch {
            when (val result = loadInbox()) {
                is HarnessInboxLoadResult.Available -> _state.value = _state.value.copy(
                    activeInboxTasks = result.tasks,
                    inboxNotice = null,
                    error = null,
                )

                is HarnessInboxLoadResult.Unavailable -> _state.value = _state.value.copy(
                    activeInboxTasks = emptyList(),
                    inboxNotice = redactCodeHutUiText(result.message),
                )

                is HarnessInboxLoadResult.Failure -> _state.value = _state.value.copy(
                    activeInboxTasks = emptyList(),
                    inboxNotice = redactCodeHutUiText(result.message),
                )
            }
        }
    }

    fun sendImageToInbox(mediaType: String?, bytes: ByteArray) {
        launchScope.launch {
            when (val result = sendImage(HarnessImageAttachment(mediaType.orEmpty(), bytes))) {
                HarnessImageSendResult.Sent -> _state.value = _state.value.copy(
                    inboxNotice = "图片已进入 Harness 收件箱队列。是否可被理解取决于当前工作模型是否支持视觉输入。",
                    error = null,
                )

                is HarnessImageSendResult.Failure -> _state.value = _state.value.copy(
                    error = redactCodeHutUiText(result.message),
                )
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun publishPcBridgeError(message: String) {
        _state.value = _state.value.copy(error = redactCodeHutUiText(message))
    }
}
