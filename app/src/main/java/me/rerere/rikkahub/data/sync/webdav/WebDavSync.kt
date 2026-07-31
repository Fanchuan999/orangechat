/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync.webdav

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.files.SkillPaths
import me.rerere.rikkahub.data.sync.BackupPathResolver
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.plugin.repository.PluginRepository
import me.rerere.rikkahub.plugin.repository.PluginSettingsExport
import me.rerere.rikkahub.plugin.scanner.PluginScanner
import me.rerere.rikkahub.utils.fileSizeToString
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "WebDavSync"
private const val BACKUP_MANIFEST_ENTRY = "backup_manifest.json"
private const val DISPLAY_ASSETS_PREFIX = "display_assets/"
private const val PLUGIN_PRIVATE_FILES_PREFIX = "plugin_private/files/"
private const val PLUGIN_PRIVATE_PREFERENCES_PREFIX = "plugin_private/shared_prefs/"
private const val PLUGIN_PREFERENCES_FILE_PREFIX = "plugin_data_"
private const val PLUGIN_PREFERENCES_FILE_SUFFIX = ".xml"

private val displayAssetDirectories = listOf(
    "custom_fonts",
    "input_backgrounds",
    "drawer_backgrounds",
    "avatar_frames",
    "bubble_backgrounds",
)

private val displayAssetPaths: List<Pair<String, (DisplaySetting) -> String>> = listOf(
    "customFontPath" to { it.customFontPath },
    "inputBackgroundPath" to { it.inputBackgroundPath },
    "drawerBackgroundPath" to { it.drawerBackgroundPath },
    "userAvatarFramePath" to { it.userAvatarFramePath },
    "aiAvatarFramePath" to { it.aiAvatarFramePath },
    "userBubbleImagePath" to { it.userBubbleImagePath },
    "assistantBubbleImagePath" to { it.assistantBubbleImagePath },
)

class WebDavSync(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
    private val httpClient: HttpClient,
    private val pluginRepository: PluginRepository,
) {
    private fun getClient(config: WebDavConfig): WebDavClient {
        return WebDavClient(config, httpClient)
    }

    suspend fun testConnection(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        // Test by listing the root directory
        client.propfind(depth = 0).getOrThrow()
        Log.i(TAG, "testConnection: Connection successful")
    }

    suspend fun backup(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config, includePlugins = false)
        val client = getClient(config)

        // Ensure the backup directory exists
        client.ensureCollectionExists().getOrThrow()

        // Upload the backup file
        client.put(
            path = file.name,
            file = file,
            contentType = "application/zip"
        ).getOrThrow()

        Log.i(TAG, "backup: Uploaded ${file.name} (${file.length().fileSizeToString()})")

        // Clean up temp file
        file.delete()
    }

    suspend fun listBackupFiles(config: WebDavConfig): List<WebDavBackupItem> = withContext(Dispatchers.IO) {
        val client = getClient(config)

        // Ensure the backup directory exists
        client.ensureCollectionExists().getOrThrow()

        val resources = client.list().getOrThrow()

        resources
            .filter { !it.isCollection && it.displayName.startsWith("backup_") && it.displayName.endsWith(".zip") }
            .map { resource ->
                WebDavBackupItem(
                    href = resource.href,
                    displayName = resource.displayName,
                    size = resource.contentLength,
                    lastModified = resource.lastModified ?: Instant.EPOCH
                )
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun restore(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        val backupFile = File(context.cacheDir, item.displayName)

        try {
            // Download backup file directly to file to avoid OOM
            Log.i(TAG, "restore: Downloading ${item.displayName}")
            client.downloadToFile(item.displayName, backupFile).getOrThrow()

            Log.i(TAG, "restore: Downloaded ${backupFile.length().fileSizeToString()}")

            // Restore from backup file
            restoreFromBackupFile(backupFile, config, includePlugins = false)
        } finally {
            // Clean up temp file
            if (backupFile.exists()) {
                backupFile.delete()
                Log.i(TAG, "restore: Cleaned up temporary backup file")
            }
        }
    }

    suspend fun deleteBackupFile(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        client.delete(item.displayName).getOrThrow()
        Log.i(TAG, "deleteBackupFile: Deleted ${item.displayName}")
    }

    suspend fun restoreFromLocalFile(file: File, config: WebDavConfig) = withContext(Dispatchers.IO) {
        Log.i(TAG, "restoreFromLocalFile: Starting restore from ${file.absolutePath}")

        if (!file.exists()) {
            throw Exception("Backup file does not exist")
        }

        if (!file.canRead()) {
            throw Exception("Cannot read backup file")
        }

        // Local exports can include the external Orangechat/plugins directory. A newly installed package
        // has not received the special all-files grant yet, so fail before restoring any other archive entry.
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
        ) {
            throw Exception(
                "恢复插件需要「所有文件访问权限」。请在系统设置中允许 Daddy 管理所有文件后重新导入备份。"
            )
        }

        try {
            restoreFromBackupFile(file, config, includePlugins = true)
            Log.i(TAG, "restoreFromLocalFile: Restore completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromLocalFile: Failed to restore from local file", e)
            throw Exception("Restore failed: ${e.message}")
        }
    }

    suspend fun prepareBackupFile(
        config: WebDavConfig,
        includePlugins: Boolean = true
    ): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val backupFile = File(context.cacheDir, "backup_$timestamp.zip")

        if (backupFile.exists()) {
            backupFile.delete()
        }

        // Create zip file and backup data
        ZipOutputStream(FileOutputStream(backupFile)).use { zipOut ->
            val settings = settingsStore.settingsFlow.value
            val manifest = if (config.items.contains(WebDavConfig.BackupItem.FILES)) {
                backupDisplayAssets(zipOut, settings.displaySetting)
            } else {
                BackupManifest()
            }
            addVirtualFileToZip(
                zipOut = zipOut,
                name = BACKUP_MANIFEST_ENTRY,
                content = json.encodeToString(manifest)
            )
            addVirtualFileToZip(
                zipOut = zipOut,
                name = "settings.json",
                content = json.encodeToString(settings)
            )

            // Backup database files
            if (config.items.contains(WebDavConfig.BackupItem.DATABASE)) {
                val dbFile = context.getDatabasePath("rikka_hub")
                if (dbFile.exists()) {
                    addFileToZip(zipOut, dbFile, "rikka_hub.db")
                }

                val walFile = File(dbFile.parentFile, "rikka_hub-wal")
                if (walFile.exists()) {
                    addFileToZip(zipOut, walFile, "rikka_hub-wal")
                }

                val shmFile = File(dbFile.parentFile, "rikka_hub-shm")
                if (shmFile.exists()) {
                    addFileToZip(zipOut, shmFile, "rikka_hub-shm")
                }
            }

            // Backup app files
            if (config.items.contains(WebDavConfig.BackupItem.FILES)) {
                val uploadFolder = File(context.filesDir, FileFolders.UPLOAD)
                if (uploadFolder.exists() && uploadFolder.isDirectory) {
                    Log.i(TAG, "prepareBackupFile: Backing up files from ${uploadFolder.absolutePath}")
                    uploadFolder.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            addFileToZip(zipOut, file, "${FileFolders.UPLOAD}/${file.name}")
                        }
                    }
                } else {
                    Log.w(TAG, "prepareBackupFile: Upload folder does not exist or is not a directory")
                }

                val skillsFolder = File(context.filesDir, FileFolders.SKILLS)
                if (skillsFolder.exists() && skillsFolder.isDirectory) {
                    Log.i(TAG, "prepareBackupFile: Backing up skills from ${skillsFolder.absolutePath}")
                    addDirectoryToZip(
                        zipOut = zipOut,
                        rootDir = skillsFolder,
                        currentDir = skillsFolder,
                        entryPrefix = "${FileFolders.SKILLS}/"
                    )
                } else {
                    Log.w(TAG, "prepareBackupFile: Skills folder does not exist or is not a directory")
                }

                // Backup plugin settings and plugin folders (only for local backup/export)
                if (includePlugins) {
                    try {
                        val pluginSettings = pluginRepository.exportPluginSettings()
                        addVirtualFileToZip(
                            zipOut = zipOut,
                            name = "plugin_settings.json",
                            content = json.encodeToString(pluginSettings)
                        )
                        Log.i(TAG, "prepareBackupFile: Backed up plugin settings")
                    } catch (e: Exception) {
                        Log.e(TAG, "prepareBackupFile: Failed to back up plugin settings", e)
                    }

                    val pluginsFolder = PluginScanner(context).pluginsDir
                    if (pluginsFolder.exists() && pluginsFolder.isDirectory) {
                        Log.i(TAG, "prepareBackupFile: Backing up plugins from ${pluginsFolder.absolutePath}")
                        addDirectoryToZip(
                            zipOut = zipOut,
                            rootDir = pluginsFolder,
                            currentDir = pluginsFolder,
                            entryPrefix = "${PluginScanner.PLUGINS_DIR}/"
                        )
                    } else {
                        Log.w(TAG, "prepareBackupFile: Plugins folder does not exist or is not a directory")
                    }

                    // PluginDataStore keeps each plugin's actual content in app-private files and
                    // SharedPreferences. These were previously omitted, so restoring an export only
                    // recreated the plugin package and settings, not the plugin's saved content.
                    val pluginDataFolder = File(context.filesDir, "plugin_data")
                    if (pluginDataFolder.exists() && pluginDataFolder.isDirectory) {
                        addDirectoryToZip(
                            zipOut = zipOut,
                            rootDir = pluginDataFolder,
                            currentDir = pluginDataFolder,
                            entryPrefix = PLUGIN_PRIVATE_FILES_PREFIX,
                        )
                        Log.i(TAG, "prepareBackupFile: Backed up plugin private files")
                    }

                    val sharedPreferencesFolder = File(context.applicationInfo.dataDir, "shared_prefs")
                    sharedPreferencesFolder.listFiles()
                        ?.filter { preferenceFile ->
                            preferenceFile.isFile &&
                                preferenceFile.name.startsWith(PLUGIN_PREFERENCES_FILE_PREFIX) &&
                                preferenceFile.name.endsWith(PLUGIN_PREFERENCES_FILE_SUFFIX)
                        }
                        ?.forEach { preferenceFile ->
                            addFileToZip(
                                zipOut = zipOut,
                                file = preferenceFile,
                                entryName = "$PLUGIN_PRIVATE_PREFERENCES_PREFIX${preferenceFile.name}",
                            )
                        }
                }
            }
        }

        Log.i(
            TAG,
            "prepareBackupFile: Created backup file ${backupFile.name} (${backupFile.length().fileSizeToString()})"
        )
        backupFile
    }

    private suspend fun restoreFromBackupFile(
        backupFile: File,
        config: WebDavConfig,
        includePlugins: Boolean = true
    ) = withContext(Dispatchers.IO) {
        Log.i(TAG, "restoreFromBackupFile: Starting restore from ${backupFile.absolutePath}")

        var backupManifest = BackupManifest()
        var restoredSettings: Settings? = null
        val restoredDisplayAssets = mutableMapOf<String, File>()

        ZipInputStream(FileInputStream(backupFile)).use { zipIn ->
            var entry: ZipEntry?
            while (zipIn.nextEntry.also { entry = it } != null) {
                entry?.let { zipEntry ->
                    Log.i(TAG, "restoreFromBackupFile: Processing entry ${zipEntry.name}")

                    when (zipEntry.name) {
                        BACKUP_MANIFEST_ENTRY -> {
                            val manifestJson = zipIn.readBytes().toString(Charsets.UTF_8)
                            backupManifest = json.decodeFromString<BackupManifest>(manifestJson)
                            Log.i(TAG, "restoreFromBackupFile: Read backup manifest v${backupManifest.version}")
                        }

                        "settings.json" -> {
                            val settingsJson = zipIn.readBytes().toString(Charsets.UTF_8)
                            Log.i(TAG, "restoreFromBackupFile: Restoring settings")
                            try {
                                val migratedJson = SettingsJsonMigrator.migrate(settingsJson)
                                restoredSettings = json.decodeFromString<Settings>(migratedJson)
                            } catch (e: Exception) {
                                Log.e(TAG, "restoreFromBackupFile: Failed to restore settings", e)
                                throw Exception("Failed to restore settings: ${e.message}")
                            }
                        }

                        "rikka_hub.db", "rikka_hub-wal", "rikka_hub-shm" -> {
                            if (config.items.contains(WebDavConfig.BackupItem.DATABASE)) {
                                val dbFile = when (zipEntry.name) {
                                    "rikka_hub.db" -> context.getDatabasePath("rikka_hub")
                                    "rikka_hub-wal" -> File(
                                        context.getDatabasePath("rikka_hub").parentFile,
                                        "rikka_hub-wal"
                                    )

                                    "rikka_hub-shm" -> File(
                                        context.getDatabasePath("rikka_hub").parentFile,
                                        "rikka_hub-shm"
                                    )

                                    else -> null
                                }

                                dbFile?.let { targetFile ->
                                    Log.i(
                                        TAG,
                                        "restoreFromBackupFile: Restoring ${zipEntry.name} " +
                                            "to ${targetFile.absolutePath}"
                                    )
                                    targetFile.parentFile?.mkdirs()
                                    FileOutputStream(targetFile).use { outputStream ->
                                        zipIn.copyTo(outputStream)
                                    }
                                    Log.i(
                                        TAG,
                                        "restoreFromBackupFile: Restored ${zipEntry.name} " +
                                            "(${targetFile.length()} bytes)"
                                    )
                                }
                            }
                        }

                        else -> {
                            if (config.items.contains(WebDavConfig.BackupItem.FILES) &&
                                zipEntry.name.startsWith(DISPLAY_ASSETS_PREFIX)
                            ) {
                                restoreDisplayAssetEntry(zipIn, zipEntry.name, restoredDisplayAssets)
                            } else if (config.items.contains(WebDavConfig.BackupItem.FILES) &&
                                zipEntry.name.startsWith("${FileFolders.UPLOAD}/")
                            ) {
                                val relativePath = zipEntry.name.substringAfter("${FileFolders.UPLOAD}/")
                                if (relativePath.isNotEmpty()) {
                                    val uploadFolder = File(context.filesDir, FileFolders.UPLOAD)
                                    if (!uploadFolder.exists()) {
                                        uploadFolder.mkdirs()
                                        Log.i(TAG, "restoreFromBackupFile: Created upload directory")
                                    }

                                    val targetFile = BackupPathResolver.resolveWithin(uploadFolder, relativePath)
                                    if (targetFile == null) {
                                        Log.w(TAG, "restoreFromBackupFile: Invalid upload entry ${zipEntry.name}")
                                    } else {
                                        Log.i(
                                            TAG,
                                            "restoreFromBackupFile: Restoring file ${zipEntry.name} " +
                                                "to ${targetFile.absolutePath}"
                                        )

                                        try {
                                            FileOutputStream(targetFile).use { outputStream ->
                                                zipIn.copyTo(outputStream)
                                            }
                                            Log.i(
                                                TAG,
                                                "restoreFromBackupFile: Restored ${zipEntry.name} " +
                                                    "(${targetFile.length()} bytes)"
                                            )
                                        } catch (e: Exception) {
                                            Log.e(
                                                TAG,
                                                "restoreFromBackupFile: Failed to restore file ${zipEntry.name}",
                                                e
                                            )
                                            throw Exception("Failed to restore file ${zipEntry.name}: ${e.message}")
                                        }
                                    }
                                }
                            } else if (config.items.contains(WebDavConfig.BackupItem.FILES) &&
                                zipEntry.name.startsWith("${FileFolders.SKILLS}/")
                            ) {
                                restoreSkillEntry(zipIn, zipEntry.name)
                            } else if (includePlugins && zipEntry.name == "plugin_settings.json") {
                                try {
                                    val pluginSettingsJson = zipIn.readBytes().toString(Charsets.UTF_8)
                                    val pluginSettings = json.decodeFromString<PluginSettingsExport>(pluginSettingsJson)
                                    pluginRepository.importPluginSettings(pluginSettings)
                                    Log.i(TAG, "restoreFromBackupFile: Restored plugin settings")
                                } catch (e: Exception) {
                                    Log.e(TAG, "restoreFromBackupFile: Failed to restore plugin settings", e)
                                }
                            } else if (includePlugins &&
                                config.items.contains(WebDavConfig.BackupItem.FILES) &&
                                zipEntry.name.startsWith("${PluginScanner.PLUGINS_DIR}/")
                            ) {
                                restorePluginEntry(zipIn, zipEntry.name)
                            } else if (includePlugins &&
                                config.items.contains(WebDavConfig.BackupItem.FILES) &&
                                zipEntry.name.startsWith(PLUGIN_PRIVATE_FILES_PREFIX)
                            ) {
                                restorePluginPrivateFileEntry(zipIn, zipEntry.name)
                            } else if (includePlugins &&
                                config.items.contains(WebDavConfig.BackupItem.FILES) &&
                                zipEntry.name.startsWith(PLUGIN_PRIVATE_PREFERENCES_PREFIX)
                            ) {
                                restorePluginPreferenceEntry(zipIn, zipEntry.name)
                            } else {
                                Log.i(TAG, "restoreFromBackupFile: Skipping entry ${zipEntry.name}")
                            }
                        }
                    }

                    zipIn.closeEntry()
                }
            }
        }

        restoredSettings?.let { settings ->
            val settingsWithRestoredAssets = restoreDisplayAssetPaths(
                settings = settings,
                manifest = backupManifest,
                restoredAssets = restoredDisplayAssets,
            )
            settingsStore.update(settingsWithRestoredAssets)
            Log.i(TAG, "restoreFromBackupFile: Settings restored successfully")
        }

        Log.i(TAG, "restoreFromBackupFile: Restore completed successfully")
    }

    private fun backupDisplayAssets(zipOut: ZipOutputStream, displaySetting: DisplaySetting): BackupManifest {
        displayAssetDirectories.forEach { directoryName ->
            val directory = File(context.filesDir, directoryName)
            if (directory.isDirectory) {
                addDirectoryToZip(
                    zipOut = zipOut,
                    rootDir = directory,
                    currentDir = directory,
                    entryPrefix = "$DISPLAY_ASSETS_PREFIX$directoryName/"
                )
            }
        }

        val activeAssets = displayAssetPaths.mapNotNull { (key, pathSelector) ->
            val path = pathSelector(displaySetting)
            val asset = path.takeIf { it.isNotBlank() }?.let(::File)
            val relativePath = asset?.let { FileUtils.getRelativePathInFilesDir(context.filesDir, it) }
            val isDisplayAsset = relativePath?.let { relative ->
                displayAssetDirectories.any { directory -> relative.startsWith("$directory/") }
            } == true

            if (asset?.isFile == true && isDisplayAsset && relativePath != null) {
                key to "$DISPLAY_ASSETS_PREFIX$relativePath"
            } else {
                null
            }
        }.toMap()

        Log.i(TAG, "prepareBackupFile: Backed up ${activeAssets.size} active display assets")
        return BackupManifest(displayAssets = activeAssets)
    }

    private fun restoreDisplayAssetEntry(
        zipIn: ZipInputStream,
        entryName: String,
        restoredAssets: MutableMap<String, File>,
    ) {
        val relativePath = entryName.removePrefix(DISPLAY_ASSETS_PREFIX)
        val isDisplayAsset = displayAssetDirectories.any { directory ->
            relativePath.startsWith("$directory/")
        }
        val targetFile = if (isDisplayAsset) {
            BackupPathResolver.resolveWithin(context.filesDir, relativePath)
        } else {
            null
        }

        if (targetFile == null) {
            Log.w(TAG, "restoreFromBackupFile: Invalid display asset entry $entryName")
            return
        }

        targetFile.parentFile?.mkdirs()
        FileOutputStream(targetFile).use { outputStream ->
            zipIn.copyTo(outputStream)
        }
        restoredAssets[entryName] = targetFile
        Log.i(TAG, "restoreFromBackupFile: Restored display asset $entryName")
    }

    private fun restoreDisplayAssetPaths(
        settings: Settings,
        manifest: BackupManifest,
        restoredAssets: Map<String, File>,
    ): Settings {
        fun restoredPath(key: String): String? = manifest.displayAssets[key]
            ?.let(restoredAssets::get)
            ?.absolutePath

        val display = settings.displaySetting
        return settings.copy(
            displaySetting = display.copy(
                customFontPath = restoredPath("customFontPath") ?: display.customFontPath,
                inputBackgroundPath = restoredPath("inputBackgroundPath") ?: display.inputBackgroundPath,
                drawerBackgroundPath = restoredPath("drawerBackgroundPath") ?: display.drawerBackgroundPath,
                userAvatarFramePath = restoredPath("userAvatarFramePath") ?: display.userAvatarFramePath,
                aiAvatarFramePath = restoredPath("aiAvatarFramePath") ?: display.aiAvatarFramePath,
                userBubbleImagePath = restoredPath("userBubbleImagePath") ?: display.userBubbleImagePath,
                assistantBubbleImagePath = restoredPath("assistantBubbleImagePath") ?: display.assistantBubbleImagePath,
            )
        )
    }

    private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
        FileInputStream(file).use { fis ->
            val zipEntry = ZipEntry(entryName)
            zipOut.putNextEntry(zipEntry)
            fis.copyTo(zipOut)
            zipOut.closeEntry()
            Log.d(TAG, "addFileToZip: Added $entryName (${file.length()} bytes) to zip")
        }
    }

    private fun addDirectoryToZip(
        zipOut: ZipOutputStream,
        rootDir: File,
        currentDir: File,
        entryPrefix: String,
    ) {
        currentDir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                addDirectoryToZip(
                    zipOut = zipOut,
                    rootDir = rootDir,
                    currentDir = file,
                    entryPrefix = entryPrefix,
                )
            } else if (file.isFile) {
                val relativePath = file.relativeTo(rootDir).invariantSeparatorsPath
                addFileToZip(zipOut, file, "$entryPrefix$relativePath")
            }
        }
    }

    private fun restorePluginEntry(zipIn: ZipInputStream, entryName: String) {
        val relativePath = entryName.substringAfter("${PluginScanner.PLUGINS_DIR}/")
        if (relativePath.isBlank()) {
            Log.w(TAG, "restoreFromBackupFile: Invalid plugin entry $entryName")
            return
        }

        val pluginsRoot = PluginScanner(context).pluginsDir.apply { mkdirs() }
        val targetFile = BackupPathResolver.resolveWithin(pluginsRoot, relativePath)
        if (targetFile == null) {
            Log.w(TAG, "restoreFromBackupFile: Invalid plugin entry $entryName")
            return
        }
        targetFile.parentFile?.mkdirs()

        try {
            FileOutputStream(targetFile).use { outputStream ->
                zipIn.copyTo(outputStream)
            }
            Log.i(TAG, "restoreFromBackupFile: Restored plugin file $entryName (${targetFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromBackupFile: Failed to restore plugin file $entryName", e)
            throw Exception("Failed to restore plugin file $entryName: ${e.message}")
        }
    }

    private fun restorePluginPrivateFileEntry(zipIn: ZipInputStream, entryName: String) {
        val relativePath = entryName.removePrefix(PLUGIN_PRIVATE_FILES_PREFIX)
        val pluginDataRoot = File(context.filesDir, "plugin_data").apply { mkdirs() }
        restorePrivatePluginEntry(zipIn, entryName, pluginDataRoot, relativePath)
    }

    private fun restorePluginPreferenceEntry(zipIn: ZipInputStream, entryName: String) {
        val fileName = entryName.removePrefix(PLUGIN_PRIVATE_PREFERENCES_PREFIX)
        if (
            fileName.isBlank() ||
            fileName.contains('/') ||
            !fileName.startsWith(PLUGIN_PREFERENCES_FILE_PREFIX) ||
            !fileName.endsWith(PLUGIN_PREFERENCES_FILE_SUFFIX)
        ) {
            Log.w(TAG, "restoreFromBackupFile: Invalid plugin preferences entry $entryName")
            return
        }

        val sharedPreferencesRoot = File(context.applicationInfo.dataDir, "shared_prefs").apply { mkdirs() }
        restorePrivatePluginEntry(zipIn, entryName, sharedPreferencesRoot, fileName)
    }

    private fun restorePrivatePluginEntry(
        zipIn: ZipInputStream,
        entryName: String,
        targetRoot: File,
        relativePath: String,
    ) {
        val targetFile = BackupPathResolver.resolveWithin(targetRoot, relativePath)
        if (targetFile == null) {
            Log.w(TAG, "restoreFromBackupFile: Invalid plugin private-data entry $entryName")
            return
        }

        targetFile.parentFile?.mkdirs()
        try {
            FileOutputStream(targetFile).use { outputStream ->
                zipIn.copyTo(outputStream)
            }
            Log.i(TAG, "restoreFromBackupFile: Restored plugin private data $entryName")
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromBackupFile: Failed to restore plugin private data $entryName", e)
            throw Exception("Failed to restore plugin private data $entryName: ${e.message}")
        }
    }

    private fun restoreSkillEntry(zipIn: ZipInputStream, entryName: String) {
        val relativePath = entryName.substringAfter("${FileFolders.SKILLS}/")
        val skillName = relativePath.substringBefore('/', missingDelimiterValue = "")
        val skillRelativePath = relativePath.substringAfter('/', missingDelimiterValue = "")

        if (skillName.isBlank() || skillRelativePath.isBlank()) {
            Log.w(TAG, "restoreFromBackupFile: Invalid skill entry $entryName")
            return
        }

        val skillsRoot = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() }
        val skillDir = SkillPaths.resolveSkillDir(skillsRoot, skillName)
            ?: throw Exception("Invalid skill directory: $entryName")
        val targetFile = SkillPaths.resolveSkillFile(skillDir, skillRelativePath)
            ?: throw Exception("Invalid skill file path: $entryName")

        skillDir.mkdirs()
        targetFile.parentFile?.mkdirs()

        try {
            FileOutputStream(targetFile).use { outputStream ->
                zipIn.copyTo(outputStream)
            }
            Log.i(TAG, "restoreFromBackupFile: Restored skill file $entryName (${targetFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromBackupFile: Failed to restore skill file $entryName", e)
            throw Exception("Failed to restore skill file $entryName: ${e.message}")
        }
    }

    private fun addVirtualFileToZip(zipOut: ZipOutputStream, name: String, content: String) {
        val zipEntry = ZipEntry(name)
        zipOut.putNextEntry(zipEntry)
        zipOut.write(content.toByteArray())
        zipOut.closeEntry()
        Log.i(TAG, "addVirtualFileToZip: $name (${content.length} bytes)")
    }
}

data class WebDavBackupItem(
    val href: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
