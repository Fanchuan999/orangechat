/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.codehut

import me.rerere.rikkahub.data.ai.mcp.serverUrl
import me.rerere.rikkahub.data.codehut.CodeHutTask
import me.rerere.rikkahub.data.codehut.CodeHutTaskPolicy
import me.rerere.rikkahub.data.codehut.CodeHutTaskStatus
import me.rerere.rikkahub.data.codehut.HarnessGatewayResult
import me.rerere.rikkahub.data.codehut.TaskResultSummary
import me.rerere.rikkahub.data.codehut.TaskTicket
import me.rerere.rikkahub.data.datastore.HarnessSnapshot
import me.rerere.rikkahub.data.datastore.HarnessStatus
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.WorkCapability
import me.rerere.rikkahub.data.datastore.WorkProtocol
import me.rerere.rikkahub.data.datastore.getCurrentAssistant

data class CodeHutEnvironmentItem(
    val title: String,
    val status: String,
    val detail: String,
)

data class CodeHutEnvironmentPresentation(
    val model: CodeHutEnvironmentItem,
    val service: CodeHutEnvironmentItem,
    val skills: CodeHutEnvironmentItem,
    val mcp: CodeHutEnvironmentItem,
    val github: CodeHutEnvironmentItem,
    val diagnostics: CodeHutEnvironmentItem,
    val permissions: CodeHutEnvironmentItem,
) {
    val items: List<CodeHutEnvironmentItem>
        get() = listOf(model, service, skills, mcp, github, diagnostics, permissions)

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

fun codeHutEnvironmentPresentation(
    settings: Settings,
    harnessSnapshot: HarnessSnapshot,
): CodeHutEnvironmentPresentation {
    val selectedBinding = settings.codeHutSetting.defaultBindingId?.let { id ->
        settings.codeHutSetting.bindings.firstOrNull { it.id == id }
    }
    val model = if (selectedBinding == null) {
        CodeHutEnvironmentItem(
            title = "模型",
            status = "未配置",
            detail = "未配置工作模型",
        )
    } else {
        CodeHutEnvironmentItem(
            title = "模型",
            status = "Harness 单一执行器",
            detail = buildString {
                append(protocolLabel(selectedBinding.protocol))
                append(" · ")
                append(capabilityLabel(selectedBinding.capability))
            },
        )
    }

    val enabledSkills = settings.getCurrentAssistant().enabledSkills.size
    val enabledMcp = settings.mcpServers.count { it.commonOptions.enable }
    val githubConfigured = settings.mcpServers.any { server ->
        server.commonOptions.enable &&
            (
                server.commonOptions.name.contains("github", ignoreCase = true) ||
                    server.serverUrl.contains("github", ignoreCase = true)
                )
    }

    return CodeHutEnvironmentPresentation(
        model = model,
        service = CodeHutEnvironmentItem(
            title = "服务",
            status = harnessStatusLabel(harnessSnapshot.status),
            detail = "Harness 工作台：127.0.0.1:3080",
        ),
        skills = CodeHutEnvironmentItem(
            title = "Skills",
            status = if (enabledSkills > 0) "可用" else "未配置",
            detail = if (enabledSkills > 0) {
                "$enabledSkills 个已启用 Skill"
            } else {
                "未启用 Skills"
            },
        ),
        mcp = CodeHutEnvironmentItem(
            title = "MCP",
            status = if (enabledMcp > 0) "已配置" else "未配置",
            detail = if (enabledMcp > 0) "$enabledMcp 个已启用 MCP 服务" else "未配置 MCP",
        ),
        github = CodeHutEnvironmentItem(
            title = "GitHub",
            status = if (githubConfigured) "已配置" else "未配置",
            detail = if (githubConfigured) "GitHub MCP 已配置" else "未配置 GitHub",
        ),
        diagnostics = CodeHutEnvironmentItem(
            title = "诊断",
            status = if (harnessSnapshot.detail.isBlank()) "待检查" else "有状态",
            detail = harnessSnapshot.detail.ifBlank { "暂无诊断详情，刷新 Harness 后更新" },
        ),
        permissions = CodeHutEnvironmentItem(
            title = "权限",
            status = "策略接口",
            detail = "待 1 号窗口合并权限策略",
        ),
    )
}

fun applyGatewayResult(
    task: CodeHutTask,
    result: HarnessGatewayResult,
): CodeHutTask = when (result) {
    is HarnessGatewayResult.Success -> task.copy(
        status = CodeHutTaskStatus.SUCCEEDED,
        result = result.summary.redactedForCodeHutShell(),
    )

    is HarnessGatewayResult.UnsupportedApi -> task.copy(
        status = CodeHutTaskStatus.UNSUPPORTED,
        result = TaskResultSummary(
            summary = "${result.message}。请转到完整工作台继续。",
            verification = result.workbenchUrl,
        ),
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
    CodeHutTaskStatus.UNSUPPORTED -> "转到完整工作台继续"
}

fun codeHutTaskStopNotice(task: CodeHutTask): String = when (task.status) {
    CodeHutTaskStatus.QUEUED -> "已取消尚未提交的本地请求。"
    CodeHutTaskStatus.RUNNING -> "已隐藏本机外壳中的请求；当前没有文档化远端取消 API，不能保证停止工作台中的任务。"
    else -> "当前任务没有可取消的本地请求。"
}

fun redactCodeHutShellText(value: String): String = secretPatterns.fold(value) { current, pattern ->
    current.replace(pattern) { match ->
        val prefix = match.groups[1]?.value.orEmpty()
        val separator = match.groups[2]?.value.orEmpty()
        val quote = match.groups[3]?.value.orEmpty()
        "$prefix$separator$quote[REDACTED]$quote"
    }
}

private fun TaskResultSummary.redactedForCodeHutShell(): TaskResultSummary = copy(
    summary = redactCodeHutShellText(summary),
    verification = redactCodeHutShellText(verification),
)

private val secretPatterns = listOf(
    Regex(
        pattern = "(?i)\\b(authorization)(\\s*[:=]\\s*)([\"']?)(bearer\\s+[^\"'\\s,;]+)",
    ),
    Regex(
        pattern = "(?i)\\b(bearer)(\\s+)([\"']?)([^\"'\\s,;]+)",
    ),
    Regex(
        pattern = "(?i)\\b(api\\s*key|api[_-]?key|token|password|passwd|secret)(\\s*[:=]\\s*)([\"']?)([^\"'\\s,;]+)",
    ),
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

private fun protocolLabel(protocol: WorkProtocol): String = when (protocol) {
    WorkProtocol.OPENAI_CHAT_COMPLETIONS -> "OpenAI Chat Completions"
    WorkProtocol.ANTHROPIC_MESSAGES -> "Anthropic Messages"
    WorkProtocol.OPENAI_RESPONSES -> "OpenAI Responses"
}

private fun capabilityLabel(capability: WorkCapability): String = when (capability) {
    WorkCapability.NEEDS_PROBE -> "待探测"
    WorkCapability.EXECUTABLE -> "可执行"
    WorkCapability.CONSULT_ONLY -> "仅咨询"
    WorkCapability.FAILED -> "不可用"
}
