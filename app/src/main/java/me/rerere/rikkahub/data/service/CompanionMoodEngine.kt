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
                companionMoodSetting = setting.copy(state = setting.state.afterUserMessage())
            )
        }
    }

    suspend fun recordAssistantMessage() {
        settingsStore.update { settings ->
            val setting = settings.companionMoodSetting
            if (!setting.enabled) settings else settings.copy(
                companionMoodSetting = setting.copy(state = setting.state.afterAssistantMessage())
            )
        }
    }

    suspend fun recordProactiveMessage() {
        settingsStore.update { settings ->
            val setting = settings.companionMoodSetting
            if (!setting.enabled) settings else settings.copy(
                companionMoodSetting = setting.copy(state = setting.state.afterProactiveMessage())
            )
        }
    }

    /**
     * Claim one ordinary scheduled wake-up. This is deliberately persisted so
     * AlarmManager and WorkManager make the same decision when they wake near
     * each other. Device-event / aggressive-mode wake-ups do not call this.
     */
    suspend fun decideScheduledProactiveMessage(): CompanionProactiveDecision {
        var decision = CompanionProactiveDecision.Contact
        settingsStore.update { settings ->
            val setting = settings.companionMoodSetting
            val evolved = setting.copy(state = evolveCompanionMood(setting.state))
            decision = evolved.proactiveDecision()
            val settled = if (decision == CompanionProactiveDecision.FindActivity) {
                evolved.copy(
                    state = evolved.state.copy(
                        immersion = 0.35f,
                        connection = (evolved.state.connection - 0.05f).coerceAtLeast(0.01f),
                    )
                )
            } else {
                evolved
            }
            settings.copy(companionMoodSetting = settled)
        }
        return decision
    }
}
