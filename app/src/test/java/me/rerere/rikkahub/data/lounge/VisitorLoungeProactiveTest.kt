package me.rerere.rikkahub.data.lounge

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class VisitorLoungeProactiveTest {
    @Test
    fun `policy requires consent and enforces friend cooldown plus daily cap`() {
        val now = Instant.parse("2026-08-28T12:00:00Z")
        val friend = friend("friend-1")
        val policy = VisitorLoungeProactivePolicy()

        assertEquals(
            VisitorLoungeProactiveDecision.ALLOW,
            policy.evaluate(friend, emptyList(), now),
        )
        assertEquals(
            VisitorLoungeProactiveDecision.FRIEND_COOLDOWN,
            policy.evaluate(friend, listOf(visit("friend-1", now.minusSeconds(60))), now),
        )
        assertEquals(
            VisitorLoungeProactiveDecision.GLOBAL_DAILY_LIMIT,
            policy.evaluate(
                friend,
                listOf(
                    visit("friend-2", now.minusSeconds(4 * 60 * 60)),
                    visit("friend-3", now.minusSeconds(8 * 60 * 60)),
                    visit("friend-4", now.minusSeconds(12 * 60 * 60)),
                ),
                now,
            ),
        )
        assertEquals(
            VisitorLoungeProactiveDecision.CONSENT_REQUIRED,
            policy.evaluate(friend.copy(consent = VisitorLoungeConsent.MANUAL_ONLY), emptyList(), now),
        )
    }

    private fun friend(id: String, name: String = "Friend") = FriendPublicRecord(
        id = id,
        displayName = name,
        endpoint = "https://friend.example/mcp",
        consent = VisitorLoungeConsent.ALLOW_PROACTIVE,
    )

    private fun visit(friendId: String, startedAt: Instant) = VisitorLoungeVisit(
        id = "visit-$friendId-${startedAt.toEpochMilli()}",
        friendId = friendId,
        sourceConversationId = "conversation",
        mode = VisitorLoungeVisitMode.PROACTIVE,
        status = VisitorLoungeVisitStatus.COMPLETED,
        topic = "hello",
        startedAt = startedAt,
    )
}
