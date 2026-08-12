/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.widget

import me.rerere.rikkahub.data.datastore.CompanionMoodSetting
import me.rerere.rikkahub.data.datastore.evolveCompanionMood
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DaddyWidgetState(
    val moodLabel: String,
    val focusLabel: String,
    val shortLine: String,
    val recentReply: String,
    val stepsText: String,
    val weatherText: String,
    val timeText: String,
    val dateText: String,
)

fun buildDaddyWidgetState(
    moodSetting: CompanionMoodSetting,
    shortLine: String,
    recentReply: String,
    stepsToday: Int?,
    weather: String?,
    nowMillis: Long = System.currentTimeMillis(),
): DaddyWidgetState {
    val mood = evolveCompanionMood(moodSetting.state, nowMillis)
    val moodLabel = when {
        mood.valence > 0.32f -> "心情轻快"
        mood.valence < -0.32f -> "有点安静"
        else -> "心绪平稳"
    }
    val focusLabel = when {
        mood.connection > 0.75f -> "牵挂你"
        mood.connection > 0.45f -> "想靠近"
        mood.immersion > 0.45f -> "沉浸陪伴"
        mood.arousal > 0.62f -> "精神明亮"
        else -> "陪在当下"
    }
    return DaddyWidgetState(
        moodLabel = moodLabel,
        focusLabel = focusLabel,
        shortLine = shortLine.trim().ifBlank { "今天也在你身边。" }.take(48),
        recentReply = recentReply.cleanWidgetLine().ifBlank { "还没有最近回复，等你来找我。" },
        stepsText = stepsToday?.takeIf { it >= 0 }?.let { "$it 步" } ?: "步数待同步",
        weatherText = weather?.trim().takeUnless { it.isNullOrBlank() } ?: "天气待接入",
        timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nowMillis)),
        dateText = SimpleDateFormat("MM月dd日 E", Locale.getDefault()).format(Date(nowMillis)),
    )
}

private fun String.cleanWidgetLine(): String = trim()
    .replace(Regex("\\s+"), " ")
    .take(80)
