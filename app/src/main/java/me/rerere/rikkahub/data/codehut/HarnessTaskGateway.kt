/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.codehut

sealed interface HarnessGatewayResult {
    /**
     * The ticket is local only. No task has been sent to Harness.
     */
    data class PreparedForWorkbench(
        val workbenchUrl: String,
        val message: String,
    ) : HarnessGatewayResult

    data class Failure(val message: String) : HarnessGatewayResult
}

class HarnessTaskGateway(
    private val workbenchUrl: String = "http://127.0.0.1:3080",
) {
    init {
        require(workbenchUrl.startsWith("http://127.0.0.1:") || workbenchUrl.startsWith("http://localhost:")) {
            "workbenchUrl must remain loopback-only"
        }
    }

    /**
     * Validates a local ticket without guessing or probing a private Harness task endpoint.
     * The user must explicitly copy this prompt and paste it into the verified running workbench.
     */
    fun prepareForWorkbench(ticket: TaskTicket): HarnessGatewayResult {
        try {
            CodeHutTaskPolicy.validate(ticket)
        } catch (error: IllegalArgumentException) {
            return HarnessGatewayResult.Failure(
                redactCodeHutUiText(error.message.orEmpty()),
            )
        }

        return HarnessGatewayResult.PreparedForWorkbench(
            workbenchUrl = workbenchUrl,
            message = "任务已准备，尚未提交给 Harness。请复制任务内容，再在完整工作台中手动粘贴运行。",
        )
    }
}
