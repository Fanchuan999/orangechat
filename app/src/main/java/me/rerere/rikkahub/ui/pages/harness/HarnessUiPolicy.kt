/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.harness

import me.rerere.rikkahub.data.datastore.HarnessInstallStage
import me.rerere.rikkahub.data.datastore.HarnessSnapshot
import me.rerere.rikkahub.data.datastore.HarnessStatus

internal enum class HarnessUiSeverity {
    NORMAL,
    SUCCESS,
    WARNING,
    ERROR,
}

internal data class HarnessStatusPresentation(
    val label: String,
    val explanation: String,
    val severity: HarnessUiSeverity,
)

internal enum class HarnessInstallPhase(val label: String) {
    PRECHECK("检查环境"),
    DEBIAN("安装 Debian"),
    NODE("安装 Node"),
    HARNESS("安装 Harness"),
    START("启动并检查"),
}

internal val harnessInstallPhases: List<HarnessInstallPhase> = HarnessInstallPhase.entries

internal fun harnessInstallPhase(stage: HarnessInstallStage): HarnessInstallPhase? = when (stage) {
    HarnessInstallStage.UNKNOWN -> null
    HarnessInstallStage.PRECHECK,
    HarnessInstallStage.INSTALL_PROOT -> HarnessInstallPhase.PRECHECK
    HarnessInstallStage.INSTALL_DEBIAN -> HarnessInstallPhase.DEBIAN
    HarnessInstallStage.INSTALL_NODE -> HarnessInstallPhase.NODE
    HarnessInstallStage.INSTALL_HARNESS,
    HarnessInstallStage.WRITE_SCRIPTS -> HarnessInstallPhase.HARNESS
    HarnessInstallStage.START_AND_HEALTHCHECK,
    HarnessInstallStage.READY -> HarnessInstallPhase.START
    HarnessInstallStage.FAILED -> null
}

internal fun failedHarnessInstallPhase(detail: String): HarnessInstallPhase? {
    val normalized = detail.lowercase()
    return when {
        "start_and_healthcheck" in normalized || "healthcheck" in normalized -> HarnessInstallPhase.START
        "write_scripts" in normalized || "install_harness" in normalized -> HarnessInstallPhase.HARNESS
        "install_node" in normalized -> HarnessInstallPhase.NODE
        "install_debian" in normalized -> HarnessInstallPhase.DEBIAN
        "install_proot" in normalized || "precheck" in normalized -> HarnessInstallPhase.PRECHECK
        else -> null
    }
}

internal fun harnessStatusPresentation(snapshot: HarnessSnapshot): HarnessStatusPresentation {
    if (snapshot.legacyRuntimeFound && snapshot.status == HarnessStatus.NOT_INSTALLED) {
        return HarnessStatusPresentation(
            label = "发现旧运行时，需要迁移",
            explanation = "旧版 Termux 运行时会保留；点击安装 / 修复可迁移到独立 Debian，不会清空已有配置。",
            severity = HarnessUiSeverity.WARNING,
        )
    }
    return when (snapshot.status) {
        HarnessStatus.NOT_INSTALLED -> HarnessStatusPresentation(
            label = "未安装",
            explanation = "尚未准备 Daddy 的独立 Debian Harness 运行环境。",
            severity = HarnessUiSeverity.NORMAL,
        )
        HarnessStatus.INSTALLING -> HarnessStatusPresentation(
            label = "安装中",
            explanation = "正在分阶段准备运行环境；退出页面后安装仍会继续。",
            severity = HarnessUiSeverity.NORMAL,
        )
        HarnessStatus.STOPPED -> HarnessStatusPresentation(
            label = "未启动",
            explanation = "运行环境已准备好，但 Harness 当前没有运行。",
            severity = HarnessUiSeverity.NORMAL,
        )
        HarnessStatus.STARTING -> HarnessStatusPresentation(
            label = "启动并检查",
            explanation = "正在启动 Harness，并检查本机 3080 端口。",
            severity = HarnessUiSeverity.NORMAL,
        )
        HarnessStatus.RUNNING -> HarnessStatusPresentation(
            label = "运行中",
            explanation = "Harness 工作台已在本机安全运行。",
            severity = HarnessUiSeverity.SUCCESS,
        )
        HarnessStatus.MANUALLY_STOPPED -> HarnessStatusPresentation(
            label = "已手动停止",
            explanation = "这是你主动停止的，本次不会自动复活；点击启动后才恢复。",
            severity = HarnessUiSeverity.WARNING,
        )
        HarnessStatus.BACKING_OFF -> HarnessStatusPresentation(
            label = "等待自动恢复",
            explanation = "连续失败后恢复间隔会逐步延长，避免反复耗电和刷屏。",
            severity = HarnessUiSeverity.WARNING,
        )
        HarnessStatus.REPAIRING -> HarnessStatusPresentation(
            label = "需要继续安装",
            explanation = "检测到未完成的阶段；点击安装 / 修复会从已验证的步骤继续。",
            severity = HarnessUiSeverity.WARNING,
        )
        HarnessStatus.ERROR -> HarnessStatusPresentation(
            label = "异常",
            explanation = "Harness 没有通过状态检查；可查看失败阶段和脱敏日志后继续修复。",
            severity = HarnessUiSeverity.ERROR,
        )
    }
}

internal fun nextHarnessInstallProgress(
    previous: Int,
    snapshot: HarnessSnapshot,
    installationAttemptActive: Boolean,
): Int {
    val reported = when (snapshot.installStage) {
        HarnessInstallStage.READY -> 100
        HarnessInstallStage.FAILED -> when (failedHarnessInstallPhase(snapshot.detail)) {
            HarnessInstallPhase.PRECHECK -> 5
            HarnessInstallPhase.DEBIAN -> 35
            HarnessInstallPhase.NODE -> 55
            HarnessInstallPhase.HARNESS -> 75
            HarnessInstallPhase.START -> 95
            null -> snapshot.installProgressPercent.coerceIn(0, 100)
        }
        else -> snapshot.installProgressPercent.coerceIn(0, 100)
    }
    return if (installationAttemptActive) maxOf(previous.coerceIn(0, 100), reported) else reported
}

internal fun canOpenHarnessWorkspace(status: HarnessStatus): Boolean = status == HarnessStatus.RUNNING
