package me.rerere.rikkahub.data.service

import java.time.Instant
import java.time.ZoneId
import me.rerere.rikkahub.data.datastore.CompanionDeadline
import me.rerere.rikkahub.data.datastore.CompanionSpaceSetting
import me.rerere.rikkahub.data.datastore.DeadlineStatus
import me.rerere.rikkahub.data.datastore.TodoistSyncState

/** Computes local-calendar reminders without creating system alarms. */
object DeadlineReminderPlanner {
    val DEFAULT_OFFSETS_DAYS: List<Int> = listOf(7, 3, 1)

    fun planAll(
        setting: CompanionSpaceSetting,
        nowMillis: Long,
        zone: ZoneId,
    ): List<DeadlineReminder> = setting.deadlines.flatMap { deadline ->
        plan(
            deadline = deadline,
            globalEnabled = setting.deadlineRemindersEnabled,
            nowMillis = nowMillis,
            zone = zone,
            includeMissed = true,
        )
    }

    fun plan(
        deadline: CompanionDeadline,
        globalEnabled: Boolean,
        nowMillis: Long,
        zone: ZoneId,
        offsetsDays: List<Int> = DEFAULT_OFFSETS_DAYS,
        includeMissed: Boolean = true,
    ): List<DeadlineReminder> {
        if (!globalEnabled || !deadline.remindersEnabled) return emptyList()
        if (deadline.status == DeadlineStatus.DONE || deadline.status == DeadlineStatus.CANCELLED) {
            return emptyList()
        }
        val due = Instant.ofEpochMilli(deadline.dueAtEpochMillis).atZone(zone)
        if (due.toInstant().toEpochMilli() <= nowMillis) return emptyList()

        val reminders = offsetsDays
            .distinct()
            .filter { it > 0 && it !in deadline.disabledReminderOffsets && it !in deadline.deliveredReminderOffsets }
            .mapNotNull { offset ->
                val trigger = due.minusDays(offset.toLong())
                val triggerMillis = trigger.toInstant().toEpochMilli()
                if (triggerMillis < deadline.dueAtEpochMillis) {
                    DeadlineReminder(
                        deadlineId = deadline.id.toString(),
                        offsetDays = offset,
                        triggerAtEpochMillis = triggerMillis,
                    )
                } else {
                    null
                }
            }
            .sortedBy { it.triggerAtEpochMillis }

        if (!includeMissed) {
            return reminders.filter { it.triggerAtEpochMillis > nowMillis }
        }

        val missed = reminders.filter { it.triggerAtEpochMillis <= nowMillis }.maxByOrNull { it.triggerAtEpochMillis }
        return reminders.filter { it.triggerAtEpochMillis > nowMillis } + listOfNotNull(missed)
    }
}

data class DeadlineReminder(
    val deadlineId: String,
    val offsetDays: Int,
    val triggerAtEpochMillis: Long,
)

object TodoistDraftGate {
    fun canWrite(deadline: CompanionDeadline): Boolean =
        deadline.todoistConfirmedRevision == deadline.draftRevision &&
            deadline.todoistSyncState in setOf(TodoistSyncState.CONFIRMED, TodoistSyncState.SYNC_FAILED)
}
