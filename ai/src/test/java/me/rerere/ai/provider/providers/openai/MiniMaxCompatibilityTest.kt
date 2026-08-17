/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class MiniMaxCompatibilityTest {
    private val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())

    private fun buildRequest(
        baseUrl: String,
        model: Model,
        reasoningLevel: ReasoningLevel,
        temperature: Float? = 0.8f,
        topP: Float? = 0.9f,
        maxTokens: Int? = 4096,
    ): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        return method.invoke(
            api,
            listOf(UIMessage.user("你好")),
            TextGenerationParams(
                model = model,
                temperature = temperature,
                topP = topP,
                maxTokens = maxTokens,
                reasoningLevel = reasoningLevel,
            ),
            ProviderSetting.OpenAI(baseUrl = baseUrl),
            true,
        ) as JsonObject
    }

    private fun parseMessage(input: JsonObject): UIMessage {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "parseMessage",
            JsonObject::class.java,
        ).apply { isAccessible = true }
        return method.invoke(api, input) as UIMessage
    }

    @Test
    fun `MiniMax request asks API to split reasoning from content`() {
        val body = buildRequest(
            baseUrl = "https://api.minimaxi.com/v1",
            model = Model(
                modelId = "MiniMax-M2.7",
                abilities = listOf(ModelAbility.REASONING),
            ),
            reasoningLevel = ReasoningLevel.HIGH,
        )

        assertEquals(true, body["reasoning_split"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `MiniMax reasoning_details becomes reasoning while content stays reply`() {
        val message = parseMessage(
            buildJsonObject {
                put("role", "assistant")
                put("content", "还有多远？买瓶冰水降降温。")
                putJsonArray("reasoning_details") {
                    add(
                        buildJsonObject {
                            put("type", "reasoning.text")
                            put("text", "小乖还在走路，我应该简短关心。")
                        },
                    )
                }
            },
        )

        assertEquals(
            "小乖还在走路，我应该简短关心。",
            message.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning,
        )
        assertEquals(
            "还有多远？买瓶冰水降降温。",
            message.parts.filterIsInstance<UIMessagePart.Text>().single().text,
        )
    }

    private fun delta(reasoning: String, content: String): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("content", content)
        putJsonArray("reasoning_details") {
            add(
                buildJsonObject {
                    put("type", "reasoning.text")
                    put("text", reasoning)
                },
            )
        }
    }

    @Test
    fun `MiniMax cumulative reasoning and content emit only new suffix`() {
        val normalizer = MiniMaxStreamDeltaNormalizer()

        val first = normalizer.normalize(delta(reasoning = "先判断", content = "还"))
        val second = normalizer.normalize(delta(reasoning = "先判断，再回应", content = "还有多远？"))

        assertEquals("先判断", extractReasoningText(first))
        assertEquals("，再回应", extractReasoningText(second))
        assertEquals("还", first["content"]?.jsonPrimitive?.content)
        assertEquals("有多远？", second["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `MiniMax incremental chunks remain incremental`() {
        val normalizer = MiniMaxStreamDeltaNormalizer()

        normalizer.normalize(delta(reasoning = "先判断", content = "还"))
        val next = normalizer.normalize(delta(reasoning = "再回应", content = "有多远？"))

        assertEquals("再回应", extractReasoningText(next))
        assertEquals("有多远？", next["content"]?.jsonPrimitive?.content)
    }
}
