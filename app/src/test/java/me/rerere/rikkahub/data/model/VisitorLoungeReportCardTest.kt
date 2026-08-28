package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisitorLoungeReportCardTest {
    @Test
    fun `visitor report serializes only local safe fields and is excluded from generation`() {
        val report = VisitorLoungeReportCard(
            visitId = "visit-1",
            friendDisplayName = "Alice",
            status = "COMPLETED",
            summary = "Short local summary",
        )
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(report.toToolPart()))

        assertTrue(message.isVisitorLoungeReportCard())
        assertEquals(emptyList<UIMessage>(), listOf(message).withoutVisitorLoungeReportCards())
        assertFalse(report.toToolPart().input.contains("https://"))
        assertFalse(report.toToolPart().input.contains("Bearer"))
    }

    @Test
    fun `ordinary tool messages are never filtered`() {
        val ordinary = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool("call-1", "ordinary_tool", "{}")),
        )

        assertEquals(listOf(ordinary), listOf(ordinary).withoutVisitorLoungeReportCards())
    }
}
