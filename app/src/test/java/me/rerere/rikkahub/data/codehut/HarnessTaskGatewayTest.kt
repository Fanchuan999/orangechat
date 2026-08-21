/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.codehut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessTaskGatewayTest {
    private val ticket = CodeHutTaskPolicy.createTicket(
        taskText = "repair parser",
        selectedFiles = listOf("src/Parser.kt"),
    )

    @Test
    fun prepareForWorkbenchKeepsTicketLocalAndNeverClaimsSubmission() {
        val result = HarnessTaskGateway().prepareForWorkbench(ticket)

        assertTrue(result is HarnessGatewayResult.PreparedForWorkbench)
        result as HarnessGatewayResult.PreparedForWorkbench
        assertTrue(result.workbenchUrl.contains("127.0.0.1:3080"))
        assertTrue(result.message.contains("尚未提交"))
    }

    @Test
    fun invalidLocalTicketReturnsFailureWithoutTryingToReachHarness() {
        val result = HarnessTaskGateway().prepareForWorkbench(
            ticket.copy(selectedFiles = emptyList()),
        )

        assertTrue(result is HarnessGatewayResult.Failure)
        assertEquals("at least one selected file is required", (result as HarnessGatewayResult.Failure).message)
    }
}
