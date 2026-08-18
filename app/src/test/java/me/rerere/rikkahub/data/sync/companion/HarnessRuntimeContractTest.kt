/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync.companion

import me.rerere.rikkahub.data.datastore.HarnessInstallStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessRuntimeContractTest {
    @Test
    fun linuxRuntimeVersionsArePinnedAndNeverUseLatest() {
        assertEquals("daddy-linux", HarnessRuntimeContract.CONTAINER_NAME)
        assertEquals("debian:bookworm", HarnessRuntimeContract.DEBIAN_IMAGE)
        assertEquals("aarch64", HarnessRuntimeContract.ARCHITECTURE)
        assertEquals("24.19.0", HarnessRuntimeContract.NODE_VERSION)
        assertEquals("0.1.0-rc.7", HarnessRuntimeContract.HARNESS_VERSION)

        val pins = listOf(
            HarnessRuntimeContract.DEBIAN_IMAGE,
            HarnessRuntimeContract.NODE_VERSION,
            HarnessRuntimeContract.HARNESS_VERSION,
        ).joinToString(" ")
        assertFalse(pins.contains("latest", ignoreCase = true))
    }

    @Test
    fun everyKnownWireStageParsesInInstallationOrder() {
        val stages = listOf(
            "precheck" to HarnessInstallStage.PRECHECK,
            "install_proot" to HarnessInstallStage.INSTALL_PROOT,
            "install_debian" to HarnessInstallStage.INSTALL_DEBIAN,
            "install_node" to HarnessInstallStage.INSTALL_NODE,
            "install_harness" to HarnessInstallStage.INSTALL_HARNESS,
            "write_scripts" to HarnessInstallStage.WRITE_SCRIPTS,
            "start_and_healthcheck" to HarnessInstallStage.START_AND_HEALTHCHECK,
            "ready" to HarnessInstallStage.READY,
            "failed" to HarnessInstallStage.FAILED,
        )

        assertEquals(stages.map { it.second }, stages.map { parseHarnessInstallStage(it.first) })
    }

    @Test
    fun missingOrFutureWireStagesDegradeToUnknown() {
        assertEquals(HarnessInstallStage.UNKNOWN, parseHarnessInstallStage(""))
        assertEquals(HarnessInstallStage.UNKNOWN, parseHarnessInstallStage("future_stage"))
    }

    @Test
    fun linuxReadyMarkerCannotBeConfusedWithLegacyAndroidRuntime() {
        assertTrue(isLinuxHarnessRuntime(HarnessRuntimeContract.LINUX_RUNTIME_MARKER))
        assertFalse(isLinuxHarnessRuntime("android-native"))
        assertFalse(isLinuxHarnessRuntime(""))
    }
}
