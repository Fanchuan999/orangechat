package me.rerere.rikkahub.data.lounge

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.AppScope
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.VisitorLoungeReportCard
import me.rerere.rikkahub.data.model.toToolPart
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

sealed interface VisitorLoungeStartResult {
    data class Started(val visit: VisitorLoungeVisit) : VisitorLoungeStartResult
    data object Busy : VisitorLoungeStartResult
    data object MissingCredential : VisitorLoungeStartResult
    data object ProactiveNotAllowed : VisitorLoungeStartResult
    data object ProactiveRateLimited : VisitorLoungeStartResult
}

/** Coordinates the app's single outgoing visitor-lounge session. */
class VisitorLoungeVisitCoordinator(
    private val repository: VisitorLoungeRepository,
    private val secretStore: VisitorLoungeSecretStore,
    private val mcpClient: VisitorLoungeMcpClient,
    private val appScope: AppScope,
    private val conversationRepository: ConversationRepository,
    private val proactivePolicy: VisitorLoungeProactivePolicy = VisitorLoungeProactivePolicy(),
    private val now: () -> Instant = Instant::now,
) {
    private val outgoingMutex = Mutex()
    private val visitJobs = ConcurrentHashMap<String, Job>()

    suspend fun startManual(sourceConversationId: String?, friendId: String, topic: String): VisitorLoungeStartResult =
        start(sourceConversationId, friendId, topic, VisitorLoungeVisitMode.MANUAL)

    suspend fun startProactive(sourceConversationId: String, friendId: String, topic: String): VisitorLoungeStartResult =
        start(sourceConversationId, friendId, topic, VisitorLoungeVisitMode.PROACTIVE)

    private suspend fun start(
        sourceConversationId: String?,
        friendId: String,
        topic: String,
        mode: VisitorLoungeVisitMode,
    ): VisitorLoungeStartResult {
        if (!outgoingMutex.tryLock()) return VisitorLoungeStartResult.Busy
        val friend = repository.getFriend(friendId)
        if (friend == null) {
            outgoingMutex.unlock()
            return VisitorLoungeStartResult.MissingCredential
        }
        if (mode == VisitorLoungeVisitMode.PROACTIVE) {
            when (proactivePolicy.evaluate(friend, repository.visits(), now())) {
                VisitorLoungeProactiveDecision.CONSENT_REQUIRED -> {
                    outgoingMutex.unlock()
                    return VisitorLoungeStartResult.ProactiveNotAllowed
                }
                VisitorLoungeProactiveDecision.FRIEND_COOLDOWN,
                VisitorLoungeProactiveDecision.GLOBAL_DAILY_LIMIT
                -> {
                    outgoingMutex.unlock()
                    return VisitorLoungeStartResult.ProactiveRateLimited
                }
                VisitorLoungeProactiveDecision.ALLOW -> Unit
            }
        }
        val hasCredential = secretStore.withKey(friendId) { true } == true
        if (!hasCredential) {
            outgoingMutex.unlock()
            return VisitorLoungeStartResult.MissingCredential
        }
        return try {
            val visit = repository.createVisit(
                friendId = friendId,
                sourceConversationId = sourceConversationId,
                mode = mode,
                topic = topic,
            )
            visitJobs[visit.id] = appScope.launch {
                try {
                    runVisit(visit, friend)
                } finally {
                    visitJobs.remove(visit.id)
                    outgoingMutex.unlock()
                }
            }
            VisitorLoungeStartResult.Started(visit)
        } catch (error: Exception) {
            outgoingMutex.unlock()
            throw error
        }
    }

    suspend fun cancel(visitId: String) {
        visitJobs[visitId]?.cancel()
        repository.updateVisitStatus(visitId, VisitorLoungeVisitStatus.CANCELLED, "Visit cancelled")
    }

    private suspend fun runVisit(visit: VisitorLoungeVisit, friend: FriendPublicRecord) {
        val deadline = now().plus(Duration.ofMinutes(MAX_VISIT_MINUTES))
        var session: VisitorLoungeMcpSession? = null
        var remoteVisitId = visit.id
        var completed = false
        try {
            repository.updateVisitStatus(visit.id, VisitorLoungeVisitStatus.CONNECTING)
            val result = secretStore.withKey(visit.friendId) { visitorKey ->
                val connected = mcpClient.open(friend.endpoint, visitorKey)
                session = connected
                val info = mcpClient.getLoungeInfo(connected, deadline)
                repository.appendTranscript(visit.id, VisitorLoungeTranscriptKind.LOCAL_STATUS, "Connected to visitor lounge")
                repository.appendTranscript(visit.id, VisitorLoungeTranscriptKind.INBOUND, info)
                if (requiresIdentityClaim(info)) {
                    val claim = mcpClient.claimIdentity(connected, "Daddy", deadline)
                    repository.appendTranscript(visit.id, VisitorLoungeTranscriptKind.LOCAL_STATUS, "Identity claimed")
                    repository.appendTranscript(visit.id, VisitorLoungeTranscriptKind.INBOUND, claim)
                }
                val started = mcpClient.beginVisit(connected, visit.id, visit.topic, deadline)
                remoteVisitId = extractRemoteVisitId(started) ?: visit.id
                repository.updateVisitStatus(visit.id, VisitorLoungeVisitStatus.VISITING)
                repository.appendTranscript(visit.id, VisitorLoungeTranscriptKind.OUTBOUND, visit.topic)
                repository.appendTranscript(visit.id, VisitorLoungeTranscriptKind.INBOUND, started)
                val reply = mcpClient.talkToHost(connected, remoteVisitId, visit.topic, deadline)
                repository.appendTranscript(visit.id, VisitorLoungeTranscriptKind.INBOUND, reply)
                val ended = mcpClient.endVisit(connected, remoteVisitId, deadline)
                repository.appendTranscript(visit.id, VisitorLoungeTranscriptKind.LOCAL_STATUS, "Visit ended")
                repository.appendTranscript(visit.id, VisitorLoungeTranscriptKind.INBOUND, ended)
                completed = true
                true
            }
            if (result != true) {
                repository.updateVisitStatus(visit.id, VisitorLoungeVisitStatus.FAILED, "Visitor Key is unavailable")
            } else {
                repository.updateVisitStatus(visit.id, VisitorLoungeVisitStatus.COMPLETED, "Visit completed")
            }
        } catch (error: CancellationException) {
            repository.updateVisitStatus(visit.id, VisitorLoungeVisitStatus.CANCELLED, "Visit cancelled")
            throw error
        } catch (error: VisitorLoungeProtocolException) {
            repository.updateVisitStatus(
                visit.id,
                if (now().isAfter(deadline)) VisitorLoungeVisitStatus.TIMED_OUT else VisitorLoungeVisitStatus.FAILED,
                error.message ?: "Visitor lounge protocol failed",
            )
        } catch (_: Exception) {
            repository.updateVisitStatus(visit.id, VisitorLoungeVisitStatus.FAILED, "Visitor lounge visit failed")
        } finally {
            withContext(NonCancellable) {
                if (!completed) session?.let { activeSession ->
                    runCatching { mcpClient.endVisit(activeSession, remoteVisitId, deadline) }
                }
                runCatching { session?.close() }
                runCatching { appendChatReport(visit.id) }
            }
        }
    }

    private suspend fun appendChatReport(visitId: String) {
        val visit = repository.getVisit(visitId) ?: return
        val sourceConversationId = visit.sourceConversationId ?: return
        val conversationId = runCatching { Uuid.parse(sourceConversationId) }.getOrNull() ?: return
        val conversation = conversationRepository.getConversationById(conversationId) ?: return
        if (conversation.currentMessages.any { message ->
                message.parts.any { part ->
                    part is me.rerere.ai.ui.UIMessagePart.Tool && part.toolCallId == "visitor_lounge_$visitId"
                }
            }
        ) return
        val friend = repository.getFriend(visit.friendId) ?: return
        val card = VisitorLoungeReportCard(
            visitId = visit.id,
            friendDisplayName = friend.displayName,
            status = visit.status.name,
            summary = visit.summary,
        )
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(card.toToolPart()),
        )
        conversationRepository.updateConversation(
            conversation.copy(
                messageNodes = conversation.messageNodes + MessageNode.of(message),
                updateAt = now(),
            ),
        )
    }

    private fun requiresIdentityClaim(info: String): Boolean =
        Regex("""(?i)[\"']?claim_required[\"']?\s*[:=]\s*true""").containsMatchIn(info)

    private fun extractRemoteVisitId(value: String): String? =
        Regex("""[\"']visit_id[\"']\s*:\s*[\"']([^\"']+)[\"']""").find(value)?.groupValues?.getOrNull(1)

    companion object {
        const val MAX_VISIT_MINUTES = 10L
    }
}
