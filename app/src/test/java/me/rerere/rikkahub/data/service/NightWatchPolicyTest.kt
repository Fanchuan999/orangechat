package me.rerere.rikkahub.data.service

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class NightWatchPolicyTest {
    private val beijingTimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    @Test
    fun `watch armed before six expires at same day six`() {
        val armedAt = beijingTime(2026, Calendar.AUGUST, 11, 2, 0)

        assertEquals(
            beijingTime(2026, Calendar.AUGUST, 11, 6, 0),
            nightWatchExpiryAt(armedAt),
        )
    }

    @Test
    fun `watch armed after six expires at next day six`() {
        val armedAt = beijingTime(2026, Calendar.AUGUST, 11, 22, 0)

        assertEquals(
            beijingTime(2026, Calendar.AUGUST, 12, 6, 0),
            nightWatchExpiryAt(armedAt),
        )
    }

    @Test
    fun `watch armed exactly at six expires at next day six`() {
        val armedAt = beijingTime(2026, Calendar.AUGUST, 11, 6, 0)

        assertEquals(
            beijingTime(2026, Calendar.AUGUST, 12, 6, 0),
            nightWatchExpiryAt(armedAt),
        )
    }

    private fun beijingTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(beijingTimeZone).run {
            clear()
            set(year, month, day, hour, minute, 0)
            timeInMillis
        }
}
