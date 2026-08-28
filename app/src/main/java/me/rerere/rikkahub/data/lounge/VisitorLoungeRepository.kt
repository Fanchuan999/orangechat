package me.rerere.rikkahub.data.lounge

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.VisitorLoungeDao
import me.rerere.rikkahub.data.db.entity.VisitorLoungeFriendEntity
import me.rerere.rikkahub.data.db.entity.VisitorLoungeMessageEntity
import me.rerere.rikkahub.data.db.entity.VisitorLoungeVisitEntity

interface VisitorLoungeRecordStore {
    fun observeFriends(): Flow<List<FriendPublicRecord>>
    suspend fun getFriend(friendId: String): FriendPublicRecord?
    suspend fun upsertFriend(friend: FriendPublicRecord)
    suspend fun deleteFriend(friendId: String)

    fun observeVisits(): Flow<List<VisitorLoungeVisit>>
    suspend fun visits(): List<VisitorLoungeVisit>
    suspend fun getVisit(visitId: String): VisitorLoungeVisit?
    suspend fun upsertVisit(visit: VisitorLoungeVisit)
    suspend fun updateVisit(visit: VisitorLoungeVisit)

    fun observeTranscript(visitId: String): Flow<List<VisitorLoungeTranscriptEntry>>
    suspend fun transcript(visitId: String): List<VisitorLoungeTranscriptEntry>
    suspend fun appendTranscript(entry: VisitorLoungeTranscriptEntry)
    suspend fun trimTranscript(visitId: String, keep: Int)
}

class VisitorLoungeRepository(
    private val store: VisitorLoungeRecordStore,
    private val removeCredential: suspend (String) -> Unit = {},
    private val clock: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    fun observeFriends(): Flow<List<FriendPublicRecord>> = store.observeFriends()

    suspend fun proactiveFriends(): List<FriendPublicRecord> =
        observeFriends().first().filter { it.consent == VisitorLoungeConsent.ALLOW_PROACTIVE }

    fun observeVisits(): Flow<List<VisitorLoungeVisit>> = store.observeVisits()

    suspend fun visits(): List<VisitorLoungeVisit> = store.visits()

    fun observeTranscript(visitId: String): Flow<List<VisitorLoungeTranscriptEntry>> = store.observeTranscript(visitId)

    suspend fun getFriend(friendId: String): FriendPublicRecord? = store.getFriend(friendId)

    suspend fun getVisit(visitId: String): VisitorLoungeVisit? = store.getVisit(visitId)

    suspend fun saveFriend(friend: FriendPublicRecord) {
        require(friend.id.isNotBlank()) { "Friend ID is required" }
        require(friend.displayName.isNotBlank()) { "Friend name is required" }
        val now = clock()
        val existing = store.getFriend(friend.id)
        store.upsertFriend(
            friend.copy(
                endpoint = VisitorLoungeEndpointPolicy.normalize(friend.endpoint).toString(),
                relationship = VisitorLoungeRedactor.redact(friend.relationship).take(MAX_RELATIONSHIP_CHARACTERS),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    suspend fun deleteFriend(friendId: String) {
        store.deleteFriend(friendId)
        removeCredential(friendId)
    }

    suspend fun createVisit(
        friendId: String,
        sourceConversationId: String?,
        mode: VisitorLoungeVisitMode,
        topic: String,
    ): VisitorLoungeVisit {
        require(store.getFriend(friendId) != null) { "Friend not found" }
        val now = clock()
        val visit = VisitorLoungeVisit(
            id = newId(),
            friendId = friendId,
            sourceConversationId = sourceConversationId,
            mode = mode,
            status = VisitorLoungeVisitStatus.QUEUED,
            topic = VisitorLoungeRedactor.redact(topic).take(MAX_TOPIC_CHARACTERS),
            startedAt = now,
        )
        store.upsertVisit(visit)
        return visit
    }

    suspend fun updateVisitStatus(
        visitId: String,
        status: VisitorLoungeVisitStatus,
        summary: String? = null,
    ) {
        val previous = store.getVisit(visitId) ?: return
        val isTerminal = status in TERMINAL_STATUSES
        store.updateVisit(
            previous.copy(
                status = status,
                summary = summary?.let(VisitorLoungeRedactor::redact)?.take(MAX_SUMMARY_CHARACTERS) ?: previous.summary,
                finishedAt = if (isTerminal) clock() else previous.finishedAt,
            ),
        )
    }

    suspend fun appendTranscript(visitId: String, kind: VisitorLoungeTranscriptKind, text: String) {
        if (store.getVisit(visitId) == null) return
        store.appendTranscript(
            VisitorLoungeTranscriptEntry(
                id = newId(),
                visitId = visitId,
                kind = kind,
                text = VisitorLoungeRedactor.redact(text).take(MAX_TRANSCRIPT_DISPLAY_CHARACTERS),
                createdAt = clock(),
            ),
        )
        store.trimTranscript(visitId, MAX_TRANSCRIPT_ROWS)
    }

    suspend fun recoverIncompleteVisits() {
        store.visits()
            .filter { it.status in INCOMPLETE_STATUSES }
            .forEach { visit -> updateVisitStatus(visit.id, VisitorLoungeVisitStatus.INTERRUPTED, "App was restarted") }
    }

    companion object {
        const val MAX_TRANSCRIPT_ROWS = 20
        const val MAX_TRANSCRIPT_DISPLAY_CHARACTERS = 800
        const val MAX_TOPIC_CHARACTERS = 500
        private const val MAX_RELATIONSHIP_CHARACTERS = 200
        private const val MAX_SUMMARY_CHARACTERS = 300
        private val INCOMPLETE_STATUSES = setOf(
            VisitorLoungeVisitStatus.QUEUED,
            VisitorLoungeVisitStatus.CONNECTING,
            VisitorLoungeVisitStatus.VISITING,
        )
        private val TERMINAL_STATUSES = setOf(
            VisitorLoungeVisitStatus.COMPLETED,
            VisitorLoungeVisitStatus.CANCELLED,
            VisitorLoungeVisitStatus.FAILED,
            VisitorLoungeVisitStatus.TIMED_OUT,
            VisitorLoungeVisitStatus.CONNECTION_LOST,
            VisitorLoungeVisitStatus.INTERRUPTED,
        )
    }
}

class RoomVisitorLoungeRecordStore(
    private val dao: VisitorLoungeDao,
) : VisitorLoungeRecordStore {
    override fun observeFriends(): Flow<List<FriendPublicRecord>> = dao.observeFriends().map { friends ->
        friends.map(VisitorLoungeFriendEntity::toPublicRecord)
    }

    override suspend fun getFriend(friendId: String): FriendPublicRecord? = dao.getFriend(friendId)?.toPublicRecord()

    override suspend fun upsertFriend(friend: FriendPublicRecord) = dao.upsertFriend(friend.toEntity())

    override suspend fun deleteFriend(friendId: String) = dao.deleteFriend(friendId)

    override fun observeVisits(): Flow<List<VisitorLoungeVisit>> = dao.observeVisits().map { visits ->
        visits.map(VisitorLoungeVisitEntity::toVisit)
    }

    override suspend fun visits(): List<VisitorLoungeVisit> = dao.visits().map(VisitorLoungeVisitEntity::toVisit)

    override suspend fun getVisit(visitId: String): VisitorLoungeVisit? = dao.getVisit(visitId)?.toVisit()

    override suspend fun upsertVisit(visit: VisitorLoungeVisit) = dao.upsertVisit(visit.toEntity())

    override suspend fun updateVisit(visit: VisitorLoungeVisit) = dao.upsertVisit(visit.toEntity())

    override fun observeTranscript(visitId: String): Flow<List<VisitorLoungeTranscriptEntry>> =
        dao.observeTranscript(visitId).map { entries -> entries.map(VisitorLoungeMessageEntity::toTranscript) }

    override suspend fun transcript(visitId: String): List<VisitorLoungeTranscriptEntry> =
        dao.transcript(visitId).map(VisitorLoungeMessageEntity::toTranscript)

    override suspend fun appendTranscript(entry: VisitorLoungeTranscriptEntry) = dao.appendTranscript(entry.toEntity())

    override suspend fun trimTranscript(visitId: String, keep: Int) = dao.trimTranscript(visitId, keep)
}

private fun VisitorLoungeFriendEntity.toPublicRecord() = FriendPublicRecord(
    id = id,
    displayName = displayName,
    relationship = relationship,
    endpoint = endpoint,
    consent = runCatching { VisitorLoungeConsent.valueOf(consent) }.getOrDefault(VisitorLoungeConsent.MANUAL_ONLY),
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
)

private fun FriendPublicRecord.toEntity() = VisitorLoungeFriendEntity(
    id = id,
    displayName = displayName,
    relationship = relationship,
    endpoint = endpoint,
    consent = consent.name,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
)

private fun VisitorLoungeVisitEntity.toVisit() = VisitorLoungeVisit(
    id = id,
    friendId = friendId,
    sourceConversationId = sourceConversationId,
    mode = runCatching { VisitorLoungeVisitMode.valueOf(mode) }.getOrDefault(VisitorLoungeVisitMode.MANUAL),
    status = runCatching { VisitorLoungeVisitStatus.valueOf(status) }.getOrDefault(VisitorLoungeVisitStatus.FAILED),
    topic = topic,
    summary = summary,
    startedAt = Instant.ofEpochMilli(startedAt),
    finishedAt = finishedAt?.let(Instant::ofEpochMilli),
)

private fun VisitorLoungeVisit.toEntity() = VisitorLoungeVisitEntity(
    id = id,
    friendId = friendId,
    sourceConversationId = sourceConversationId,
    mode = mode.name,
    status = status.name,
    topic = topic,
    summary = summary,
    startedAt = startedAt.toEpochMilli(),
    finishedAt = finishedAt?.toEpochMilli(),
)

private fun VisitorLoungeMessageEntity.toTranscript() = VisitorLoungeTranscriptEntry(
    id = id,
    visitId = visitId,
    kind = runCatching { VisitorLoungeTranscriptKind.valueOf(kind) }.getOrDefault(VisitorLoungeTranscriptKind.LOCAL_STATUS),
    text = text,
    createdAt = Instant.ofEpochMilli(createdAt),
)

private fun VisitorLoungeTranscriptEntry.toEntity() = VisitorLoungeMessageEntity(
    id = id,
    visitId = visitId,
    kind = kind.name,
    text = text,
    createdAt = createdAt.toEpochMilli(),
)
