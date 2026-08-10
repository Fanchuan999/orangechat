package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WakeSettingValidationTest {
    @Test
    fun `idle exploration defaults off with the agreed budget`() {
        val setting = ProactiveMessageSetting()

        assertFalse(setting.idleExploreEnabled)
        assertEquals(1, setting.validatedExploreRunsPerDay())
        assertEquals(20_000, setting.validatedExploreRawTokenLimit())
    }

    @Test
    fun `exploration limits are clamped to safe ranges`() {
        val tooHigh = ProactiveMessageSetting(
            idleExploreRunsPerDay = 99,
            idleExploreRawTokenLimit = 99_999,
        )
        val tooLow = ProactiveMessageSetting(
            idleExploreRunsPerDay = -1,
            idleExploreRawTokenLimit = -1,
        )

        assertEquals(3, tooHigh.validatedExploreRunsPerDay())
        assertEquals(20_000, tooHigh.validatedExploreRawTokenLimit())
        assertEquals(1, tooLow.validatedExploreRunsPerDay())
        assertEquals(2_000, tooLow.validatedExploreRawTokenLimit())
    }

    @Test
    fun `ninety minute rhythm with thirty percent randomisation gives a safe window`() {
        val setting = ProactiveMessageSetting(
            wakeRhythmEnabled = true,
            wakeIntervalMinutes = 90,
            wakeRandomPercent = 30,
        )

        assertEquals(63..117, setting.validatedWakeIntervalRange())
    }
}
