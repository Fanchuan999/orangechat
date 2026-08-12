/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

@Serializable
data class ProactiveMessageSetting(
    val enabled: Boolean = false,
    val minIntervalMinutes: Int = 30,
    val maxIntervalMinutes: Int = 90,
    val assistantId: String = "",
    // 普通主动消息、激进模式与空闲探索共用的主聊天窗口。
    // 留空表示沿用原有逻辑，选择对应助手最近使用的聊天。
    val primaryConversationId: String = "",
    val primaryConversationTitle: String = "",
    // 默认每 90 分钟给一次本地决策机会，并加入 ±30% 随机浮动。
    val wakeRhythmEnabled: Boolean = true,
    val wakeIntervalMinutes: Int = 90,
    val wakeRandomPercent: Int = 30,
    // 空闲探索默认关闭；打开后每日仅提供少量只读公开网页探索机会。
    val idleExploreEnabled: Boolean = false,
    val idleExploreRunsPerDay: Int = 1,
    val idleExploreRawTokenLimit: Int = 20_000,
    // 是否允许 AI 根据上下文判断后强制跳转屏幕到聊天界面
    val allowForceJump: Boolean = false,
    val jumpIdleThresholdMinutes: Int = 120, // 用户多久没回复(分钟)才允许跳转屏幕，默认2小时
    // 激进模式：每次手机切换应用/开屏锁屏/回桌面都触发AI思考
    val aggressiveModeEnabled: Boolean = false,
    // 激进模式下两次AI思考之间的最小间隔（秒），防抖+限流
    val aggressiveMinIntervalSeconds: Int = 60,
    // 激进模式下，检测到设备事件（切应用/开关屏/回桌面）后，
    // 等待多少秒的防抖时间才真正触发 AI 思考。原来硬编码 30 秒，现在可调节。
    val aggressiveDebounceSeconds: Int = 30,
    // 悬浮球：主动消息到达时以 Telegram 风格悬浮球提醒，点击直接进入聊天页
    val floatingBubbleEnabled: Boolean = false,
    // 晚安守夜：只在用户本人明确道晚安后运行，不依赖泛用的激进模式。
    val nightWatchSetting: NightWatchSetting = NightWatchSetting(),
)

@Serializable
data class NightWatchSetting(
    val enabled: Boolean = false,
    // 用户确认的规则：道晚安后 10 分钟开始观察；之后每 10 分钟最多提醒一次。
    val firstCheckMinutes: Int = 10,
    val repeatIntervalMinutes: Int = 10,
)

fun ProactiveMessageSetting.validatedWakeIntervalRange(): IntRange {
    if (!wakeRhythmEnabled) {
        val minimum = minIntervalMinutes.coerceIn(1, 24 * 60)
        return minimum..maxIntervalMinutes.coerceIn(minimum, 24 * 60)
    }
    val centre = wakeIntervalMinutes.coerceIn(15, 12 * 60)
    val randomPercent = wakeRandomPercent.coerceIn(0, 80)
    val variation = centre * randomPercent / 100
    return (centre - variation).coerceAtLeast(1)..(centre + variation).coerceAtMost(24 * 60)
}

fun ProactiveMessageSetting.validatedExploreRunsPerDay(): Int = idleExploreRunsPerDay.coerceIn(1, 3)

fun ProactiveMessageSetting.validatedExploreRawTokenLimit(): Int =
    idleExploreRawTokenLimit.coerceIn(2_000, 20_000)

fun ProactiveMessageSetting.withIdleExploreRunsPerDay(runs: Int): ProactiveMessageSetting =
    copy(idleExploreRunsPerDay = runs.coerceIn(1, 3))

fun ProactiveMessageSetting.withIdleExploreRawTokenLimit(tokens: Int): ProactiveMessageSetting =
    copy(idleExploreRawTokenLimit = tokens.coerceIn(2_000, 20_000))
