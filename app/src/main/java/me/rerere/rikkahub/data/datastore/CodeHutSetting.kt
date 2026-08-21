/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Work-only preferences. These references deliberately never duplicate the
 * daily chat provider configuration or an API key.
 */
@Serializable
data class CodeHutSetting(
    val executor: WorkExecutor = WorkExecutor.HARNESS,
    val defaultBindingId: Uuid? = null,
    val bindings: List<WorkModelBinding> = emptyList(),
    val approvalMode: CodeHutApprovalMode = CodeHutApprovalMode.ASK_EVERY_TIME,
    val approvalRevision: Long = 0,
    val helpApprovalExpiresAtEpochMillis: Long = 0,
)

/** A short-lived, revisioned privilege grant for the independently running Harness process. */
data class CodeHutApprovalLease(
    val mode: CodeHutApprovalMode,
    val revision: Long,
    val expiresAtEpochMillis: Long,
)

const val CODE_HUT_HELP_APPROVAL_LEASE_MILLIS: Long = 30 * 60 * 1_000L

fun CodeHutSetting.approvalLease(): CodeHutApprovalLease = CodeHutApprovalLease(
    mode = approvalMode,
    revision = approvalRevision,
    expiresAtEpochMillis = helpApprovalExpiresAtEpochMillis,
)

fun CodeHutSetting.activeApprovalMode(nowEpochMillis: Long = System.currentTimeMillis()): CodeHutApprovalMode =
    if (
        approvalMode == CodeHutApprovalMode.HELP_ME_APPROVE &&
        helpApprovalExpiresAtEpochMillis > nowEpochMillis
    ) {
        CodeHutApprovalMode.HELP_ME_APPROVE
    } else {
        CodeHutApprovalMode.ASK_EVERY_TIME
    }

@Serializable
enum class CodeHutApprovalMode {
    ASK_EVERY_TIME,
    HELP_ME_APPROVE,
}

@Serializable
enum class WorkExecutor {
    HARNESS,
}

@Serializable
enum class WorkProtocol {
    OPENAI_CHAT_COMPLETIONS,
    ANTHROPIC_MESSAGES,
    OPENAI_RESPONSES,
}

@Serializable
enum class WorkCapability {
    NEEDS_PROBE,
    EXECUTABLE,
    CONSULT_ONLY,
    FAILED,
}

@Serializable
data class WorkModelBinding(
    val id: Uuid = Uuid.random(),
    val providerId: Uuid,
    val modelId: Uuid,
    val protocol: WorkProtocol,
    val capability: WorkCapability = WorkCapability.NEEDS_PROBE,
    val failureReason: String = "",
)
