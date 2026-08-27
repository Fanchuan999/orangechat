package me.rerere.rikkahub.ui.pages.codehut

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.data.pcbridge.PcBridgeUiPolicy
import me.rerere.rikkahub.data.pcbridge.PcBridgeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PcBridgeCardInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun invitationDraftIsScrubbedWhenBridgeReturnsToUnpaired() {
        var bridgeState by mutableStateOf<PcBridgeUiState>(PcBridgeUiState.Unpaired)

        composeRule.setContent {
            MaterialTheme {
                PcBridgeCard(
                    state = bridgeState,
                    policy = PcBridgeUiPolicy.from(bridgeState),
                    onInvitationChange = {},
                    onConfirmPairing = {},
                    onRefresh = {},
                    onUnlink = {},
                    onAbandonPendingPairing = {},
                )
            }
        }

        composeRule.onNodeWithTag("pc-bridge-invitation").performTextInput("opaque-invitation-draft")
        composeRule.runOnIdle { bridgeState = PcBridgeUiState.Pairing }
        composeRule.runOnIdle { bridgeState = PcBridgeUiState.Unpaired }

        assertTrue(composeRule.onAllNodesWithText("opaque-invitation-draft").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun unlinkConfirmationIsRemovedWhenBridgeLeavesPaired() {
        var bridgeState by mutableStateOf<PcBridgeUiState>(
            PcBridgeUiState.Paired("电脑 pc_test", "中继已连接", 0L),
        )
        var unlinkCalls = 0

        composeRule.setContent {
            MaterialTheme {
                PcBridgeCard(
                    state = bridgeState,
                    policy = PcBridgeUiPolicy.from(bridgeState),
                    onInvitationChange = {},
                    onConfirmPairing = {},
                    onRefresh = {},
                    onUnlink = { unlinkCalls++ },
                    onAbandonPendingPairing = {},
                )
            }
        }

        composeRule.onNodeWithText("解除配对").performClick()
        composeRule.onNodeWithText("确认解除配对").assertExists()
        composeRule.runOnIdle { bridgeState = PcBridgeUiState.Unavailable("已断开") }

        assertTrue(composeRule.onAllNodesWithText("确认解除配对").fetchSemanticsNodes().isEmpty())
        assertEquals(0, unlinkCalls)
    }

    @Test
    fun abandoningPendingRecoveryRequiresAnExplicitConfirmation() {
        var bridgeState by mutableStateOf<PcBridgeUiState>(PcBridgeUiState.PendingRecovery)
        var abandonCalls = 0

        composeRule.setContent {
            MaterialTheme {
                PcBridgeCard(
                    state = bridgeState,
                    policy = PcBridgeUiPolicy.from(bridgeState),
                    onInvitationChange = {},
                    onConfirmPairing = {},
                    onRefresh = {},
                    onUnlink = {},
                    onAbandonPendingPairing = { abandonCalls++ },
                )
            }
        }

        composeRule.onNodeWithText("放弃本机待恢复配对").performClick()
        composeRule.onNodeWithText("确认放弃本机配对").assertExists()
        assertEquals(0, abandonCalls)
        composeRule.onNodeWithText("确认放弃本机配对").performClick()

        assertEquals(1, abandonCalls)
        composeRule.runOnIdle { bridgeState = PcBridgeUiState.Unpaired }
        assertTrue(composeRule.onAllNodesWithText("确认放弃本机配对").fetchSemanticsNodes().isEmpty())
    }
}
