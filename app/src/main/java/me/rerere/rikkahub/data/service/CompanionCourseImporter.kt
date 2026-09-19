package me.rerere.rikkahub.data.service

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import me.rerere.document.CsvParser
import me.rerere.rikkahub.data.datastore.CompanionCourse

/** Parsed courses are only previews until [CompanionSpaceService.importCourses] is called. */
data class CompanionCourseImportResult(
    val courses: List<CompanionCourse>,
    val skippedRows: Int,
)

/** Imports the two portable formats most course systems can export: CSV and iCalendar (.ics). */
object CompanionCourseImporter {
    fun parse(content: String): CompanionCourseImportResult {
        val normalized = content.trim().removePrefix("\uFEFF")
        if (normalized.isBlank()) return CompanionCourseImportResult(emptyList(), skippedRows = 0)
        return if (normalized.contains("BEGIN:VCALENDAR", ignoreCase = true)) {
            parseIcs(normalized)
        } else {
            parseCsv(normalized)
        }
    }

    private fun parseCsv(content: String): CompanionCourseImportResult {
        val rows = CsvParser.parse(content)
            .map { row -> if (row.size == 1 && row.single().contains('\t')) row.single().split('\t') else row }
            .filter { row -> row.any { it.isNotBlank() } }
        if (rows.isEmpty()) return CompanionCourseImportResult(emptyList(), skippedRows = 0)

        val header = rows.first().map(::normalizeHeader)
        val hasHeader = header.any { it in allKnownHeaders }
        val dataRows = if (hasHeader) rows.drop(1) else rows
        var skipped = 0
        val courses = dataRows.mapNotNull { row ->
            val fields = if (hasHeader) HeaderFields(header, row) else PositionalFields(row)
            course(
                name = fields.value(nameHeaders),
                weekday = fields.value(weekdayHeaders),
                start = fields.value(startHeaders),
                end = fields.value(endHeaders),
                teacher = fields.value(teacherHeaders),
                location = fields.value(locationHeaders),
                term = fields.value(termHeaders),
            ) ?: run {
                skipped++
                null
            }
        }
        return CompanionCourseImportResult(courses.distinctBy(::courseKey), skipped)
    }

    private fun parseIcs(content: String): CompanionCourseImportResult {
        val unfoldedLines = content
            .replace("\r\n", "\n")
            .lines()
            .fold(mutableListOf<String>()) { lines, line ->
                if ((line.startsWith(' ') || line.startsWith('\t')) && lines.isNotEmpty()) {
                    lines[lines.lastIndex] += line.drop(1)
                } else {
                    lines += line
                }
                lines
            }
        val events = mutableListOf<List<String>>()
        var current: MutableList<String>? = null
        unfoldedLines.forEach { line ->
            when (line.uppercase()) {
                "BEGIN:VEVENT" -> current = mutableListOf()
                "END:VEVENT" -> current?.let(events::add).also { current = null }
                else -> current?.add(line)
            }
        }

        var skipped = 0
        val courses = events.mapNotNull { event ->
            val name = icsField(event, "SUMMARY")?.let(::unescapeIcs).orEmpty()
            val start = icsDateTime(icsField(event, "DTSTART"))
            val end = icsDateTime(icsField(event, "DTEND"))
            course(
                name = name,
                weekday = start?.dayOfWeek?.value?.toString().orEmpty(),
                start = start?.let(::minutesOfDay)?.toString().orEmpty(),
                end = end?.let(::minutesOfDay)?.toString().orEmpty(),
                teacher = "",
                location = icsField(event, "LOCATION")?.let(::unescapeIcs).orEmpty(),
                term = "",
                timesAreMinutes = true,
            ) ?: run {
                skipped++
                null
            }
        }
        return CompanionCourseImportResult(courses.distinctBy(::courseKey), skipped)
    }

    private fun course(
        name: String,
        weekday: String,
        start: String,
        end: String,
        teacher: String,
        location: String,
        term: String,
        timesAreMinutes: Boolean = false,
    ): CompanionCourse? {
        val startMinutes = if (timesAreMinutes) start.toIntOrNull() else parseClock(start)
        val endMinutes = if (timesAreMinutes) end.toIntOrNull() else parseClock(end)
        val week = parseWeekday(weekday)
        if (name.isBlank() || week == null || startMinutes == null || endMinutes == null || endMinutes <= startMinutes) {
            return null
        }
        return CompanionCourse(
            name = name.trim().take(MAX_NAME_LENGTH),
            teacher = teacher.trim().take(MAX_TEACHER_LENGTH),
            location = location.trim().take(MAX_LOCATION_LENGTH),
            weekday = week,
            startMinutes = startMinutes,
            endMinutes = endMinutes,
            termLabel = term.trim().take(MAX_TERM_LENGTH),
        )
    }

    private fun parseClock(value: String): Int? {
        val fields = value.trim().replace('：', ':').split(':')
        val hour = fields.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val minute = fields.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
    }

    private fun parseWeekday(value: String): Int? = when (value.trim().lowercase()) {
        "1", "周一", "星期一", "mon", "monday" -> 1
        "2", "周二", "星期二", "tue", "tuesday" -> 2
        "3", "周三", "星期三", "wed", "wednesday" -> 3
        "4", "周四", "星期四", "thu", "thursday" -> 4
        "5", "周五", "星期五", "fri", "friday" -> 5
        "6", "周六", "星期六", "sat", "saturday" -> 6
        "7", "周日", "周天", "星期日", "星期天", "sun", "sunday" -> 7
        else -> null
    }

    private fun icsField(event: List<String>, name: String): String? = event
        .firstOrNull { line -> line.substringBefore(':').substringBefore(';').equals(name, ignoreCase = true) }
        ?.substringAfter(':', missingDelimiterValue = "")

    private fun icsDateTime(value: String?): LocalDateTime? {
        val raw = value?.trim()?.takeUnless { it.isBlank() } ?: return null
        return runCatching {
            if (raw.endsWith('Z')) {
                Instant.parse(raw.dropLast(1).let { "${it.take(4)}-${it.substring(4, 6)}-${it.substring(6, 8)}T${it.substring(9, 11)}:${it.substring(11, 13)}:${it.substring(13, 15)}Z" })
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime()
            } else {
                val formatter = when (raw.length) {
                    13 -> DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm")
                    15 -> DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
                    else -> error("Unsupported iCalendar date time")
                }
                LocalDateTime.parse(raw, formatter)
            }
        }.getOrNull()
    }

    private fun minutesOfDay(value: LocalDateTime): Int = value.hour * 60 + value.minute

    private fun unescapeIcs(value: String): String = value
        .replace("\\n", "\n")
        .replace("\\,", ",")
        .replace("\\;", ";")
        .replace("\\\\", "\\")

    private fun normalizeHeader(value: String): String = value
        .trim()
        .lowercase()
        .replace(" ", "")
        .replace("_", "")

    private fun courseKey(course: CompanionCourse): String = listOf(
        course.name.trim().lowercase(),
        course.weekday,
        course.startMinutes,
        course.endMinutes,
        course.location.trim().lowercase(),
    ).joinToString("|")

    private interface Fields {
        fun value(acceptedHeaders: Set<String>): String
    }

    private class HeaderFields(
        private val header: List<String>,
        private val row: List<String>,
    ) : Fields {
        override fun value(acceptedHeaders: Set<String>): String {
            val index = header.indexOfFirst { it in acceptedHeaders }
            return row.getOrNull(index).orEmpty()
        }
    }

    private class PositionalFields(private val row: List<String>) : Fields {
        override fun value(acceptedHeaders: Set<String>): String = when {
            acceptedHeaders === nameHeaders -> row.getOrNull(0).orEmpty()
            acceptedHeaders === weekdayHeaders -> row.getOrNull(1).orEmpty()
            acceptedHeaders === startHeaders -> row.getOrNull(2).orEmpty()
            acceptedHeaders === endHeaders -> row.getOrNull(3).orEmpty()
            acceptedHeaders === teacherHeaders -> row.getOrNull(4).orEmpty()
            acceptedHeaders === locationHeaders -> row.getOrNull(5).orEmpty()
            acceptedHeaders === termHeaders -> row.getOrNull(6).orEmpty()
            else -> ""
        }
    }

    private val nameHeaders = setOf("课程名称", "课程", "course", "name", "summary")
    private val weekdayHeaders = setOf("星期", "周几", "weekday", "day")
    private val startHeaders = setOf("开始时间", "上课时间", "start", "starttime")
    private val endHeaders = setOf("结束时间", "下课时间", "end", "endtime")
    private val teacherHeaders = setOf("教师", "老师", "teacher", "instructor")
    private val locationHeaders = setOf("地点", "教室", "location", "room")
    private val termHeaders = setOf("学期", "term", "semester")
    private val allKnownHeaders = nameHeaders + weekdayHeaders + startHeaders + endHeaders + teacherHeaders + locationHeaders + termHeaders

    private const val MAX_NAME_LENGTH = 80
    private const val MAX_TEACHER_LENGTH = 48
    private const val MAX_LOCATION_LENGTH = 80
    private const val MAX_TERM_LENGTH = 48
}
