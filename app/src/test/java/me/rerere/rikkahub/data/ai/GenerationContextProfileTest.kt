package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationContextProfileTest {
    @Test
    fun `voice calls retain only a small recent history and no operational surface`() {
        val profile = GenerationContextProfile.VoiceCall

        assertEquals(12, profile.maxRecentMessages)
        assertFalse(profile.allowsTools)
        assertFalse(profile.allowsExternalMemoryRecall)
        assertFalse(profile.allowsOperationalRules)
    }

    @Test
    fun `normal chat keeps its existing full request surface`() {
        val profile = GenerationContextProfile.Default

        assertNull(profile.maxRecentMessages)
        assertTrue(profile.allowsTools)
        assertTrue(profile.allowsExternalMemoryRecall)
        assertTrue(profile.allowsOperationalRules)
    }
}
