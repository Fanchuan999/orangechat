package me.rerere.rikkahub.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AssistantContextTruncationTest {

    @Test
    fun `cache friendly truncation defaults to disabled`() {
        assertFalse(Assistant().cacheFriendlyContextTruncation)
    }

    @Test
    fun `assistant without cache friendly field decodes as disabled`() {
        val decoded = Json.decodeFromString<Assistant>("""{"name":"Daddy","contextMessageSize":400}""")

        assertFalse(decoded.cacheFriendlyContextTruncation)
    }

    @Test
    fun `assistant selects cache friendly context only when its switch is enabled`() {
        val messages = List(401) { UIMessage.user("message $it") }
        val enabled = Assistant(contextMessageSize = 400, cacheFriendlyContextTruncation = true)
        val disabled = enabled.copy(cacheFriendlyContextTruncation = false)

        assertEquals(301, enabled.selectContextMessages(messages).size)
        assertEquals(400, disabled.selectContextMessages(messages).size)
    }

    @Test
    fun `cache friendly selection reports when it removes older messages`() {
        val messages = List(401) { UIMessage.user("message $it") }
        val assistant = Assistant(contextMessageSize = 400, cacheFriendlyContextTruncation = true)

        val selection = assistant.selectContextMessagesWithMetadata(messages)

        assertEquals(301, selection.messages.size)
        assertEquals(true, selection.didCacheFriendlyTruncate)
    }

    @Test
    fun `ordinary context limit does not report a cache friendly truncation`() {
        val messages = List(401) { UIMessage.user("message $it") }
        val assistant = Assistant(contextMessageSize = 400, cacheFriendlyContextTruncation = false)

        val selection = assistant.selectContextMessagesWithMetadata(messages)

        assertEquals(400, selection.messages.size)
        assertFalse(selection.didCacheFriendlyTruncate)
    }

    @Test
    fun `unavailable cache friendly switch does not report a cache friendly truncation`() {
        val messages = List(101) { UIMessage.user("message $it") }
        val assistant = Assistant(contextMessageSize = 100, cacheFriendlyContextTruncation = true)

        val selection = assistant.selectContextMessagesWithMetadata(messages)

        assertEquals(100, selection.messages.size)
        assertFalse(selection.didCacheFriendlyTruncate)
    }
}
