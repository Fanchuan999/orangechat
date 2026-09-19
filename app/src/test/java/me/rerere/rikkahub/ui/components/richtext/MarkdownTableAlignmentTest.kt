package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTableAlignmentTest {
    @Test
    fun `delimiter markers map each column to its requested alignment`() {
        val alignments = parseMarkdownTableAlignments(
            listOf(":---", ":---:", "---:", "---"),
        )

        assertEquals(
            listOf(
                MarkdownTableAlignment.LEFT,
                MarkdownTableAlignment.CENTER,
                MarkdownTableAlignment.RIGHT,
                MarkdownTableAlignment.LEFT,
            ),
            alignments,
        )
    }

    @Test
    fun `blank or malformed delimiter remains left aligned`() {
        val alignments = parseMarkdownTableAlignments(listOf("", "-:-", "---"))

        assertEquals(
            listOf(
                MarkdownTableAlignment.LEFT,
                MarkdownTableAlignment.LEFT,
                MarkdownTableAlignment.LEFT,
            ),
            alignments,
        )
    }
}
