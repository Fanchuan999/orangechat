package me.rerere.rikkahub.data.pcbridge

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcBridgeTaskServiceTest {
    @Test
    fun `submitting the README title task stays awaiting PC until a PC event arrives`() = runBlocking {
        val mailbox = RecordingPcTaskMailbox()
        val service = PcBridgeTaskService(
            mailbox = mailbox,
            taskIdFactory = { "task-readme" },
            attemptIdFactory = { "attempt-readme" },
            nowMillis = { 1_800_000_000_000L },
        )

        val card = service.submit(
            brief = "读取 README 标题",
            workspaceId = "daddy-orangechat",
            relativeScope = ".",
        )

        assertEquals(PcBridgeTaskCardState.AWAITING_PC, card.state)
        assertEquals(PcBridgeTaskCardState.AWAITING_PC, service.cards.first().single().state)
        assertEquals("daddy-orangechat", mailbox.sent.single().workspaceId)
        assertEquals(".", mailbox.sent.single().relativeScope)
    }

    @Test
    fun `newer PC progress wins and duplicate events are ignored`() = runBlocking {
        val mailbox = RecordingPcTaskMailbox()
        val service = PcBridgeTaskService(
            mailbox = mailbox,
            taskIdFactory = { "task-readme" },
            attemptIdFactory = { "attempt-readme" },
            nowMillis = { 1_800_000_000_000L },
        )
        service.submit("读取 README 标题", "daddy-orangechat", ".")

        service.applyProgress(
            PcBridgeTaskProgress(
                eventId = "event-running",
                taskId = "task-readme",
                attemptId = "attempt-readme",
                sequence = 2,
                state = PcBridgeRemoteTaskState.RUNNING,
                summary = "电脑正在读取 README。",
            ),
        )
        service.applyProgress(
            PcBridgeTaskProgress(
                eventId = "event-received-late",
                taskId = "task-readme",
                attemptId = "attempt-readme",
                sequence = 1,
                state = PcBridgeRemoteTaskState.RECEIVED,
                summary = "电脑已收到。",
            ),
        )
        service.applyProgress(
            PcBridgeTaskProgress(
                eventId = "event-running",
                taskId = "task-readme",
                attemptId = "attempt-readme",
                sequence = 3,
                state = PcBridgeRemoteTaskState.COMPLETE,
                summary = "# Daddy",
            ),
        )

        val card = service.cards.first().single()
        assertEquals(PcBridgeTaskCardState.RUNNING, card.state)
        assertEquals(2, card.sequence)
        assertEquals("电脑正在读取 README。", card.summary)
        assertTrue(mailbox.sent.single().brief.contains("README"))
    }

    @Test
    fun `task board cards survive a service restart without storing the full task brief`() = runBlocking {
        val storage = MemoryBoardStorage()
        val first = PcBridgeTaskService(
            mailbox = RecordingPcTaskMailbox(),
            boardStorage = storage,
            taskIdFactory = { "task-readme" },
            attemptIdFactory = { "attempt-readme" },
        )
        first.submit(
            brief = "读取 README 标题，不修改任何文件。".repeat(30),
            workspaceId = "daddy-orangechat",
        )

        val restored = PcBridgeTaskService(
            mailbox = RecordingPcTaskMailbox(),
            boardStorage = storage,
        )
        restored.restore()

        val card = restored.cards.first().single()
        assertEquals("task-readme", card.taskId)
        assertEquals(PcBridgeTaskCardState.AWAITING_PC, card.state)
        assertTrue(card.brief.length <= 320)
    }

    private class RecordingPcTaskMailbox : PcBridgeTaskMailbox {
        val sent = mutableListOf<PcBridgeTaskRequest>()

        override suspend fun enqueue(request: PcBridgeTaskRequest) {
            sent += request
        }
    }

    private class MemoryBoardStorage : PcBridgeTaskBoardRepository {
        private var snapshot: PcBridgeTaskBoardSnapshot? = null

        override suspend fun read(): PcBridgeTaskBoardSnapshot? = snapshot

        override suspend fun write(snapshot: PcBridgeTaskBoardSnapshot) {
            this.snapshot = snapshot
        }
    }
}
