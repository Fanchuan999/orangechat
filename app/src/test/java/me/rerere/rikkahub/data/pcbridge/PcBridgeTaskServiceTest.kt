package me.rerere.rikkahub.data.pcbridge

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `refresh expires a task that the PC did not receive within its delivery window`() = runBlocking {
        var now = 1_800_000_000_000L
        val service = PcBridgeTaskService(
            mailbox = RecordingPcTaskMailbox(),
            taskIdFactory = { "task-readme" },
            attemptIdFactory = { "attempt-readme" },
            nowMillis = { now },
        )
        service.submit("读取 README 标题", "daddy-orangechat", ".")

        now += 10 * 60 * 1000L + 1
        val progress = service.refreshFromPc()

        assertNull(progress)
        val card = service.cards.first().single()
        assertEquals(PcBridgeTaskCardState.FAILED, card.state)
        assertEquals("电脑未在 10 分钟内接收，任务已过期；可重新提交。", card.summary)
    }

    @Test
    fun `refresh drains queued progress so a failed task is no longer active`() = runBlocking {
        val mailbox = RecordingPcTaskMailbox().apply {
            queuedProgress += PcBridgeTaskProgress(
                eventId = "event-received",
                taskId = "task-readme",
                attemptId = "attempt-readme",
                sequence = 1,
                state = PcBridgeRemoteTaskState.RECEIVED,
                summary = "电脑已收到任务。",
            )
            queuedProgress += PcBridgeTaskProgress(
                eventId = "event-running",
                taskId = "task-readme",
                attemptId = "attempt-readme",
                sequence = 2,
                state = PcBridgeRemoteTaskState.RUNNING,
                summary = "电脑正在执行任务。",
            )
            queuedProgress += PcBridgeTaskProgress(
                eventId = "event-failed",
                taskId = "task-readme",
                attemptId = "attempt-readme",
                sequence = 3,
                state = PcBridgeRemoteTaskState.FAILED,
                summary = "电脑执行失败，未修改任何文件。",
            )
        }
        val service = PcBridgeTaskService(
            mailbox = mailbox,
            taskIdFactory = { "task-readme" },
            attemptIdFactory = { "attempt-readme" },
        )
        service.submit("读取 README 标题", "daddy-orangechat", ".")

        val latest = service.refreshFromPc()

        assertEquals(PcBridgeRemoteTaskState.FAILED, latest?.state)
        assertEquals(PcBridgeTaskCardState.FAILED, service.cards.first().single().state)
        assertTrue(mailbox.queuedProgress.isEmpty())
    }

    @Test
    fun `assistant receives a completion after UI already consumed the progress event`() = runBlocking {
        val mailbox = RecordingPcTaskMailbox().apply { queuedProgress += completeProgress() }
        val service = newService(mailbox)
        service.submit("读取 README 标题", "daddy-orangechat", ".")

        assertEquals(PcBridgeRemoteTaskState.COMPLETE, service.refreshFromPc()?.state)
        val result = service.refreshFromPcForAssistant()

        assertNull(result.latestProgress)
        assertEquals("task-readme", result.terminalResult?.taskId)
        assertEquals(PcBridgeTaskCardState.COMPLETE, result.terminalResult?.state)
        assertEquals("# Daddy", result.terminalResult?.summary)
        assertEquals(0, result.activeTaskCount)
    }

    @Test
    fun `assistant terminal result is reported once`() = runBlocking {
        val mailbox = RecordingPcTaskMailbox().apply { queuedProgress += completeProgress() }
        val service = newService(mailbox)
        service.submit("读取 README 标题", "daddy-orangechat", ".")

        val first = service.refreshFromPcForAssistant()
        val second = service.refreshFromPcForAssistant()

        assertEquals("task-readme", first.terminalResult?.taskId)
        assertNull(second.terminalResult)
        assertEquals(0, second.activeTaskCount)
    }

    @Test
    fun `assistant watermark survives board service restart`() = runBlocking {
        val storage = MemoryBoardStorage()
        val first = PcBridgeTaskService(
            mailbox = RecordingPcTaskMailbox().apply { queuedProgress += completeProgress() },
            boardStorage = storage,
            taskIdFactory = { "task-readme" },
            attemptIdFactory = { "attempt-readme" },
        )
        first.submit("读取 README 标题", "daddy-orangechat", ".")
        assertEquals("task-readme", first.refreshFromPcForAssistant().terminalResult?.taskId)

        val restored = PcBridgeTaskService(
            mailbox = RecordingPcTaskMailbox(),
            boardStorage = storage,
        )

        assertNull(restored.refreshFromPcForAssistant().terminalResult)
    }

    @Test
    fun `stale progress does not become the latest assistant result`() = runBlocking {
        val mailbox = RecordingPcTaskMailbox().apply {
            queuedProgress += PcBridgeTaskProgress(
                eventId = "event-running",
                taskId = "task-readme",
                attemptId = "attempt-readme",
                sequence = 2,
                state = PcBridgeRemoteTaskState.RUNNING,
                summary = "电脑正在读取 README。",
            )
            queuedProgress += PcBridgeTaskProgress(
                eventId = "event-stale-complete",
                taskId = "task-readme",
                attemptId = "attempt-readme",
                sequence = 1,
                state = PcBridgeRemoteTaskState.COMPLETE,
                summary = "过期结果",
            )
        }
        val service = newService(mailbox)
        service.submit("读取 README 标题", "daddy-orangechat", ".")

        val result = service.refreshFromPcForAssistant()

        assertEquals(PcBridgeTaskCardState.RUNNING, service.cards.first().single().state)
        assertNull(result.terminalResult)
        assertEquals(PcBridgeRemoteTaskState.RUNNING, result.latestProgress?.state)
    }

    private fun newService(mailbox: RecordingPcTaskMailbox) = PcBridgeTaskService(
        mailbox = mailbox,
        taskIdFactory = { "task-readme" },
        attemptIdFactory = { "attempt-readme" },
    )

    private fun completeProgress() = PcBridgeTaskProgress(
        eventId = "event-complete",
        taskId = "task-readme",
        attemptId = "attempt-readme",
        sequence = 3,
        state = PcBridgeRemoteTaskState.COMPLETE,
        summary = "# Daddy",
    )

    private class RecordingPcTaskMailbox : PcBridgeTaskMailbox {
        val sent = mutableListOf<PcBridgeTaskRequest>()
        val queuedProgress = ArrayDeque<PcBridgeTaskProgress>()

        override suspend fun enqueue(request: PcBridgeTaskRequest) {
            sent += request
        }

        override suspend fun claimNextProgress(): PcBridgeTaskProgress? =
            if (queuedProgress.isEmpty()) null else queuedProgress.removeFirst()
    }

    private class MemoryBoardStorage : PcBridgeTaskBoardRepository {
        private var snapshot: PcBridgeTaskBoardSnapshot? = null

        override suspend fun read(): PcBridgeTaskBoardSnapshot? = snapshot

        override suspend fun write(snapshot: PcBridgeTaskBoardSnapshot) {
            this.snapshot = snapshot
        }
    }
}
