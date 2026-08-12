package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionMoodSettingTest {
    @Test
    fun elapsedTimeMakesTheCompanionMoreReadyToReconnect() {
        val state = CompanionMoodState(connection = 0.2f, updatedAtMillis = 1_000L)

        val evolved = evolveCompanionMood(state, nowMillis = 5 * 60 * 60 * 1_000L)

        assertTrue(evolved.connection > state.connection)
        assertTrue(evolved.updatedAtMillis > state.updatedAtMillis)
    }

    @Test
    fun userMessageRefreshesConnectionWithoutAnalysingTheMessageText() {
        val state = CompanionMoodState(connection = 0.8f, updatedAtMillis = 1_000L)

        val refreshed = state.afterUserMessage(nowMillis = 1_000L)

        assertEquals(0f, refreshed.connection)
        assertTrue(refreshed.immersion > state.immersion)
    }

    @Test
    fun scheduledRhythmWaitsBeforeSpendingTokens() {
        val waiting = CompanionMoodSetting(
            state = CompanionMoodState(connection = 0.24f, updatedAtMillis = 1_000L)
        )
        val ready = CompanionMoodSetting(
            state = CompanionMoodState(connection = 0.52f, updatedAtMillis = 1_000L)
        )

        assertEquals(CompanionProactiveDecision.Observe, waiting.proactiveDecision(nowMillis = 1_000L))
        assertEquals(CompanionProactiveDecision.Contact, ready.proactiveDecision(nowMillis = 1_000L))
    }

    @Test
    fun prideCanChooseAQuietActivityInsteadOfAnInterruption() {
        val setting = CompanionMoodSetting(
            state = CompanionMoodState(
                connection = 0.42f,
                pride = 0.62f,
                immersion = 0.1f,
                updatedAtMillis = 1_000L,
            )
        )

        assertEquals(CompanionProactiveDecision.FindActivity, setting.proactiveDecision(nowMillis = 1_000L))
    }

    @Test
    fun promptIsShortNaturalLanguageAndCanBeDisabled() {
        val enabled = CompanionMoodSetting().promptContext(nowMillis = 10_000L)
        val disabled = CompanionMoodSetting(enabled = false).promptContext(nowMillis = 10_000L)

        assertTrue(enabled.contains("持续情绪"))
        assertFalse(enabled.contains("connection"))
        assertTrue(disabled.isEmpty())
    }

    @Test
    fun silenceIncreasesLongingAndCuriosityWithoutInventingIrritation() {
        val initial = CompanionDesireState(
            longing = 0.2f,
            curiosity = 0.12f,
            irritation = 0f,
            updatedAtMillis = 0L,
        )

        val evolved = evolveCompanionDesire(initial, nowMillis = 6 * 60 * 60 * 1_000L)

        assertTrue(evolved.longing > initial.longing)
        assertTrue(evolved.curiosity > initial.curiosity)
        assertEquals(0f, evolved.irritation, 0.001f)
    }

    @Test
    fun realConversationEventsSettleDifferentDesires() {
        val initial = CompanionDesireState(
            longing = 0.8f,
            closeness = 0.2f,
            expression = 0.7f,
            agency = 0.6f,
            irritation = 0.4f,
            updatedAtMillis = 1_000L,
        )

        val afterUser = initial.afterUserMessage(nowMillis = 1_000L)
        val afterAssistant = initial.afterAssistantMessage(nowMillis = 1_000L)
        val afterProactive = initial.afterProactiveMessage(nowMillis = 1_000L)

        assertTrue(afterUser.longing < initial.longing)
        assertTrue(afterUser.closeness > initial.closeness)
        assertTrue(afterUser.irritation < initial.irritation)
        assertTrue(afterAssistant.expression < initial.expression)
        assertTrue(afterAssistant.agency < initial.agency)
        assertTrue(afterProactive.longing < initial.longing)
        assertEquals(initial.closeness, afterProactive.closeness, 0.001f)
    }

    @Test
    fun desireDisplayItemsExposeAllNineAxesInAStableOrder() {
        val items = CompanionDesireState(
            longing = 0f,
            closeness = 1f,
            curiosity = 0.5f,
        ).displayItems()

        assertEquals(9, items.size)
        assertEquals("想你", items[0].label)
        assertEquals("亲密", items[1].label)
        assertEquals("好奇", items[2].label)
        assertEquals(0f, items[0].value, 0.001f)
        assertEquals(1f, items[1].value, 0.001f)
        assertEquals(0.5f, items[2].value, 0.001f)
        assertEquals("累（闸门）", items.last().label)
    }
}
