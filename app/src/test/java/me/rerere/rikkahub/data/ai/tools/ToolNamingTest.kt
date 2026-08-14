/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolNamingTest {
    @Test
    fun `deduplicate tool names keeps first tool and original order`() {
        val first = tool(name = "search_web", description = "first source")
        val duplicate = tool(name = "search_web", description = "later source")
        val last = tool(name = "read_webpage", description = "last source")

        val result = ToolNaming.deduplicateToolNames(listOf(first, duplicate, last))

        assertEquals(listOf("search_web", "read_webpage"), result.map { it.name })
        assertEquals("first source", result.first().description)
    }

    private fun tool(name: String, description: String): Tool = Tool(
        name = name,
        description = description,
        execute = { emptyList() },
    )
}
