package me.rerere.rikkahub.data.service

import java.util.Calendar
import java.util.TimeZone
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleExplorePolicyTest {
    private val beijing = TimeZone.getTimeZone("Asia/Shanghai")

    @Test
    fun `disabled exploration never schedules`() {
        val next = IdleExplorePolicy.nextWindow(
            setting = ProactiveMessageSetting(idleExploreEnabled = false),
            state = IdleExploreState(),
            nowMillis = at(2026, Calendar.AUGUST, 10, 12),
            jitterMinutes = 0,
        )

        assertNull(next)
    }

    @Test
    fun `daily cap blocks another run on the same day`() {
        val next = IdleExplorePolicy.nextWindow(
            setting = ProactiveMessageSetting(idleExploreEnabled = true, idleExploreRunsPerDay = 1),
            state = IdleExploreState(dayKey = "2026-08-10", runsToday = 1),
            nowMillis = at(2026, Calendar.AUGUST, 10, 12),
            jitterMinutes = 0,
        )

        assertNull(next)
    }

    @Test
    fun `late night opportunity is moved to next morning`() {
        val next = IdleExplorePolicy.nextWindow(
            setting = ProactiveMessageSetting(idleExploreEnabled = true),
            state = IdleExploreState(),
            nowMillis = at(2026, Calendar.AUGUST, 10, 23),
            jitterMinutes = 0,
        )

        assertEquals(at(2026, Calendar.AUGUST, 11, 9), next)
    }

    @Test
    fun `multiple daily runs keep a cooldown`() {
        val lastRun = at(2026, Calendar.AUGUST, 10, 9)
        val next = IdleExplorePolicy.nextWindow(
            setting = ProactiveMessageSetting(idleExploreEnabled = true, idleExploreRunsPerDay = 3),
            state = IdleExploreState(
                dayKey = "2026-08-10",
                runsToday = 1,
                lastRunAtMillis = lastRun,
            ),
            nowMillis = at(2026, Calendar.AUGUST, 10, 10),
            jitterMinutes = 0,
        )

        assertTrue(next!! >= at(2026, Calendar.AUGUST, 10, 13))
    }

    @Test
    fun `web tool output is hard capped before returning to the model`() {
        val (parts, usedChars) = boundIdleExploreToolOutput(
            parts = listOf(
                UIMessagePart.Text("123456"),
                UIMessagePart.Text("abcdef"),
                UIMessagePart.Image("https://example.com/image.png"),
            ),
            maxChars = 8,
        )

        assertEquals(8, usedChars)
        assertEquals("123456ab", parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text })
        assertTrue(parts.all { it is UIMessagePart.Text })
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int): Long =
        Calendar.getInstance(beijing).run {
            clear()
            set(year, month, day, hour, 0, 0)
            timeInMillis
        }
}
