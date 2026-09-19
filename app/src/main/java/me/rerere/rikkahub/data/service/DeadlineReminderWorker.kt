package me.rerere.rikkahub.data.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.COMPANION_DEADLINE_REMINDER_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.datastore.CompanionDeadline
import me.rerere.rikkahub.data.datastore.DeadlineStatus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.markReminderDelivered
import me.rerere.rikkahub.data.datastore.withDeadline
import me.rerere.rikkahub.utils.sendNotification
import org.koin.core.context.GlobalContext

class DeadlineReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val deadlineId = inputData.getString(KEY_DEADLINE_ID) ?: return Result.success()
        val offsetDays = inputData.getInt(KEY_OFFSET_DAYS, -1)
        if (offsetDays <= 0) return Result.success()

        val store = GlobalContext.get().get<SettingsStore>()
        val deadline = store.settingsFlow.first().companionSpaceSetting.deadlines
            .firstOrNull { it.id.toString() == deadlineId }
            ?: return Result.success()
        val setting = store.settingsFlow.first().companionSpaceSetting
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val due = Instant.ofEpochMilli(deadline.dueAtEpochMillis).atZone(zone)
        val eligible = setting.deadlineRemindersEnabled &&
            deadline.remindersEnabled &&
            deadline.status !in setOf(DeadlineStatus.DONE, DeadlineStatus.CANCELLED) &&
            offsetDays !in deadline.disabledReminderOffsets &&
            offsetDays !in deadline.deliveredReminderOffsets &&
            now < deadline.dueAtEpochMillis
        if (!eligible) return Result.success()

        val claimed = claim(store, deadline, offsetDays)
        if (!claimed) return Result.success()

        val intent = Intent(applicationContext, RouteActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId(deadlineId, offsetDays),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val sent = applicationContext.sendNotification(
            channelId = COMPANION_DEADLINE_REMINDER_NOTIFICATION_CHANNEL_ID,
            notificationId = notificationId(deadlineId, offsetDays),
        ) {
            title = "截止日期提醒：${deadline.title}"
            content = "还有 $offsetDays 天 · 截止 ${due.format(DATE_FORMATTER)}"
            autoCancel = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_REMINDER
            contentIntent = pendingIntent
            useBigTextStyle = true
        }
        if (!sent) {
            restoreClaim(store, deadline, offsetDays)
            return Result.success()
        }

        DeadlineReminderScheduler.syncFromStore(applicationContext, store)
        return Result.success()
    }

    private suspend fun restoreClaim(store: SettingsStore, deadline: CompanionDeadline, offsetDays: Int) {
        store.update { settings ->
            val current = settings.companionSpaceSetting.deadlines.firstOrNull { it.id == deadline.id }
                ?: return@update settings
            settings.copy(
                companionSpaceSetting = settings.companionSpaceSetting.withDeadline(
                    current.copy(deliveredReminderOffsets = current.deliveredReminderOffsets - offsetDays),
                ),
            )
        }
    }

    private suspend fun claim(store: SettingsStore, deadline: CompanionDeadline, offsetDays: Int): Boolean {
        var claimed = false
        store.update { settings ->
            val current = settings.companionSpaceSetting.deadlines.firstOrNull { it.id == deadline.id }
            if (current == null || offsetDays in current.deliveredReminderOffsets) {
                settings
            } else {
                claimed = true
                settings.copy(
                    companionSpaceSetting = settings.companionSpaceSetting.withDeadline(
                        current.markReminderDelivered(offsetDays),
                    ),
                )
            }
        }
        return claimed
    }

    companion object {
        const val KEY_DEADLINE_ID = "deadline_id"
        const val KEY_OFFSET_DAYS = "offset_days"

        fun inputData(reminder: DeadlineReminder): Data = Data.Builder()
            .putString(KEY_DEADLINE_ID, reminder.deadlineId)
            .putInt(KEY_OFFSET_DAYS, reminder.offsetDays)
            .build()

        private fun notificationId(deadlineId: String, offsetDays: Int): Int =
            (deadlineId.hashCode() * 31 + offsetDays).and(0x7fffffff)

        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.getDefault())
    }
}
