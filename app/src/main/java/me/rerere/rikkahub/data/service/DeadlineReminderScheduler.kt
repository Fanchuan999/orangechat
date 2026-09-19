package me.rerere.rikkahub.data.service

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.CompanionSpaceSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.core.context.GlobalContext

object DeadlineReminderScheduler {
    private const val WORK_TAG = "companion_deadline_reminder"
    private const val WORK_NAME_PREFIX = "companion_deadline_reminder_"

    fun sync(
        context: Context,
        setting: CompanionSpaceSetting,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWorkByTag(WORK_TAG)
        val reminders = DeadlineReminderPlanner.planAll(
            setting = setting,
            nowMillis = nowMillis,
            zone = zone,
        )
        reminders.forEach { reminder ->
            val delayMillis = max(0L, reminder.triggerAtEpochMillis - nowMillis)
            val request = OneTimeWorkRequestBuilder<DeadlineReminderWorker>()
                .setInputData(DeadlineReminderWorker.inputData(reminder))
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .addTag(WORK_TAG)
                .build()
            workManager.enqueueUniqueWork(
                workName(reminder.deadlineId, reminder.offsetDays),
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }

    suspend fun syncFromStore(
        context: Context,
        settingsStore: SettingsStore = GlobalContext.get().get(),
    ) {
        val setting = settingsStore.settingsFlow.first().companionSpaceSetting
        sync(context, setting)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
    }

    internal fun workName(deadlineId: String, offsetDays: Int): String =
        "$WORK_NAME_PREFIX${deadlineId}_$offsetDays"
}
