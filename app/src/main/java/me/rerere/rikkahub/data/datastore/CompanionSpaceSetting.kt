/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable
import java.time.LocalDate
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
    val courses: List<CompanionCourse> = emptyList(),
    val deadlines: List<CompanionDeadline> = emptyList(),
    val deadlineRemindersEnabled: Boolean = true,
    val widgetSetting: CompanionWidgetSetting = CompanionWidgetSetting(),
    /** Kept locally so Daddy's normal backup can restore the Amap service key too. */
    val amapRouteSetting: AmapRouteSetting = AmapRouteSetting(),
)

@Serializable
data class CompanionCourse(
    val id: Uuid = Uuid.random(),
    val name: String,
    val teacher: String = "",
    val location: String = "",
    /** ISO weekday: Monday = 1, Sunday = 7. */
    val weekday: Int,
    val startMinutes: Int,
    val endMinutes: Int,
    val termLabel: String = "",
    val validFromEpochDay: Long? = null,
    val validUntilEpochDay: Long? = null,
) {
    fun appliesOn(date: LocalDate): Boolean =
        date.dayOfWeek.value == weekday &&
            (validFromEpochDay == null || date.toEpochDay() >= validFromEpochDay) &&
            (validUntilEpochDay == null || date.toEpochDay() <= validUntilEpochDay)
}

@Serializable
data class DeadlineStep(
    val id: Uuid = Uuid.random(),
    val title: String,
    val order: Int,
    val completed: Boolean = false,
    val suggestedDateEpochDay: Long? = null,
)

@Serializable
enum class DeadlineStatus {
    OPEN,
    IN_PROGRESS,
    DONE,
    CANCELLED,
}

@Serializable
enum class TodoistSyncState {
    LOCAL_ONLY,
    CONFIRMED,
    SYNCED,
    SYNC_FAILED,
    SYNC_UNKNOWN,
}

@Serializable
data class CompanionDeadline(
    val id: Uuid = Uuid.random(),
    val type: String,
    val title: String,
    val courseId: Uuid? = null,
    val dueAtEpochMillis: Long,
    val note: String = "",
    val status: DeadlineStatus = DeadlineStatus.OPEN,
    val remindersEnabled: Boolean = true,
    val disabledReminderOffsets: Set<Int> = emptySet(),
    val deliveredReminderOffsets: Set<Int> = emptySet(),
    val steps: List<DeadlineStep> = emptyList(),
    val draftRevision: Long = 0L,
    val todoistConfirmedRevision: Long? = null,
    val todoistSyncState: TodoistSyncState = TodoistSyncState.LOCAL_ONLY,
    val todoistTaskId: String? = null,
)

fun CompanionDeadline.withSteps(newSteps: List<DeadlineStep>): CompanionDeadline =
    copy(
        steps = newSteps.sortedBy { it.order },
        deliveredReminderOffsets = emptySet(),
        draftRevision = draftRevision + 1,
        todoistConfirmedRevision = null,
        todoistSyncState = TodoistSyncState.LOCAL_ONLY,
        todoistTaskId = null,
    )

fun CompanionDeadline.withDraftRevision(): CompanionDeadline = copy(
    deliveredReminderOffsets = emptySet(),
    draftRevision = draftRevision + 1,
    todoistConfirmedRevision = null,
    todoistSyncState = TodoistSyncState.LOCAL_ONLY,
    todoistTaskId = null,
)

fun CompanionDeadline.confirmTodoistDraft(): CompanionDeadline = copy(
    todoistConfirmedRevision = draftRevision,
    todoistSyncState = TodoistSyncState.CONFIRMED,
)

fun CompanionDeadline.withTodoistSyncSuccess(taskId: String): CompanionDeadline = copy(
    todoistSyncState = TodoistSyncState.SYNCED,
    todoistTaskId = taskId,
)

fun CompanionDeadline.withTodoistSyncFailure(): CompanionDeadline = copy(
    todoistSyncState = TodoistSyncState.SYNC_FAILED,
)

/** The server accepted the call but did not return a task ID we can safely persist or retry. */
fun CompanionDeadline.withTodoistSyncUnknown(): CompanionDeadline = copy(
    todoistSyncState = TodoistSyncState.SYNC_UNKNOWN,
)

fun CompanionDeadline.markReminderDelivered(offsetDays: Int): CompanionDeadline = copy(
    deliveredReminderOffsets = deliveredReminderOffsets + offsetDays,
)

fun CompanionSpaceSetting.withCourse(course: CompanionCourse): CompanionSpaceSetting = copy(
    courses = (courses.filterNot { it.id == course.id } + course)
        .sortedWith(compareBy<CompanionCourse> { it.weekday }.thenBy { it.startMinutes })
        .takeLast(MAX_COMPANION_COURSES),
)

fun CompanionSpaceSetting.withoutCourse(id: Uuid): CompanionSpaceSetting = copy(
    courses = courses.filterNot { it.id == id },
)

/** Adds only new timetable slots, so a repeated import never erases manual edits. */
fun CompanionSpaceSetting.withImportedCourses(imported: List<CompanionCourse>): CompanionSpaceSetting {
    val existingKeys = courses.mapTo(mutableSetOf(), CompanionCourse::importKey)
    val availableSlots = (MAX_COMPANION_COURSES - courses.size).coerceAtLeast(0)
    val additions = imported
        .filter { course -> existingKeys.add(course.importKey()) }
        .take(availableSlots)
    return copy(
        courses = (courses + additions)
            .sortedWith(compareBy<CompanionCourse> { it.weekday }.thenBy { it.startMinutes }),
    )
}

private fun CompanionCourse.importKey(): String = listOf(
    name.trim().lowercase(),
    weekday,
    startMinutes,
    endMinutes,
    location.trim().lowercase(),
).joinToString("|")

fun CompanionSpaceSetting.withDeadline(deadline: CompanionDeadline): CompanionSpaceSetting = copy(
    deadlines = (deadlines.filterNot { it.id == deadline.id } + deadline)
        .sortedBy { it.dueAtEpochMillis }
        .takeLast(MAX_COMPANION_DEADLINES),
)

fun CompanionSpaceSetting.withoutDeadline(id: Uuid): CompanionSpaceSetting = copy(
    deadlines = deadlines.filterNot { it.id == id },
)

fun CompanionSpaceSetting.coursesOn(date: LocalDate): List<CompanionCourse> =
    courses.filter { it.appliesOn(date) }

@Serializable
data class CompanionWidgetSetting(
    val enabled: Boolean = true,
    val backgroundImageUri: String = "",
    val shortLine: String = "",
)

/** The local-only configuration for Daddy's Termux-hosted Amap MCP service. */
@Serializable
data class AmapRouteSetting(
    val apiKey: String = "",
    val configuredAtMillis: Long? = null,
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
const val MAX_COMPANION_COURSES = 100
const val MAX_COMPANION_DEADLINES = 300
