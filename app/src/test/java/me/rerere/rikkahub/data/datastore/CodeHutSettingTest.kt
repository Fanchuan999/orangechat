/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class CodeHutSettingTest {
    @Test
    fun defaultsUseHarnessWithoutSelectingOrPersistingAProvider() {
        val setting = CodeHutSetting()

        assertEquals(WorkExecutor.HARNESS, setting.executor)
        assertNull(setting.defaultBindingId)
        assertEquals(emptyList<WorkModelBinding>(), setting.bindings)
        assertEquals(CodeHutApprovalMode.ASK_EVERY_TIME, setting.approvalMode)
    }

    @Test
    fun bindingMetadataSurvivesJsonRoundTripWithoutCredentials() {
        val expected = CodeHutSetting(
            defaultBindingId = Uuid.parse("cabbb6e9-3f18-4f2c-8bc1-a0a6630323b8"),
            approvalMode = CodeHutApprovalMode.HELP_ME_APPROVE,
            bindings = listOf(
                WorkModelBinding(
                    id = Uuid.parse("cabbb6e9-3f18-4f2c-8bc1-a0a6630323b9"),
                    providerId = Uuid.parse("cabbb6e9-3f18-4f2c-8bc1-a0a6630323ba"),
                    modelId = Uuid.parse("cabbb6e9-3f18-4f2c-8bc1-a0a6630323bb"),
                    protocol = WorkProtocol.ANTHROPIC_MESSAGES,
                    capability = WorkCapability.NEEDS_PROBE,
                )
            ),
        )

        val encoded = JsonInstant.encodeToString(expected)

        assertEquals(expected, JsonInstant.decodeFromString<CodeHutSetting>(encoded))
        assertEquals(false, encoded.contains("apiKey", ignoreCase = true))
        assertTrue(encoded.contains("HELP_ME_APPROVE"))
    }

    @Test
    fun helpApprovalLeaseExpiresAtItsExactDeadline() {
        val setting = CodeHutSetting(
            approvalMode = CodeHutApprovalMode.HELP_ME_APPROVE,
            approvalRevision = 42,
            helpApprovalExpiresAtEpochMillis = 1_000,
        )

        assertEquals(CodeHutApprovalMode.HELP_ME_APPROVE, setting.activeApprovalMode(999))
        assertEquals(CodeHutApprovalMode.ASK_EVERY_TIME, setting.activeApprovalMode(1_000))
        assertEquals(42, setting.approvalLease().revision)
        assertEquals(1_000, setting.approvalLease().expiresAtEpochMillis)
    }
}
