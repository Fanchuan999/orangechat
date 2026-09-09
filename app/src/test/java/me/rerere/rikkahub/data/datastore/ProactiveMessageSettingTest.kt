package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveMessageSettingTest {
    @Test
    fun `old proactive settings default to no primary conversation`() {
        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString<ProactiveMessageSetting>("{\"enabled\":true}")

        assertEquals("", decoded.primaryConversationId)
        assertEquals("", decoded.primaryConversationTitle)
    }

    @Test
    fun `old proactive settings keep autonomous external activity disabled`() {
        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString<ProactiveMessageSetting>("{\"enabled\":true}")

        assertFalse(decoded.autonomousActivity.enabled)
        assertTrue(decoded.autonomousActivity.normalizedAllowedMcpTools().isEmpty())
    }
}
