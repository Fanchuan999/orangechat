package me.rerere.rikkahub.data.service

import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProactiveTargetPolicyTest {
    @Test
    fun `explicit night watch conversation wins over configured primary`() {
        val explicit = Uuid.random()
        val configured = Uuid.random()
        val setting = ProactiveMessageSetting(primaryConversationId = configured.toString())

        assertEquals(explicit, requestedProactiveConversationId(explicit, setting))
    }

    @Test
    fun `configured primary is used when trigger has no explicit target`() {
        val configured = Uuid.random()
        val setting = ProactiveMessageSetting(primaryConversationId = configured.toString())

        assertEquals(configured, requestedProactiveConversationId(null, setting))
    }

    @Test
    fun `malformed configured id safely returns null`() {
        val setting = ProactiveMessageSetting(primaryConversationId = "not-a-uuid")

        assertNull(requestedProactiveConversationId(null, setting))
    }

    @Test
    fun `blank configured id safely returns null`() {
        assertNull(requestedProactiveConversationId(null, ProactiveMessageSetting()))
    }
}
