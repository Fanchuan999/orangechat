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
}
