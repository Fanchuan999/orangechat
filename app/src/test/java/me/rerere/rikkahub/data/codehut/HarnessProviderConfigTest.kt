/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.codehut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThat
import org.junit.Test
import org.hamcrest.CoreMatchers.containsString
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.datastore.WorkCapability
import me.rerere.rikkahub.data.datastore.WorkModelBinding
import me.rerere.rikkahub.data.datastore.WorkProtocol

class HarnessProviderConfigTest {
    @Test
    fun m3PatchUsesAnthropicAndLeaseNotProviderKey() {
        val binding = binding(WorkProtocol.ANTHROPIC_MESSAGES)
        val lease = lease(binding)

        val patch = HarnessProviderConfigFactory.create(binding, lease)

        assertThat(patch.yaml, containsString("api: anthropic-messages"))
        assertThat(patch.yaml, containsString("apiKeyEnv: DADDY_CODE_HUT_LEASE"))
        assertThat(patch.yaml, containsString("baseURL: ${lease.baseUrl}"))
        assertThat(patch.yaml, containsString("id: ${binding.modelId}"))
        assertFalse(patch.yaml.contains("saved-go-key"))
        assertEquals(lease.token, patch.environment["DADDY_CODE_HUT_LEASE"])
        assertEquals(lease.baseUrl, patch.environment["DADDY_CODE_HUT_BASE_URL"])
    }

    @Test
    fun deepSeekPatchHasThinkingCompatibility() {
        val binding = binding(WorkProtocol.OPENAI_CHAT_COMPLETIONS)

        val patch = HarnessProviderConfigFactory.create(binding, lease(binding))

        assertThat(patch.yaml, containsString("supportsDeveloperRole: false"))
        assertThat(patch.yaml, containsString("maxTokensField: max_tokens"))
    }

    @Test
    fun patchIsStableForRepeatedApplyAndContainsNoCredentialMaterial() {
        val binding = binding(WorkProtocol.OPENAI_RESPONSES)
        val lease = lease(binding)

        val first = HarnessProviderConfigFactory.create(binding, lease)
        val second = HarnessProviderConfigFactory.create(binding, lease)

        assertEquals(first, second)
        assertFalse(first.yaml.contains("apiKey:"))
        assertFalse(first.yaml.contains("Authorization"))
        assertNotEquals("saved-go-key", first.environment["DADDY_CODE_HUT_LEASE"])
        assertTrue(first.yaml.contains("api: openai-responses"))
    }

    private fun binding(protocol: WorkProtocol) = WorkModelBinding(
        providerId = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
        modelId = Uuid.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
        protocol = protocol,
        capability = WorkCapability.EXECUTABLE,
    )

    private fun lease(binding: WorkModelBinding) = BridgeLease(
        bindingId = binding.id,
        token = "short-lived-lease-token",
        baseUrl = "http://127.0.0.1:49152/providers/${binding.id}",
        expiresAtMillis = 123L,
    )
}
