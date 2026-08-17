/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.harness

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessUrlPolicyTest {
    @Test
    fun onlyExactHarnessLoopbackOriginStaysInternal() {
        assertTrue(HarnessUrlPolicy.isInternal("http://127.0.0.1:3080/"))
        assertTrue(HarnessUrlPolicy.isInternal("http://127.0.0.1:3080/session/1"))
        assertFalse(HarnessUrlPolicy.isInternal("http://localhost:3080/"))
        assertFalse(HarnessUrlPolicy.isInternal("http://127.0.0.1:8000/"))
        assertFalse(HarnessUrlPolicy.isInternal("https://example.com/"))
        assertFalse(HarnessUrlPolicy.isInternal("javascript:alert(1)"))
        assertFalse(HarnessUrlPolicy.isInternal("http://127.0.0.1:3080.evil.example/"))
    }

    @Test
    fun onlyOrdinaryHttpLinksMayLeaveTheWorkspace() {
        assertTrue(HarnessUrlPolicy.isExternalHttp("https://example.com/docs"))
        assertTrue(HarnessUrlPolicy.isExternalHttp("http://example.com/"))
        assertFalse(HarnessUrlPolicy.isExternalHttp("javascript:alert(1)"))
        assertFalse(HarnessUrlPolicy.isExternalHttp("file:///sdcard/secret"))
        assertFalse(HarnessUrlPolicy.isExternalHttp("content://private/item"))
    }
}
