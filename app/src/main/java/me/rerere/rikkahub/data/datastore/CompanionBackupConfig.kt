/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

/** Connection details that are safe to keep with the local application settings. */
@Serializable
data class CompanionBackupConfig(
    val ombreBaseUrl: String = "http://127.0.0.1:8000",
)
