/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal data class SupabaseBackupTarget(
    val baseUrl: String,
    val apiKey: String,
    val tableName: String,
)

data class SupabaseRestoreReport(
    val restoredTables: Int,
    val restoredRows: Int,
    val skippedTables: List<String>,
)

/** Exports the configured OrangeChat-managed tables through the normal PostgREST/RLS connection. */
internal class SupabaseBackupClient {
    suspend fun export(settings: Settings, destination: File): Int = withContext(Dispatchers.IO) {
        val tables = JSONArray()
        collectTargets(settings).forEach { target ->
            val rows = fetchAll(target)
            tables.put(
                JSONObject()
                    .put("base_url", target.baseUrl.trimEnd('/'))
                    .put("table", target.tableName)
                    .put("rows", rows),
            )
        }
        destination.writeText(
            JSONObject()
                .put("version", ARCHIVE_VERSION)
                .put("tables", tables)
                .toString(),
        )
        tables.length()
    }

    suspend fun restore(settings: Settings, source: File): SupabaseRestoreReport = withContext(Dispatchers.IO) {
        val archive = JSONObject(source.readText())
        require(archive.optInt("version") == ARCHIVE_VERSION) { "不支持的 Supabase 备份版本。" }

        var restoredTables = 0
        var restoredRows = 0
        val skippedTables = mutableListOf<String>()
        val configuredTargets = collectTargets(settings)
        val tables = archive.optJSONArray("tables") ?: JSONArray()
        for (index in 0 until tables.length()) {
            val tableArchive = tables.optJSONObject(index)
                ?: throw IllegalStateException("Supabase 备份表格式错误。")
            val baseUrl = tableArchive.optString("base_url").trimEnd('/')
            val tableName = tableArchive.optString("table")
            val rows = tableArchive.optJSONArray("rows") ?: JSONArray()
            val target = configuredTargets.firstOrNull {
                it.baseUrl.trimEnd('/') == baseUrl && it.tableName == tableName
            }
            if (target == null) {
                skippedTables += "$tableName（恢复后的橘瓣没有对应配置）"
                continue
            }
            if (!allRowsHaveId(rows)) {
                skippedTables += "$tableName（没有稳定的 id，已阻止可能重复的导入）"
                continue
            }
            upsertAll(target, rows)
            restoredTables++
            restoredRows += rows.length()
        }
        SupabaseRestoreReport(restoredTables, restoredRows, skippedTables)
    }

    internal fun collectTargets(settings: Settings): List<SupabaseBackupTarget> {
        val targets = buildList {
            settings.systemToolsSetting.takeIf {
                it.supabaseEnabled && it.supabaseUrl.isNotBlank() && it.supabaseApiKey.isNotBlank()
            }?.let {
                add(SupabaseBackupTarget(it.supabaseUrl, it.supabaseApiKey, it.supabaseTableName))
            }
            settings.externalMemories.filter { memory ->
                memory.enabled && memory.supabaseUrl.isNotBlank() && memory.supabaseKey.isNotBlank()
            }.forEach { memory ->
                if (memory.autoSaveMessages) {
                    add(SupabaseBackupTarget(memory.supabaseUrl, memory.supabaseKey, memory.tableName))
                }
                if (memory.autoSaveDiarySummary) {
                    add(SupabaseBackupTarget(memory.supabaseUrl, memory.supabaseKey, memory.summariesTableName))
                }
            }
        }
        return targets
            .onEach { validateTarget(it) }
            .distinctBy { "${it.baseUrl.trimEnd('/')}\n${it.tableName}" }
    }

    private fun fetchAll(target: SupabaseBackupTarget): JSONArray {
        val allRows = JSONArray()
        var offset = 0
        while (true) {
            val connection = open(target, "GET", "?select=*").apply {
                setRequestProperty("Range-Unit", "items")
                setRequestProperty("Range", "$offset-${offset + PAGE_SIZE - 1}")
            }
            val page = connection.useConnection { request ->
                requireSuccessful(request, target.tableName)
                JSONArray(request.inputStream.bufferedReader().use { it.readText() })
            }
            for (rowIndex in 0 until page.length()) {
                allRows.put(page.get(rowIndex))
            }
            if (page.length() < PAGE_SIZE) return allRows
            offset += PAGE_SIZE
        }
    }

    private fun upsertAll(target: SupabaseBackupTarget, rows: JSONArray) {
        for (start in 0 until rows.length() step UPSERT_BATCH_SIZE) {
            val batch = JSONArray()
            for (index in start until minOf(start + UPSERT_BATCH_SIZE, rows.length())) {
                batch.put(rows.get(index))
            }
            val connection = open(target, "POST", "?on_conflict=id").apply {
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
                doOutput = true
            }
            connection.useConnection { request ->
                request.outputStream.bufferedWriter().use { it.write(batch.toString()) }
                requireSuccessful(request, target.tableName)
                request.inputStream.close()
            }
        }
    }

    private fun allRowsHaveId(rows: JSONArray): Boolean {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: return false
            if (!row.has("id") || row.isNull("id")) return false
        }
        return true
    }

    private fun open(target: SupabaseBackupTarget, method: String, query: String): HttpURLConnection {
        return (URL("${target.baseUrl.trimEnd('/')}/rest/v1/${target.tableName}$query").openConnection()
            as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("apikey", target.apiKey)
            setRequestProperty("Authorization", "Bearer ${target.apiKey}")
            setRequestProperty("Accept", "application/json")
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
    }

    private fun validateTarget(target: SupabaseBackupTarget) {
        require(target.tableName.matches(TABLE_NAME)) { "Supabase 表名不安全：${target.tableName}" }
        require(target.baseUrl.startsWith("https://") || target.baseUrl.startsWith("http://")) {
            "Supabase 地址格式不正确。"
        }
    }

    private fun requireSuccessful(connection: HttpURLConnection, tableName: String) {
        val code = connection.responseCode
        if (code !in 200..299) {
            val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IllegalStateException("Supabase 表 $tableName 请求失败（HTTP $code）：${body.take(ERROR_BODY_LIMIT)}")
        }
    }

    private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T {
        return try {
            block(this)
        } finally {
            disconnect()
        }
    }

    private companion object {
        const val ARCHIVE_VERSION = 1
        const val PAGE_SIZE = 1_000
        const val UPSERT_BATCH_SIZE = 100
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 120_000
        const val ERROR_BODY_LIMIT = 300
        val TABLE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
