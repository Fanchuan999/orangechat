/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

@Serializable
data class HarnessSetting(
    val autoKeepRunning: Boolean = true,
    val manuallyStopped: Boolean = false,
    val installedVersion: String = "",
)

enum class HarnessStatus {
    NOT_INSTALLED,
    INSTALLING,
    STOPPED,
    STARTING,
    RUNNING,
    MANUALLY_STOPPED,
    BACKING_OFF,
    REPAIRING,
    ERROR,
}

enum class HarnessInstallStage {
    UNKNOWN,
    PRECHECK,
    INSTALL_PROOT,
    INSTALL_DEBIAN,
    INSTALL_NODE,
    INSTALL_HARNESS,
    WRITE_SCRIPTS,
    START_AND_HEALTHCHECK,
    READY,
    FAILED,
}

data class HarnessSnapshot(
    val status: HarnessStatus = HarnessStatus.NOT_INSTALLED,
    val installedVersion: String = "",
    val detail: String = "",
    val logTail: String = "",
    val installStage: HarnessInstallStage = HarnessInstallStage.UNKNOWN,
    val legacyRuntimeFound: Boolean = false,
    val installProgressPercent: Int = 0,
)
