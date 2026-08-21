/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.codehut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CodeHutTaskPolicyTest {
    @Test
    fun ticketContainsOnlyApprovedTaskScope() {
        val ticket = CodeHutTaskPolicy.createTicket(
            taskText = "repair parser",
            selectedFiles = listOf("src/Parser.kt"),
        )

        assertEquals(listOf("src/Parser.kt"), ticket.selectedFiles)
        assertFalse(ticket.prompt.contains("Ombre"))
        assertFalse(ticket.prompt.contains("Conversation"))
        assertFalse(ticket.prompt.contains("Assistant"))
    }

    @Test
    fun ticketAllowsOrdinaryContextWordsBecauseIsolationIsStructural() {
        val ticket = CodeHutTaskPolicy.createTicket(
            taskText = "repair assistant conversation export",
            selectedFiles = listOf("src/Parser.kt"),
            constraints = listOf("document memory format only"),
        )

        assertEquals("repair assistant conversation export", ticket.taskText)
        assertEquals(listOf("document memory format only"), ticket.constraints)
    }

    @Test
    fun ticketNormalizesFilesAndConstraintsWithoutAddingContext() {
        val ticket = CodeHutTaskPolicy.createTicket(
            taskText = "  repair parser  ",
            selectedFiles = listOf("src/Parser.kt", "src/Parser.kt", " tests/ParserTest.kt "),
            workingDirectory = "app",
            constraints = listOf("  keep public API  ", "keep public API"),
        )

        assertEquals("repair parser", ticket.taskText)
        assertEquals(listOf("src/Parser.kt", "tests/ParserTest.kt"), ticket.selectedFiles)
        assertEquals(listOf("keep public API"), ticket.constraints)
        assertEquals("app", ticket.workingDirectory)
    }
}
