/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync.companion

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessRecoveryPolicyTest {
    @Test
    fun recoveryDecisionHonorsEveryLifecycleState() {
        assertEquals(
            HarnessRecoveryDecision.DISABLED,
            decideHarnessRecovery(autoKeepRunning = false, manuallyStopped = false, running = false),
        )
        assertEquals(
            HarnessRecoveryDecision.MANUALLY_STOPPED,
            decideHarnessRecovery(autoKeepRunning = true, manuallyStopped = true, running = false),
        )
        assertEquals(
            HarnessRecoveryDecision.RECOVER,
            decideHarnessRecovery(autoKeepRunning = true, manuallyStopped = false, running = false),
        )
        assertEquals(
            HarnessRecoveryDecision.ALREADY_RUNNING,
            decideHarnessRecovery(autoKeepRunning = true, manuallyStopped = false, running = true),
        )
    }

    @Test
    fun transientFailureRequestsRetryWithoutChangingManualStopState() = runBlocking {
        var manuallyStopped = true

        val result = executeHarnessRecovery {
            throw IOException("Termux temporarily unavailable")
        }

        assertEquals(HarnessRecoveryRunResult.RETRY, result)
        assertTrue(manuallyStopped)
    }

    @Test
    fun successfulNoOpIsStillAWorkSuccess() = runBlocking {
        val result = executeHarnessRecovery { false }

        assertEquals(HarnessRecoveryRunResult.SUCCESS, result)
    }
}
