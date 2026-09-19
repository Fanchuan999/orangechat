package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ContextBudgetTest {
    @Test
    fun `build groups prompt pieces and retains only display labels`() {
        val privatePrompt = "private prompt text must not be retained"
        val report = ContextBudgetBuilder()
            .addText(ContextBudgetCategory.PERSONA, "人设", privatePrompt)
            .addText(ContextBudgetCategory.PERSONA, "补充规则", "你好")
            .addText(ContextBudgetCategory.PLUGINS, "网页阅读器", "abcde")
            .addText(ContextBudgetCategory.HISTORY, "空历史", "")
            .build()

        assertEquals(estimatePromptTokens(privatePrompt) + 4, report.totalEstimatedTokens)
        assertEquals(
            listOf(
                ContextBudgetCategoryTotal(ContextBudgetCategory.PERSONA, estimatePromptTokens(privatePrompt) + 2),
                ContextBudgetCategoryTotal(ContextBudgetCategory.PLUGINS, 2),
            ),
            report.categoryTotals,
        )
        assertEquals(
            listOf(
                ContextBudgetEntry(ContextBudgetCategory.PERSONA, "人设", estimatePromptTokens(privatePrompt)),
                ContextBudgetEntry(ContextBudgetCategory.PERSONA, "补充规则", 2),
                ContextBudgetEntry(ContextBudgetCategory.PLUGINS, "网页阅读器", 2),
            ),
            report.entries,
        )
        assertFalse(report.toString().contains(privatePrompt))
    }

    @Test
    fun `build omits zero token entries while keeping manually supplied estimates`() {
        val report = ContextBudgetBuilder()
            .addEstimated(ContextBudgetCategory.SYSTEM_TOOLS, "日历", 6)
            .addEstimated(ContextBudgetCategory.SYSTEM_TOOLS, "空工具", 0)
            .build()

        assertEquals(6, report.totalEstimatedTokens)
        assertEquals(
            listOf(ContextBudgetEntry(ContextBudgetCategory.SYSTEM_TOOLS, "日历", 6)),
            report.entries,
        )
    }

    @Test
    fun `tracker keeps only the newest report`() {
        val tracker = ContextBudgetTracker()
        val first = ContextBudgetBuilder()
            .addEstimated(ContextBudgetCategory.HISTORY, "历史消息", 4)
            .build()
        val newest = ContextBudgetBuilder()
            .addEstimated(ContextBudgetCategory.LOCAL_TOOLS, "日历", 8)
            .build()

        tracker.publish(first)
        tracker.publish(newest)

        assertEquals(newest, tracker.latestReport.value)
    }

    @Test
    fun `deduplicate keeps the first category for a tool name`() {
        val local = ContextBudgetTool(
            tool = testTool(name = "calendar"),
            category = ContextBudgetCategory.LOCAL_TOOLS,
        )
        val duplicateMcp = ContextBudgetTool(
            tool = testTool(name = "calendar"),
            category = ContextBudgetCategory.MCP,
        )

        val result = deduplicateContextBudgetTools(listOf(local, duplicateMcp))

        assertEquals(listOf(local), result)
    }

    private fun testTool(name: String): Tool = Tool(
        name = name,
        description = "test",
        execute = { emptyList() },
    )
}
