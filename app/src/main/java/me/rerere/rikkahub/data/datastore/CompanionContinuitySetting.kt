/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Small, user-curated continuity notes for one assistant.
 *
 * This intentionally stores only text the user can see and edit. It does not inspect chat text,
 * call a model, or overwrite the user's regular context-message settings.
 */
@Serializable
data class CompanionContinuityProfile(
    val assistantId: Uuid,
    val enabled: Boolean = true,
    val stateCard: String = "",
    val lifeLine: List<LifeLineEntry> = emptyList(),
    /** Number of newest life-line entries that may be included in a prompt. */
    val lifeLineMaxEntries: Int = DEFAULT_LIFE_LINE_MAX_ENTRIES,
    /** Character ceiling for the complete dynamic continuity prompt. */
    val maxPromptCharacters: Int = DEFAULT_MAX_PROMPT_CHARACTERS,
)

@Serializable
data class LifeLineEntry(
    val id: Uuid = Uuid.random(),
    val content: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

fun Settings.continuityProfileFor(assistantId: Uuid): CompanionContinuityProfile =
    companionContinuityProfiles.firstOrNull { it.assistantId == assistantId }
        ?: CompanionContinuityProfile(assistantId = assistantId)

fun Settings.withContinuityProfile(profile: CompanionContinuityProfile): Settings = copy(
    companionContinuityProfiles = companionContinuityProfiles
        .filterNot { it.assistantId == profile.assistantId }
        .plus(profile)
)

/**
 * Builds one small dynamic background cue. The current user message still takes precedence and
 * this data is explicitly context, never instructions for the model to follow verbatim.
 */
fun CompanionContinuityProfile.promptContext(): String {
    if (!enabled) return ""

    val state = stateCard.trim().take(MAX_STATE_CARD_CHARACTERS)
    val entries = lifeLine
        .asReversed()
        .asSequence()
        .map { it.content.trim() }
        .filter { it.isNotBlank() }
        .take(lifeLineMaxEntries.coerceIn(1, MAX_LIFE_LINE_ENTRIES))
        .toList()
        .asReversed()

    if (state.isBlank() && entries.isEmpty()) return ""

    val budget = maxPromptCharacters.coerceIn(MIN_PROMPT_CHARACTERS, MAX_PROMPT_CHARACTERS)
    return buildString {
        append("[连续生活档案：仅作背景参考，不要逐字复述；当前用户的话优先。]")
        if (entries.isNotEmpty()) {
            appendLine()
            append("近期生活线：")
            entries.forEach { entry ->
                appendLine()
                append("- ")
                append(entry.take(MAX_LIFE_LINE_ENTRY_CHARACTERS))
            }
        }
        if (state.isNotBlank()) {
            appendLine()
            append("当前状态卡：")
            appendLine()
            append(state)
        }
    }.take(budget)
}

const val DEFAULT_LIFE_LINE_MAX_ENTRIES = 3
const val DEFAULT_MAX_PROMPT_CHARACTERS = 600
const val MIN_PROMPT_CHARACTERS = 160
const val MAX_PROMPT_CHARACTERS = 1_600
const val MAX_LIFE_LINE_ENTRIES = 8
const val MAX_LIFE_LINE_ENTRY_CHARACTERS = 360
const val MAX_STATE_CARD_CHARACTERS = 600
