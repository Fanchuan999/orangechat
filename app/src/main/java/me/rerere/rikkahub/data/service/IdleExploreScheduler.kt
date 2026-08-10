/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.core.context.GlobalContext

object IdleExploreScheduler {
    private const val TAG = "IdleExploreScheduler"
    private const val UNIQUE_WORK_NAME = "idle_explore_work"
    private const val PREFS_NAME = "idle_explore_state"

    fun sync(context: Context, setting: ProactiveMessageSetting) {
        if (setting.idleExploreEnabled) scheduleNext(context, setting, replace = true) else cancel(context)
    }

    fun scheduleNext(context: Context, setting: ProactiveMessageSetting, replace: Boolean = true) {
        if (!setting.idleExploreEnabled) {
            cancel(context)
            return
        }
        val now = System.currentTimeMillis()
        val next = IdleExplorePolicy.nextWindow(
            setting = setting,
            state = readState(context),
            nowMillis = now,
            jitterMinutes = Random.nextInt(0, 121),
        ) ?: IdleExplorePolicy.nextMorning(now)
        val delayMillis = max(60_000L, next - now)
        val request = OneTimeWorkRequestBuilder<IdleExploreWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
        Log.i(TAG, "Scheduled idle exploration opportunity in ${delayMillis / 60_000L} minutes")
    }

    fun markOpportunity(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        val previous = readState(context)
        val dayKey = IdleExplorePolicy.dayKey(nowMillis)
        val runs = if (previous.dayKey == dayKey) previous.runsToday + 1 else 1
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("day_key", dayKey)
            .putInt("runs_today", runs)
            .putLong("last_run_at", nowMillis)
            .apply()
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    private fun readState(context: Context): IdleExploreState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return IdleExploreState(
            dayKey = prefs.getString("day_key", "").orEmpty(),
            runsToday = prefs.getInt("runs_today", 0),
            lastRunAtMillis = prefs.getLong("last_run_at", 0L),
        )
    }
}

class IdleExploreWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val setting = GlobalContext.get().get<SettingsStore>()
            .settingsFlow.first().proactiveMessageSetting
        if (!setting.idleExploreEnabled) return Result.success()

        if (NightWatchManager.isArmed(applicationContext)) {
            IdleExploreScheduler.scheduleNext(applicationContext, setting, replace = false)
            return Result.success()
        }

        IdleExploreScheduler.markOpportunity(applicationContext)
        val intent = Intent(applicationContext, ProactiveMessageTriggerService::class.java).apply {
            putExtra(ProactiveMessageTriggerService.EXTRA_IDLE_EXPLORE_TRIGGER, true)
            putExtra(ProactiveMessageTriggerService.EXTRA_FORCE_TRIGGER, true)
        }
        runCatching { ContextCompat.startForegroundService(applicationContext, intent) }
            .onFailure { Log.e("IdleExploreWorker", "Unable to start exploration", it) }
        IdleExploreScheduler.scheduleNext(applicationContext, setting, replace = false)
        return Result.success()
    }
}
