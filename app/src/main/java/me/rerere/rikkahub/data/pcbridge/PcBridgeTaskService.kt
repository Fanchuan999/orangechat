package me.rerere.rikkahub.data.pcbridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom

class PcBridgeTaskService(
    private val mailbox: PcBridgeTaskMailbox,
    private val taskIdFactory: () -> String = ::newOpaqueId,
    private val attemptIdFactory: () -> String = ::newOpaqueId,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val boardStorage: PcBridgeTaskBoardRepository = InMemoryPcBridgeTaskBoardRepository(),
) {
    private val mutableCards = MutableStateFlow<List<PcBridgeTaskCard>>(emptyList())
    val cards: StateFlow<List<PcBridgeTaskCard>> = mutableCards.asStateFlow()

    private val handledEventIds = mutableSetOf<String>()
    private val restoreMutex = Mutex()
    private var restored = false

    /** The task surface is available only after a phone-to-PC pairing has been confirmed. */
    suspend fun isConfigured(): Boolean = mailbox.isConfigured()

    suspend fun submit(
        brief: String,
        workspaceId: String,
        relativeScope: String = ".",
    ): PcBridgeTaskCard {
        restore()
        val request = PcBridgeTaskRequest(
            taskId = requireIdentifier(taskIdFactory(), "task id"),
            attemptId = requireIdentifier(attemptIdFactory(), "attempt id"),
            idempotencyKey = requireIdentifier(taskIdFactory(), "idempotency key"),
            executorType = PcBridgeExecutorType.DESKTOP_CLAUDE_CODE,
            workspaceId = requireWorkspaceId(workspaceId),
            relativeScope = requireRelativeScope(relativeScope),
            brief = requireBrief(brief),
        )
        mailbox.enqueue(request)
        return PcBridgeTaskCard(
            taskId = request.taskId,
            attemptId = request.attemptId,
            workspaceId = request.workspaceId,
            relativeScope = request.relativeScope,
            brief = request.brief.take(MAX_CARD_BRIEF_CHARS),
            state = PcBridgeTaskCardState.AWAITING_PC,
            summary = "任务已加密发送，等待电脑接收。",
            sequence = -1,
            updatedAtMillis = nowMillis(),
        ).also { addCard(it) }
    }

    suspend fun applyProgress(progress: PcBridgeTaskProgress) {
        restore()
        if (!handledEventIds.add(requireIdentifier(progress.eventId, "event id"))) return
        requireIdentifier(progress.taskId, "task id")
        requireIdentifier(progress.attemptId, "attempt id")
        require(progress.sequence >= 0) { "Task progress sequence is invalid" }
        require(progress.summary.isNotBlank() && progress.summary.length <= MAX_SUMMARY_CHARS) {
            "Task progress summary is invalid"
        }

        val current = mutableCards.value
        val index = current.indexOfFirst { card ->
            card.taskId == progress.taskId && card.attemptId == progress.attemptId
        }
        if (index < 0 || progress.sequence <= current[index].sequence) {
            persist()
            return
        }
        val updated = current[index].copy(
            state = progress.state.toCardState(),
            summary = progress.summary,
            sequence = progress.sequence,
            updatedAtMillis = nowMillis(),
        )
        mutableCards.value = current.toMutableList().also { it[index] = updated }
        persist()
    }

    /** Pulls one compact PC event. The caller may invoke this from a refresh button or a later poller. */
    suspend fun refreshFromPc(): PcBridgeTaskProgress? {
        restore()
        val progress = mailbox.claimNextProgress() ?: return null
        applyProgress(progress)
        return progress
    }

    /** Restores only compact local card state; it does not read chat history, Ombre or Supabase. */
    suspend fun restore() {
        restoreMutex.withLock {
            if (restored) return
            val snapshot = boardStorage.read()
            if (snapshot != null) {
                mutableCards.value = snapshot.cards
                handledEventIds += snapshot.handledEventIds
            }
            restored = true
        }
    }

    private suspend fun addCard(card: PcBridgeTaskCard) {
        mutableCards.value = mutableCards.value + card
        persist()
    }

    private suspend fun persist() {
        boardStorage.write(
            PcBridgeTaskBoardSnapshot(
                cards = mutableCards.value.takeLast(MAX_STORED_CARDS),
                handledEventIds = handledEventIds.toList().takeLast(MAX_HANDLED_EVENT_IDS),
            ),
        )
    }

    private fun PcBridgeRemoteTaskState.toCardState(): PcBridgeTaskCardState = when (this) {
        PcBridgeRemoteTaskState.QUEUED -> PcBridgeTaskCardState.AWAITING_PC
        PcBridgeRemoteTaskState.RECEIVED -> PcBridgeTaskCardState.RECEIVED
        PcBridgeRemoteTaskState.PLANNING,
        PcBridgeRemoteTaskState.RUNNING -> PcBridgeTaskCardState.RUNNING
        PcBridgeRemoteTaskState.WAITING_APPROVAL -> PcBridgeTaskCardState.WAITING_PHONE_APPROVAL
        PcBridgeRemoteTaskState.COMPLETE -> PcBridgeTaskCardState.COMPLETE
        PcBridgeRemoteTaskState.FAILED,
        PcBridgeRemoteTaskState.INTERRUPTED -> PcBridgeTaskCardState.FAILED
        PcBridgeRemoteTaskState.CANCELED -> PcBridgeTaskCardState.CANCELED
    }

    private fun requireIdentifier(value: String, field: String): String {
        require(IDENTIFIER.matches(value)) { "Invalid $field" }
        return value
    }

    private fun requireWorkspaceId(value: String): String {
        val workspaceId = requireIdentifier(value, "workspace id")
        require(workspaceId in REGISTERED_WORKSPACE_IDS) {
            "Task workspace is not registered on the paired PC"
        }
        return workspaceId
    }

    private fun requireRelativeScope(value: String): String {
        val normalized = value.trim().replace('\\', '/').removePrefix("./").trimEnd('/').ifEmpty { "." }
        require(normalized.length <= MAX_SCOPE_CHARS && !normalized.startsWith('/') &&
            !normalized.matches(Regex("^[A-Za-z]:.*")) &&
            normalized.split('/').none { it == ".." || it.isEmpty() && normalized != "." }) {
            "Task scope must stay inside the registered workspace"
        }
        return normalized
    }

    private fun requireBrief(value: String): String {
        val normalized = value.trim()
        require(normalized.isNotEmpty() && normalized.length <= MAX_BRIEF_CHARS) { "Task brief is invalid" }
        return normalized
    }

    private companion object {
        const val MAX_BRIEF_CHARS = 20_000
        const val MAX_SCOPE_CHARS = 512
        const val MAX_SUMMARY_CHARS = 1_200
        const val MAX_CARD_BRIEF_CHARS = 320
        const val MAX_STORED_CARDS = 80
        const val MAX_HANDLED_EVENT_IDS = 256
        val IDENTIFIER = Regex("[A-Za-z0-9_-]{1,128}")
        val REGISTERED_WORKSPACE_IDS = setOf("daddy-orangechat", "daddy-general")

        fun newOpaqueId(): String = ByteArray(18).also(SecureRandom()::nextBytes).let(PcBridgeCrypto::encodeBase64Url)
    }
}

private class InMemoryPcBridgeTaskBoardRepository : PcBridgeTaskBoardRepository {
    private var snapshot: PcBridgeTaskBoardSnapshot? = null

    override suspend fun read(): PcBridgeTaskBoardSnapshot? = snapshot

    override suspend fun write(snapshot: PcBridgeTaskBoardSnapshot) {
        this.snapshot = snapshot
    }
}
