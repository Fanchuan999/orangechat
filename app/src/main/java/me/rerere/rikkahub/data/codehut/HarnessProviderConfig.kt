/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.codehut

import me.rerere.rikkahub.data.datastore.WorkModelBinding
import me.rerere.rikkahub.data.datastore.WorkProtocol

data class HarnessProviderPatch(
    val yaml: String,
    val environment: Map<String, String>,
)

/**
 * Builds the one managed provider route used by Code Hut.
 *
 * The YAML is a Harness configuration overlay. It contains only references to
 * the loopback bridge and the short-lived environment variable name; the
 * provider credential is intentionally absent.
 */
object HarnessProviderConfigFactory {
    const val PROVIDER_ID = "daddy-code-hut"
    const val LEASE_ENVIRONMENT = "DADDY_CODE_HUT_LEASE"
    const val BASE_URL_ENVIRONMENT = "DADDY_CODE_HUT_BASE_URL"

    fun create(binding: WorkModelBinding, lease: BridgeLease): HarnessProviderPatch {
        require(binding.id == lease.bindingId) { "代码小屋工作模型与凭据租约不匹配。" }
        require(lease.token.isNotBlank()) { "代码小屋凭据租约不能为空。" }
        require(lease.baseUrl.startsWith("http://127.0.0.1:")) {
            "代码小屋凭据桥必须使用本机回环地址。"
        }

        val protocol = when (binding.protocol) {
            WorkProtocol.OPENAI_CHAT_COMPLETIONS -> "openai-completions"
            WorkProtocol.ANTHROPIC_MESSAGES -> "anthropic-messages"
            WorkProtocol.OPENAI_RESPONSES -> "openai-responses"
        }
        val yaml = buildString {
            appendLine("- id: llm")
            appendLine("  name: '@deepseek-ai/dsh-llm-pi-ai'")
            appendLine("  config:")
            appendLine("    providers:")
            appendLine("      $PROVIDER_ID:")
            appendLine("        displayName: Daddy Code Hut")
            appendLine("        apiKeyEnv: $LEASE_ENVIRONMENT")
            appendLine("        api: $protocol")
            appendLine("        baseURL: ${lease.baseUrl}")
            if (binding.protocol == WorkProtocol.OPENAI_CHAT_COMPLETIONS) {
                appendLine("        compat:")
                appendLine("          supportsDeveloperRole: false")
                appendLine("          maxTokensField: max_tokens")
                appendLine("          thinkingFormat: openai")
            }
            appendLine("        models:")
            appendLine("          - id: ${binding.modelId}")
            appendLine("            input: [text]")
        }

        return HarnessProviderPatch(
            yaml = yaml,
            environment = linkedMapOf(
                LEASE_ENVIRONMENT to lease.token,
                BASE_URL_ENVIRONMENT to lease.baseUrl,
            ),
        )
    }
}
