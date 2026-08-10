/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import java.util.Calendar
import java.util.TimeZone

internal val BEIJING_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

/**
 * Classifies only text that the user actually sent. Model output and automatic trigger prompts
 * never pass through this policy, so Daddy cannot invent a reply that arms or disarms the watch.
 */
internal object NightWatchMessageClassifier {
    enum class Action { Arm, Disarm, None }

    private val disarmPhrases = listOf(
        "早上好",
        "起床啦",
        "我起床啦",
        "睡醒",
        "我不睡了",
        "不睡了",
        "我起床了",
        "起床了",
        "别管我了",
        "别管我",
        "不要管我",
        "不用管我",
        "别催我",
        "别逮我",
    )
    private val negativeSleepPhrases = listOf("不睡", "没睡", "睡不着", "不想睡")
    private val bedtimePhrases = listOf(
        "晚安",
        "去睡",
        "先睡",
        "睡觉",
        "睡了",
        "睡啦",
        "睡咯",
        "我要睡",
        "准备睡",
        "该睡",
    )

    fun classify(text: String): Action {
        val normalized = text.lowercase().replace(Regex("\\s+"), "")
        if (normalized.isBlank()) return Action.None
        if (disarmPhrases.any(normalized::contains)) return Action.Disarm
        if (negativeSleepPhrases.any(normalized::contains)) return Action.None
        return if (bedtimePhrases.any(normalized::contains)) Action.Arm else Action.None
    }
}

/**
 * A bedtime message sent after 06:00 belongs to the coming night and expires the next morning.
 * A message sent between midnight and 05:59 belongs to the current night and expires that same
 * morning. Exactly 06:00 starts a new watch window, so it expires the following day.
 */
internal fun nightWatchExpiryAt(
    nowMillis: Long,
    timeZone: TimeZone = BEIJING_TIME_ZONE,
): Long = Calendar.getInstance(timeZone).run {
    timeInMillis = nowMillis
    val expiresToday = get(Calendar.HOUR_OF_DAY) < 6
    set(Calendar.HOUR_OF_DAY, 6)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    if (!expiresToday) add(Calendar.DAY_OF_YEAR, 1)
    timeInMillis
}
