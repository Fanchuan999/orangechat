/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Minimal client for Ombre-Brain's password-protected local export and migration APIs. */
internal enum class OmbreDashboardAuthAction {
    Setup,
    Login,
}

/** Older Ombre versions did not return this field, so they keep the existing login flow. */
internal fun ombreDashboardAuthAction(statusBody: String): OmbreDashboardAuthAction =
    if (SETUP_NEEDED_PATTERN.containsMatchIn(statusBody)) {
        OmbreDashboardAuthAction.Setup
    } else {
        OmbreDashboardAuthAction.Login
    }

private val SETUP_NEEDED_PATTERN = Regex("\\\"setup_needed\\\"\\s*:\\s*true")

internal class OmbreBackupClient(
    baseUrl: String,
) {
    private val baseUrl = normalizeBaseUrl(baseUrl)

    suspend fun export(password: CharArray, destination: File) = withContext(Dispatchers.IO) {
        val sessionCookie = establishSession(password)
        val connection = open("/api/export", "GET", sessionCookie)
        connection.useConnection { request ->
            requireSuccessful(request)
            request.inputStream.use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
        }
        require(destination.length() > 0L) { "Ombre 导出的备份为空。" }
    }

    suspend fun restore(password: CharArray, backupFile: File) = withContext(Dispatchers.IO) {
        require(backupFile.exists() && backupFile.length() > 0L) { "Ombre 备份文件不存在或为空。" }

        val sessionCookie = establishSession(password)
        val upload = open("/api/migrate/upload", "POST", sessionCookie).apply {
            setRequestProperty("Content-Type", "application/zip")
            doOutput = true
        }
        val uploadResponse = upload.useConnection { request ->
            backupFile.inputStream().use { source -> request.outputStream.use { target -> source.copyTo(target) } }
            requireSuccessful(request)
            request.inputStream.bufferedReader().use { it.readText() }
        }
        val jobId = JSONObject(uploadResponse).optString("job_id")
        require(jobId.isNotBlank()) { "Ombre 没有返回迁移任务编号。" }

        val apply = open("/api/migrate/apply", "POST", sessionCookie).apply {
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        apply.useConnection { request ->
            request.outputStream.bufferedWriter().use { writer ->
                writer.write(JSONObject().put("job_id", jobId).put("decisions", JSONObject()).toString())
            }
            requireSuccessful(request)
            request.inputStream.close()
        }
    }

    private fun establishSession(password: CharArray): String {
        require(password.isNotEmpty()) { "请输入 Ombre Dashboard 密码。首次使用可在这里设置一个至少 6 位的密码。" }
        return when (dashboardAuthAction()) {
            OmbreDashboardAuthAction.Setup -> {
                require(password.size >= MINIMUM_PASSWORD_LENGTH) {
                    "Ombre 首次设置密码至少需要 $MINIMUM_PASSWORD_LENGTH 位。"
                }
                authenticate("/auth/setup", password)
            }
            OmbreDashboardAuthAction.Login -> authenticate("/auth/login", password)
        }
    }

    private fun dashboardAuthAction(): OmbreDashboardAuthAction {
        val connection = open("/auth/status", "GET", null)
        return connection.useConnection { request ->
            when (val code = request.responseCode) {
                in 200..299 -> ombreDashboardAuthAction(request.inputStream.bufferedReader().use { it.readText() })
                404, 405 -> OmbreDashboardAuthAction.Login
                else -> throw requestFailure(code, request)
            }
        }
    }

    private fun authenticate(path: String, password: CharArray): String {
        val connection = open(path, "POST", null).apply {
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        return connection.useConnection { request ->
            request.outputStream.bufferedWriter().use { writer ->
                writer.write(JSONObject().put("password", password.concatToString()).toString())
            }
            requireSuccessful(request)
            request.inputStream.close()
            request.headerFields.entries
                .firstOrNull { (key, _) -> key.equals("Set-Cookie", ignoreCase = true) }
                ?.value
                ?.firstOrNull()
                ?.substringBefore(';')
                ?.takeIf { it.startsWith("ombre_session=") }
                ?: throw IllegalStateException("Ombre 登录未返回会话 Cookie。")
        }
    }

    private fun open(path: String, method: String, sessionCookie: String?): HttpURLConnection {
        return (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            sessionCookie?.let { setRequestProperty("Cookie", it) }
            setRequestProperty("Accept", "application/json")
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
    }

    private fun requireSuccessful(connection: HttpURLConnection) {
        val code = connection.responseCode
        if (code !in 200..299) {
            throw requestFailure(code, connection)
        }
    }

    private fun requestFailure(code: Int, connection: HttpURLConnection): IllegalStateException {
        val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return IllegalStateException("Ombre 请求失败（HTTP $code）：${body.take(ERROR_BODY_LIMIT)}")
    }

    private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T {
        return try {
            block(this)
        } finally {
            disconnect()
        }
    }

    private fun normalizeBaseUrl(value: String): String {
        val normalized = value.trim().trimEnd('/')
        val uri = runCatching { URI(normalized) }.getOrNull()
            ?: throw IllegalArgumentException("Ombre 地址格式不正确。")
        require(uri.scheme in setOf("http", "https") && uri.port != -1) { "Ombre 地址必须包含协议和端口。" }
        require(uri.host in setOf("127.0.0.1", "localhost", "::1")) {
            "为保护 Ombre 密码，联动备份仅允许本机回环地址。"
        }
        return normalized
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 120_000
        const val ERROR_BODY_LIMIT = 300
        const val MINIMUM_PASSWORD_LENGTH = 6
    }
}
