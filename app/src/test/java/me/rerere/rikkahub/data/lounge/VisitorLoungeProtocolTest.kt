package me.rerere.rikkahub.data.lounge

import java.net.URI
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisitorLoungeProtocolTest {
    @Test
    fun `calls outside the six visitor tools are rejected before network use`() = runBlocking {
        val session = FakeVisitorLoungeMcpSession()
        val client = VisitorLoungeMcpClient(FakeVisitorLoungeMcpSessionFactory(session))
        client.open("https://friend.example/mcp", "visitor-key")

        val failure = runCatching { client.call(session, "delete_everything", buildJsonObject { }) }

        assertTrue(failure.exceptionOrNull() is VisitorLoungeProtocolException)
        assertTrue(session.calls.isEmpty())
    }

    @Test
    fun `server missing a required visitor tool is rejected`() = runBlocking {
        val session = FakeVisitorLoungeMcpSession(tools = VisitorLoungeMcpClient.allowedTools - "end_visit")
        val client = VisitorLoungeMcpClient(FakeVisitorLoungeMcpSessionFactory(session))

        val failure = runCatching { client.open("https://friend.example/mcp", "visitor-key") }

        assertTrue(failure.exceptionOrNull() is VisitorLoungeProtocolException)
        assertTrue(session.closed)
    }

    @Test
    fun `talk payload and displayed remote text are bounded and redacted`() = runBlocking {
        val session = FakeVisitorLoungeMcpSession(
            response = "Authorization: Bearer visitor-key " + "x".repeat(900),
        )
        val client = VisitorLoungeMcpClient(FakeVisitorLoungeMcpSessionFactory(session))
        client.open("https://friend.example/mcp", "visitor-key")

        val tooLong = runCatching { client.talkToHost(session, "visit-1", "x".repeat(501)) }
        val response = client.talkToHost(session, "visit-1", "hello")

        assertTrue(tooLong.exceptionOrNull() is VisitorLoungeProtocolException)
        assertEquals(1, session.calls.size)
        assertTrue(response.length <= VisitorLoungeMcpClient.MAX_DISPLAYED_REMOTE_CHARACTERS)
        assertFalse(response.contains("visitor-key"))
    }

    @Test
    fun `absolute visit deadline rejects a later tool call`() = runBlocking {
        val session = FakeVisitorLoungeMcpSession()
        val now = Instant.ofEpochMilli(1000)
        val client = VisitorLoungeMcpClient(
            factory = FakeVisitorLoungeMcpSessionFactory(session),
            now = { now },
        )
        client.open("https://friend.example/mcp", "visitor-key")

        val failure = runCatching {
            client.call(session, "get_lounge_info", buildJsonObject { }, deadline = now.minusMillis(1))
        }

        assertTrue(failure.exceptionOrNull() is VisitorLoungeProtocolException)
        assertTrue(session.calls.isEmpty())
    }
}

private class FakeVisitorLoungeMcpSession(
    override val tools: Set<String> = VisitorLoungeMcpClient.allowedTools,
    private val response: String = "hello",
) : VisitorLoungeMcpSession {
    val calls = mutableListOf<Pair<String, JsonObject>>()
    var closed = false

    override suspend fun call(name: String, arguments: JsonObject): String {
        calls += name to arguments
        return response
    }

    override suspend fun close() {
        closed = true
    }
}

private class FakeVisitorLoungeMcpSessionFactory(
    private val session: VisitorLoungeMcpSession,
) : VisitorLoungeMcpSessionFactory {
    override suspend fun connect(endpoint: URI, visitorKey: String): VisitorLoungeMcpSession = session
}
