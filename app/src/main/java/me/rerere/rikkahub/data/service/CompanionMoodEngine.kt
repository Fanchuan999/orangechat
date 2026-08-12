/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.content.Context
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.CompanionAffectKind
import me.rerere.rikkahub.data.datastore.CompanionProactiveDecision
import me.rerere.rikkahub.data.datastore.afterAssistantMessage
import me.rerere.rikkahub.data.datastore.afterProactiveMessage
import me.rerere.rikkahub.data.datastore.afterUserMessage
import me.rerere.rikkahub.data.datastore.detectCompanionAffectEvents
import me.rerere.rikkahub.data.datastore.evolveCompanionDesire
import me.rerere.rikkahub.data.datastore.evolveCompanionMood
import me.rerere.rikkahub.data.datastore.activeAffectEvents
import me.rerere.rikkahub.data.datastore.withDetectedAffectEvents
import me.rerere.rikkahub.data.datastore.proactiveDecision
import me.rerere.rikkahub.widget.DaddyWidgetProvider

/**
 * Persists only tiny local numeric state. It never talks to a model, network, notification service,
 * or sensor. The regular chat/proactive paths decide when a model call happens as before.
 */
class CompanionMoodEngine(
    private val settingsStore: SettingsStore,
    private val context: Context,
) {
    suspend fun recordUserMessage(text: String) {
        settingsStore.update { settings ->
            val setting = settings.companionMoodSetting
            val detected = detectCompanionAffectEvents(text)
            val affectKinds = detected.map { it.kind }.toSet()
            val nextMood = setting.state.afterUserMessage()
            val nextDesire = setting.desireState.afterUserMessage()
            if (!setting.enabled) settings else settings.copy(
                companionMoodSetting = setting.copy(
                    state = nextMood.copy(
                        valence = when {
                            CompanionAffectKind.LOW_MOOD in affectKinds || CompanionAffectKind.DISCOMFORT in affectKinds -> -0.28f
                            CompanionAffectKind.IRRITATION in affectKinds -> -0.18f
                            CompanionAffectKind.JOY in affectKinds -> 0.46f
                            else -> nextMood.valence
                        },
                    ),
                    desireState = if (setting.desireEnabled) {
                        nextDesire.copy(
                            fatigue = if (CompanionAffectKind.FATIGUE in affectKinds) 0.62f else nextDesire.fatigue,
                            irritation = if (CompanionAffectKind.IRRITATION in affectKinds) 0.58f else nextDesire.irritation,
                            care = if (CompanionAffectKind.DISCOMFORT in affectKinds) 0.75f else nextDesire.care,
                            closeness = if (CompanionAffectKind.INTIMACY in affectKinds) 0.78f else nextDesire.closeness,
                        )
                    } else {
                        setting.desireState
                    },
                    affectEvents = setting.affectEvents.withDetectedAffectEvents(detected),
                )
            )
        }
    }

    suspend fun clearAffectEvents() {
        settingsStore.update { settings ->
            settings.copy(companionMoodSetting = settings.companionMoodSetting.copy(affectEvents = emptyList()))
        }
    }

    suspend fun clearAffectEvent(kind: CompanionAffectKind) {
        settingsStore.update { settings ->
            settings.copy(
                companionMoodSetting = settings.companionMoodSetting.copy(
                    affectEvents = settings.companionMoodSetting.affectEvents.filterNot { it.kind == kind },
                ),
            )
        }
        DaddyWidgetProvider.refreshAll(context)
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
        DaddyWidgetProvider.refreshAll(context)
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
        DaddyWidgetProvider.refreshAll(context)
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
            val quietWeather = setting.activeAffectEvents().any {
                it.kind == CompanionAffectKind.FATIGUE ||
                    it.kind == CompanionAffectKind.LOW_MOOD ||
                    it.kind == CompanionAffectKind.DISCOMFORT
            }
            val evolved = setting.copy(
                state = evolveCompanionMood(setting.state),
                desireState = if (setting.desireEnabled) {
                    evolveCompanionDesire(setting.desireState)
                } else {
                    setting.desireState
                },
            )
            decision = if (source == WakeSource.Scheduled && quietWeather && evolved.desireState.fatigue >= 0.50f) {
                WakeDecision.Rest
            } else if (source == WakeSource.Scheduled && !setting.desireEnabled) {
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
