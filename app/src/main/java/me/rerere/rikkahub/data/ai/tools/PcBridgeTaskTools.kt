/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.pcbridge.PcBridgeTaskCard
import me.rerere.rikkahub.data.pcbridge.PcBridgeTaskService

/**
 * Native, phone-side access to the paired PC relay.
 *
 * Only routing metadata and AES-GCM ciphertext leave the phone. The model never receives the
 * pairing token or envelope key, and this surface deliberately exposes registered workspace IDs
 * instead of computer paths.
 */
class PcBridgeTaskTools(
    private val taskService: PcBridgeTaskService,
) {
    suspend fun getTools(): List<Tool> {
        if (!taskService.isConfigured()) return emptyList()
        return listOf(submitTaskTool(), refreshTaskBoardTool())
    }

    private fun submitTaskTool() = Tool(
        name = "submit_pc_task",
        description = """
            Securely queue an explicit user-requested computer task for the paired PC. The phone encrypts the task
            before it leaves the device; use only registered workspace IDs, never a Windows path. The task is only
            queued here, so do not claim it is complete. Use for concrete file/code/automation work the user asks to
            perform on their PC. Allowed workspace_id values: daddy-orangechat (Daddy source repository),
            daddy-general (the user's general PC workspace). relative_scope must stay inside that workspace.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("task_brief", buildJsonObject {
                        put("type", "string")
                        put("description", "Complete task brief: objective, constraints, success criteria, and relevant files")
                    })
                    put("workspace_id", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add(JsonPrimitive("daddy-orangechat"))
                            add(JsonPrimitive("daddy-general"))
                        })
                        put("description", "Registered PC workspace; default daddy-orangechat")
                    })
                    put("relative_scope", buildJsonObject {
                        put("type", "string")
                        put("description", "Relative scope within the registered workspace; default .")
                    })
                },
                required = listOf("task_brief"),
            )
        },
        execute = { input ->
            val params = input.jsonObject
            val brief = params["task_brief"]?.jsonPrimitive?.contentOrNull
                ?: return@Tool jsonResult(success = false, error = "task_brief is required")
            val workspaceId = params["workspace_id"]?.jsonPrimitive?.contentOrNull ?: "daddy-orangechat"
            val relativeScope = params["relative_scope"]?.jsonPrimitive?.contentOrNull ?: "."
            try {
                val card = taskService.submit(brief, workspaceId, relativeScope)
                jsonResult(
                    success = true,
                    taskId = card.taskId,
                    state = card.state.name.lowercase(),
                    message = "任务已加密发送，等待电脑接收；请勿声称已完成。",
                )
            } catch (error: Exception) {
                jsonResult(success = false, error = error.message ?: "PC task queue failed")
            }
        },
    )

    private fun refreshTaskBoardTool() = Tool(
        name = "refresh_pc_task_board",
        description = "Fetch one encrypted PC progress event for the paired phone task board. Use only when the user asks about a PC task's progress or result; do not fabricate a status.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = {
            try {
                val updated = taskService.refreshFromPc()
                val active = taskService.cards.value.filter { card -> !card.isTerminal() }
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", true)
                            put("updated", updated != null)
                            put("active_task_count", active.size)
                            if (updated != null) {
                                put("task_id", updated.taskId)
                                put("state", updated.state.name.lowercase())
                                put("summary", updated.summary)
                            }
                        }.toString(),
                    ),
                )
            } catch (error: Exception) {
                jsonResult(success = false, error = error.message ?: "PC task status refresh failed")
            }
        },
    )

    private fun jsonResult(
        success: Boolean,
        taskId: String? = null,
        state: String? = null,
        message: String? = null,
        error: String? = null,
    ): List<UIMessagePart> = listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("success", success)
                taskId?.let { put("task_id", it) }
                state?.let { put("state", it) }
                message?.let { put("message", it) }
                error?.let { put("error", it) }
            }.toString(),
        ),
    )

    private fun PcBridgeTaskCard.isTerminal() = state.name in setOf("COMPLETE", "FAILED", "CANCELED")
}
