/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.codehut

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.codehut.CodeHutTask
import me.rerere.rikkahub.data.codehut.CodeHutTaskStatus
import me.rerere.rikkahub.data.codehut.HarnessTaskGateway
import me.rerere.rikkahub.data.datastore.HarnessSnapshot
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.companion.HarnessManager

data class CodeHutUiState(
    val settings: Settings,
    val harnessSnapshot: HarnessSnapshot,
    val draft: CodeHutTaskDraft = CodeHutTaskDraft(),
    val activeTask: CodeHutTask? = null,
    val error: String? = null,
) {
    val environment: CodeHutEnvironmentPresentation
        get() = codeHutEnvironmentPresentation(settings, harnessSnapshot)

    val isSubmitting: Boolean
        get() = activeTask?.status == CodeHutTaskStatus.RUNNING

    val workbench: CodeHutWorkbenchPresentation
        get() = codeHutWorkbenchPresentation(harnessSnapshot.status)
}

class CodeHutVM(
    private val settingsStore: SettingsStore,
    private val harnessManager: HarnessManager,
    private val taskGateway: HarnessTaskGateway,
) : ViewModel() {
    private var submitJob: Job? = null
    private val _state = MutableStateFlow(
        CodeHutUiState(
            settings = settingsStore.settingsFlow.value,
            harnessSnapshot = harnessManager.snapshot.value,
        )
    )
    val state: StateFlow<CodeHutUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(settingsStore.settingsFlow, harnessManager.snapshot) { settings, snapshot ->
                settings to snapshot
            }.collect { (settings, snapshot) ->
                _state.value = _state.value.copy(settings = settings, harnessSnapshot = snapshot)
            }
        }
    }

    fun updateDraft(draft: CodeHutTaskDraft) {
        _state.value = _state.value.copy(draft = draft, error = null)
    }

    fun submitTask() {
        if (submitJob?.isActive == true) return
        if (!canOpenCodeHutWorkbench(_state.value.harnessSnapshot.status)) {
            _state.value = _state.value.copy(
                error = codeHutWorkbenchPresentation(_state.value.harnessSnapshot.status).guidance,
            )
            return
        }
        val task = try {
            _state.value.draft.toTask().copy(status = CodeHutTaskStatus.RUNNING)
        } catch (error: IllegalArgumentException) {
            _state.value = _state.value.copy(error = error.message ?: "任务票据无效")
            return
        }

        _state.value = _state.value.copy(activeTask = task, error = null)
        submitJob = viewModelScope.launch {
            val result = taskGateway.submit(task.ticket)
            _state.value = _state.value.copy(activeTask = applyGatewayResult(task, result))
        }
    }

    fun stopTask() {
        submitJob?.cancel()
        val current = _state.value.activeTask ?: return
        _state.value = _state.value.copy(
            activeTask = null,
            error = codeHutTaskStopNotice(current),
        )
    }

    fun resetTask() {
        submitJob?.cancel()
        _state.value = _state.value.copy(activeTask = null, error = null)
    }

    fun refreshHarness() {
        viewModelScope.launch {
            runCatching { harnessManager.inspect() }
                .onFailure { error ->
                    _state.value = _state.value.copy(error = error.message ?: "刷新 Harness 状态失败")
                }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
