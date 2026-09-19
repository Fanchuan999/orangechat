package me.rerere.rikkahub.data.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionCourseImporterTest {
    @Test
    fun `imports Chinese CSV course headers and weekday`() {
        val result = CompanionCourseImporter.parse(
            """
            课程名称,星期,开始时间,结束时间,教师,地点
            高级统计,周一,09:00,10:40,王老师,教学楼 201
            研究方法,3,14:00,15:40,李老师,图书馆 3F
            """.trimIndent(),
        )

        assertEquals(2, result.courses.size)
        assertEquals("高级统计", result.courses[0].name)
        assertEquals(1, result.courses[0].weekday)
        assertEquals(9 * 60, result.courses[0].startMinutes)
        assertEquals(10 * 60 + 40, result.courses[0].endMinutes)
        assertEquals("教学楼 201", result.courses[0].location)
        assertEquals(3, result.courses[1].weekday)
    }

    @Test
    fun `imports weekly iCalendar events as courses`() {
        val result = CompanionCourseImporter.parse(
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:机器学习
            DTSTART;TZID=Asia/Shanghai:20260914T133000
            DTEND;TZID=Asia/Shanghai:20260914T151000
            LOCATION:理科楼 402
            RRULE:FREQ=WEEKLY
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
        )

        assertEquals(1, result.courses.size)
        val course = result.courses.single()
        assertEquals("机器学习", course.name)
        assertEquals(1, course.weekday)
        assertEquals(13 * 60 + 30, course.startMinutes)
        assertEquals(15 * 60 + 10, course.endMinutes)
        assertEquals("理科楼 402", course.location)
        assertTrue(result.skippedRows == 0)
    }
}
