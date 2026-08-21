/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.codehut

import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.codehut.CodeHutTaskStatus
import me.rerere.rikkahub.data.codehut.HarnessGatewayResult
import me.rerere.rikkahub.data.codehut.TaskResultSummary
import me.rerere.rikkahub.data.datastore.CodeHutSetting
import me.rerere.rikkahub.data.datastore.HarnessSnapshot
import me.rerere.rikkahub.data.datastore.HarnessStatus
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.WorkCapability
import me.rerere.rikkahub.data.datastore.WorkModelBinding
import me.rerere.rikkahub.data.datastore.WorkProtocol
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class CodeHutShellPolicyTest {
    @Test
    fun defaultEnvironmentShowsExplicitUnconfiguredStates() {
        val environment = codeHutEnvironmentPresentation(
            settings = Settings(),
            harnessSnapshot = HarnessSnapshot(status = HarnessStatus.NOT_INSTALLED),
        )

        assertEquals("未配置工作模型", environment.model.detail)
        assertEquals("未安装", environment.service.status)
        assertEquals("未启用 Skills", environment.skills.detail)
        assertEquals("未配置 MCP", environment.mcp.detail)
        assertEquals("未配置 GitHub", environment.github.detail)
        assertEquals("遵循当前 Harness 风险确认策略；高风险操作仍须单独确认。", environment.permissions.detail)
    }

    @Test
    fun environmentSummaryDoesNotExposeOauthSecrets() {
        val base = Settings()
        val settings = base.copy(
            mcpServers = listOf(
                McpServerConfig.StreamableHTTPServer(
                    commonOptions = McpCommonOptions(
                        enable = true,
                        name = "GitHub",
                        oauth = McpOAuthState(
                            enabled = true,
                            accessToken = "ghp_real_secret",
                            refreshToken = "refresh_secret",
                            clientSecret = "client_secret",
                        ),
                    ),
                    url = "https://api.githubcopilot.example/mcp",
                ),
            ),
            assistants = listOf(base.assistants.first().copy(enabledSkills = setOf("kotlin-review"))),
        )

        val text = codeHutEnvironmentPresentation(
            settings = settings,
            harnessSnapshot = HarnessSnapshot(status = HarnessStatus.RUNNING),
        ).flattenText()

        assertTrue(text.contains("GitHub MCP 已配置"))
        assertTrue(text.contains("1 个已启用 Skill"))
        assertFalse(text.contains("ghp_real_secret"))
        assertFalse(text.contains("refresh_secret"))
        assertFalse(text.contains("client_secret"))
    }

    @Test
    fun workbenchAvailabilityMatchesHarnessRunningState() {
        HarnessStatus.entries.forEach { status ->
            val presentation = codeHutWorkbenchPresentation(status)

            assertEquals(status.name, status == HarnessStatus.RUNNING, presentation.canOpen)
        }

        val stopped = codeHutWorkbenchPresentation(HarnessStatus.STOPPED)
        assertEquals("安装 / 启动 Harness", stopped.actionLabel)
        assertTrue(stopped.guidance.contains("Harness 设置"))
    }

    @Test
    fun taskDraftCreatesTicketFromOnlyExplicitTaskFilesDirectoryAndConstraints() {
        val ticket = CodeHutTaskDraft(
            taskText = "修复解析器空输入崩溃",
            selectedFilesText = "app/src/main/java/Parser.kt\napp/src/test/java/ParserTest.kt",
            workingDirectory = "app",
            constraintsText = "保持现有聊天 Provider 不变\n不要生成 APK",
        ).toTicket()

        assertEquals("修复解析器空输入崩溃", ticket.taskText)
        assertEquals(
            listOf("app/src/main/java/Parser.kt", "app/src/test/java/ParserTest.kt"),
            ticket.selectedFiles,
        )
        assertEquals("app", ticket.workingDirectory)
        assertEquals(listOf("保持现有聊天 Provider 不变", "不要生成 APK"), ticket.constraints)
        assertFalse(ticket.prompt.contains("Daddy"))
        assertFalse(ticket.prompt.contains("Ombre", ignoreCase = true))
        assertFalse(ticket.prompt.contains("conversation", ignoreCase = true))
    }

    @Test
    fun taskDraftAllowsOrdinaryWordsWithoutTreatingThemAsInjectedContext() {
        val ticket = CodeHutTaskDraft(
            taskText = "review assistant conversation export docs",
            selectedFilesText = "docs/conversation-export.md",
            constraintsText = "document memory layout only, do not include chat logs",
        ).toTicket()

        assertEquals("review assistant conversation export docs", ticket.taskText)
        assertEquals(listOf("document memory layout only, do not include chat logs"), ticket.constraints)
    }

    @Test
    fun preparedGatewayResultRequiresExplicitWorkbenchContinuation() {
        val task = CodeHutTaskDraft(
            taskText = "更新 README",
            selectedFilesText = "README.md",
        ).toTask()

        val updated = applyGatewayResult(
            task = task,
            result = HarnessGatewayResult.PreparedForWorkbench(
                workbenchUrl = "http://127.0.0.1:3080",
                message = "任务已准备，尚未提交给 Harness。请复制任务内容，再在完整工作台中手动粘贴运行。",
            ),
        )

        assertEquals(CodeHutTaskStatus.PREPARED, updated.status)
        assertEquals("复制任务内容", codeHutTaskActionLabel(updated))
        assertTrue(updated.result?.summary.orEmpty().contains("工作台"))
        assertTrue(updated.result?.summary.orEmpty().contains("尚未提交"))
    }

    @Test
    fun taskResultDisplayIsRedactedBeforeNativeShellDisplay() {
        val updated = TaskResultSummary(
            summary = "ok Authorization: Bearer sk-live-secret api key=abc123 token: zzz password=hunter2",
            changedFiles = listOf("README.md", "build/api_key=leaked-key.txt"),
            verification = "curl -H 'Authorization: Bearer ghp_secret' token=plain",
        ).redactedForCodeHutShell()
        val text = listOf(
            updated.summary,
            updated.verification,
            updated.changedFiles.joinToString("\n"),
        ).joinToString("\n")

        assertTrue(text.contains("[REDACTED]"))
        assertFalse(text.contains("sk-live-secret"))
        assertFalse(text.contains("abc123"))
        assertFalse(text.contains("zzz"))
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("ghp_secret"))
        assertFalse(text.contains("leaked-key"))
    }

    @Test
    fun environmentDiagnosticsRedactSecretsAndCapOutput() {
        val rawDetail = "Authorization: Bearer sk-live-secret api_key=abc123 " + "x".repeat(2_500)
        val environment = codeHutEnvironmentPresentation(
            settings = Settings(),
            harnessSnapshot = HarnessSnapshot(
                status = HarnessStatus.ERROR,
                detail = rawDetail,
            ),
        )

        val diagnostic = environment.diagnostics.detail

        assertFalse(diagnostic.contains("sk-live-secret"))
        assertFalse(diagnostic.contains("abc123"))
        assertTrue(diagnostic.length <= 2_000)
    }

    @Test
    fun gatewayAndRefreshDiagnosticsUseSharedRedaction() {
        val raw = "refresh failed: {\"token\":\"token-secret\"} password=hunter2 " + "x".repeat(2_500)

        val displayed = redactCodeHutShellText(raw)

        assertFalse(displayed.contains("token-secret"))
        assertFalse(displayed.contains("hunter2"))
        assertTrue(displayed.length <= 2_000)
    }

    @Test
    fun runningTaskActionDoesNotClaimRemoteStopWithoutDocumentedApi() {
        val running = CodeHutTaskDraft(
            taskText = "更新 README",
            selectedFilesText = "README.md",
        ).toTask().copy(status = CodeHutTaskStatus.RUNNING)

        assertEquals("隐藏本地请求", codeHutTaskActionLabel(running))
        assertTrue(codeHutTaskStopNotice(running).contains("不能保证停止"))
    }

    @Test
    fun configuredBindingReportsHarnessExecutorWithoutProviderKeyMaterial() {
        val binding = WorkModelBinding(
            providerId = Uuid.random(),
            modelId = Uuid.random(),
            protocol = WorkProtocol.OPENAI_RESPONSES,
            capability = WorkCapability.EXECUTABLE,
        )
        val environment = codeHutEnvironmentPresentation(
            settings = Settings(
                codeHutSetting = CodeHutSetting(
                    defaultBindingId = binding.id,
                    bindings = listOf(binding),
                ),
            ),
            harnessSnapshot = HarnessSnapshot(status = HarnessStatus.RUNNING),
        )

        assertEquals("Harness 单一执行器", environment.model.status)
        assertTrue(environment.model.detail.contains("OpenAI Responses"))
        assertFalse(environment.flattenText().contains("sk-"))
        assertFalse(environment.flattenText().contains("api key", ignoreCase = true))
    }
}
