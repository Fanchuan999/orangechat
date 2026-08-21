/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.codehut

import me.rerere.rikkahub.data.datastore.CodeHutApprovalMode
import me.rerere.rikkahub.data.datastore.CodeHutSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHutApprovalPolicyTest {
    @Test
    fun helpMeApproveAutoAllowsLowRiskActions() {
        val decision = CodeHutApprovalPolicy.decide(
            setting = CodeHutSetting(approvalMode = CodeHutApprovalMode.HELP_ME_APPROVE),
            request = HarnessApprovalRequest(
                taskKey = "task-1",
                action = "build",
                target = "./gradlew test",
                reason = "普通构建与测试",
                category = HarnessRiskCategory.LOW_RISK,
            ),
        )

        assertEquals(CodeHutApprovalDecision.AutoAllow, decision)
    }

    @Test
    fun helpMeApproveStillBlocksDangerousActions() {
        val decision = CodeHutApprovalPolicy.decide(
            setting = CodeHutSetting(approvalMode = CodeHutApprovalMode.HELP_ME_APPROVE),
            request = HarnessApprovalRequest(
                taskKey = "task-2",
                action = "push",
                target = "origin/main",
                reason = "会把改动提交到外部仓库",
                category = HarnessRiskCategory.GIT_PUSH_OR_RELEASE,
            ),
        )

        assertTrue(decision is CodeHutApprovalDecision.RequireConfirmation)
        val confirmation = decision as CodeHutApprovalDecision.RequireConfirmation
        assertEquals("push", confirmation.action)
        assertEquals("origin/main", confirmation.target)
        assertEquals("会把改动提交到外部仓库", confirmation.reason)
        assertEquals(true, confirmation.canAllowForTask)
    }

    @Test
    fun askEveryTimeRemainsTheDefaultFallback() {
        val decision = CodeHutApprovalPolicy.decide(
            setting = CodeHutSetting(),
            request = HarnessApprovalRequest(
                taskKey = "task-3",
                action = "write",
                target = "notes.txt",
                reason = "即使是低风险操作，默认策略也不能扩大权限",
                category = HarnessRiskCategory.LOW_RISK,
            ),
        )

        assertTrue(decision is CodeHutApprovalDecision.RequireConfirmation)
    }
}
