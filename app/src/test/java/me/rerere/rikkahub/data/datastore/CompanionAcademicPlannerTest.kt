package me.rerere.rikkahub.data.datastore

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.service.DeadlineReminderPlanner
import me.rerere.rikkahub.data.service.TodoistDraftGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class CompanionAcademicPlannerTest {
    @Test
    fun oldCompanionSpaceJsonGetsEmptyAcademicData() {
        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString<CompanionSpaceSetting>("{\"sharedTasks\":[]}")

        assertTrue(decoded.courses.isEmpty())
        assertTrue(decoded.deadlines.isEmpty())
        assertTrue(decoded.deadlineRemindersEnabled)
    }

    @Test
    fun coursesMatchAWeekdayAndInclusiveValidityRange() {
        val course = CompanionCourse(
            name = "高级统计",
            weekday = 1,
            startMinutes = 9 * 60,
            endMinutes = 11 * 60,
            validFromEpochDay = LocalDate.of(2026, 9, 1).toEpochDay(),
            validUntilEpochDay = LocalDate.of(2026, 12, 31).toEpochDay(),
        )

        assertTrue(course.appliesOn(LocalDate.of(2026, 9, 7)))
        assertFalse(course.appliesOn(LocalDate.of(2026, 9, 8)))
        assertFalse(course.appliesOn(LocalDate.of(2027, 1, 4)))
    }

    @Test
    fun reminderUsesLocalCalendarDaysAcrossDst() {
        val zone = ZoneId.of("America/New_York")
        val due = ZonedDateTime.of(2026, 3, 15, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val deadline = CompanionDeadline(title = "报告", type = "报告", dueAtEpochMillis = due)

        val reminders = DeadlineReminderPlanner.plan(
            deadline = deadline,
            globalEnabled = true,
            nowMillis = ZonedDateTime.of(2026, 3, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli(),
            zone = zone,
        )

        assertEquals(
            listOf(7, 3, 1),
            reminders.map { it.offsetDays },
        )
        assertEquals(
            ZonedDateTime.of(2026, 3, 8, 10, 0, 0, 0, zone).toInstant(),
            Instant.ofEpochMilli(reminders.first { it.offsetDays == 7 }.triggerAtEpochMillis),
        )
        assertEquals(3, reminders.first { it.offsetDays == 3 }.offsetDays)
    }

    @Test
    fun pastAndDisabledRemindersAreNotScheduled() {
        val now = Instant.parse("2026-09-12T12:00:00Z").toEpochMilli()
        val deadline = CompanionDeadline(
            title = "考试",
            type = "考试",
            dueAtEpochMillis = Instant.parse("2026-09-13T12:00:00Z").toEpochMilli(),
            disabledReminderOffsets = setOf(1),
        )

        val reminders = DeadlineReminderPlanner.plan(deadline, true, now, ZoneId.of("UTC"))
        assertEquals(listOf(3), reminders.map { it.offsetDays })

        val past = deadline.copy(dueAtEpochMillis = now - 1)
        assertTrue(DeadlineReminderPlanner.plan(past, true, now, ZoneId.of("UTC")).isEmpty())
        assertTrue(DeadlineReminderPlanner.plan(deadline, false, now, ZoneId.of("UTC")).isEmpty())
    }

    @Test
    fun deadlineReplacementInvalidatesTheOldTodoistConfirmation() {
        val id = Uuid.random()
        val original = CompanionDeadline(
            id = id,
            title = "初稿",
            type = "作业",
            dueAtEpochMillis = 1_800_000_000_000,
            steps = listOf(DeadlineStep(title = "读题", order = 0)),
        )
        val confirmed = original.confirmTodoistDraft()
        assertTrue(TodoistDraftGate.canWrite(confirmed))

        val edited = confirmed.withSteps(
            listOf(
                DeadlineStep(title = "读题", order = 0),
                DeadlineStep(title = "写初稿", order = 1),
            )
        )

        assertEquals(original.draftRevision + 1, edited.draftRevision)
        assertFalse(TodoistDraftGate.canWrite(edited))
        assertEquals(TodoistSyncState.LOCAL_ONLY, edited.todoistSyncState)
    }

    @Test
    fun importingAnExistingCourseKeepsTheManuallyEditedEntry() {
        val edited = CompanionCourse(
            name = "高级统计",
            teacher = "王老师",
            location = "教学楼 201",
            weekday = 1,
            startMinutes = 9 * 60,
            endMinutes = 10 * 60 + 40,
        )
        val importedDuplicate = edited.copy(id = Uuid.random(), teacher = "")

        val updated = CompanionSpaceSetting(courses = listOf(edited))
            .withImportedCourses(listOf(importedDuplicate))

        assertEquals(1, updated.courses.size)
        assertEquals("王老师", updated.courses.single().teacher)
    }
}
