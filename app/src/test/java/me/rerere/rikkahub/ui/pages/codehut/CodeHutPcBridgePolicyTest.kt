package me.rerere.rikkahub.ui.pages.codehut

import me.rerere.rikkahub.data.datastore.HarnessSnapshot
import me.rerere.rikkahub.data.pcbridge.PcBridgeUiPolicy
import me.rerere.rikkahub.data.pcbridge.PcBridgeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CodeHutPcBridgePolicyTest {
    @Test
    fun `pairing presentation has no destructive action`() {
        val policy = PcBridgeUiPolicy.from(PcBridgeUiState.Pairing)

        assertEquals("正在安全连接电脑…", policy.status)
        assertNull(policy.dangerAction)
    }

    @Test
    fun `code hut exposes paired bridge through safe policy`() {
        val state = CodeHutUiState(
            harnessSnapshot = HarnessSnapshot(),
            pcBridge = PcBridgeUiState.Paired(
                deviceLabel = "电脑 /private/computer/path",
                statusText = "token=relay-secret",
                refreshedAtEpochMillis = 1L,
            ),
        )

        assertEquals("解除配对", state.pcBridgePolicy.dangerAction)
        assertFalse(state.pcBridgePolicy.flattenText().contains("relay-secret"))
        assertFalse(state.pcBridgePolicy.flattenText().contains("/private/computer/path"))
    }
}
