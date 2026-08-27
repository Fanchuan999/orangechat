package me.rerere.rikkahub.ui.pages.codehut

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import me.rerere.rikkahub.data.codehut.HarnessImageAttachment
import me.rerere.rikkahub.data.codehut.HarnessImageSendResult
import me.rerere.rikkahub.data.codehut.HarnessInboxLoadResult
import me.rerere.rikkahub.data.datastore.HarnessSnapshot
import me.rerere.rikkahub.data.pcbridge.PcBridgeUiActions
import me.rerere.rikkahub.data.pcbridge.PcBridgeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class CodeHutVMActionSafetyTest {
    @Test
    fun `pair refresh and unlink failures never expose bridge secrets`() {
        val actions = FailingPcActions()
        val vm = testVm(actions)

        vm.updatePcInvitation("DADDY-PC2:secret-invitation")
        assertSafeError(vm, "无法读取电脑邀请，请重新粘贴。")
        vm.confirmPcPairing()
        assertSafeError(vm, "安全连接电脑失败，请重试。")
        vm.refreshPcBridge()
        assertSafeError(vm, "无法刷新电脑状态，请重试。")
        vm.unlinkPcBridge()
        assertSafeError(vm, "无法解除电脑配对，请重试。")
    }

    @Test
    fun `pc action cancellation remains cancelled and is not rendered as an error`() {
        val vm = testVm(FailingPcActions(cancelOnRefresh = true))

        val refresh = vm.refreshPcBridge()

        assertTrue(refresh.isCancelled)
        assertEquals(null, vm.state.value.error)
    }

    @Test
    fun `startup refresh restores persisted pairing exactly once after state collection starts`() {
        val actions = StartupPcActions()

        val vm = testVm(actions)

        assertEquals(1, actions.refreshCount)
        assertTrue(vm.state.value.pcBridge is PcBridgeUiState.Paired)
        assertEquals(null, vm.state.value.error)
    }

    @Test
    fun `abandon pending pairing delegates only to the explicit local action`() {
        val actions = PendingRecoveryPcActions()
        val vm = testVm(actions)
        val refreshCountBeforeAbandon = actions.refreshCount

        vm.abandonPendingPcBridge()

        assertEquals(1, actions.abandonCount)
        assertEquals(refreshCountBeforeAbandon, actions.refreshCount)
        assertTrue(vm.state.value.pcBridge is PcBridgeUiState.Unpaired)
    }

    @Test
    fun `abandon pairing is not delegated outside pending recovery`() {
        listOf(
            PcBridgeUiState.Unavailable("offline"),
            PcBridgeUiState.Paired("电脑 pc-test", "中继已连接", 1L),
        ).forEach { bridgeState ->
            val actions = StaticPcActions(bridgeState)
            val vm = testVm(actions)

            vm.abandonPendingPcBridge()

            assertEquals(0, actions.abandonCount)
            assertEquals(bridgeState, vm.state.value.pcBridge)
        }
    }

    private fun testVm(actions: PcBridgeUiActions) = CodeHutVM(
        initialHarnessSnapshot = HarnessSnapshot(),
        harnessSnapshots = emptyFlow(),
        inspectHarness = {},
        loadInbox = { HarnessInboxLoadResult.Unavailable("unused") },
        sendImage = { _: HarnessImageAttachment -> HarnessImageSendResult.Sent },
        pairingService = actions,
        actionScope = CoroutineScope(Dispatchers.Unconfined),
        refreshInboxOnStart = false,
    )

    private fun assertSafeError(vm: CodeHutVM, expected: String) {
        assertEquals(expected, vm.state.value.error)
        assertFalse(vm.state.value.error.orEmpty().contains("relay-secret"))
        assertFalse(vm.state.value.error.orEmpty().contains("DADDY-PC2"))
    }

    private class FailingPcActions(
        private val cancelOnRefresh: Boolean = false,
    ) : PcBridgeUiActions {
        override val state = MutableStateFlow<PcBridgeUiState>(PcBridgeUiState.Unpaired)
        override fun updateInvitationCode(value: String) = error("token=relay-secret $value")
        override suspend fun confirmPairing() = error("token=relay-secret")
        override suspend fun refreshStatus() {
            if (cancelOnRefresh) throw CancellationException("relay-secret")
            error("token=relay-secret")
        }
        override suspend fun unlink() = error("token=relay-secret")
        override suspend fun abandonPendingPairing() = error("token=relay-secret")
    }

    private class StartupPcActions : PcBridgeUiActions {
        override val state = MutableStateFlow<PcBridgeUiState>(PcBridgeUiState.Unpaired)
        var refreshCount = 0
            private set

        override fun updateInvitationCode(value: String) = Unit
        override suspend fun confirmPairing() = Unit
        override suspend fun refreshStatus() {
            refreshCount += 1
            state.value = PcBridgeUiState.Paired(
                deviceLabel = "电脑 pc-test",
                statusText = "中继已连接",
                refreshedAtEpochMillis = 1L,
            )
        }
        override suspend fun unlink() = Unit
        override suspend fun abandonPendingPairing() = Unit
    }

    private class PendingRecoveryPcActions : PcBridgeUiActions {
        override val state = MutableStateFlow<PcBridgeUiState>(PcBridgeUiState.PendingRecovery)
        var abandonCount = 0
            private set
        var refreshCount = 0
            private set

        override fun updateInvitationCode(value: String) = Unit
        override suspend fun confirmPairing() = Unit
        override suspend fun refreshStatus() {
            refreshCount += 1
        }
        override suspend fun unlink() = Unit
        override suspend fun abandonPendingPairing() {
            abandonCount += 1
            state.value = PcBridgeUiState.Unpaired
        }
    }

    private class StaticPcActions(initialState: PcBridgeUiState) : PcBridgeUiActions {
        override val state = MutableStateFlow(initialState)
        var abandonCount = 0
            private set

        override fun updateInvitationCode(value: String) = Unit
        override suspend fun confirmPairing() = Unit
        override suspend fun refreshStatus() = Unit
        override suspend fun unlink() = Unit
        override suspend fun abandonPendingPairing() {
            abandonCount += 1
        }
    }
}
