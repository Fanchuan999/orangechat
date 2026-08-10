package me.rerere.rikkahub.data.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NightWatchEvidenceTest {
    @Test
    fun `night watch keeps only recent user and assistant plain text`() {
        val messages = (1..14).map { index ->
            UIMessage(
                role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("message-$index")),
            )
        } + UIMessage(
            role = MessageRole.TOOL,
            parts = listOf(UIMessagePart.Text("tool-result")),
        )

        val evidence = nightWatchEvidence(messages, limit = 12)

        assertEquals(12, evidence.history.size)
        assertTrue(evidence.history.none { it.role == MessageRole.TOOL })
        assertEquals("message-14", evidence.lastUserText)
    }

    @Test
    fun `night watch strips images reasoning and tool parts from evidence`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Image("local://photo"),
                    UIMessagePart.Text("晚安"),
                    UIMessagePart.Reasoning("not user evidence"),
                ),
            ),
        )

        val evidence = nightWatchEvidence(messages)

        assertEquals("晚安", evidence.lastUserText)
        assertEquals(listOf(UIMessagePart.Text("晚安")), evidence.history.single().parts)
    }
}
