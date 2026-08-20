/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync.companion

import java.net.InetSocketAddress
import java.net.Proxy
import me.rerere.rikkahub.data.datastore.HarnessInstallStage
import me.rerere.rikkahub.data.datastore.HarnessStatus
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessManagerPolicyTest {
    @Test
    fun harnessHealthClientBypassesTheSystemProxyForTheLoopbackOnlyWorkspace() {
        val upstreamClient = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 8899)))
            .build()

        val healthClient = buildHarnessHealthClient(upstreamClient)

        assertEquals(Proxy.NO_PROXY, healthClient.proxy)
        assertEquals(2_000, healthClient.connectTimeoutMillis)
        assertEquals(2_000, healthClient.readTimeoutMillis)
        assertEquals(2_000, healthClient.callTimeoutMillis)
    }

    @Test
    fun statusRequiresBothProcessAndHttpHealth() {
        assertEquals(HarnessStatus.RUNNING, classifyHarness(true, true, true, ""))
        assertEquals(HarnessStatus.ERROR, classifyHarness(true, true, false, "web failed"))
        assertEquals(HarnessStatus.STOPPED, classifyHarness(true, false, false, ""))
        assertEquals(HarnessStatus.NOT_INSTALLED, classifyHarness(false, false, false, ""))
    }

    @Test
    fun linuxReadyRequiresMarkerReadyStageManagedProcessAndHttpHealth() {
        val ready = HarnessProbe(
            runtimeMarker = HarnessRuntimeContract.LINUX_RUNTIME_MARKER,
            installStage = HarnessInstallStage.READY,
            processRunning = true,
            version = HarnessRuntimeContract.HARNESS_VERSION,
        )

        assertEquals(HarnessStatus.RUNNING, classifyLinuxHarness(ready, httpHealthy = true))
        assertEquals(HarnessStatus.ERROR, classifyLinuxHarness(ready, httpHealthy = false))
        assertEquals(
            HarnessStatus.NOT_INSTALLED,
            classifyLinuxHarness(ready.copy(runtimeMarker = "android-native"), httpHealthy = true),
        )
    }

    @Test
    fun legacyRuntimeIsReportedButNeverTreatedAsInstalledLinux() {
        val probe = parseHarnessProbe("legacy=1\nstage=unknown\nruntime=android-native")

        assertTrue(probe.legacyRuntimeFound)
        assertEquals(HarnessStatus.NOT_INSTALLED, classifyLinuxHarness(probe, httpHealthy = false))
    }

    @Test
    fun wireStagesMapToStableProgressAndLifecycleStates() {
        assertEquals(5, harnessInstallStageProgress(HarnessInstallStage.PRECHECK))
        assertEquals(35, harnessInstallStageProgress(HarnessInstallStage.INSTALL_DEBIAN))
        assertEquals(75, harnessInstallStageProgress(HarnessInstallStage.INSTALL_HARNESS))
        assertEquals(100, harnessInstallStageProgress(HarnessInstallStage.READY))

        val installing = HarnessProbe(
            installStage = HarnessInstallStage.INSTALL_NODE,
            setupRunning = true,
        )
        assertEquals(HarnessStatus.INSTALLING, classifyLinuxHarness(installing, httpHealthy = false))
        assertEquals(
            HarnessStatus.REPAIRING,
            classifyLinuxHarness(installing.copy(setupRunning = false), httpHealthy = false),
        )
    }

    @Test
    fun failedStageKeepsSanitizedDetail() {
        val probe = parseHarnessProbe(
            "stage=failed\ndetail=npm failed Authorization: Bearer secret-value\nlegacy=0",
        )

        assertEquals(HarnessInstallStage.FAILED, probe.installStage)
        assertFalse(redactHarnessLog(probe.detail).contains("secret-value"))
        assertEquals(HarnessStatus.ERROR, classifyLinuxHarness(probe, httpHealthy = false))
    }

    @Test
    fun recoveryHonorsManualStop() {
        assertFalse(shouldRecover(autoKeepRunning = true, manuallyStopped = true, running = false))
        assertTrue(shouldRecover(autoKeepRunning = true, manuallyStopped = false, running = false))
        assertFalse(shouldRecover(autoKeepRunning = false, manuallyStopped = false, running = false))
        assertFalse(shouldRecover(autoKeepRunning = true, manuallyStopped = false, running = true))
    }

    @Test
    fun submittedBridgeCommandIsNeverReplayedThroughRunCommandFallback() {
        assertFalse(shouldFallbackToRunCommand(bridgeSubmitted = true))
        assertTrue(shouldFallbackToRunCommand(bridgeSubmitted = false))
    }

    @Test
    fun firstInstallAllowsAtLeastThirtyMinutesForLargeDependencyTrees() {
        assertTrue(harnessInstallWaitDurationMillis(pollIntervalMillis = 750L) >= 30 * 60 * 1_000L)
        assertTrue(harnessActionWaitDurationMillis(pollIntervalMillis = 750L) >= 60 * 1_000L)
    }

    @Test
    fun activeBackgroundInstallIsObservedInsteadOfSubmittedAgain() {
        assertFalse(shouldSubmitHarnessInstall(setupRunning = true))
        assertTrue(shouldSubmitHarnessInstall(setupRunning = false))
        assertFalse(shouldFallbackToRunCommand(bridgeSubmitted = true))
    }

    @Test
    fun logRedactionRemovesCommonSecrets() {
        val text = redactHarnessLog(
            "authorization: bearer abc\n" +
                "API_KEY=secret\n" +
                "Cookie: sid=x\n" +
                "apiKey: another-secret\n" +
                "token: private-token\n" +
                "{\"password\":\"quoted-password\",\"access_token\":\"json-token\"}",
        )

        assertFalse(text.contains("abc"))
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("sid=x"))
        assertFalse(text.contains("private-token"))
        assertFalse(text.contains("quoted-password"))
        assertFalse(text.contains("json-token"))
        assertEquals(7, Regex(Regex.escape("[REDACTED]")).findAll(text).count())
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
