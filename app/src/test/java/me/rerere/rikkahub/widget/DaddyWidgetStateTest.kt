package me.rerere.rikkahub.widget

import me.rerere.rikkahub.data.datastore.CompanionMoodSetting
import me.rerere.rikkahub.data.datastore.CompanionMoodState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DaddyWidgetStateTest {
    @Test
    fun buildsCompactStateWithoutModelCalls() {
        val state = buildDaddyWidgetState(
            moodSetting = CompanionMoodSetting(
                state = CompanionMoodState(
                    connection = 0.86f,
                    valence = 0.38f,
                    arousal = 0.45f,
                    immersion = 0.32f,
                    updatedAtMillis = 1_000L,
                ),
            ),
            shortLine = "我在这里。",
            recentReply = "刚刚那句话，我记着呢。",
            stepsToday = 6194,
            weather = null,
            nowMillis = 1_000L,
        )

        assertEquals("心情轻快", state.moodLabel)
        assertEquals("牵挂你", state.focusLabel)
        assertEquals("我在这里。", state.shortLine)
        assertEquals("6194 步", state.stepsText)
        assertEquals("天气待接入", state.weatherText)
        assertTrue(state.recentReply.contains("刚刚那句话"))
    }

    @Test
    fun fallsBackWhenOptionalDataIsMissing() {
        val state = buildDaddyWidgetState(
            moodSetting = CompanionMoodSetting(
                state = CompanionMoodState(
                    connection = 0.1f,
                    valence = 0f,
                    arousal = 0.1f,
                    immersion = 0.15f,
                    updatedAtMillis = 1_000L,
                ),
            ),
            shortLine = "",
            recentReply = "",
            stepsToday = null,
            weather = "",
            nowMillis = 1_000L,
        )

        assertEquals("心绪平稳", state.moodLabel)
        assertEquals("陪在当下", state.focusLabel)
        assertEquals("今天也在你身边。", state.shortLine)
        assertEquals("步数待同步", state.stepsText)
        assertEquals("天气待接入", state.weatherText)
    }
}
