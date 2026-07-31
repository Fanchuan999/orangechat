package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.AssistantMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationPromptsTest {
    @Test
    fun `zero memory budget preserves every manual memory`() {
        val prompt = buildMemoryPrompt(
            memories = listOf(
                AssistantMemory(1, "喜欢蓝色玻璃杯"),
                AssistantMemory(2, "晚上更喜欢安静聊天"),
            ),
        )

        assertTrue(prompt.contains("喜欢蓝色玻璃杯"))
        assertTrue(prompt.contains("晚上更喜欢安静聊天"))
    }

    @Test
    fun `memory budget keeps the first entry and stops before later entries`() {
        val prompt = buildMemoryPrompt(
            memories = listOf(
                AssistantMemory(1, "abcd"),
                AssistantMemory(2, "efgh"),
            ),
            contentTokenBudget = 1,
        )

        assertTrue(prompt.contains("abcd"))
        assertFalse(prompt.contains("efgh"))
    }

    @Test
    fun `external memory budget preserves the most recently injected recall first`() {
        val prompt = buildExternalMemoryPrompt(
            recalledMemories = listOf("older", "newer"),
            contentTokenBudget = 2,
        )

        assertTrue(prompt.contains("newer"))
        assertFalse(prompt.contains("older"))
    }

    @Test
    fun `estimated token counter treats four ASCII characters as one token`() {
        assertEquals(1, estimatePromptTokens("abcd"))
        assertEquals(2, estimatePromptTokens("abcde"))
        assertEquals(2, estimatePromptTokens("你好"))
    }
}
