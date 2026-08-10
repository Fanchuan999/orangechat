/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.rikkahub.data.datastore.validatedExploreRunsPerDay

data class IdleExploreState(
    val dayKey: String = "",
    val runsToday: Int = 0,
    val lastRunAtMillis: Long = 0L,
)

object IdleExplorePolicy {
    private const val MINIMUM_LEAD_MILLIS = 30 * 60_000L
    private const val DAY_MILLIS = 24 * 60 * 60_000L

    fun nextWindow(
        setting: ProactiveMessageSetting,
        state: IdleExploreState,
        nowMillis: Long = System.currentTimeMillis(),
        jitterMinutes: Int = 0,
        timeZone: TimeZone = BEIJING_TIME_ZONE,
    ): Long? {
        if (!setting.idleExploreEnabled) return null

        val todayKey = dayKey(nowMillis, timeZone)
        val runsToday = if (state.dayKey == todayKey) state.runsToday else 0
        val dailyLimit = setting.validatedExploreRunsPerDay()
        if (runsToday >= dailyLimit) return null

        val cooldownMillis = DAY_MILLIS / dailyLimit / 2
        val earliestFromNow = nowMillis + MINIMUM_LEAD_MILLIS +
            jitterMinutes.coerceIn(0, 180) * 60_000L
        val earliestFromPrevious = if (state.lastRunAtMillis > 0L) {
            state.lastRunAtMillis + cooldownMillis
        } else {
            0L
        }
        return moveIntoWakingHours(max(earliestFromNow, earliestFromPrevious), timeZone)
    }

    fun dayKey(nowMillis: Long, timeZone: TimeZone = BEIJING_TIME_ZONE): String =
        Calendar.getInstance(timeZone).run {
            timeInMillis = nowMillis
            String.format(
                Locale.ROOT,
                "%04d-%02d-%02d",
                get(Calendar.YEAR),
                get(Calendar.MONTH) + 1,
                get(Calendar.DAY_OF_MONTH),
            )
        }

    fun nextMorning(nowMillis: Long, timeZone: TimeZone = BEIJING_TIME_ZONE): Long =
        Calendar.getInstance(timeZone).run {
            timeInMillis = nowMillis
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            timeInMillis
        }

    private fun moveIntoWakingHours(candidateMillis: Long, timeZone: TimeZone): Long =
        Calendar.getInstance(timeZone).run {
            timeInMillis = candidateMillis
            when (get(Calendar.HOUR_OF_DAY)) {
                in 0..8 -> {
                    set(Calendar.HOUR_OF_DAY, 9)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                in 22..23 -> {
                    add(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 9)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
            }
            timeInMillis
        }
}

internal fun boundIdleExploreToolOutput(
    parts: List<UIMessagePart>,
    maxChars: Int,
): Pair<List<UIMessagePart>, Int> {
    var remaining = maxChars.coerceAtLeast(0)
    val bounded = buildList {
        parts.filterIsInstance<UIMessagePart.Text>().forEach { part ->
            if (remaining <= 0) return@forEach
            val compact = part.text.replace(Regex("[\\t ]+"), " ")
                .replace(Regex("\\n{3,}"), "\n\n")
            val text = compact.take(remaining)
            if (text.isNotEmpty()) {
                add(part.copy(text = text))
                remaining -= text.length
            }
        }
    }
    if (bounded.isEmpty()) {
        val fallback = "{\"notice\":\"网页原始内容预算已用完，请根据已有结果结束探索。\"}"
            .take(maxChars.coerceAtLeast(0))
        return listOf(UIMessagePart.Text(fallback)) to fallback.length
    }
    return bounded to (maxChars.coerceAtLeast(0) - remaining)
}
