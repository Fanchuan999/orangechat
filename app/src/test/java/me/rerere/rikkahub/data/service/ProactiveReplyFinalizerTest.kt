/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

class ProactiveReplyFinalizerTest {
    @Test
    fun `proactive finalization separates reasoning and exposes only reply text`() = runBlocking {
        val raw = UIMessage.assistant("<think>不同的内部思考</think>还有多远？")
        val finished = finalizeProactiveReply(
            message = raw,
        ) { messages ->
            assertEquals(raw, messages.single())
            listOf(
                raw.copy(
                    parts = listOf(
                        UIMessagePart.Reasoning("不同的内部思考"),
                        UIMessagePart.Text("还有多远？"),
                    ),
                ),
            )
        }

        assertEquals(
            "不同的内部思考",
            finished.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning,
        )
        assertEquals("还有多远？", proactiveVisibleReplyText(finished))
    }

    @Test
    fun `different reasoning with same final text shares one proactive fingerprint`() {
        fun message(reasoning: String, text: String) = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning),
                UIMessagePart.Text(text),
            ),
        )

        val first = message(reasoning = "思考甲", text = "买瓶冰水降降温。")
        val second = message(reasoning = "思考乙", text = "买瓶冰水降降温。")

        assertEquals(
            proactiveReplyFingerprint(proactiveVisibleReplyText(first)),
            proactiveReplyFingerprint(proactiveVisibleReplyText(second)),
        )
    }
}
