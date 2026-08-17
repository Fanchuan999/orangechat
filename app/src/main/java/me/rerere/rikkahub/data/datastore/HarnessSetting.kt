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
    STOPPED,
    RUNNING,
    ERROR,
}

data class HarnessSnapshot(
    val status: HarnessStatus = HarnessStatus.NOT_INSTALLED,
    val installedVersion: String = "",
    val detail: String = "",
    val logTail: String = "",
)
