package me.rerere.rikkahub.data.pcbridge

import kotlinx.coroutines.flow.StateFlow

interface PcBridgeUiActions {
    val state: StateFlow<PcBridgeUiState>
    fun updateInvitationCode(value: String)
    suspend fun confirmPairing()
    suspend fun refreshStatus()
    suspend fun unlink()
    suspend fun abandonPendingPairing()
    suspend fun forgetUnavailableConfirmedPairing()
}
