package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.ai.core.Tool

/**
 * Local-only, content-free estimate of the material prepared for one model request.
 *
 * Prompt text is deliberately converted to a count before it enters this model, so debug UI can
 * explain request size without retaining or exposing chat, memory, or credential contents.
 */
enum class ContextBudgetCategory(val displayName: String) {
    PERSONA("人设与对话提示"),
    FIXED_RULES("固定通用规则"),
    MEMORIES("记忆"),
    EXTERNAL_MEMORY("外置记忆召回"),
    RECENT_CHAT_REFERENCE("近期对话参考"),
    CONTINUITY("生活线、状态与情绪"),
    HISTORY("历史消息"),
    WEB_SEARCH("网页搜索"),
    LOCAL_TOOLS("本地工具"),
    SYSTEM_TOOLS("系统工具"),
    MCP("MCP"),
    PLUGINS("插件"),
    PC_BRIDGE("PC Bridge"),
    WORKSPACE("工作区工具"),
    SKILLS("技能"),
    OTHER("其他"),
}

/** Keeps the source category beside a tool until its final provider request is assembled. */
data class ContextBudgetTool(
    val tool: Tool,
    val category: ContextBudgetCategory,
)

/**
 * Mirrors the existing first-wins duplicate-name behavior without losing the originating category.
 */
fun deduplicateContextBudgetTools(
    tools: List<ContextBudgetTool>,
    onDuplicate: ((String) -> Unit)? = null,
): List<ContextBudgetTool> {
    val names = mutableSetOf<String>()
    return buildList {
        tools.forEach { source ->
            if (names.add(source.tool.name)) {
                add(source)
            } else {
                onDuplicate?.invoke(source.tool.name)
            }
        }
    }
}

data class ContextBudgetEntry(
    val category: ContextBudgetCategory,
    val label: String,
    val estimatedTokens: Int,
)

data class ContextBudgetCategoryTotal(
    val category: ContextBudgetCategory,
    val estimatedTokens: Int,
)

data class ContextBudgetReport(
    val totalEstimatedTokens: Int,
    val categoryTotals: List<ContextBudgetCategoryTotal>,
    val entries: List<ContextBudgetEntry>,
)

class ContextBudgetBuilder {
    private val entries = mutableListOf<ContextBudgetEntry>()

    fun addText(
        category: ContextBudgetCategory,
        label: String,
        content: CharSequence,
    ): ContextBudgetBuilder = addEstimated(
        category = category,
        label = label,
        estimatedTokens = estimatePromptTokens(content.toString()),
    )

    fun addEstimated(
        category: ContextBudgetCategory,
        label: String,
        estimatedTokens: Int,
    ): ContextBudgetBuilder {
        if (estimatedTokens > 0) {
            entries += ContextBudgetEntry(
                category = category,
                label = label,
                estimatedTokens = estimatedTokens,
            )
        }
        return this
    }

    fun build(): ContextBudgetReport {
        val categoryTotals = ContextBudgetCategory.entries.mapNotNull { category ->
            val total = entries
                .asSequence()
                .filter { it.category == category }
                .sumOf { it.estimatedTokens }
            total.takeIf { it > 0 }?.let { ContextBudgetCategoryTotal(category, it) }
        }
        return ContextBudgetReport(
            totalEstimatedTokens = categoryTotals.sumOf { it.estimatedTokens },
            categoryTotals = categoryTotals,
            entries = entries.toList(),
        )
    }
}

/** Holds only the newest local estimate so request bodies and old reports never accumulate in memory. */
class ContextBudgetTracker {
    private val mutableLatestReport = MutableStateFlow<ContextBudgetReport?>(null)

    val latestReport: StateFlow<ContextBudgetReport?> = mutableLatestReport.asStateFlow()

    fun publish(report: ContextBudgetReport) {
        mutableLatestReport.value = report
    }
}
