package me.rerere.rikkahub.data.ai

/**
 * Decides which completed part of a conversation can be replaced by a local rolling digest.
 *
 * The actual digest text is stored locally. This planner knows only message identifiers, so it
 * can detect a selected-branch change without persisting any extra chat content.
 */
object ConversationDigestPlanner {
    const val RAW_MESSAGE_WINDOW = 30
    const val DIGEST_BATCH_SIZE = 15

    fun plan(
        messageIds: List<String>,
        current: ConversationDigestRecord?,
    ): ConversationDigestPlan {
        if (messageIds.size <= RAW_MESSAGE_WINDOW) {
            return ConversationDigestPlan.NoDigest
        }

        val usableCurrent = current?.takeIf {
            it.coveredMessageCount > 0 &&
                it.summary.isNotBlank() &&
                it.coveredMessageCount <= messageIds.size &&
                prefixKey(messageIds, it.coveredMessageCount) == it.coveredPrefixKey
        }

        if (usableCurrent != null) {
            val nextCoveredMessageCount = usableCurrent.coveredMessageCount + DIGEST_BATCH_SIZE
            return if (messageIds.size - RAW_MESSAGE_WINDOW >= nextCoveredMessageCount) {
                compact(
                    previousSummary = usableCurrent.summary,
                    sourceStartIndex = usableCurrent.coveredMessageCount,
                    sourceEndExclusive = nextCoveredMessageCount,
                    messageIds = messageIds,
                )
            } else {
                ConversationDigestPlan.Ready(
                    summary = usableCurrent.summary,
                    rawMessageStartIndex = usableCurrent.coveredMessageCount,
                    rawMessageCount = messageIds.size - usableCurrent.coveredMessageCount,
                )
            }
        }

        if (messageIds.size < RAW_MESSAGE_WINDOW + DIGEST_BATCH_SIZE) {
            return ConversationDigestPlan.NoDigest
        }

        return compact(
            previousSummary = null,
            sourceStartIndex = 0,
            sourceEndExclusive = DIGEST_BATCH_SIZE,
            messageIds = messageIds,
        )
    }

    fun shouldNotifyFailure(current: ConversationDigestRecord?, batchKey: String): Boolean =
        current?.lastFailedBatchKey != batchKey

    fun prefixKey(messageIds: List<String>, count: Int): String =
        messageIds.take(count).joinToString(separator = "|")

    private fun compact(
        previousSummary: String?,
        sourceStartIndex: Int,
        sourceEndExclusive: Int,
        messageIds: List<String>,
    ): ConversationDigestPlan.Compact = ConversationDigestPlan.Compact(
        previousSummary = previousSummary,
        sourceStartIndex = sourceStartIndex,
        sourceEndExclusive = sourceEndExclusive,
        nextCoveredMessageCount = sourceEndExclusive,
        nextPrefixKey = prefixKey(messageIds, sourceEndExclusive),
        batchKey = messageIds.subList(sourceStartIndex, sourceEndExclusive).joinToString(separator = "|"),
    )
}

data class ConversationDigestRecord(
    val summary: String,
    val coveredMessageCount: Int,
    val coveredPrefixKey: String,
    val lastFailedBatchKey: String? = null,
)

sealed interface ConversationDigestPlan {
    data object NoDigest : ConversationDigestPlan

    data class Ready(
        val summary: String,
        val rawMessageStartIndex: Int,
        val rawMessageCount: Int,
    ) : ConversationDigestPlan

    data class Compact(
        val previousSummary: String?,
        val sourceStartIndex: Int,
        val sourceEndExclusive: Int,
        val nextCoveredMessageCount: Int,
        val nextPrefixKey: String,
        val batchKey: String,
    ) : ConversationDigestPlan
}
