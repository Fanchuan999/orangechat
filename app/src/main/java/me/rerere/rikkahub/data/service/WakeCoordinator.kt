/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import me.rerere.rikkahub.data.datastore.CompanionDesireState

enum class WakeSource {
    NightWatch,
    Aggressive,
    Scheduled,
    Explore,
}

enum class WakeDecision {
    Contact,
    FindActivity,
    Rest,
    Quiet,
}

data class WakeInput(
    val source: WakeSource,
    val desire: CompanionDesireState,
    val localGateEnabled: Boolean = true,
    val conversationBusy: Boolean = false,
    val quietHours: Boolean = false,
)

/** A free, deterministic decision. Only [WakeDecision.Contact] is allowed to call the chat model. */
object WakeCoordinator {
    fun decide(input: WakeInput): WakeDecision {
        if (input.conversationBusy) return WakeDecision.Quiet
        if (input.source == WakeSource.NightWatch || input.source == WakeSource.Aggressive) {
            return WakeDecision.Contact
        }
        if (!input.localGateEnabled) return WakeDecision.Contact

        val desire = input.desire
        val contactSignal =
            desire.longing * 0.30f +
                desire.expression * 0.25f +
                desire.care * 0.20f +
                desire.closeness * 0.10f +
                desire.irritation * 0.05f -
                desire.fatigue * 0.20f
        val activitySignal =
            desire.curiosity * 0.30f +
                desire.wander * 0.35f +
                desire.agency * 0.35f -
                desire.fatigue * 0.20f

        return when {
            input.source == WakeSource.Explore && activitySignal >= 0.24f -> WakeDecision.FindActivity
            input.source == WakeSource.Explore -> WakeDecision.Quiet
            contactSignal >= 0.34f -> WakeDecision.Contact
            activitySignal >= 0.24f -> WakeDecision.FindActivity
            input.quietHours || desire.fatigue >= 0.68f -> WakeDecision.Rest
            else -> WakeDecision.Quiet
        }
    }
}
