package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRenderSnapshotTest {
    @Test
    fun `complex mixed content keeps its input and HTML route in one snapshot`() {
        val content = """
            ## 今天的安排

            1. 写报告
            2. 复习

            <details><summary>展开</summary><p>补充内容</p></details>

            | 项目 | 状态 |
            | --- | --- |
            | 报告 | 进行中 |
        """.trimIndent()

        val snapshot = buildMarkdownRenderSnapshot(content)

        assertEquals(content, snapshot.input)
        assertTrue(snapshot is MarkdownRenderSnapshot.Html)
        assertTrue((snapshot as MarkdownRenderSnapshot.Html).html.contains("<table>"))
    }

    @Test
    fun `repeated complex snapshots are identical`() {
        val content = """
            ### 清单

            - [ ] 读文献
            - [x] 整理笔记

            <blockquote>别着急</blockquote>
        """.trimIndent()

        val snapshots = List(10) { buildMarkdownRenderSnapshot(content) }
        val first = snapshots.first() as MarkdownRenderSnapshot.Html

        snapshots.forEach { snapshot ->
            assertTrue(snapshot is MarkdownRenderSnapshot.Html)
            assertEquals(first.html, (snapshot as MarkdownRenderSnapshot.Html).html)
        }
    }
}
