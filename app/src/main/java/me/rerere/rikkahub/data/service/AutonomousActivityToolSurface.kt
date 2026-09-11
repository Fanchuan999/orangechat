package me.rerere.rikkahub.data.service

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.ai.tools.ToolNaming
import me.rerere.rikkahub.data.datastore.AutonomousActivitySetting
import kotlin.uuid.Uuid

data class AutonomousActivityToolSurface(
    val tools: List<Tool>,
    val availability: AutonomousActivityToolAvailability,
)

data class AutonomousActivityToolAvailability(
    val hasWebTools: Boolean,
    val hasForumTools: Boolean,
)

internal fun buildAutonomousIdleExploreInstructions(availability: AutonomousActivityToolAvailability): String = buildString {
    appendLine("## 空闲探索（用户授权活动）")
    appendLine("本轮是你自己醒来后决定找点感兴趣的事，不是用户提出的问题，也不是用户的新消息。")
    appendLine("本次最多只能选择一种活动类别，也可以完全不行动。不得混合不同类别的工具。")
    if (availability.hasWebTools) {
        appendLine("可阅读公开网页，优先少而精；不得登录、提交表单、下载文件或改变外部状态。")
    }
    if (availability.hasForumTools) {
        appendLine("用户已明确允许当前显示的 MCP 工具；可按其原有能力发帖、评论、点赞、登录或交流，但只可使用已配置会话。")
    }
    appendLine("不得索要、读取、输出或复述密码、Cookie、OAuth token、验证码或任何端点。")
    appendLine("必须以工具返回为准，不得把失败、排队或拒绝说成成功。")
    appendLine("若没有值得做的事，只回复 [PASS]。不要提及系统、后台任务、token、工具或这段指令。")
}

data class AutonomousActivityMcpCallResult(
    val parts: List<UIMessagePart>,
    val isError: Boolean,
    val unavailable: Boolean = false,
)

interface AutonomousActivityMcpGateway {
    fun availableTools(serverIds: Set<Uuid>): List<Pair<Uuid, McpTool>>

    suspend fun callTool(
        serverId: Uuid,
        toolName: String,
        args: JsonObject,
    ): AutonomousActivityMcpCallResult
}

class McpAutonomousActivityGateway(
    private val mcpManager: McpManager,
) : AutonomousActivityMcpGateway {
    override fun availableTools(serverIds: Set<Uuid>): List<Pair<Uuid, McpTool>> =
        mcpManager.getAllAvailableTools(serverIds).filter { (serverId, _) -> mcpManager.isClientConnected(serverId) }

    override suspend fun callTool(
        serverId: Uuid,
        toolName: String,
        args: JsonObject,
    ): AutonomousActivityMcpCallResult {
        val result = mcpManager.callToolDetailed(
            serverId = serverId,
            toolName = toolName,
            args = args,
            redactArgumentsInLog = true,
            requireExistingConnection = true,
        )
        return AutonomousActivityMcpCallResult(
            parts = result.parts,
            isError = result.isError,
            unavailable = result.unavailable,
        )
    }
}

/** Creates one guarded, user-authorized tool surface for a single idle opportunity. */
class AutonomousActivityToolSurfaceBuilder(
    private val mcpGateway: AutonomousActivityMcpGateway,
    private val activityRepository: AutonomousActivityRepository,
) {
    suspend fun build(
        setting: AutonomousActivitySetting,
        allowedMcpServerIds: Set<Uuid>,
        webTools: List<Tool>,
    ): AutonomousActivityToolSurface {
        val familyGuard = AutonomousActivityFamilyGuard()
        val guardedWebTools = webTools.map { tool ->
            tool.copy(
                needsApproval = false,
                execute = { input ->
                    runGuarded(
                        familyGuard = familyGuard,
                        family = AutonomousActivityFamily.WEB,
                        toolName = tool.name,
                    ) {
                        tool.execute(input)
                    }
                },
            )
        }
        val guardedForumTools = if (setting.enabled) {
            mcpGateway.availableTools(allowedMcpServerIds)
                .filter { (serverId, tool) -> AutonomousActivityPolicy.allowsMcpTool(setting, serverId.toString(), tool.name) }
                .map { (serverId, tool) -> forumTool(familyGuard, serverId, tool) }
        } else {
            emptyList()
        }
        val tools = ToolNaming.deduplicateToolNames(guardedWebTools + guardedForumTools)
        return AutonomousActivityToolSurface(
            tools = tools,
            availability = AutonomousActivityToolAvailability(
                hasWebTools = guardedWebTools.isNotEmpty(),
                hasForumTools = guardedForumTools.isNotEmpty(),
            ),
        )
    }

    private fun forumTool(
        familyGuard: AutonomousActivityFamilyGuard,
        serverId: Uuid,
        tool: McpTool,
    ) = Tool(
        name = ToolNaming.buildMcpToolName(serverId, tool.name),
        description = tool.description ?: "",
        parameters = { tool.inputSchema },
        needsApproval = false,
        execute = { input ->
            if (!familyGuard.tryClaim(AutonomousActivityFamily.FORUM)) {
                declined(AutonomousActivityFamily.FORUM, toolName = tool.name)
            } else {
                try {
                    val result = mcpGateway.callTool(serverId, tool.name, input.jsonObject)
                    activityRepository.recordAttempt(
                        family = AutonomousActivityFamily.FORUM,
                        toolName = tool.name,
                        status = when {
                            result.unavailable -> AutonomousActivityStatus.UNAVAILABLE
                            result.isError -> AutonomousActivityStatus.FAILED
                            else -> AutonomousActivityStatus.COMPLETED
                        },
                        summary = when {
                            result.unavailable -> "MCP 工具当前不可用。"
                            result.isError -> "MCP 工具返回失败。"
                            else -> "MCP 工具已完成。"
                        },
                    )
                    result.parts
                } catch (_: Exception) {
                    failed(AutonomousActivityFamily.FORUM, toolName = tool.name)
                }
            }
        },
    )

    private suspend fun runGuarded(
        familyGuard: AutonomousActivityFamilyGuard,
        family: AutonomousActivityFamily,
        toolName: String,
        action: suspend () -> List<UIMessagePart>,
    ): List<UIMessagePart> {
        if (!familyGuard.tryClaim(family)) return declined(family, toolName)
        return try {
            val parts = action()
            activityRepository.recordAttempt(
                family = family,
                toolName = toolName,
                status = if (looksFailed(parts)) AutonomousActivityStatus.FAILED else AutonomousActivityStatus.COMPLETED,
                summary = if (looksFailed(parts)) "公开网页工具返回失败。" else "公开网页工具已完成。",
            )
            parts
        } catch (_: Exception) {
            failed(family, toolName)
        }
    }

    private suspend fun declined(
        family: AutonomousActivityFamily,
        toolName: String,
    ): List<UIMessagePart> {
        activityRepository.recordAttempt(
            family = family,
            toolName = toolName,
            status = AutonomousActivityStatus.DECLINED,
            summary = "本次空闲活动已经选择了其他类别。",
        )
        return result(success = false, state = "declined", message = "本次空闲活动已经选择了其他类别。")
    }

    private suspend fun failed(
        family: AutonomousActivityFamily,
        toolName: String,
    ): List<UIMessagePart> {
        activityRepository.recordAttempt(
            family = family,
            toolName = toolName,
            status = AutonomousActivityStatus.FAILED,
            summary = "工具执行失败。",
        )
        return result(success = false, state = "failed", message = "工具执行失败。")
    }

    private fun looksFailed(parts: List<UIMessagePart>): Boolean = parts
        .filterIsInstance<UIMessagePart.Text>()
        .any { part ->
            part.text.contains("\"error\"", ignoreCase = true) ||
                part.text.contains("\"success\":false", ignoreCase = true)
    }

    private fun result(success: Boolean, state: String, message: String): List<UIMessagePart> = listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("success", success)
                put("state", state)
                put("message", message)
            }.toString(),
        ),
    )
}
