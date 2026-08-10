/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.CompanionProactiveDecision
import me.rerere.rikkahub.data.datastore.afterAssistantMessage
import me.rerere.rikkahub.data.datastore.afterProactiveMessage
import me.rerere.rikkahub.data.datastore.afterUserMessage
import me.rerere.rikkahub.data.datastore.evolveCompanionDesire
import me.rerere.rikkahub.data.datastore.evolveCompanionMood
import me.rerere.rikkahub.data.datastore.proactiveDecision

/**
 * Persists only tiny local numeric state. It never talks to a model, network, notification service,
 * or sensor. The regular chat/proactive paths decide when a model call happens as before.
 */
class CompanionMoodEngine(
    private val settingsStore: SettingsStore,
) {
    suspend fun recordUserMessage() {
        settingsStore.update { settings ->
            val setting = settings.companionMoodSetting
            if (!setting.enabled) settings else settings.copy(
                companionMoodSetting = setting.copy(
                    state = setting.state.afterUserMessage(),
                    desireState = if (setting.desireEnabled) {
                        setting.desireState.afterUserMessage()
                    } else {
                        setting.desireState
                    },
                )
            )
        }
    }

    suspend fun recordAssistantMessage() {
        settingsStore.update { settings ->
            val setting = settings.companionMoodSetting
            if (!setting.enabled) settings else settings.copy(
                companionMoodSetting = setting.copy(
                    state = setting.state.afterAssistantMessage(),
                    desireState = if (setting.desireEnabled) {
                        setting.desireState.afterAssistantMessage()
                    } else {
                        setting.desireState
                    },
                )
            )
        }
    }

    suspend fun recordProactiveMessage() {
        settingsStore.update { settings ->
            val setting = settings.companionMoodSetting
            if (!setting.enabled) settings else settings.copy(
                companionMoodSetting = setting.copy(
                    state = setting.state.afterProactiveMessage(),
                    desireState = if (setting.desireEnabled) {
                        setting.desireState.afterProactiveMessage()
                    } else {
                        setting.desireState
                    },
                )
            )
        }
    }

    /**
     * Claim one ordinary scheduled wake-up. This is deliberately persisted so
     * AlarmManager and WorkManager make the same decision when they wake near
     * each other. Device-event / aggressive-mode wake-ups do not call this.
     */
    suspend fun decideWake(source: WakeSource): WakeDecision {
        var decision = WakeDecision.Contact
        settingsStore.update { settings ->
            val setting = settings.companionMoodSetting
            val evolved = setting.copy(
                state = evolveCompanionMood(setting.state),
                desireState = if (setting.desireEnabled) {
                    evolveCompanionDesire(setting.desireState)
                } else {
                    setting.desireState
                },
            )
            decision = if (source == WakeSource.Scheduled && !setting.desireEnabled) {
                when (evolved.proactiveDecision()) {
                    CompanionProactiveDecision.Contact -> WakeDecision.Contact
                    CompanionProactiveDecision.FindActivity -> WakeDecision.FindActivity
                    CompanionProactiveDecision.Observe -> WakeDecision.Quiet
                }
            } else {
                WakeCoordinator.decide(
                    WakeInput(
                        source = source,
                        desire = evolved.desireState,
                        localGateEnabled = setting.enabled && setting.proactiveRhythmEnabled,
                    )
                )
            }
            val settled = if (decision == WakeDecision.FindActivity) {
                evolved.copy(
                    state = evolved.state.copy(
                        immersion = 0.35f,
                        connection = (evolved.state.connection - 0.05f).coerceAtLeast(0.01f),
                    ),
                    desireState = evolved.desireState.copy(
                        curiosity = (evolved.desireState.curiosity - 0.08f).coerceAtLeast(0f),
                        wander = (evolved.desireState.wander - 0.16f).coerceAtLeast(0f),
                        agency = (evolved.desireState.agency - 0.12f).coerceAtLeast(0f),
                        fatigue = (evolved.desireState.fatigue + 0.04f).coerceAtMost(1f),
                    ),
                )
            } else {
                evolved
            }
            settings.copy(companionMoodSetting = settled)
        }
        return decision
    }

    suspend fun decideScheduledProactiveMessage(): CompanionProactiveDecision =
        when (decideWake(WakeSource.Scheduled)) {
            WakeDecision.Contact -> CompanionProactiveDecision.Contact
            WakeDecision.FindActivity -> CompanionProactiveDecision.FindActivity
            WakeDecision.Rest,
            WakeDecision.Quiet -> CompanionProactiveDecision.Observe
        }
}
