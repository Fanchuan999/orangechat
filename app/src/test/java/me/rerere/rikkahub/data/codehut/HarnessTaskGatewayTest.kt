/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.codehut

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessTaskGatewayTest {
    private val ticket = CodeHutTaskPolicy.createTicket(
        taskText = "repair parser",
        selectedFiles = listOf("src/Parser.kt"),
    )

    @Test
    fun noDocumentedPublicApiReturnsWorkbenchFallback() = runBlocking {
        val result = HarnessTaskGateway().submit(ticket)

        assertTrue(result is HarnessGatewayResult.UnsupportedApi)
        assertTrue((result as HarnessGatewayResult.UnsupportedApi).workbenchUrl.contains("127.0.0.1:3080"))
    }

    @Test
    fun successfulApiResultIsCappedBeforeReturningToDaddy() = runBlocking {
        val gateway = HarnessTaskGateway(
            documentedApi = HarnessTaskTransport {
                HarnessGatewayResult.Success(
                    TaskResultSummary(
                        summary = "x".repeat(4_000),
                        changedFiles = listOf("src/Parser.kt"),
                        verification = "y".repeat(4_000),
                    ),
                )
            },
            maxSummaryChars = 160,
        )

        val result = gateway.submit(ticket)

        assertTrue(result is HarnessGatewayResult.Success)
        val summary = (result as HarnessGatewayResult.Success).summary
        assertTrue(summary.summary.length <= 160)
        assertTrue(summary.verification.length <= 160)
    }

    @Test
    fun undocumentedApiTimeoutBecomesFailure() = runBlocking {
        val gateway = HarnessTaskGateway(
            documentedApi = HarnessTaskTransport {
                delay(100)
                HarnessGatewayResult.Success(TaskResultSummary(summary = "late"))
            },
            timeoutMillis = 10,
        )

        val result = gateway.submit(ticket)

        assertTrue(result is HarnessGatewayResult.Failure)
        assertTrue((result as HarnessGatewayResult.Failure).message.contains("超时"))
    }
}
