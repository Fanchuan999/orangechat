/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.harness

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessUiTextTest {
    @Test
    fun operationFailuresRedactCredentialsBeforeHarnessSettingsDisplay() {
        val raw = "Termux failed: Authorization: Bearer sk-live-secret token=token-secret " +
            "x".repeat(2_500)

        val displayed = redactHarnessUiNotice(raw)

        assertFalse(displayed.contains("sk-live-secret"))
        assertFalse(displayed.contains("token-secret"))
        assertTrue(displayed.length <= 2_000)
    }
}
