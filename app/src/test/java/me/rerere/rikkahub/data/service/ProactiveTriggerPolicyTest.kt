package me.rerere.rikkahub.data.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveTriggerPolicyTest {
    @Test
    fun `night watch suppresses every other automatic trigger source`() {
        assertTrue(shouldSuppressForNightWatch(ProactiveTriggerKind.Scheduled, nightWatchArmed = true))
        assertTrue(shouldSuppressForNightWatch(ProactiveTriggerKind.Aggressive, nightWatchArmed = true))
        assertTrue(shouldSuppressForNightWatch(ProactiveTriggerKind.Explore, nightWatchArmed = true))
        assertFalse(shouldSuppressForNightWatch(ProactiveTriggerKind.NightWatch, nightWatchArmed = true))
    }

    @Test
    fun `same proactive reply is suppressed within its cooldown window`() {
        val now = 1_000_000L
        val fingerprint = proactiveReplyFingerprint("该去睡觉啦")

        assertTrue(
            shouldSuppressDuplicateProactiveReply(
                previousFingerprint = fingerprint,
                previousAtMillis = now - 10 * 60_000L,
                candidate = "  该去睡觉啦  ",
                nowMillis = now,
            )
        )
    }

    @Test
    fun `same proactive reply may be sent again after its cooldown window`() {
        val now = 1_000_000L

        assertFalse(
            shouldSuppressDuplicateProactiveReply(
                previousFingerprint = proactiveReplyFingerprint("该去睡觉啦"),
                previousAtMillis = now - 15 * 60_000L,
                candidate = "该去睡觉啦",
                nowMillis = now,
            )
        )
    }

    @Test
    fun `different proactive reply is not suppressed`() {
        val now = 1_000_000L

        assertFalse(
            shouldSuppressDuplicateProactiveReply(
                previousFingerprint = proactiveReplyFingerprint("该去睡觉啦"),
                previousAtMillis = now - 1_000L,
                candidate = "今天辛苦啦，早点休息。",
                nowMillis = now,
            )
        )
    }
}
