package me.rerere.rikkahub.data.ai.tools

import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.lounge.VisitorLoungeStartResult
import me.rerere.rikkahub.data.lounge.VisitorLoungeToolFriend
import me.rerere.rikkahub.data.lounge.VisitorLoungeToolGateway
import me.rerere.rikkahub.data.lounge.VisitorLoungeVisit
import me.rerere.rikkahub.data.lounge.VisitorLoungeVisitMode
import me.rerere.rikkahub.data.lounge.VisitorLoungeVisitStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisitorLoungeToolsTest {
    @Test
    fun `saved friend can be visited from chat without exposing connection details`() = runBlocking {
        val gateway = RecordingGateway(
            friends = listOf(VisitorLoungeToolFriend(id = "friend-1", displayName = "Alice")),
        )
        val tool = VisitorLoungeTools(gateway)
            .getTools(ToolInvocationContext(callerConversationId = "conversation-1"))
            .single()

        val result = tool.execute(
            Json.parseToJsonElement("""{"friend_id":"friend-1","topic":"打个招呼，聊聊今天"}"""),
        )
        val payload = result.singleTextJson()

        assertEquals("visit_visitor_lounge", tool.name)
        assertFalse(tool.needsApproval)
        assertEquals("conversation-1", gateway.sourceConversationId)
        assertEquals("friend-1", gateway.friendId)
        assertEquals("打个招呼，聊聊今天", gateway.topic)
        assertTrue(payload["success"]!!.jsonPrimitive.boolean)
        assertEquals("visit-1", payload["visit_id"]!!.jsonPrimitive.content)
        assertFalse(tool.description.contains("https://"))
        assertFalse(result.singleText().contains("visitor-key"))
    }

    @Test
    fun `no saved friends means no visitor lounge tool is exposed`() = runBlocking {
        val tools = VisitorLoungeTools(RecordingGateway(emptyList()))
            .getTools(ToolInvocationContext(callerConversationId = "conversation-1"))

        assertTrue(tools.isEmpty())
    }

    @Test
    fun `unknown friend id is rejected before a visit is started`() = runBlocking {
        val gateway = RecordingGateway(
            friends = listOf(VisitorLoungeToolFriend(id = "friend-1", displayName = "Alice")),
        )
        val tool = VisitorLoungeTools(gateway)
            .getTools(ToolInvocationContext(callerConversationId = "conversation-1"))
            .single()

        val result = tool.execute(Json.parseToJsonElement("""{"friend_id":"unknown","topic":"hello"}"""))

        assertFalse(result.singleTextJson()["success"]!!.jsonPrimitive.boolean)
        assertEquals(null, gateway.friendId)
    }

    private fun List<UIMessagePart>.singleText(): String = filterIsInstance<UIMessagePart.Text>().single().text

    private fun List<UIMessagePart>.singleTextJson() = Json.parseToJsonElement(singleText()).jsonObject

    private class RecordingGateway(
        private val friends: List<VisitorLoungeToolFriend>,
    ) : VisitorLoungeToolGateway {
        var sourceConversationId: String? = null
        var friendId: String? = null
        var topic: String? = null

        override suspend fun savedFriends(): List<VisitorLoungeToolFriend> = friends

        override suspend fun startManual(
            sourceConversationId: String?,
            friendId: String,
            topic: String,
        ): VisitorLoungeStartResult {
            this.sourceConversationId = sourceConversationId
            this.friendId = friendId
            this.topic = topic
            return VisitorLoungeStartResult.Started(
                VisitorLoungeVisit(
                    id = "visit-1",
                    friendId = friendId,
                    sourceConversationId = sourceConversationId,
                    mode = VisitorLoungeVisitMode.MANUAL,
                    status = VisitorLoungeVisitStatus.QUEUED,
                    topic = topic,
                    startedAt = Instant.ofEpochMilli(1_000),
                ),
            )
        }
    }
}
