/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MiMoCompatibilityTest {
    private val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())

    private fun buildMiMoRequest(reasoningLevel: ReasoningLevel): JsonObject {
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
                model = Model(
                    modelId = "mimo-v2.5",
                    abilities = listOf(ModelAbility.REASONING),
                ),
                temperature = 0.8f,
                topP = 0.9f,
                maxTokens = 4096,
                reasoningLevel = reasoningLevel,
            ),
            ProviderSetting.OpenAI(baseUrl = "https://api.xiaomimimo.com/v1"),
            true,
        ) as JsonObject
    }

    @Test
    fun `MiMo xhigh maps to enabled thinking without reasoning effort`() {
        val body = buildMiMoRequest(reasoningLevel = ReasoningLevel.XHIGH)

        assertEquals("enabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertFalse(body.containsKey("reasoning_effort"))
        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("top_p"))
        assertEquals(4096, body["max_completion_tokens"]?.jsonPrimitive?.int)
        assertFalse(body.containsKey("max_tokens"))
    }

    @Test
    fun `MiMo off maps to disabled thinking and keeps sampling parameters`() {
        val body = buildMiMoRequest(reasoningLevel = ReasoningLevel.OFF)

        assertEquals("disabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals(0.8f, body["temperature"]?.jsonPrimitive?.float)
        assertEquals(0.9f, body["top_p"]?.jsonPrimitive?.float)
    }

    @Test
    fun `SSE wrapped MiMo error exposes server message`() {
        val payload = parseOpenAIErrorPayload(
            "data:{\"error\":{\"code\":\"400\",\"message\":\"Invalid request parameters\",\"type\":\"Bad Request\"}}\n\n",
        )

        val error = payload.jsonObject["error"]!!.jsonObject
        assertEquals("400", error["code"]?.jsonPrimitive?.content)
        assertEquals("Invalid request parameters", error["message"]?.jsonPrimitive?.content)
    }
}
