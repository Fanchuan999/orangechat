/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.codehut

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.WorkCapability
import me.rerere.rikkahub.data.datastore.WorkProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkModelPolicyTest {
    @Test
    fun minimaxM3ThroughOpenCodeGoUsesAnthropicMessages() {
        val provider = ProviderSetting.Claude(
            name = "OpenCode Go",
            apiKey = "saved-go-key",
        )
        val model = Model(modelId = "minimax-m3", abilities = listOf(ModelAbility.TOOL))

        val candidate = WorkModelPolicy.candidateFor(provider, model)

        assertEquals(WorkProtocol.ANTHROPIC_MESSAGES, candidate?.protocol)
        assertEquals(WorkCapability.NEEDS_PROBE, candidate?.capability)
    }

    @Test
    fun openAiCompatibleDeepSeekUsesChatCompletions() {
        val provider = ProviderSetting.OpenAI(apiKey = "saved-deepseek-key")
        val model = Model(modelId = "DeepSeek-V4-Flash", abilities = listOf(ModelAbility.TOOL))

        assertEquals(
            WorkProtocol.OPENAI_CHAT_COMPLETIONS,
            WorkModelPolicy.candidateFor(provider, model)?.protocol,
        )
    }

    @Test
    fun responsesConfiguredOpenAiProviderUsesResponsesProtocol() {
        val provider = ProviderSetting.OpenAI(apiKey = "saved-gpt-key", useResponseApi = true)
        val model = Model(modelId = "gpt-5.6-luna", abilities = listOf(ModelAbility.TOOL))

        assertEquals(
            WorkProtocol.OPENAI_RESPONSES,
            WorkModelPolicy.candidateFor(provider, model)?.protocol,
        )
    }

    @Test
    fun chatOnlyModelsRemainVisibleButAreMarkedConsultOnly() {
        val provider = ProviderSetting.OpenAI(apiKey = "saved-key")
        val model = Model(modelId = "chat-only")

        assertEquals(
            WorkCapability.CONSULT_ONLY,
            WorkModelPolicy.candidateFor(provider, model)?.capability,
        )
    }

    @Test
    fun disabledOrUnconfiguredProviderCannotBecomeWorkCandidate() {
        val disabled = ProviderSetting.OpenAI(enabled = false, apiKey = "saved-key")
        val blankKey = ProviderSetting.OpenAI(apiKey = "")
        val model = Model(modelId = "DeepSeek-V4-Pro", abilities = listOf(ModelAbility.TOOL))

        assertNull(WorkModelPolicy.candidateFor(disabled, model))
        assertNull(WorkModelPolicy.candidateFor(blankKey, model))
    }
}
