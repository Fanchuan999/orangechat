/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync.companion

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import me.rerere.rikkahub.data.datastore.HarnessSetting

object HarnessRecoveryScheduler {
    internal const val UNIQUE_WORK_NAME = "daddy_harness_recovery"

    fun sync(context: Context, setting: HarnessSetting) {
        val workManager = WorkManager.getInstance(context)
        val shouldSchedule = setting.installedVersion.isNotBlank() &&
            setting.autoKeepRunning &&
            !setting.manuallyStopped
        if (!shouldSchedule) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<HarnessRecoveryWorker>(15, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
