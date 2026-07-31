/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync.webdav

import kotlinx.serialization.Serializable

@Serializable
internal data class BackupManifest(
    val version: Int = 2,
    val displayAssets: Map<String, String> = emptyMap(),
)
