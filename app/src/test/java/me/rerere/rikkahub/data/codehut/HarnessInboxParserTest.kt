package me.rerere.rikkahub.data.codehut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessInboxParserTest {
    private val parser = HarnessInboxParser()

    @Test
    fun queuedHeaderBeforeTurnStartIsQueued() {
        val tasks = parser.parse(
            listOf(
                HarnessInboxEvent("user/message", taskText("daddy-1", "排队任务")),
            ),
        )

        assertEquals(HarnessInboxTaskState.QUEUED, tasks.single().state)
    }

    @Test
    fun endedTaskIsNotReturnedAsActive() {
        val tasks = parser.parse(
            listOf(
                HarnessInboxEvent("turn/start"),
                HarnessInboxEvent("user/message", taskText("daddy-1", "已完成任务")),
                HarnessInboxEvent("assistant/message", "完成"),
                HarnessInboxEvent("turn/end"),
            ),
        )

        assertTrue(tasks.isEmpty())
    }

    @Test
    fun unknownEventNeverBecomesAwaitingConfirmation() {
        val task = parser.parse(
            listOf(
                HarnessInboxEvent("turn/start"),
                HarnessInboxEvent("user/message", taskText("daddy-1", "未知状态任务")),
                HarnessInboxEvent("request/context"),
            ),
        ).single()

        assertEquals(HarnessInboxTaskState.RUNNING, task.state)
    }

    private fun taskText(id: String, title: String) = """
        【Daddy任务】
        task_id: $id
        created_at: 2026-08-24T10:00:00.000Z
        title: $title
        【任务简报】
        [REDACTED]
    """.trimIndent()
}
