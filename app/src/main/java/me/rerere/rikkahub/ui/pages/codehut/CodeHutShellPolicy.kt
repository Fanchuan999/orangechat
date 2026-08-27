/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.codehut

import me.rerere.rikkahub.data.codehut.CodeHutTask
import me.rerere.rikkahub.data.codehut.CodeHutTaskPolicy
import me.rerere.rikkahub.data.codehut.CodeHutTaskStatus
import me.rerere.rikkahub.data.codehut.HarnessGatewayResult
import me.rerere.rikkahub.data.codehut.TaskResultSummary
import me.rerere.rikkahub.data.codehut.TaskTicket
import me.rerere.rikkahub.data.codehut.redactCodeHutUiText
import me.rerere.rikkahub.data.datastore.HarnessSnapshot
import me.rerere.rikkahub.data.datastore.HarnessStatus

data class CodeHutEnvironmentItem(
    val title: String,
    val status: String,
    val detail: String,
)

data class CodeHutEnvironmentPresentation(
    val service: CodeHutEnvironmentItem,
    val permissions: CodeHutEnvironmentItem,
) {
    val items: List<CodeHutEnvironmentItem>
        get() = listOf(service, permissions)

    fun flattenText(): String = items.joinToString("\n") { "${it.title} ${it.status} ${it.detail}" }
}

data class CodeHutWorkbenchPresentation(
    val canOpen: Boolean,
    val status: String,
    val actionLabel: String,
    val guidance: String,
)

data class CodeHutTaskDraft(
    val taskText: String = "",
    val selectedFilesText: String = "",
    val workingDirectory: String = ".",
    val constraintsText: String = "",
) {
    fun toTicket(): TaskTicket = CodeHutTaskPolicy.createTicket(
        taskText = taskText,
        selectedFiles = selectedFilesText.lines().map { it.trim() }.filter { it.isNotEmpty() },
        workingDirectory = workingDirectory,
        constraints = constraintsText.lines().map { it.trim() }.filter { it.isNotEmpty() },
    )

    fun toTask(): CodeHutTask = CodeHutTask(ticket = toTicket(), status = CodeHutTaskStatus.QUEUED)
}

fun codeHutWorkbenchPresentation(status: HarnessStatus): CodeHutWorkbenchPresentation {
    val canOpen = canOpenCodeHutWorkbench(status)
    return CodeHutWorkbenchPresentation(
        canOpen = canOpen,
        status = harnessStatusLabel(status),
        actionLabel = if (canOpen) "打开工作台" else "安装 / 启动 Harness",
        guidance = if (canOpen) {
            "Harness 工作台已在 127.0.0.1:3080 运行。"
        } else {
            "Harness 当前${harnessStatusLabel(status)}，请先进入 Harness 设置安装或启动。"
        },
    )
}

fun canOpenCodeHutWorkbench(status: HarnessStatus): Boolean = status == HarnessStatus.RUNNING

fun codeHutEnvironmentPresentation(harnessSnapshot: HarnessSnapshot): CodeHutEnvironmentPresentation {
    return CodeHutEnvironmentPresentation(
        service = CodeHutEnvironmentItem(
            title = "服务",
            status = harnessStatusLabel(harnessSnapshot.status),
            detail = "Harness 工作台：127.0.0.1:3080",
        ),
        permissions = CodeHutEnvironmentItem(
            title = "权限",
            status = "安全确认",
            detail = "遵循当前 Harness 风险确认策略；高风险操作仍须单独确认。",
        ),
    )
}

fun applyGatewayResult(
    task: CodeHutTask,
    result: HarnessGatewayResult,
): CodeHutTask = when (result) {
    is HarnessGatewayResult.PreparedForWorkbench -> task.copy(
        status = CodeHutTaskStatus.PREPARED,
        result = TaskResultSummary(
            summary = result.message,
            verification = result.workbenchUrl,
        ).redactedForCodeHutShell(),
    )

    is HarnessGatewayResult.Failure -> task.copy(
        status = CodeHutTaskStatus.FAILED,
        result = TaskResultSummary(summary = redactCodeHutShellText(result.message)),
    )
}

fun codeHutTaskActionLabel(task: CodeHutTask): String = when (task.status) {
    CodeHutTaskStatus.DRAFT -> "新建任务"
    CodeHutTaskStatus.QUEUED -> "取消本地请求"
    CodeHutTaskStatus.RUNNING -> "隐藏本地请求"

    CodeHutTaskStatus.SUCCEEDED -> "查看结果"
    CodeHutTaskStatus.FAILED -> "重新提交"
    CodeHutTaskStatus.PREPARED -> "复制任务内容"
    CodeHutTaskStatus.UNSUPPORTED -> "转到完整工作台继续"
}

fun codeHutTaskStopNotice(task: CodeHutTask): String = when (task.status) {
    CodeHutTaskStatus.QUEUED -> "已取消尚未提交的本地请求。"
    CodeHutTaskStatus.RUNNING -> "已隐藏本机外壳中的请求；当前没有文档化远端取消 API，不能保证停止工作台中的任务。"
    else -> "当前任务没有可取消的本地请求。"
}

fun redactCodeHutShellText(value: String): String = redactCodeHutUiText(value)

fun TaskResultSummary.redactedForCodeHutShell(): TaskResultSummary = copy(
    summary = redactCodeHutShellText(summary),
    changedFiles = changedFiles.map(::redactCodeHutShellText),
    verification = redactCodeHutShellText(verification),
)

fun harnessStatusLabel(status: HarnessStatus): String = when (status) {
    HarnessStatus.NOT_INSTALLED -> "未安装"
    HarnessStatus.INSTALLING -> "安装中"
    HarnessStatus.STOPPED -> "未启动"
    HarnessStatus.STARTING -> "启动中"
    HarnessStatus.RUNNING -> "运行中"
    HarnessStatus.MANUALLY_STOPPED -> "主动停止"
    HarnessStatus.BACKING_OFF -> "等待恢复"
    HarnessStatus.REPAIRING -> "修复中"
    HarnessStatus.ERROR -> "异常"
}
