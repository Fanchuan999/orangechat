package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.pcbridge.PcBridgeTaskMailbox
import me.rerere.rikkahub.data.pcbridge.PcBridgeTaskRequest
import me.rerere.rikkahub.data.pcbridge.PcBridgeTaskService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcBridgeTaskToolsTest {
    @Test
    fun `paired phone exposes an encrypted PC task submission tool without an extra model approval`() = runBlocking {
        val mailbox = RecordingMailbox()
        val tools = PcBridgeTaskTools(
            PcBridgeTaskService(
                mailbox = mailbox,
                taskIdFactory = { "task-readme" },
                attemptIdFactory = { "attempt-readme" },
            ),
        ).getTools()

        val submit = tools.single { it.name == "submit_pc_task" }
        submit.execute(
            Json.parseToJsonElement(
                """{"task_brief":"读取 README 标题，不修改任何文件。","workspace_id":"daddy-orangechat","relative_scope":"."}""",
            ),
        )

        assertFalse(submit.needsApproval)
        assertEquals("读取 README 标题，不修改任何文件。", mailbox.requests.single().brief)
        assertEquals("daddy-orangechat", mailbox.requests.single().workspaceId)
        assertTrue(tools.any { it.name == "refresh_pc_task_board" })
    }

    @Test
    fun `unpaired phone does not expose PC task tools`() = runBlocking {
        val tools = PcBridgeTaskTools(PcBridgeTaskService(UnpairedMailbox())).getTools()

        assertTrue(tools.isEmpty())
    }

    private class RecordingMailbox : PcBridgeTaskMailbox {
        val requests = mutableListOf<PcBridgeTaskRequest>()

        override suspend fun isConfigured(): Boolean = true

        override suspend fun enqueue(request: PcBridgeTaskRequest) {
            requests += request
        }
    }

    private class UnpairedMailbox : PcBridgeTaskMailbox {
        override suspend fun enqueue(request: PcBridgeTaskRequest) = Unit
    }
}
