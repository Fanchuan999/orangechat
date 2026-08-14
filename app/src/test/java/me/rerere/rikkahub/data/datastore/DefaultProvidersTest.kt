/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProvidersTest {
    @Test
    fun `default providers should include MiMo with expected chat models`() {
        val provider = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .single { it.name == "MiMo" }

        assertTrue(provider.builtIn)
        assertTrue(provider.enabled)
        assertEquals("https://api.xiaomimimo.com/v1", provider.baseUrl)
        assertEquals("/chat/completions", provider.chatCompletionsPath)
        assertEquals(
            listOf("mimo-v2.5", "mimo-v2.5-pro"),
            provider.models.map { it.modelId }
        )
    }

    @Test
    fun `default providers should include vercel ai gateway with expected balance config`() {
        val vercelProviders = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .filter { it.name == "Vercel AI Gateway" }

        assertEquals(1, vercelProviders.size)

        val provider = vercelProviders.single()
        assertEquals("https://ai-gateway.vercel.sh/v1", provider.baseUrl)
        assertFalse(provider.enabled)
        assertTrue(provider.builtIn)
        assertTrue(provider.balanceOption.enabled)
        assertEquals("/credits", provider.balanceOption.apiPath)
        assertEquals("balance", provider.balanceOption.resultPath)
    }
}
