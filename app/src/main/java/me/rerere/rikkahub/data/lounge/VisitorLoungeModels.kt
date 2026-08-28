package me.rerere.rikkahub.data.lounge

import java.time.Instant

/** Public friend metadata. Visitor credentials deliberately have no representation in this model. */
data class FriendPublicRecord(
    val id: String,
    val displayName: String,
    val relationship: String = "",
    val endpoint: String,
    val consent: VisitorLoungeConsent = VisitorLoungeConsent.MANUAL_ONLY,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

data class VisitorLoungeFriend(
    val public: FriendPublicRecord,
    val lastProactiveVisitAt: Instant? = null,
)

data class VisitorLoungeVisit(
    val id: String,
    val friendId: String,
    val sourceConversationId: String?,
    val mode: VisitorLoungeVisitMode,
    val status: VisitorLoungeVisitStatus,
    val topic: String,
    val summary: String = "",
    val startedAt: Instant = Instant.now(),
    val finishedAt: Instant? = null,
)

data class VisitorLoungeTranscriptEntry(
    val id: String,
    val visitId: String,
    val kind: VisitorLoungeTranscriptKind,
    val text: String,
    val createdAt: Instant = Instant.now(),
)

enum class VisitorLoungeVisitMode {
    MANUAL,
    PROACTIVE,
}

enum class VisitorLoungeVisitStatus {
    QUEUED,
    CONNECTING,
    VISITING,
    COMPLETED,
    CANCELLED,
    FAILED,
    TIMED_OUT,
    CONNECTION_LOST,
    INTERRUPTED,
}

enum class VisitorLoungeTranscriptKind {
    LOCAL_STATUS,
    OUTBOUND,
    INBOUND,
}

enum class VisitorLoungeConsent {
    MANUAL_ONLY,
    ALLOW_PROACTIVE,
}
