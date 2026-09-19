package me.rerere.rikkahub.data.pcbridge

import kotlinx.serialization.Serializable

/** Plaintext exists only on the paired phone and PC after mailbox decryption. */
data class PcBridgeTaskRequest(
    val taskId: String,
    val attemptId: String,
    val idempotencyKey: String,
    val executorType: PcBridgeExecutorType,
    val workspaceId: String,
    val relativeScope: String,
    val brief: String,
)

enum class PcBridgeExecutorType {
    DESKTOP_CLAUDE_CODE,
    DESKTOP_HARNESS,
}

enum class PcBridgeRemoteTaskState {
    QUEUED,
    RECEIVED,
    PLANNING,
    RUNNING,
    WAITING_APPROVAL,
    COMPLETE,
    FAILED,
    CANCELED,
    INTERRUPTED,
}

data class PcBridgeTaskProgress(
    val eventId: String,
    val taskId: String,
    val attemptId: String,
    val sequence: Int,
    val state: PcBridgeRemoteTaskState,
    val summary: String,
)

enum class PcBridgeTaskCardState {
    AWAITING_PC,
    RECEIVED,
    RUNNING,
    WAITING_PHONE_APPROVAL,
    COMPLETE,
    FAILED,
    CANCELED,
}

data class PcBridgeTaskCard(
    val taskId: String,
    val attemptId: String,
    val workspaceId: String,
    val relativeScope: String,
    val brief: String,
    val state: PcBridgeTaskCardState,
    val summary: String,
    val sequence: Int,
    val updatedAtMillis: Long,
    val terminalGeneration: Long? = null,
)

data class PcBridgeTaskRefreshResult(
    val latestProgress: PcBridgeTaskProgress?,
    val terminalResult: PcBridgeTaskTerminalResult?,
    val activeTaskCount: Int,
)

data class PcBridgeTaskTerminalResult(
    val taskId: String,
    val attemptId: String,
    val state: PcBridgeTaskCardState,
    val summary: String,
    val sequence: Int,
)

@Serializable
internal data class PcBridgeEncryptedEnvelope(
    val version: Int,
    val iv: String,
    val ciphertext: String,
)

@Serializable
internal data class PcBridgeRelayEnvelope(
    val envelopeId: String,
    val taskId: String,
    val attemptId: String,
    val targetDeviceId: String,
    val sequence: Int,
    val expiresAt: Long,
    val encryption: PcBridgeEncryptedEnvelope,
)

interface PcBridgeTaskMailbox {
    /**
     * A task tool should never be shown as usable until the phone holds a confirmed pairing.
     * Implementations that are only used in tests may keep the safe default.
     */
    suspend fun isConfigured(): Boolean = false

    suspend fun enqueue(request: PcBridgeTaskRequest)

    suspend fun claimNextProgress(): PcBridgeTaskProgress? = null
}
