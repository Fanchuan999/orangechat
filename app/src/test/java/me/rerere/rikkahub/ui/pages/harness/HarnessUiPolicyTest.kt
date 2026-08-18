/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.harness

import me.rerere.rikkahub.data.datastore.HarnessInstallStage
import me.rerere.rikkahub.data.datastore.HarnessSnapshot
import me.rerere.rikkahub.data.datastore.HarnessStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessUiPolicyTest {
    @Test
    fun everyStatusHasChineseCopyAndSeverity() {
        HarnessStatus.entries.forEach { status ->
            val presentation = harnessStatusPresentation(HarnessSnapshot(status = status))
            assertTrue(status.name, presentation.label.any { it.code > 127 })
            assertTrue(status.name, presentation.explanation.isNotBlank())
        }
        assertEquals(
            HarnessUiSeverity.SUCCESS,
            harnessStatusPresentation(HarnessSnapshot(status = HarnessStatus.RUNNING)).severity,
        )
        assertEquals(
            HarnessUiSeverity.ERROR,
            harnessStatusPresentation(HarnessSnapshot(status = HarnessStatus.ERROR)).severity,
        )
    }

    @Test
    fun installationPhasesStayInApprovedOrder() {
        assertEquals(
            listOf("检查环境", "安装 Debian", "安装 Node", "安装 Harness", "启动并检查"),
            harnessInstallPhases.map { it.label },
        )
        assertEquals(HarnessInstallPhase.PRECHECK, harnessInstallPhase(HarnessInstallStage.INSTALL_PROOT))
        assertEquals(HarnessInstallPhase.HARNESS, harnessInstallPhase(HarnessInstallStage.WRITE_SCRIPTS))
        assertEquals(HarnessInstallPhase.START, harnessInstallPhase(HarnessInstallStage.READY))
    }

    @Test
    fun progressNeverDecreasesDuringOneAttemptAndReadyIsComplete() {
        assertEquals(
            55,
            nextHarnessInstallProgress(
                previous = 55,
                snapshot = HarnessSnapshot(installProgressPercent = 35),
                installationAttemptActive = true,
            ),
        )
        assertEquals(
            100,
            nextHarnessInstallProgress(
                previous = 55,
                snapshot = HarnessSnapshot(
                    installStage = HarnessInstallStage.READY,
                    installProgressPercent = 95,
                ),
                installationAttemptActive = true,
            ),
        )
        assertEquals(
            5,
            nextHarnessInstallProgress(
                previous = 55,
                snapshot = HarnessSnapshot(installProgressPercent = 5),
                installationAttemptActive = false,
            ),
        )
        assertEquals(
            55,
            nextHarnessInstallProgress(
                previous = 0,
                snapshot = HarnessSnapshot(
                    detail = "install_node exited with code 1",
                    installStage = HarnessInstallStage.FAILED,
                ),
                installationAttemptActive = false,
            ),
        )
    }

    @Test
    fun workbenchOnlyOpensWhenRuntimeIsRunning() {
        HarnessStatus.entries.forEach { status ->
            assertEquals(status.name, status == HarnessStatus.RUNNING, canOpenHarnessWorkspace(status))
        }
    }

    @Test
    fun legacyRuntimeRequiresMigration() {
        val presentation = harnessStatusPresentation(
            HarnessSnapshot(
                status = HarnessStatus.NOT_INSTALLED,
                legacyRuntimeFound = true,
            )
        )

        assertEquals("发现旧运行时，需要迁移", presentation.label)
        assertEquals(HarnessUiSeverity.WARNING, presentation.severity)
        assertFalse(canOpenHarnessWorkspace(HarnessStatus.NOT_INSTALLED))
    }

    @Test
    fun manualStopAndBackoffHaveDifferentExplanations() {
        val manual = harnessStatusPresentation(HarnessSnapshot(status = HarnessStatus.MANUALLY_STOPPED))
        val backoff = harnessStatusPresentation(HarnessSnapshot(status = HarnessStatus.BACKING_OFF))

        assertTrue(manual.explanation.contains("不会自动复活"))
        assertTrue(backoff.explanation.contains("逐步延长"))
        assertTrue(manual.explanation != backoff.explanation)
    }

    @Test
    fun failedStageIsRecoveredFromTheInstallerDetail() {
        assertEquals(
            HarnessInstallPhase.NODE,
            failedHarnessInstallPhase("install_node exited with code 1"),
        )
        assertEquals(
            HarnessInstallPhase.START,
            failedHarnessInstallPhase("start_and_healthcheck failed"),
        )
    }
}
