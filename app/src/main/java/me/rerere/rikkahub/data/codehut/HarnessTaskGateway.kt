/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.codehut

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

sealed interface HarnessGatewayResult {
    data class Success(val summary: TaskResultSummary) : HarnessGatewayResult

    data class UnsupportedApi(
        val workbenchUrl: String,
        val message: String,
    ) : HarnessGatewayResult

    data class Failure(val message: String) : HarnessGatewayResult
}

fun interface HarnessTaskTransport {
    suspend fun submit(ticket: TaskTicket): HarnessGatewayResult
}

class HarnessTaskGateway(
    private val documentedApi: HarnessTaskTransport? = null,
    private val workbenchUrl: String = "http://127.0.0.1:3080",
    private val timeoutMillis: Long = 5_000L,
    private val maxSummaryChars: Int = 2_000,
) {
    init {
        require(workbenchUrl.startsWith("http://127.0.0.1:") || workbenchUrl.startsWith("http://localhost:")) {
            "workbenchUrl must remain loopback-only"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        require(maxSummaryChars > 0) { "maxSummaryChars must be positive" }
    }

    suspend fun submit(ticket: TaskTicket): HarnessGatewayResult {
        val validatedTicket = try {
            CodeHutTaskPolicy.validate(ticket)
        } catch (error: IllegalArgumentException) {
            return HarnessGatewayResult.Failure(error.message.orEmpty().take(maxSummaryChars))
        }

        val api = documentedApi ?: return HarnessGatewayResult.UnsupportedApi(
            workbenchUrl = workbenchUrl,
            message = "未发现已验证的 Harness 任务 API，请在工作台中继续操作",
        )

        return try {
            withTimeout(timeoutMillis) {
                api.submit(validatedTicket).capped(maxSummaryChars)
            }
        } catch (_: TimeoutCancellationException) {
            HarnessGatewayResult.Failure("Harness 任务请求超时")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            HarnessGatewayResult.Failure(
                "Harness 任务请求失败: ${redact(error.message.orEmpty()).take(maxSummaryChars)}",
            )
        }
    }

    private fun HarnessGatewayResult.capped(maxChars: Int): HarnessGatewayResult = when (this) {
        is HarnessGatewayResult.Success -> copy(summary = summary.capped(maxChars))
        is HarnessGatewayResult.UnsupportedApi -> copy(message = message.take(maxChars))
        is HarnessGatewayResult.Failure -> copy(message = redact(message).take(maxChars))
    }

    private fun redact(value: String): String = value.replace(
        Regex("(?i)(api[_ -]?key|authorization|token|secret)\\s*[:=]\\s*[^,;\\s]+"),
        "$1=[REDACTED]",
    )
}
