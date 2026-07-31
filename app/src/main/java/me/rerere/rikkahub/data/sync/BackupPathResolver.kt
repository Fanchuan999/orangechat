/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync

import java.io.File

/**
 * Resolves archive paths while preventing a backup entry from escaping its intended directory.
 */
internal object BackupPathResolver {
    fun resolveWithin(root: File, relativePath: String): File? {
        if (relativePath.isBlank()) return null

        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val target = runCatching { File(canonicalRoot, relativePath).canonicalFile }.getOrNull() ?: return null
        val rootPath = canonicalRoot.path
        val targetPath = target.path

        return target.takeIf {
            targetPath.startsWith(rootPath + File.separator)
        }
    }
}
