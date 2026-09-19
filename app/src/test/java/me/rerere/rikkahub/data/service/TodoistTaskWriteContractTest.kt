package me.rerere.rikkahub.data.service

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.rikkahub.data.ai.mcp.McpTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.uuid.Uuid

class TodoistTaskWriteContractTest {
    @Test
    fun `guessed legacy Todoist fields never authorize a write`() {
        val candidate = TodoistMcpToolCandidate(
            serverId = Uuid.random(),
            serverName = "Todoist",
            tool = McpTool(
                name = "create_task",
                inputSchema = schema("content", "description", "due_date", "operation_key"),
            ),
        )

        assertNull(TodoistTaskWriteContract.resolve(listOf(candidate)))
    }

    @Test
    fun `only an explicit idempotent deadline contract authorizes a write`() {
        val serverId = Uuid.random()
        val candidate = TodoistMcpToolCandidate(
            serverId = serverId,
            serverName = "Todoist MCP",
            tool = McpTool(
                name = "create_task",
                inputSchema = schema("content", "description", "due_datetime", "idempotency_key"),
            ),
        )

        val contract = TodoistTaskWriteContract.resolve(listOf(candidate))

        assertEquals(serverId, contract?.serverId)
        assertEquals("create_task", contract?.toolName)
        assertTrue(contract?.accepts("content") == true)
        assertTrue(contract?.accepts("description") == true)
        assertTrue(contract?.accepts("due_datetime") == true)
        assertTrue(contract?.accepts("idempotency_key") == true)
    }

    @Test
    fun `Todoist write uses a UTC RFC3339 due time and keeps the operation key`() {
        val dueAt = ZonedDateTime.of(2026, 12, 11, 12, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli()
        val draft = TodoistDeadlineDraft(
            deadlineId = "deadline",
            revision = 3,
            operationKey = "deadline:3",
            previewHash = "preview",
            content = "考试：英语六级",
            description = "截止：2026-12-11 12:00",
            dueAtEpochMillis = dueAt,
        )

        val args = TodoistTaskWriteContract.argumentsFor(draft)

        assertEquals("2026-12-11T04:00:00Z", args["due_datetime"]?.toString()?.trim('"'))
        assertEquals("deadline:3", args["idempotency_key"]?.toString()?.trim('"'))
    }

    @Test
    fun `accepts a Todoist alphanumeric task ID from a successful response`() {
        val taskId = TodoistTaskResultParser.extractTaskId(
            listOf(me.rerere.ai.ui.UIMessagePart.Text("{\"id\":\"6XGgmFVcrG5RRjVr\"}")),
        )

        assertEquals("6XGgmFVcrG5RRjVr", taskId)
    }

    private fun schema(vararg fields: String): InputSchema.Obj = InputSchema.Obj(
        properties = buildJsonObject {
            fields.forEach { field ->
                put(field, buildJsonObject { put("type", "string") })
            }
        },
    )
}
