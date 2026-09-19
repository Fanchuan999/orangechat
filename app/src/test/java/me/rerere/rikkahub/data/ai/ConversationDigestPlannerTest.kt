package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDigestPlannerTest {
    @Test
    fun `does not make a provider request before thirty raw and fifteen older messages exist`() {
        val plan = ConversationDigestPlanner.plan(
            messageIds = messageIds(44),
            current = null,
        )

        assertTrue(plan is ConversationDigestPlan.NoDigest)
    }

    @Test
    fun `first digest waits for fifteen older messages and preserves the newest thirty`() {
        val plan = ConversationDigestPlanner.plan(
            messageIds = messageIds(45),
            current = null,
        )

        val compact = assertCompact(plan)
        assertNull(compact.previousSummary)
        assertEquals(0, compact.sourceStartIndex)
        assertEquals(15, compact.sourceEndExclusive)
        assertEquals(15, compact.nextCoveredMessageCount)
        assertEquals("m1|m2|m3|m4|m5|m6|m7|m8|m9|m10|m11|m12|m13|m14|m15", compact.nextPrefixKey)
    }

    @Test
    fun `next digest only adds the next fifteen messages to an existing rolling digest`() {
        val current = ConversationDigestRecord(
            summary = "已有摘要",
            coveredMessageCount = 15,
            coveredPrefixKey = "m1|m2|m3|m4|m5|m6|m7|m8|m9|m10|m11|m12|m13|m14|m15",
        )

        val plan = ConversationDigestPlanner.plan(
            messageIds = messageIds(60),
            current = current,
        )

        val compact = assertCompact(plan)
        assertEquals("已有摘要", compact.previousSummary)
        assertEquals(15, compact.sourceStartIndex)
        assertEquals(30, compact.sourceEndExclusive)
        assertEquals(30, compact.nextCoveredMessageCount)
    }

    @Test
    fun `existing digest keeps its summary and all newer raw messages until the next batch is ready`() {
        val current = ConversationDigestRecord(
            summary = "已有摘要",
            coveredMessageCount = 15,
            coveredPrefixKey = "m1|m2|m3|m4|m5|m6|m7|m8|m9|m10|m11|m12|m13|m14|m15",
        )

        val plan = ConversationDigestPlanner.plan(messageIds(59), current)

        val ready = assertReady(plan)
        assertEquals("已有摘要", ready.summary)
        assertEquals(15, ready.rawMessageStartIndex)
        assertEquals(44, ready.rawMessageCount)
    }

    @Test
    fun `changed conversation branch rebuilds instead of using a digest for another history`() {
        val current = ConversationDigestRecord(
            summary = "stale summary",
            coveredMessageCount = 15,
            coveredPrefixKey = "old-1|old-2|old-3|old-4|old-5|old-6|old-7|old-8|old-9|old-10|old-11|old-12|old-13|old-14|old-15",
        )

        val plan = ConversationDigestPlanner.plan(messageIds(45), current)

        val compact = assertCompact(plan)
        assertNull(compact.previousSummary)
        assertEquals(0, compact.sourceStartIndex)
        assertEquals(15, compact.sourceEndExclusive)
    }

    @Test
    fun `same failed batch is not announced repeatedly and a new batch is announced`() {
        val current = ConversationDigestRecord(
            summary = "",
            coveredMessageCount = 0,
            coveredPrefixKey = "",
            lastFailedBatchKey = "m1|m2|m3|m4|m5|m6|m7|m8|m9|m10|m11|m12|m13|m14|m15",
        )

        assertFalse(
            ConversationDigestPlanner.shouldNotifyFailure(
                current = current,
                batchKey = "m1|m2|m3|m4|m5|m6|m7|m8|m9|m10|m11|m12|m13|m14|m15",
            )
        )
        assertTrue(
            ConversationDigestPlanner.shouldNotifyFailure(
                current = current,
                batchKey = "m16|m17|m18|m19|m20|m21|m22|m23|m24|m25|m26|m27|m28|m29|m30",
            )
        )
    }

    private fun assertCompact(plan: ConversationDigestPlan): ConversationDigestPlan.Compact {
        assertTrue("Expected a digest compaction plan", plan is ConversationDigestPlan.Compact)
        return plan as ConversationDigestPlan.Compact
    }

    private fun assertReady(plan: ConversationDigestPlan): ConversationDigestPlan.Ready {
        assertTrue("Expected a ready digest context", plan is ConversationDigestPlan.Ready)
        return plan as ConversationDigestPlan.Ready
    }

    private fun messageIds(count: Int): List<String> = (1..count).map { "m$it" }
}
