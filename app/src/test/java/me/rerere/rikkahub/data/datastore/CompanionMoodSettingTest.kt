package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertFalse
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

        assertTrue(refreshed.connection < state.connection)
        assertTrue(refreshed.immersion > state.immersion)
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
