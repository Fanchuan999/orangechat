/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.rikkahub.data.gadgetbridge.HealthDataSourceInfo
import me.rerere.rikkahub.data.gadgetbridge.HealthMetric
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.gadgetbridge.GadgetbridgeReader

fun createGadgetbridgeTool(customPath: String = ""): Tool = Tool(
    name = "get_gadgetbridge_data",
    needsApproval = true,
    description = "Get health and fitness data from Gadgetbridge (wearable device companion app). " +
        "Returns only metrics actually exported by the connected wearable, with source and freshness metadata. " +
        "Unavailable readings are returned as null, never as zero. " +
        "Reads from Gadgetbridge's auto-exported database. " +
        "Requires storage permission and Gadgetbridge auto-export to be enabled.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("data_type") {
                    put("type", "string")
                    put(
                        "description",
                        "Type of health data to retrieve: 'all' (default), 'steps', " +
                            "'heart_rate', 'sleep', 'daily_summary'"
                    )
                    put("enum", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("all"))
                        add(kotlinx.serialization.json.JsonPrimitive("steps"))
                        add(kotlinx.serialization.json.JsonPrimitive("heart_rate"))
                        add(kotlinx.serialization.json.JsonPrimitive("sleep"))
                        add(kotlinx.serialization.json.JsonPrimitive("daily_summary"))
                    })
                }
                putJsonObject("days") {
                    put("type", "integer")
                    put("description", "Number of recent calendar days to return, from 1 to 30. Defaults to 7.")
                    put("minimum", 1)
                    put("maximum", 30)
                }
            }
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val dataType = params["data_type"]?.jsonPrimitive?.content ?: "all"
        val days = params["days"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 30) ?: 7

        try {
            if (!GadgetbridgeReader.dbFileExists(customPath)) {
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put(
                            "error",
                            "Gadgetbridge database not found. Please enable auto-export in Gadgetbridge settings. " +
                                "Expected path: /sdcard/Download/手环/Gadgetbridge.db"
                        )
                    }.toString()
                ))
            }

            val snapshot = GadgetbridgeReader.readHealthSnapshot(
                summaryDays = days,
                sleepDays = minOf(days, 7),
                customPath = customPath,
            ) ?: return@Tool listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put(
                        "error",
                        "Unable to read a consistent Gadgetbridge database snapshot. Please synchronize the band, " +
                            "wait for export to finish, and try again."
                    )
                }.toString()
            ))
            val summaries = snapshot.dailySummaries
            val latest = snapshot.latestActivity
            val sleepSummaries = snapshot.sleepSummaries
            val today = summaries.lastOrNull()

            val result = when (dataType) {
                "steps" -> {
                    buildJsonObject {
                        put("success", true)
                        put("data_type", "steps")
                        put("days", days)
                        putNullableInt("today_steps", today?.steps)
                        put("daily_summaries", kotlinx.serialization.json.buildJsonArray {
                            summaries.forEach { s ->
                                add(buildJsonObject {
                                    put("date", s.date.toString())
                                    put("steps", s.steps)
                                    putNullableInt("calories", s.calories)
                                })
                            }
                        })
                        putSourceInfo(snapshot.sourceInfo)
                    }.toString()
                }
                "heart_rate" -> {
                    buildJsonObject {
                        put("success", true)
                        put("data_type", "heart_rate")
                        put("days", days)
                        putNullableInt("current_heart_rate", latest?.heartRate)
                        putNullableInstant(
                            "current_heart_rate_measured_at",
                            latest?.takeIf { it.heartRate != null }?.timestamp,
                        )
                        put("daily_summaries", kotlinx.serialization.json.buildJsonArray {
                            summaries.forEach { s ->
                                add(buildJsonObject {
                                    put("date", s.date.toString())
                                    putNullableInt("hr_max", s.hrMax)
                                    putNullableInt("hr_min", s.hrMin)
                                    putNullableInt("hr_avg", s.hrAvg)
                                    putNullableInt("hr_resting", s.hrResting)
                                })
                            }
                        })
                        putSourceInfo(snapshot.sourceInfo)
                    }.toString()
                }
                "sleep" -> {
                    buildJsonObject {
                        put("success", true)
                        put("data_type", "sleep")
                        put("days", minOf(days, 7))
                        put("recent_sleep_list", buildSleepSessionList(sleepSummaries))
                        putSourceInfo(snapshot.sourceInfo)
                    }.toString()
                }
                "daily_summary" -> {
                    buildJsonObject {
                        put("success", true)
                        put("data_type", "daily_summary")
                        put("days", days)
                        put("summaries", kotlinx.serialization.json.buildJsonArray {
                            summaries.forEach { s ->
                                add(buildJsonObject {
                                    put("date", s.date.toString())
                                    put("steps", s.steps)
                                    putNullableInt("calories", s.calories)
                                    putNullableInt("hr_max", s.hrMax)
                                    putNullableInt("hr_min", s.hrMin)
                                    putNullableInt("hr_avg", s.hrAvg)
                                    putNullableInt("hr_resting", s.hrResting)
                                    putNullableInt("stress_avg", s.stressAvg)
                                    putNullableInt("spo2_avg", s.spo2Avg)
                                })
                            }
                        })
                        putSourceInfo(snapshot.sourceInfo)
                    }.toString()
                }
                else -> {
                    // "all" - return a compact, trustworthy health context.
                    buildJsonObject {
                        put("success", true)
                        put("data_type", "all")
                        put("days", days)
                        putNullableInt("current_heart_rate", latest?.heartRate)
                        putNullableInstant(
                            "current_heart_rate_measured_at",
                            latest?.takeIf { it.heartRate != null }?.timestamp,
                        )
                        putNullableInt("current_spo2", snapshot.latestSpo2)
                        putNullableInt("current_stress", snapshot.latestStress)
                        putNullableInt("today_steps", today?.steps)
                        putNullableInt("today_calories", today?.calories)
                        put("recent_sleep_list", buildSleepSessionList(sleepSummaries))
                        put("daily_summaries", kotlinx.serialization.json.buildJsonArray {
                            summaries.forEach { s ->
                                add(buildJsonObject {
                                    put("date", s.date.toString())
                                    put("steps", s.steps)
                                    putNullableInt("calories", s.calories)
                                    putNullableInt("hr_max", s.hrMax)
                                    putNullableInt("hr_min", s.hrMin)
                                    putNullableInt("hr_avg", s.hrAvg)
                                    putNullableInt("stress_avg", s.stressAvg)
                                    putNullableInt("spo2_avg", s.spo2Avg)
                                })
                            }
                        })
                        putSourceInfo(snapshot.sourceInfo)
                    }.toString()
                }
            }

            listOf(UIMessagePart.Text(result))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", e.message ?: "Unknown error reading Gadgetbridge data")
                }.toString()
            ))
        }
    }
)

/**
 * 将睡眠摘要列表构建为 JSON 数组。
 * 每条包含：type（"nap" 或 "sleep"）、start、end、total_minutes、duration_text，
 * 非小憩额外加 deep_sleep_minutes、light_sleep_minutes、rem_sleep_minutes。
 */
private fun buildSleepSessionList(
    sleepSummaries: List<me.rerere.rikkahub.data.gadgetbridge.SleepSummary>,
): kotlinx.serialization.json.JsonArray {
    val sdf = SimpleDateFormat("M/d HH:mm", Locale.getDefault())
    return kotlinx.serialization.json.buildJsonArray {
        sleepSummaries.forEach { summary ->
            add(buildJsonObject {
                put("type", if (summary.isNap) "nap" else "sleep")
                put("start", sdf.format(Date(summary.timestamp)))
                put("end", sdf.format(Date(summary.wakeupTime)))
                put("start_at", Instant.ofEpochMilli(summary.timestamp).toString())
                put("end_at", Instant.ofEpochMilli(summary.wakeupTime).toString())
                put("total_minutes", summary.totalDuration)
                val hours = summary.totalDuration / 60
                val mins = summary.totalDuration % 60
                put("duration_text", "${hours}h ${mins}min")
                if (!summary.isNap) {
                    put("deep_sleep_minutes", summary.deepSleep)
                    put("light_sleep_minutes", summary.lightSleep)
                    put("rem_sleep_minutes", summary.remSleep)
                }
            })
        }
    }
}

private fun JsonObjectBuilder.putNullableInt(key: String, value: Int?) {
    if (value == null) put(key, JsonNull) else put(key, value)
}

private fun JsonObjectBuilder.putNullableInstant(key: String, epochMillis: Long?) {
    if (epochMillis == null) put(key, JsonNull) else put(key, Instant.ofEpochMilli(epochMillis).toString())
}

private fun JsonObjectBuilder.putSourceInfo(sourceInfo: HealthDataSourceInfo) {
    put("source", buildSourceInfo(sourceInfo))
}

private fun buildSourceInfo(sourceInfo: HealthDataSourceInfo): JsonObject = buildJsonObject {
    put("name", sourceInfo.source)
    if (sourceInfo.manufacturer == null) put("manufacturer", JsonNull) else put("manufacturer", sourceInfo.manufacturer)
    put("database_exported_at", Instant.ofEpochMilli(sourceInfo.databaseExportedAt).toString())
    val ageMinutes = ((System.currentTimeMillis() - sourceInfo.databaseExportedAt).coerceAtLeast(0L) / 60_000L)
    put("database_age_minutes", ageMinutes)
    put("database_may_be_stale", ageMinutes >= 6 * 60)
    put("supported_metrics", kotlinx.serialization.json.buildJsonArray {
        sourceInfo.supportedMetrics.sortedBy(HealthMetric::id).forEach { add(it.id) }
    })
    put("not_exported_metrics", kotlinx.serialization.json.buildJsonArray {
        HealthMetric.entries
            .filterNot(sourceInfo.supportedMetrics::contains)
            .forEach { add(it.id) }
    })
}
