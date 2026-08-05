/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.scanner

import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.security.SecurityAuditRepository
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.PluginManifest
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * 插件扫描器
 * 负责扫描、导入和管理插件目录
 */
class PluginScanner(
    private val context: Context,
    private val auditRepo: SecurityAuditRepository? = null,
) {
    companion object {
        const val PLUGINS_DIR = "Orangechat/plugins"
        const val MANIFEST_FILE = "manifest.json"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * The app-private directory is always writable, including on Android 11+
     * devices that block writes to the shared storage root despite a file
     * picker being able to read the ZIP. Plugin code is not user content; the
     * actual plugin data lives in [PluginDataStore] and remains separate.
     */
    val pluginsDir: File
        get() = File(context.filesDir, "plugins").apply { mkdirs() }

    /**
     * Legacy OrangeChat plugin location. Kept read-only as a migration source
     * so installations created before the private-directory migration keep
     * working and can be copied to the managed directory when possible.
     */
    private val legacyPluginsDir: File
        get() = File(Environment.getExternalStorageDirectory(), PLUGINS_DIR)

    /**
     * 确保插件目录存在
     */
    fun ensurePluginsDir(): File = pluginsDir

    /**
     * 扫描所有插件
     */
    fun scanPlugins(): List<PluginInfo> {
        migrateLegacyPluginsIfPossible()

        // Prefer the managed copy if both locations contain the same plugin.
        // This lets a new version safely replace a legacy plugin even if the
        // old shared-storage folder cannot be deleted by the OS.
        val plugins = linkedMapOf<String, PluginInfo>()
        scanPluginDirectory(legacyPluginsDir).forEach { plugin ->
            plugins.putIfAbsent(plugin.manifest.id, plugin)
        }
        scanPluginDirectory(ensurePluginsDir()).forEach { plugin ->
            plugins[plugin.manifest.id] = plugin
        }
        return plugins.values.toList()
    }

    private fun scanPluginDirectory(dir: File): List<PluginInfo> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles { file -> file.isDirectory }
            ?.mapNotNull { pluginDir -> loadPluginInfo(pluginDir) }
            ?: emptyList()
    }

    private fun migrateLegacyPluginsIfPossible() {
        val legacy = legacyPluginsDir
        val managed = ensurePluginsDir()
        if (!legacy.isDirectory) return

        legacy.listFiles { file -> file.isDirectory }?.forEach { legacyPlugin ->
            val manifest = File(legacyPlugin, MANIFEST_FILE)
            val managedPlugin = File(managed, legacyPlugin.name)
            if (!manifest.isFile || managedPlugin.exists()) return@forEach

            runCatching {
                if (!legacyPlugin.copyRecursively(managedPlugin, overwrite = false)) {
                    throw IllegalStateException("无法迁移插件 ${legacyPlugin.name}")
                }
            }
        }
    }

    /**
     * 加载单个插件信息
     */
    fun loadPluginInfo(pluginDir: File): PluginInfo? {
        val manifestFile = File(pluginDir, MANIFEST_FILE)
        if (!manifestFile.exists()) {
            return null
        }

        return try {
            val content = manifestFile.readText()
            val manifest = json.decodeFromString(PluginManifest.serializer(), content)

            // 完整性校验：若存在 .integrity 文件则验证，失败则禁用并标记错误
            val integrityFile = File(pluginDir, ".integrity")
            if (integrityFile.exists()) {
                val stored = integrityFile.readText().trim()
                val current = computePluginChecksum(pluginDir)
                if (stored != current) {
                    return PluginInfo(
                        manifest = manifest,
                        directory = pluginDir,
                        isEnabled = false,
                        loadError = "完整性校验失败：插件文件已被篡改或损坏"
                    )
                }
            }

            PluginInfo(
                manifest = manifest,
                directory = pluginDir,
                isEnabled = true // 默认启用
            )
        } catch (e: Exception) {
            // 解析失败，返回错误状态的插件
            PluginInfo(
                manifest = PluginManifest(
                    id = pluginDir.name,
                    name = pluginDir.name,
                    description = "加载失败: ${e.message}",
                    version = "error",
                    author = "unknown",
                    icon = "⚠️",
                    entry = "",
                    tools = emptyList(),
                    config = emptyList()
                ),
                directory = pluginDir,
                isEnabled = false,
                loadError = e.message
            )
        }
    }

    /**
     * 从ZIP文件预览插件（不解压到插件目录，仅解析 manifest）
     * 返回 manifest 和临时目录，供调用方展示权限确认对话框。
     * 调用方确认后应调用 [completeImport] 完成导入；取消时应清理临时目录。
     */
    suspend fun previewFromZip(uri: Uri): Result<Pair<PluginManifest, File>> {
        return try {
            val tempFile = File(context.cacheDir, "plugin_preview_${System.currentTimeMillis()}.zip")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return Result.failure(IllegalStateException("无法读取文件"))

            val tempDir = File(context.cacheDir, "plugin_preview_${System.currentTimeMillis()}")
            unzip(tempFile, tempDir)

            val manifestFile = findManifest(tempDir)
                ?: run {
                    tempFile.delete()
                    tempDir.deleteRecursively()
                    return Result.failure(IllegalArgumentException("找不到 manifest.json"))
                }

            val manifest = json.decodeFromString(PluginManifest.serializer(), manifestFile.readText())

            // 预览阶段保留 tempFile 和 tempDir，供后续 completeImport 使用
            Result.success(manifest to tempDir)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 完成插件导入（在 previewFromZip 后调用）
     * @param manifest 预览阶段解析的 manifest
     * @param tempDir 预览阶段解压的临时目录
     */
    suspend fun completeImport(manifest: PluginManifest, tempDir: File): Result<PluginInfo> {
        return try {
            val existingPlugins = scanPlugins()
            val isUpdate = existingPlugins.any { it.manifest.id == manifest.id }

            val manifestFile = findManifest(tempDir)
                ?: run {
                    tempDir.deleteRecursively()
                    return Result.failure(IllegalArgumentException("找不到 manifest.json"))
                }

            val entryFile = File(manifestFile.parentFile, manifest.entry)
            if (!entryFile.exists()) {
                tempDir.deleteRecursively()
                return Result.failure(IllegalArgumentException("找不到入口文件: ${manifest.entry}"))
            }

            val pluginDir = installPluginDirectory(manifestFile.parentFile, manifest.id)
            tempDir.deleteRecursively()

            // 写入完整性校验和
            runCatching {
                val checksum = computePluginChecksum(pluginDir)
                File(pluginDir, ".integrity").writeText(checksum)
            }

            loadPluginInfo(pluginDir)?.let {
                auditRepo?.log(
                    category = "plugin",
                    action = if (isUpdate) "updated" else "installed",
                    target = manifest.id,
                    detail = "插件 ${manifest.name} (${manifest.id}) v${manifest.version}" +
                        if (isUpdate) " 已安全更新，保留插件数据和设置" else " 已安装，作者: ${manifest.author}",
                    status = "success",
                )
                Result.success(it)
            } ?: Result.failure(IllegalStateException("无法加载插件信息"))
        } catch (e: Exception) {
            tempDir.deleteRecursively()
            Result.failure(e)
        }
    }

    /**
     * 从ZIP文件导入插件（旧版一次性导入，保留用于兼容）
     */
    suspend fun importFromZip(uri: Uri): Result<PluginInfo> {
        return try {
            // 1. 复制到临时文件
            val tempFile = File(context.cacheDir, "plugin_import_${System.currentTimeMillis()}.zip")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return Result.failure(IllegalStateException("无法读取文件"))

            // 2. 解压到临时目录
            val tempDir = File(context.cacheDir, "plugin_import_${System.currentTimeMillis()}")
            unzip(tempFile, tempDir)

            // 3. 查找manifest.json
            val manifestFile = findManifest(tempDir)
                ?: return Result.failure(IllegalArgumentException("找不到 manifest.json"))

            // 4. 解析manifest
            val content = manifestFile.readText()
            val manifest = json.decodeFromString(PluginManifest.serializer(), content)

            // 5. 验证入口文件
            val entryFile = File(manifestFile.parentFile, manifest.entry)
            if (!entryFile.exists()) {
                tempFile.delete()
                tempDir.deleteRecursively()
                return Result.failure(IllegalArgumentException("找不到入口文件: ${manifest.entry}"))
            }

            // 6. 安全替换插件代码。PluginDataStore is app-private and is
            // deliberately never touched here, so an update keeps book data,
            // preferences and all other plugin state.
            val pluginDir = installPluginDirectory(manifestFile.parentFile, manifest.id)

            // 7. 清理临时文件
            tempFile.delete()
            tempDir.deleteRecursively()

            // 8. 返回插件信息
            loadPluginInfo(pluginDir)?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("无法加载插件信息"))

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 删除插件
     */
    fun deletePlugin(pluginId: String): Boolean {
        var deleted = false
        listOf(
            File(pluginsDir, pluginId),
            File(legacyPluginsDir, pluginId),
        ).distinctBy { it.absolutePath }.forEach { pluginDir ->
            if (pluginDir.exists()) {
                deleted = pluginDir.deleteRecursively() || deleted
            }
        }
        return deleted
    }

    /**
     * 获取插件目录
     */
    fun getPluginDir(pluginId: String): File {
        return File(pluginsDir, pluginId)
    }

    /**
     * Stage plugin files in the app-private directory, then swap them into
     * place. If the swap fails, restore the previous code directory. Plugin
     * data and plugin settings are intentionally outside this directory.
     */
    private fun installPluginDirectory(sourceDir: File?, pluginId: String): File {
        val source = sourceDir ?: throw IllegalArgumentException("插件文件目录无效")
        val root = ensurePluginsDir()
        val target = File(root, pluginId)
        val nonce = System.currentTimeMillis()
        val staging = File(root, ".${pluginId}.staging.$nonce")
        val backup = File(root, ".${pluginId}.backup.$nonce")

        staging.deleteRecursively()
        backup.deleteRecursively()
        if (!source.copyRecursively(staging, overwrite = true)) {
            staging.deleteRecursively()
            throw IllegalStateException("无法准备插件文件")
        }

        val hadPreviousCode = target.exists()
        try {
            if (hadPreviousCode && !target.renameTo(backup)) {
                if (!target.copyRecursively(backup, overwrite = true) || !target.deleteRecursively()) {
                    throw IllegalStateException("无法替换旧插件文件")
                }
            }

            if (!staging.renameTo(target)) {
                if (!staging.copyRecursively(target, overwrite = true)) {
                    throw IllegalStateException("无法安装插件文件")
                }
                staging.deleteRecursively()
            }

            backup.deleteRecursively()
            return target
        } catch (error: Exception) {
            target.deleteRecursively()
            if (backup.exists()) {
                if (!backup.renameTo(target)) {
                    backup.copyRecursively(target, overwrite = true)
                    backup.deleteRecursively()
                }
            }
            staging.deleteRecursively()
            throw error
        }
    }

    /**
     * 解压ZIP文件
     */
    private fun unzip(zipFile: File, destDir: File) {
        destDir.mkdirs()
        val root = destDir.canonicalFile
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry: java.util.zip.ZipEntry? = zis.nextEntry
            while (entry != null) {
                // ZIPs produced by Windows tools may use backslashes in their
                // entry names. Android treats a backslash as a normal filename
                // character, so ui\\index.html would otherwise become one file
                // named "ui\\index.html" instead of ui/index.html.
                val normalizedEntryName = entry.name.replace('\\', '/').removePrefix("/")
                val file = File(root, normalizedEntryName).canonicalFile
                val isInsideDestination = file.path == root.path ||
                    file.path.startsWith(root.path + File.separator)
                if (!isInsideDestination) {
                    throw IllegalArgumentException("插件压缩包包含不安全路径: ${entry.name}")
                }

                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { output ->
                        zis.copyTo(output)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * 查找manifest.json文件
     * 优先在根目录查找，然后在子目录中查找
     */
    private fun findManifest(dir: File): File? {
        // 优先在根目录找
        val rootManifest = File(dir, MANIFEST_FILE)
        if (rootManifest.exists()) {
            return rootManifest
        }

        // 在子目录中查找
        return dir.listFiles { file -> file.isDirectory }
            ?.asSequence()
            ?.map { subdir -> File(subdir, MANIFEST_FILE) }
            ?.firstOrNull { it.exists() }
    }

    /**
     * 计算插件目录的 SHA-256 校验和（排除 .integrity 文件本身，稳定排序）
     */
    private fun computePluginChecksum(dir: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        dir.walkTopDown()
            .filter { it.isFile && it.name != ".integrity" }
            .sortedBy { it.relativeTo(dir).path.replace('\\', '/') }
            .forEach { file ->
                digest.update(file.relativeTo(dir).path.replace('\\', '/').toByteArray(Charsets.UTF_8))
                digest.update(file.readBytes())
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
