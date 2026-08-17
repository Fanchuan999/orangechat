/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync.companion

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal enum class HarnessRecoveryDecision {
    DISABLED,
    MANUALLY_STOPPED,
    ALREADY_RUNNING,
    RECOVER,
}

internal fun decideHarnessRecovery(
    autoKeepRunning: Boolean,
    manuallyStopped: Boolean,
    running: Boolean,
): HarnessRecoveryDecision = when {
    !autoKeepRunning -> HarnessRecoveryDecision.DISABLED
    manuallyStopped -> HarnessRecoveryDecision.MANUALLY_STOPPED
    running -> HarnessRecoveryDecision.ALREADY_RUNNING
    else -> HarnessRecoveryDecision.RECOVER
}

internal enum class HarnessRecoveryRunResult {
    SUCCESS,
    RETRY,
}

internal suspend fun executeHarnessRecovery(
    recover: suspend () -> Boolean,
): HarnessRecoveryRunResult = runCatching { recover() }.fold(
    onSuccess = { HarnessRecoveryRunResult.SUCCESS },
    onFailure = { HarnessRecoveryRunResult.RETRY },
)

class HarnessRecoveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    private val harnessManager: HarnessManager by inject()

    override suspend fun doWork(): Result = when (
        executeHarnessRecovery { harnessManager.recoverIfNeeded() }
    ) {
        HarnessRecoveryRunResult.SUCCESS -> Result.success()
        HarnessRecoveryRunResult.RETRY -> Result.retry()
    }
}
