/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */


package me.rerere.rikkahub.data.gadgetbridge

import java.time.LocalDate

/**
 * A metric that the connected wearable has actually exported. This is kept
 * separate from a missing reading: an unsupported metric must never be
 * represented as zero when it is passed to the AI.
 */
enum class HealthMetric(val id: String) {
    STEPS("steps"),
    HEART_RATE("heart_rate"),
    SLEEP("sleep"),
    SPO2("spo2"),
    STRESS("stress"),
    CALORIES("calories"),
}

/**
 * Metadata for the database snapshot that was used to build health data.
 * [databaseExportedAt] is the file modification time, not a claim that the
 * wearable measured every metric at that instant.
 */
data class HealthDataSourceInfo(
    val source: String = "Gadgetbridge",
    val manufacturer: String? = null,
    val databaseExportedAt: Long,
    val supportedMetrics: Set<HealthMetric>,
)

/**
 * A consistent health read made from one SQLite connection. It avoids mixing
 * values from separate database exports while the user is syncing their band.
 */
data class HealthSnapshot(
    val sourceInfo: HealthDataSourceInfo,
    val latestActivity: ActivitySample?,
    val dailySummaries: List<DailySummary>,
    val sleepSummaries: List<SleepSummary>,
    val latestSpo2: Int?,
    val latestStress: Int?,
)

data class DailySummary(
    val timestamp: Long,
    val date: LocalDate,
    val steps: Int,
    val hrResting: Int?,
    val hrMax: Int?,
    val hrMin: Int?,
    val hrAvg: Int?,
    val stressAvg: Int?,
    val calories: Int?,
    val spo2Avg: Int?,
)

data class ActivitySample(
    val timestamp: Long,
    val heartRate: Int?,
    val steps: Int?,
    val stress: Int?,
    val spo2: Int?,
    val rawIntensity: Int?,
)

/** Mi Band 5 exports activity timestamps in epoch seconds. */
internal object MiBand5HealthMapper {
    fun mapActivitySample(
        timestampSeconds: Long,
        heartRate: Int?,
        steps: Int?,
        rawIntensity: Int?,
    ): ActivitySample = ActivitySample(
        timestamp = timestampSeconds * 1000L,
        heartRate = heartRate,
        steps = steps,
        // RAW_INTENSITY is movement intensity, not the user's stress level.
        stress = null,
        // Mi Band 5 does not export a blood-oxygen reading through this table.
        spo2 = null,
        rawIntensity = rawIntensity,
    )
}

data class SleepSummary(
    val timestamp: Long,      // 入睡时间（毫秒时间戳）
    val wakeupTime: Long,     // 醒来时间（毫秒时间戳）
    val totalDuration: Int,   // 总时长（分钟）
    val deepSleep: Int,       // 深睡时长（分钟）
    val lightSleep: Int,      // 浅睡时长（分钟）
    val remSleep: Int,        // REM时长（分钟）
    val awakeDuration: Int,   // 清醒时长（分钟）
    val isAwake: Boolean,     // 是否只是短暂清醒（IS_AWAKE=1）
) {
    val isNap: Boolean get() = isAwake && deepSleep == 0 && lightSleep == 0 && remSleep == 0
}

data class HealthUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val dbFileExists: Boolean = true,
    val sourceInfo: HealthDataSourceInfo? = null,
    val currentHeartRate: Int? = null,
    val dailySummaries7: List<DailySummary> = emptyList(),
    val dailySummaries30: List<DailySummary> = emptyList(),
    val sleepSummaries: List<SleepSummary> = emptyList(),
    val latestSpo2: Int? = null,
    val latestStress: Int? = null,
    val todaySteps: Int = 0,
    val todayCalories: Int? = null,
    val stepsRange: StepsRange = StepsRange.SEVEN_DAYS,
)

enum class StepsRange {
    SEVEN_DAYS,
    THIRTY_DAYS,
}
