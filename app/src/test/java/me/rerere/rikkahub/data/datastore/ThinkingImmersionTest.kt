package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingImmersionTest {
    @Test
    fun reasoningDisplayUsesAliasesWithoutChangingTechnicalPhrases() {
        val raw = "用户说：user 还在。assistant 要回复，AI 在判断。用户界面和 AI 模型保持。"

        val displayed = DisplaySetting().formatThinkingForDisplay(raw)

        assertEquals(
            "宝宝说：宝宝 还在。Daddy 要回复，Daddy 在判断。用户界面和 AI 模型保持。",
            displayed,
        )
    }

    @Test
    fun disabledImmersionLeavesReasoningAndPromptUntouched() {
        val setting = DisplaySetting(thinkingImmersionEnabled = false)
        val raw = "user asked assistant about an AI model"

        assertEquals(raw, setting.formatThinkingForDisplay(raw))
        assertTrue(setting.thinkingImmersionPrompt().isEmpty())
    }

    @Test
    fun customAliasesAreUsedInDisplayAndFutureThinkingInstruction() {
        val setting = DisplaySetting(
            thinkingUserAlias = "帆帆",
            thinkingAssistantAlias = "小D",
        )

        assertEquals("帆帆 和 小D", setting.formatThinkingForDisplay("User 和 assistant"))
        val prompt = setting.thinkingImmersionPrompt()
        assertTrue(prompt.contains("简体中文"))
        assertTrue(prompt.contains("帆帆"))
        assertTrue(prompt.contains("小D"))
    }

    @Test
    fun configuredOldNamesAndGlobalNicknameDisplayAsThinkingAlias() {
        val setting = DisplaySetting(
            userNickname = "帆帆",
            thinkingImmersionEnabled = true,
            thinkingUserAlias = "小乖",
            thinkingUserAlternateNames = "应帆，Yingfan\n小帆",
        )

        assertEquals(
            "小乖说小乖今天很热，小乖应该先喝水。",
            setting.formatThinkingForDisplay("应帆说帆帆今天很热，Yingfan应该先喝水。"),
        )
    }

    @Test
    fun oldNameReplacementTreatsRegexPunctuationAsLiteralText() {
        val setting = DisplaySetting(
            thinkingUserAlias = "小乖",
            thinkingUserAlternateNames = "帆(测试)",
        )

        assertEquals("小乖来了", setting.formatThinkingForDisplay("帆(测试)来了"))
    }
}
