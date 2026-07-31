/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.afterAssistantMessage
import me.rerere.rikkahub.data.datastore.afterProactiveMessage
import me.rerere.rikkahub.data.datastore.afterUserMessage

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
}
