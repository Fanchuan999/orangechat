package me.rerere.rikkahub.data.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousIdlePromptTest {
    @Test
    fun `idle prompt describes only activity families present on the authorized tool surface`() {
        val allFamilies = buildAutonomousIdleExploreInstructions(
            AutonomousActivityToolAvailability(
                hasWebTools = true,
                hasForumTools = true,
                hasVisitorLoungeTools = true,
            ),
        )
        val webOnly = buildAutonomousIdleExploreInstructions(
            AutonomousActivityToolAvailability(
                hasWebTools = true,
                hasForumTools = false,
                hasVisitorLoungeTools = false,
            ),
        )

        assertTrue(allFamilies.contains("公开网页"))
        assertTrue(allFamilies.contains("论坛"))
        assertTrue(allFamilies.contains("发帖、评论、点赞或登录"))
        assertTrue(allFamilies.contains("会客室"))
        assertTrue(webOnly.contains("公开网页"))
        assertFalse(webOnly.contains("发帖、评论、点赞或登录"))
        assertFalse(webOnly.contains("会客室拜访"))
    }

    @Test
    fun `idle prompt allows pass but forbids mixing families credentials and invented success`() {
        val text = buildAutonomousIdleExploreInstructions(
            AutonomousActivityToolAvailability(
                hasWebTools = true,
                hasForumTools = true,
                hasVisitorLoungeTools = true,
            ),
        )

        assertTrue(text.contains("只能选择一种"))
        assertTrue(text.contains("[PASS]"))
        assertTrue(text.contains("不得索要、读取、输出或复述密码、Cookie、OAuth token、Visitor Key"))
        assertTrue(text.contains("不得把失败、排队或拒绝说成成功"))
    }
}
