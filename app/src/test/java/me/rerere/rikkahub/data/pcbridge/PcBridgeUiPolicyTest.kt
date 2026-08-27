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
    fun `only pending recovery offers local abandon action`() {
        val pending = PcBridgeUiPolicy.from(PcBridgeUiState.PendingRecovery)

        assertEquals("放弃本机待恢复配对", pending.dangerAction)
        assertEquals("刷新状态", pending.primaryAction)
        assertEquals(null, PcBridgeUiPolicy.from(PcBridgeUiState.Unavailable("offline")).dangerAction)
        assertFalse(
            PcBridgeUiPolicy.from(PcBridgeUiState.Paired("电脑", "已连接", 1L)).dangerAction == "放弃本机待恢复配对",
        )
    }
}
