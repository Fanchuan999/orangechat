/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Local-first data for Daddy's companion space. It is regular Settings data,
 * so the existing Daddy backup/restore flow carries it with the user.
 */
@Serializable
data class CompanionSpaceSetting(
    val diaryCandidates: List<DiaryCandidate> = emptyList(),
    val photos: List<CompanionPhoto> = emptyList(),
    val anniversaries: List<CompanionAnniversary> = emptyList(),
    val letters: List<CompanionLetter> = emptyList(),
    val sharedTasks: List<CompanionSharedTask> = emptyList(),
)

/** A local image reference in the companion-space photo wall. */
@Serializable
data class CompanionPhoto(
    val id: Uuid = Uuid.random(),
    val uri: String,
    val caption: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
)

/** A date the two of you want to keep visible in the little house. */
@Serializable
data class CompanionAnniversary(
    val id: Uuid = Uuid.random(),
    val title: String,
    val dateText: String,
    val note: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
)

/** A manually written note or letter. It deliberately never calls a model. */
@Serializable
data class CompanionLetter(
    val id: Uuid = Uuid.random(),
    val author: String,
    val title: String,
    val content: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

/** A small shared checklist item, stored locally with the rest of the room. */
@Serializable
data class CompanionSharedTask(
    val id: Uuid = Uuid.random(),
    val content: String,
    val completed: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

/**
 * A short reflection generated from recent chat text. It starts as a draft and
 * only receives [ombreSavedAtMillis] after the user explicitly confirms it.
 */
@Serializable
data class DiaryCandidate(
    val id: Uuid = Uuid.random(),
    val title: String,
    val content: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val ombreSavedAtMillis: Long? = null,
    /** Number of today's plain-text turns considered when this draft was made. */
    val sourceMessageCount: Int = 0,
    /** Total original characters across those turns, before any local excerpting. */
    val sourceCharacterCount: Int = 0,
    /** True when a busy day needed a short local excerpt from each turn. */
    val sourceUsesExcerpts: Boolean = false,
)

fun CompanionSpaceSetting.withCandidate(candidate: DiaryCandidate): CompanionSpaceSetting = copy(
    diaryCandidates = (diaryCandidates.filterNot { it.id == candidate.id } + candidate)
        .sortedBy { it.createdAtMillis }
        .takeLast(MAX_DIARY_CANDIDATES),
)

fun CompanionSpaceSetting.withPhoto(photo: CompanionPhoto): CompanionSpaceSetting = copy(
    photos = (photos.filterNot { it.id == photo.id } + photo)
        .sortedBy { it.createdAtMillis }
        .takeLast(MAX_COMPANION_PHOTOS),
)

fun CompanionSpaceSetting.withoutPhoto(id: Uuid): CompanionSpaceSetting = copy(
    photos = photos.filterNot { it.id == id },
)

fun CompanionSpaceSetting.withAnniversary(anniversary: CompanionAnniversary): CompanionSpaceSetting = copy(
    anniversaries = (anniversaries.filterNot { it.id == anniversary.id } + anniversary)
        .sortedBy { it.createdAtMillis }
        .takeLast(MAX_COMPANION_ANNIVERSARIES),
)

fun CompanionSpaceSetting.withoutAnniversary(id: Uuid): CompanionSpaceSetting = copy(
    anniversaries = anniversaries.filterNot { it.id == id },
)

fun CompanionSpaceSetting.withLetter(letter: CompanionLetter): CompanionSpaceSetting = copy(
    letters = (letters.filterNot { it.id == letter.id } + letter)
        .sortedBy { it.createdAtMillis }
        .takeLast(MAX_COMPANION_LETTERS),
)

fun CompanionSpaceSetting.withoutLetter(id: Uuid): CompanionSpaceSetting = copy(
    letters = letters.filterNot { it.id == id },
)

fun CompanionSpaceSetting.withSharedTask(task: CompanionSharedTask): CompanionSpaceSetting = copy(
    sharedTasks = (sharedTasks.filterNot { it.id == task.id } + task)
        .sortedBy { it.createdAtMillis }
        .takeLast(MAX_COMPANION_SHARED_TASKS),
)

fun CompanionSpaceSetting.withoutSharedTask(id: Uuid): CompanionSpaceSetting = copy(
    sharedTasks = sharedTasks.filterNot { it.id == id },
)

const val MAX_DIARY_CANDIDATES = 60
const val MAX_COMPANION_PHOTOS = 48
const val MAX_COMPANION_ANNIVERSARIES = 48
const val MAX_COMPANION_LETTERS = 80
const val MAX_COMPANION_SHARED_TASKS = 120
