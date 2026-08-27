package me.rerere.rikkahub.data.pcbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PcBridgeUiPolicyTest {
    @Test
    fun `unpaired presentation explains computer command`() {
        assertEquals("连接电脑", PcBridgeUiPolicy.from(PcBridgeUiState.Unpaired).primaryAction)
    }

    @Test
    fun `paired presentation never displays token material`() {
        val policy = PcBridgeUiPolicy.from(PcBridgeUiState.Paired("这台电脑", "中继已连接", 1L))

        assertEquals("解除配对", policy.dangerAction)
        assertFalse(policy.detail.contains("token", ignoreCase = true))
    }

    @Test
    fun `policy ignores untrusted state text`() {
        val policy = PcBridgeUiPolicy.from(
            PcBridgeUiState.Unavailable("token=relay-secret /private/computer/path ciphertext=abc"),
        )

        assertFalse(policy.flattenText().contains("relay-secret"))
        assertFalse(policy.flattenText().contains("/private/computer/path"))
        assertFalse(policy.flattenText().contains("ciphertext=abc"))
    }

    @Test
    fun `each recovery state offers only its explicit local recovery action`() {
        val pending = PcBridgeUiPolicy.from(PcBridgeUiState.PendingRecovery)
        val confirmed = PcBridgeUiPolicy.from(PcBridgeUiState.ConfirmedRecovery)

        assertEquals("放弃本机待恢复配对", pending.dangerAction)
        assertEquals(PcBridgeLocalRecoveryAction.AbandonPendingPairing, pending.localRecoveryAction)
        assertEquals("刷新状态", pending.primaryAction)
        assertEquals("忘记本机电脑配对", confirmed.dangerAction)
        assertEquals(PcBridgeLocalRecoveryAction.ForgetUnavailableConfirmedPairing, confirmed.localRecoveryAction)
        assertFalse(confirmed.flattenText().contains("relay-secret"))
        assertEquals(null, PcBridgeUiPolicy.from(PcBridgeUiState.Unavailable("offline")).dangerAction)
        assertEquals(null, PcBridgeUiPolicy.from(PcBridgeUiState.Unavailable("offline")).localRecoveryAction)
        assertEquals(null, PcBridgeUiPolicy.from(PcBridgeUiState.Unpaired).localRecoveryAction)
        assertEquals(
            null,
            PcBridgeUiPolicy.from(PcBridgeUiState.InvitationDraft(null, "邀请码无效或已过期。")).localRecoveryAction,
        )
        assertEquals(null, PcBridgeUiPolicy.from(PcBridgeUiState.Pairing).localRecoveryAction)
        assertFalse(
            PcBridgeUiPolicy.from(PcBridgeUiState.Paired("电脑", "已连接", 1L)).dangerAction == "放弃本机待恢复配对",
        )
        assertEquals(null, PcBridgeUiPolicy.from(PcBridgeUiState.Paired("电脑", "已连接", 1L)).localRecoveryAction)
    }
}
