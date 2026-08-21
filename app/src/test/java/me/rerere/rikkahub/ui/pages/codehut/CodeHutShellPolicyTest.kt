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
        assertEquals("待 1 号窗口合并权限策略", environment.permissions.detail)
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
    fun unsupportedGatewayResultRequiresWorkbenchContinuation() {
        val task = CodeHutTaskDraft(
            taskText = "更新 README",
            selectedFilesText = "README.md",
        ).toTask()

        val updated = applyGatewayResult(
            task = task,
            result = HarnessGatewayResult.UnsupportedApi(
                workbenchUrl = "http://127.0.0.1:3080",
                message = "未发现已验证的 Harness 任务 API，请在工作台中继续操作",
            ),
        )

        assertEquals(CodeHutTaskStatus.UNSUPPORTED, updated.status)
        assertEquals("转到完整工作台继续", codeHutTaskActionLabel(updated))
        assertTrue(updated.result?.summary.orEmpty().contains("工作台"))
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
