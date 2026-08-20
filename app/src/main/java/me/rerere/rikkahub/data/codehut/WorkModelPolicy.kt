/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.codehut

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.WorkCapability
import me.rerere.rikkahub.data.datastore.WorkProtocol
import kotlin.uuid.Uuid

data class WorkModelCandidate(
    val providerId: Uuid,
    val modelId: Uuid,
    val providerName: String,
    val modelName: String,
    val protocol: WorkProtocol,
    val capability: WorkCapability,
)

/**
 * Classifies existing Daddy model settings for a *single* Harness executor.
 * No provider credential is copied into a candidate.
 */
object WorkModelPolicy {
    fun candidateFor(provider: ProviderSetting, model: Model): WorkModelCandidate? {
        if (!provider.enabled || model.type != ModelType.CHAT) return null

        val effectiveProvider = model.providerOverwrite ?: provider
        val protocol = protocolFor(effectiveProvider) ?: return null
        if (!hasConfiguredCredential(effectiveProvider)) return null

        return WorkModelCandidate(
            providerId = provider.id,
            modelId = model.id,
            providerName = provider.name,
            modelName = model.displayName.ifBlank { model.modelId },
            protocol = protocol,
            capability = if (ModelAbility.TOOL in model.abilities) {
                WorkCapability.NEEDS_PROBE
            } else {
                WorkCapability.CONSULT_ONLY
            },
        )
    }

    fun resolveExecutableCandidates(providers: List<ProviderSetting>): List<WorkModelCandidate> =
        providers.flatMap { provider ->
            provider.models.mapNotNull { model -> candidateFor(provider, model) }
        }.filter { it.capability != WorkCapability.CONSULT_ONLY }

    private fun protocolFor(provider: ProviderSetting): WorkProtocol? = when (provider) {
        is ProviderSetting.OpenAI -> if (provider.useResponseApi) {
            WorkProtocol.OPENAI_RESPONSES
        } else {
            WorkProtocol.OPENAI_CHAT_COMPLETIONS
        }

        is ProviderSetting.Claude -> WorkProtocol.ANTHROPIC_MESSAGES
        is ProviderSetting.Google -> null
    }

    private fun hasConfiguredCredential(provider: ProviderSetting): Boolean = when (provider) {
        is ProviderSetting.OpenAI -> provider.apiKey.isNotBlank()
        is ProviderSetting.Claude -> provider.apiKey.isNotBlank()
        is ProviderSetting.Google -> false
    }
}
