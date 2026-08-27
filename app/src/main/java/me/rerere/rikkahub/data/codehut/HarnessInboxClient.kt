/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.codehut

import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface HarnessInboxLoadResult {
    data class Available(val tasks: List<HarnessInboxTask>) : HarnessInboxLoadResult
    data class Unavailable(val message: String) : HarnessInboxLoadResult
    data class Failure(val message: String) : HarnessInboxLoadResult
}

sealed interface HarnessImageSendResult {
    data object Sent : HarnessImageSendResult
    data class Failure(val message: String) : HarnessImageSendResult
}

class HarnessInboxClient(
    upstreamClient: OkHttpClient,
    private val parser: HarnessInboxParser = HarnessInboxParser(),
    private val baseUrl: String = BASE_URL,
) {
    private val client = buildCodeHutUpstreamClient(upstreamClient).newBuilder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    init {
        require(baseUrl == BASE_URL) { "Harness inbox must remain loopback-only" }
    }

    suspend fun loadActiveTasks(): HarnessInboxLoadResult = withContext(Dispatchers.IO) {
        runCatching {
            val sessions = rpc("session.list", buildJsonObject { })
            val inbox = sessions.itemsOrEmpty().mapNotNull { item ->
                runCatching { item.jsonObject }.getOrNull()
            }.firstOrNull { item ->
                item["projections"]?.jsonObject
                    ?.get("values")?.jsonObject
                    ?.get("title")?.jsonPrimitive?.contentOrNull == INBOX_TITLE
            } ?: return@withContext HarnessInboxLoadResult.Unavailable("暂时没有找到 Daddy 收件箱。")
            val sessionId = inbox["sessionId"]?.jsonPrimitive?.contentOrNull
                ?: return@withContext HarnessInboxLoadResult.Failure("暂时无法读取任务状态，请在工作台查看。")
            val history = rpc(
                "session.history",
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("maxMessages", 200)
                },
            )
            val events = history["events"]?.jsonArray.orEmpty().mapNotNull(::toInboxEvent)
            HarnessInboxLoadResult.Available(parser.parse(events))
        }.getOrElse {
            HarnessInboxLoadResult.Failure(
                redactCodeHutUiText("暂时无法读取任务状态，请在工作台查看。"),
            )
        }
    }

    suspend fun sendImageToInbox(attachment: HarnessImageAttachment): HarnessImageSendResult = withContext(Dispatchers.IO) {
        runCatching {
            val info = HarnessImagePolicy.validate(attachment.mediaType, attachment.bytes.size)
            val sessions = rpc("session.list", buildJsonObject { })
            val inbox = sessions.itemsOrEmpty().mapNotNull { item ->
                runCatching { item.jsonObject }.getOrNull()
            }.firstOrNull { item ->
                item["projections"]?.jsonObject
                    ?.get("values")?.jsonObject
                    ?.get("title")?.jsonPrimitive?.contentOrNull == INBOX_TITLE
            } ?: return@withContext HarnessImageSendResult.Failure("暂时没有找到 Daddy 收件箱。")
            val sessionId = inbox["sessionId"]?.jsonPrimitive?.contentOrNull
                ?: return@withContext HarnessImageSendResult.Failure("暂时无法发送图片，请在工作台查看。")
            rpc(
                "session.prompt",
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("mode", "queue")
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", "【Daddy图片】用户上传了一张图片，请仅在需要时结合图片完成当前任务。") })
                        add(buildJsonObject {
                            put("type", "image")
                            put("mediaType", info.mediaType)
                            put("data", android.util.Base64.encodeToString(attachment.bytes, android.util.Base64.NO_WRAP))
                        })
                    }
                },
            )
            HarnessImageSendResult.Sent
        }.getOrElse {
            HarnessImageSendResult.Failure("暂时无法发送图片，请在工作台查看。")
        }
    }

    private fun rpc(method: String, payload: JsonObject): JsonObject {
        val body = buildJsonObject {
            put("type", "client-request")
            put("rpcId", "daddy-inbox-${UUID.randomUUID()}")
            put("method", method)
            put("payload", payload)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$baseUrl/api/$method")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Harness request failed")
            val parsed = json.parseToJsonElement(response.body.string()).jsonObject
            val result = parsed["result"]?.jsonObject ?: error("Harness response missing result")
            if (result["ok"]?.jsonPrimitive?.contentOrNull != "true") error("Harness response was not ok")
            return result["value"]?.jsonObject ?: error("Harness response missing value")
        }
    }

    private fun JsonObject.itemsOrEmpty(): JsonArray = this["items"]?.jsonArray ?: JsonArray(emptyList())

    private fun toInboxEvent(element: JsonElement): HarnessInboxEvent? {
        val event = element.jsonObject["event"]?.jsonObject ?: element.jsonObject
        val type = event["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val text = event["data"]?.jsonObject
            ?.get("content")?.jsonArray
            ?.mapNotNull { item ->
                item.jsonObject.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                    ?.get("text")?.jsonPrimitive?.contentOrNull
            }?.joinToString(separator = "")
            .orEmpty()
        return HarnessInboxEvent(type = type, text = text)
    }

    private companion object {
        const val BASE_URL = "http://127.0.0.1:3080"
        const val INBOX_TITLE = "📥 Daddy收件箱"
        const val TIMEOUT_SECONDS = 5L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val json = Json { ignoreUnknownKeys = true }
    }
}
