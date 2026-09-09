package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.lounge.VisitorLoungeStartResult
import me.rerere.rikkahub.data.lounge.VisitorLoungeToolFriend
import me.rerere.rikkahub.data.lounge.VisitorLoungeToolGateway

/** Native normal-chat Visitor Lounge tool. It deliberately has no endpoint or credential access. */
class VisitorLoungeTools(
    private val gateway: VisitorLoungeToolGateway,
) {
    suspend fun getTools(invocationContext: ToolInvocationContext): List<Tool> {
        val friends = gateway.savedFriends()
        if (friends.isEmpty()) return emptyList()
        return listOf(visitTool(invocationContext, friends))
    }

    private fun visitTool(
        invocationContext: ToolInvocationContext,
        friends: List<VisitorLoungeToolFriend>,
    ) = Tool(
        name = TOOL_NAME,
        description = buildString {
            append("Visit a saved Visitor Lounge friend only when the user explicitly asks. ")
            append("This only starts a visit; do not claim it has completed. Available friends: ")
            append(friends.joinToString { friend -> "${friend.id} (${friend.displayName})" })
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("friend_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Saved friend ID to visit")
                        put("enum", buildJsonArray { friends.forEach { add(JsonPrimitive(it.id)) } })
                    })
                    put("topic", buildJsonObject {
                        put("type", "string")
                        put("description", "A short greeting topic; defaults to a simple hello")
                    })
                },
                required = listOf("friend_id"),
            )
        },
        execute = { input ->
            val parameters = input.jsonObject
            val friendId = parameters["friend_id"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: return@Tool result(success = false, message = "friend_id 是必填项。")
            if (friendId !in friends.map(VisitorLoungeToolFriend::id)) {
                return@Tool result(success = false, message = "所选会客室当前不可用。")
            }
            if (gateway.savedFriends().none { it.id == friendId }) {
                return@Tool result(success = false, message = "所选会客室当前不可用。")
            }
            val topic = parameters["topic"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.take(MAX_TOPIC_CHARACTERS)
                ?.ifBlank { DEFAULT_TOPIC }
                ?: DEFAULT_TOPIC
            when (val started = gateway.startManual(invocationContext.callerConversationId, friendId, topic)) {
                is VisitorLoungeStartResult.Started -> result(
                    success = true,
                    visitId = started.visit.id,
                    state = started.visit.status.name.lowercase(),
                    message = "拜访已开始，正在等待会客室回应；请勿声称已完成。",
                )

                VisitorLoungeStartResult.Busy -> result(
                    success = false,
                    state = "busy",
                    message = "当前已有一场会客室拜访在进行中。",
                )

                VisitorLoungeStartResult.MissingCredential -> result(
                    success = false,
                    state = "unavailable",
                    message = "所选会客室当前不可用。",
                )

                VisitorLoungeStartResult.ProactiveNotAllowed,
                VisitorLoungeStartResult.ProactiveRateLimited,
                -> result(
                    success = false,
                    state = "unavailable",
                    message = "所选会客室当前不可用。",
                )
            }
        },
    )

    private fun result(
        success: Boolean,
        visitId: String? = null,
        state: String? = null,
        message: String,
    ): List<UIMessagePart> = listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("success", success)
                visitId?.let { put("visit_id", it) }
                state?.let { put("state", it) }
                put("message", message)
            }.toString(),
        ),
    )

    private companion object {
        const val TOOL_NAME = "visit_visitor_lounge"
        const val MAX_TOPIC_CHARACTERS = 500
        const val DEFAULT_TOPIC = "打个招呼，聊聊今天。"
    }
}
