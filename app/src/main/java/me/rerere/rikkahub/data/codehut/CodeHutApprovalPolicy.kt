/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.codehut

import me.rerere.rikkahub.data.datastore.CodeHutApprovalMode
import me.rerere.rikkahub.data.datastore.CodeHutSetting

enum class HarnessRiskCategory {
    LOW_RISK,
    DELETE,
    OVERWRITE,
    BULK_MOVE,
    PACKAGE_INSTALL,
    CREDENTIAL,
    EXTERNAL_SUBMIT,
    GIT_PUSH_OR_RELEASE,
    PRIVILEGED,
    ANDROID_SYSTEM,
    HIGH_RISK_SHELL,
}

data class HarnessApprovalRequest(
    val taskKey: String,
    val action: String,
    val target: String,
    val reason: String,
    val category: HarnessRiskCategory,
)

sealed interface CodeHutApprovalDecision {
    data object AutoAllow : CodeHutApprovalDecision

    data class RequireConfirmation(
        val action: String,
        val target: String,
        val reason: String,
        val canAllowOnce: Boolean = true,
        val canAllowForTask: Boolean = true,
        val canDeny: Boolean = true,
    ) : CodeHutApprovalDecision
}

object CodeHutApprovalPolicy {
    fun decide(
        setting: CodeHutSetting,
        request: HarnessApprovalRequest,
    ): CodeHutApprovalDecision {
        if (setting.approvalMode == CodeHutApprovalMode.HELP_ME_APPROVE &&
            request.category == HarnessRiskCategory.LOW_RISK
        ) {
            return CodeHutApprovalDecision.AutoAllow
        }

        return CodeHutApprovalDecision.RequireConfirmation(
            action = request.action,
            target = request.target,
            reason = request.reason,
        )
    }
}
