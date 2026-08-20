/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessWebViewportPolicyTest {
    @Test
    fun embeddedWorkbenchKeepsTheResponsiveMobileLayoutWithoutOverviewScaling() {
        assertTrue(harnessWebViewport.useWideViewPort)
        assertFalse(harnessWebViewport.loadWithOverviewMode)
        assertEquals(100, harnessWebViewport.textZoomPercent)
    }
}
