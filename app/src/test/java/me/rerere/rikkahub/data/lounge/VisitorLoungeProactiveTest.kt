package me.rerere.rikkahub.data.lounge

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisitorLoungeProactiveTest {
    @Test
    fun `internal visit directive is parsed and removed from the visible reply`() {
        val directive = VisitorLoungeProactiveDirectiveParser.consume(
            "我去替你问候一下。[[VISIT_LOUNGE:friend-1|问问今天过得如何]]",
        )

        requireNotNull(directive)
        assertEquals("friend-1", directive.friendId)
        assertEquals("问问今天过得如何", directive.topic)
        assertEquals("我去替你问候一下。", directive.visibleText)
    }

    @Test
    fun `malformed directive is not treated as a visit request`() {
        assertNull(VisitorLoungeProactiveDirectiveParser.consume("[[VISIT_LOUNGE:friend-1]]"))
    }

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

    @Test
    fun `prompt exposes only authorized public room labels and marker shape`() {
        val prompt = VisitorLoungeProactiveDirectiveParser.promptFor(listOf(friend("friend-1", "Alice")))

        assertTrue(prompt.contains("friend-1"))
        assertTrue(prompt.contains("Alice"))
        assertTrue(prompt.contains("[[VISIT_LOUNGE:"))
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
