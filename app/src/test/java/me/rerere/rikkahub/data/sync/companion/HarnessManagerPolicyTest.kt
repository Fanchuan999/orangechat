/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync.companion

import me.rerere.rikkahub.data.datastore.HarnessStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessManagerPolicyTest {
    @Test
    fun statusRequiresBothProcessAndHttpHealth() {
        assertEquals(HarnessStatus.RUNNING, classifyHarness(true, true, true, ""))
        assertEquals(HarnessStatus.ERROR, classifyHarness(true, true, false, "web failed"))
        assertEquals(HarnessStatus.STOPPED, classifyHarness(true, false, false, ""))
        assertEquals(HarnessStatus.NOT_INSTALLED, classifyHarness(false, false, false, ""))
    }

    @Test
    fun recoveryHonorsManualStop() {
        assertFalse(shouldRecover(autoKeepRunning = true, manuallyStopped = true, running = false))
        assertTrue(shouldRecover(autoKeepRunning = true, manuallyStopped = false, running = false))
        assertFalse(shouldRecover(autoKeepRunning = false, manuallyStopped = false, running = false))
        assertFalse(shouldRecover(autoKeepRunning = true, manuallyStopped = false, running = true))
    }

    @Test
    fun logRedactionRemovesCommonSecrets() {
        val text = redactHarnessLog(
            "Authorization: Bearer abc\n" +
                "API_KEY=secret\n" +
                "Cookie: sid=x\n" +
                "apiKey: another-secret\n" +
                "token: private-token",
        )

        assertFalse(text.contains("abc"))
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("sid=x"))
        assertFalse(text.contains("private-token"))
        assertEquals(5, text.lineSequence().count { it.contains("[REDACTED]") })
    }

    @Test
    fun logTailIsBoundedByLinesAndBytes() {
        val text = (1..260).joinToString("\n") { index -> "$index:${"x".repeat(200)}" }
        val bounded = boundedHarnessLog(text)

        assertTrue(bounded.toByteArray().size <= 24 * 1024)
        assertTrue(bounded.lineSequence().count() <= 200)
        assertTrue(bounded.contains("260:"))
        assertFalse(bounded.lineSequence().any { it.startsWith("1:") })
    }
}
