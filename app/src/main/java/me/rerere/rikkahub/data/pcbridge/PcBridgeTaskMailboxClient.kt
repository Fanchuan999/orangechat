package me.rerere.rikkahub.data.pcbridge

import java.security.SecureRandom
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TASK_ENVELOPE_TTL_MILLIS = 10 * 60 * 1000L

/**
 * Phone-side private mailbox client. Supabase receives only identifiers,
 * routing metadata and AES-GCM ciphertext; it never receives task text.
 */
class PcBridgeTaskMailboxClient(
    private val secretStore: PcBridgeSecretStore,
    private val relayClient: PcBridgeRelayClient,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val opaqueIdFactory: () -> String = ::newOpaqueId,
) : PcBridgeTaskMailbox {
    override suspend fun isConfigured(): Boolean {
        val credentials = secretStore.load() ?: return false
        return try {
            !credentials.pendingConfirmation
        } finally {
            credentials.envelopeKey.fill(0)
        }
    }

    override suspend fun enqueue(request: PcBridgeTaskRequest) {
        val credentials = requireConfirmedCredentials()
        try {
            val envelopeId = requireIdentifier(opaqueIdFactory(), "envelope id")
            val expiresAt = boundedExpiry(nowMillis())
            val plaintext = Json.encodeToString(request.toWirePayload()).toByteArray(Charsets.UTF_8)
            val encryption = try {
                PcBridgeMailboxCipher.encrypt(
                    key = credentials.envelopeKey,
                    plaintext = plaintext,
                    associatedData = PcBridgeMailboxCipher.deliveryAssociatedData(
                        taskId = request.taskId,
                        attemptId = request.attemptId,
                        sequence = 0,
                    ),
                )
            } finally {
                plaintext.fill(0)
            }
            relayClient.enqueueEnvelope(
                credentials,
                PcBridgeRelayEnvelope(
                    envelopeId = envelopeId,
                    taskId = request.taskId,
                    attemptId = request.attemptId,
                    targetDeviceId = credentials.pcDeviceId,
                    sequence = 0,
                    expiresAt = expiresAt,
                    encryption = encryption,
                ),
            )
        } finally {
            credentials.envelopeKey.fill(0)
        }
    }

    override suspend fun claimNextProgress(): PcBridgeTaskProgress? {
        val credentials = requireConfirmedCredentials()
        try {
            val leaseId = requireIdentifier(opaqueIdFactory(), "lease id")
            val envelope = relayClient.claimNextEnvelope(credentials, leaseId) ?: return null
            require(envelope.targetDeviceId == credentials.phoneDeviceId) { "PC bridge progress target is invalid" }
            val plaintext = PcBridgeMailboxCipher.decrypt(
                key = credentials.envelopeKey,
                envelope = envelope.encryption,
                associatedData = PcBridgeMailboxCipher.progressAssociatedData(
                    sourceDeviceId = credentials.pcDeviceId,
                    targetDeviceId = credentials.phoneDeviceId,
                    envelopeId = envelope.envelopeId,
                    taskId = envelope.taskId,
                    attemptId = envelope.attemptId,
                    sequence = envelope.sequence,
                ),
            )
            val progress = try {
                Json.decodeFromString<PcBridgeWireProgress>(plaintext.toString(Charsets.UTF_8)).toDomain()
            } finally {
                plaintext.fill(0)
            }
            require(progress.taskId == envelope.taskId && progress.attemptId == envelope.attemptId) {
                "PC bridge progress metadata does not match its envelope"
            }
            require(progress.sequence == envelope.sequence) { "PC bridge progress sequence does not match its envelope" }
            relayClient.markEnvelopeReceived(credentials, envelope.envelopeId, leaseId)
            return progress
        } finally {
            credentials.envelopeKey.fill(0)
        }
    }

    private suspend fun requireConfirmedCredentials(): PcBridgeCredentials =
        requireNotNull(secretStore.load()) { "PC bridge is not paired on this phone" }.also {
            require(!it.pendingConfirmation) { "PC bridge pairing still needs confirmation" }
        }

    private fun PcBridgeTaskRequest.toWirePayload() = PcBridgeWireTaskRequest(
        version = 1,
        taskId = taskId,
        attemptId = attemptId,
        idempotencyKey = idempotencyKey,
        executorType = executorType.toWireValue(),
        workspaceId = workspaceId,
        relativeScope = relativeScope,
        brief = brief,
    )

    private fun PcBridgeExecutorType.toWireValue(): String = when (this) {
        PcBridgeExecutorType.DESKTOP_CLAUDE_CODE -> "desktop_claude_code"
        PcBridgeExecutorType.DESKTOP_HARNESS -> "desktop_harness"
    }

    private fun PcBridgeWireProgress.toDomain(): PcBridgeTaskProgress {
        require(version == 1) { "Unsupported PC bridge progress version" }
        return PcBridgeTaskProgress(
            eventId = requireIdentifier(eventId, "event id"),
            taskId = requireIdentifier(taskId, "task id"),
            attemptId = requireIdentifier(attemptId, "attempt id"),
            sequence = sequence.also { require(it >= 0) { "PC bridge progress sequence is invalid" } },
            state = state.toDomainState(),
            summary = summary.trim().also { require(it.isNotEmpty() && it.length <= 1_200) { "PC bridge progress summary is invalid" } },
        )
    }

    private fun String.toDomainState(): PcBridgeRemoteTaskState = when (this) {
        "queued" -> PcBridgeRemoteTaskState.QUEUED
        "received" -> PcBridgeRemoteTaskState.RECEIVED
        "planning" -> PcBridgeRemoteTaskState.PLANNING
        "running" -> PcBridgeRemoteTaskState.RUNNING
        "waiting_approval" -> PcBridgeRemoteTaskState.WAITING_APPROVAL
        "complete" -> PcBridgeRemoteTaskState.COMPLETE
        "failed" -> PcBridgeRemoteTaskState.FAILED
        "canceled" -> PcBridgeRemoteTaskState.CANCELED
        "interrupted" -> PcBridgeRemoteTaskState.INTERRUPTED
        else -> throw IllegalArgumentException("PC bridge progress state is invalid")
    }

    private fun boundedExpiry(now: Long): Long {
        require(now > 0 && now <= Long.MAX_VALUE - TASK_ENVELOPE_TTL_MILLIS) { "PC bridge clock is invalid" }
        return now + TASK_ENVELOPE_TTL_MILLIS
    }

    private fun requireIdentifier(value: String, field: String): String {
        require(IDENTIFIER.matches(value)) { "Invalid PC bridge $field" }
        return value
    }

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z0-9_-]{1,128}")

        fun newOpaqueId(): String = ByteArray(18).also(SecureRandom()::nextBytes).let(PcBridgeCrypto::encodeBase64Url)
    }
}

@Serializable
private data class PcBridgeWireTaskRequest(
    val version: Int,
    val taskId: String,
    val attemptId: String,
    val idempotencyKey: String,
    val executorType: String,
    val workspaceId: String,
    val relativeScope: String,
    val brief: String,
)

@Serializable
private data class PcBridgeWireProgress(
    val version: Int,
    val eventId: String,
    val taskId: String,
    val attemptId: String,
    val sequence: Int,
    val state: String,
    val summary: String,
)
