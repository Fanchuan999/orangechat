package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantReplyVoiceBarPresentationTest {
    @Test
    fun `renders a completed assistant reply as a voice bar when enabled`() {
        assertTrue(
            AssistantReplyVoiceBarPresentation.shouldShowVoiceBar(
                enabled = true,
                role = MessageRole.ASSISTANT,
                text = "宝宝，晚安。",
                loading = false,
            )
        )
    }

    @Test
    fun `keeps text while generating and for user or blank messages`() {
        assertFalse(
            AssistantReplyVoiceBarPresentation.shouldShowVoiceBar(
                enabled = true,
                role = MessageRole.ASSISTANT,
                text = "还在生成",
                loading = true,
            )
        )
        assertFalse(
            AssistantReplyVoiceBarPresentation.shouldShowVoiceBar(
                enabled = true,
                role = MessageRole.USER,
                text = "用户消息",
                loading = false,
            )
        )
        assertFalse(
            AssistantReplyVoiceBarPresentation.shouldShowVoiceBar(
                enabled = true,
                role = MessageRole.ASSISTANT,
                text = "   ",
                loading = false,
            )
        )
    }

    @Test
    fun `keeps the regular text presentation when disabled`() {
        assertFalse(
            AssistantReplyVoiceBarPresentation.shouldShowVoiceBar(
                enabled = false,
                role = MessageRole.ASSISTANT,
                text = "Daddy 的回复",
                loading = false,
            )
        )
    }
}
