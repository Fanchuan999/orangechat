/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync.companion

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.BackupPathResolver
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class CompanionBackupExportResult(
    val file: File,
    val supabaseTableCount: Int,
)

data class CompanionBackupRestoreResult(
    val supabaseReport: SupabaseRestoreReport,
    val externalMcpStatuses: List<ExternalMcpServiceStatus>,
)

/** Coordinates a complete local companion backup without persisting Ombre's password. */
class CompanionBackupService(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val webDavSync: WebDavSync,
) {
    private val termuxConfigBridge = TermuxConfigBridge(context)
    private val supabaseBackupClient = SupabaseBackupClient()
    private val externalMcpServiceChecker = ExternalMcpServiceChecker()

    suspend fun export(
        ombreBaseUrl: String,
        ombrePassword: CharArray,
    ): CompanionBackupExportResult = withContext(Dispatchers.IO) {
        val workDir = newWorkDir()
        var orangeBackup: File? = null
        val output = File(context.cacheDir, "orangechat_companion_${UUID.randomUUID()}.zip")
        try {
            val settings = settingsStore.settingsFlow.value
            orangeBackup = webDavSync.prepareBackupFile(
                config = settings.webDavConfig.copy(
                    items = listOf(WebDavConfig.BackupItem.DATABASE, WebDavConfig.BackupItem.FILES),
                ),
                includePlugins = true,
            )
            val ombreBackup = File(workDir, OMBRE_ARCHIVE)
            OmbreBackupClient(ombreBaseUrl).export(ombrePassword, ombreBackup)

            val supabaseBackup = File(workDir, SUPABASE_ARCHIVE)
            val supabaseTableCount = supabaseBackupClient.export(settings, supabaseBackup)

            val termuxBackup = termuxConfigBridge.exportConfigArchive()
            try {
                createArchive(
                    destination = output,
                    ombreBaseUrl = ombreBaseUrl,
                    parts = listOf(
                        ArchivePart(ORANGECHAT_ARCHIVE, orangeBackup),
                        ArchivePart(OMBRE_ARCHIVE, ombreBackup),
                        ArchivePart(SUPABASE_ARCHIVE, supabaseBackup),
                        ArchivePart(TERMUX_ARCHIVE, termuxBackup),
                    ),
                )
            } finally {
                termuxBackup.delete()
            }
            CompanionBackupExportResult(output, supabaseTableCount)
        } catch (exception: Exception) {
            output.delete()
            throw exception
        } finally {
            orangeBackup?.delete()
            workDir.deleteRecursively()
            ombrePassword.fill('\u0000')
        }
    }

    suspend fun restore(
        archive: File,
        ombrePassword: CharArray,
    ): CompanionBackupRestoreResult = withContext(Dispatchers.IO) {
        require(archive.exists() && archive.length() > 0L) { "联动备份包不存在或为空。" }
        val workDir = newWorkDir()
        try {
            val manifest = extractAndVerify(archive, workDir)
            val ombreUrl = manifest.optString("ombre_base_url")
            require(ombreUrl.isNotBlank()) { "联动备份包缺少 Ombre 地址。" }

            webDavSync.restoreFromLocalFile(
                file = File(workDir, ORANGECHAT_ARCHIVE),
                config = settingsStore.settingsFlow.value.webDavConfig.copy(
                    items = listOf(WebDavConfig.BackupItem.DATABASE, WebDavConfig.BackupItem.FILES),
                ),
            )
            val restoredSettings = settingsStore.settingsFlow.first()

            OmbreBackupClient(ombreUrl).restore(ombrePassword, File(workDir, OMBRE_ARCHIVE))
            val supabaseReport = supabaseBackupClient.restore(
                restoredSettings,
                File(workDir, SUPABASE_ARCHIVE),
            )
            termuxConfigBridge.restoreConfigArchive(File(workDir, TERMUX_ARCHIVE))
            CompanionBackupRestoreResult(
                supabaseReport = supabaseReport,
                externalMcpStatuses = externalMcpServiceChecker.check(restoredSettings),
            )
        } finally {
            workDir.deleteRecursively()
            ombrePassword.fill('\u0000')
        }
    }

    private fun createArchive(destination: File, ombreBaseUrl: String, parts: List<ArchivePart>) {
        require(parts.all { it.file.exists() && it.file.length() > 0L }) { "联动备份包含空文件。" }
        val contents = JSONArray()
        parts.forEach { part ->
            contents.put(
                JSONObject()
                    .put("name", part.name)
                    .put("size", part.file.length())
                    .put("sha256", sha256(part.file)),
            )
        }
        val manifest = JSONObject()
            .put("version", ARCHIVE_VERSION)
            .put("ombre_base_url", ombreBaseUrl.trim().trimEnd('/'))
            .put("contents", contents)

        ZipOutputStream(FileOutputStream(destination)).use { output ->
            parts.forEach { part ->
                output.putNextEntry(ZipEntry(part.name))
                FileInputStream(part.file).use { it.copyTo(output) }
                output.closeEntry()
            }
            output.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            output.write(manifest.toString().toByteArray(Charsets.UTF_8))
            output.closeEntry()
        }
    }

    private fun extractAndVerify(archive: File, workDir: File): JSONObject {
        ZipFile(archive).use { zip ->
            val manifestEntry = zip.getEntry(MANIFEST_ENTRY)
                ?: throw IllegalStateException("不是有效的橘瓣联动备份包。")
            val manifest = zip.getInputStream(manifestEntry).bufferedReader().use { JSONObject(it.readText()) }
            require(manifest.optInt("version") == ARCHIVE_VERSION) { "不支持的联动备份版本。" }
            val contents = manifest.optJSONArray("contents")
                ?: throw IllegalStateException("联动备份包缺少文件清单。")
            require(contents.length() == REQUIRED_ENTRIES.size) { "联动备份包文件清单不完整。" }

            val listedNames = mutableSetOf<String>()
            for (index in 0 until contents.length()) {
                val part = contents.optJSONObject(index)
                    ?: throw IllegalStateException("联动备份包文件清单格式错误。")
                val name = part.optString("name")
                require(name in REQUIRED_ENTRIES && listedNames.add(name)) { "联动备份包含有未知文件。" }
                val entry = zip.getEntry(name) ?: throw IllegalStateException("联动备份包缺少 $name。")
                val destination = BackupPathResolver.resolveWithin(workDir, name)
                    ?: throw IllegalStateException("联动备份包包含不安全路径。")
                val expectedSize = part.optLong("size", -1L)
                val expectedHash = part.optString("sha256")
                require(expectedSize >= 0L && expectedHash.matches(SHA256)) { "联动备份包校验信息不正确。" }

                zip.getInputStream(entry).use { input ->
                    FileOutputStream(destination).use { output -> input.copyTo(output) }
                }
                require(destination.length() == expectedSize && sha256(destination) == expectedHash) {
                    "联动备份包校验失败：$name 已损坏。"
                }
            }
            require(listedNames == REQUIRED_ENTRIES) { "联动备份包缺少必要文件。" }
            val entryNames = zip.entries().asSequence().map { it.name }.toSet()
            require(entryNames == listedNames + MANIFEST_ENTRY) { "联动备份包包含未清单化的文件。" }
            return manifest
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun newWorkDir(): File {
        return File(context.cacheDir, "companion-${UUID.randomUUID()}").also { directory ->
            check(directory.mkdirs()) { "无法创建联动备份临时目录。" }
        }
    }

    private data class ArchivePart(
        val name: String,
        val file: File,
    )

    private companion object {
        const val ARCHIVE_VERSION = 1
        const val BUFFER_SIZE = 8 * 1024
        const val MANIFEST_ENTRY = "companion_manifest.json"
        const val ORANGECHAT_ARCHIVE = "orangechat_backup.zip"
        const val OMBRE_ARCHIVE = "ombre_brain.zip"
        const val SUPABASE_ARCHIVE = "supabase.json"
        const val TERMUX_ARCHIVE = "termux_config.tar.gz"
        val REQUIRED_ENTRIES = setOf(ORANGECHAT_ARCHIVE, OMBRE_ARCHIVE, SUPABASE_ARCHIVE, TERMUX_ARCHIVE)
        val SHA256 = Regex("[a-f0-9]{64}")
    }
}
