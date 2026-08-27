package me.rerere.rikkahub.data.pcbridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CancellationException

sealed interface PcBridgeUiState {
    data object Unpaired : PcBridgeUiState
    data class InvitationDraft(val preview: String?, val error: String?) : PcBridgeUiState
    data object Pairing : PcBridgeUiState
    data class Paired(
        val deviceLabel: String,
        val statusText: String,
        val refreshedAtEpochMillis: Long,
    ) : PcBridgeUiState
    data object PendingRecovery : PcBridgeUiState
    data class Unavailable(val message: String) : PcBridgeUiState
}

class PcBridgePairingService(
    private val secretStore: PcBridgeSecretStore,
    private val relayClient: PcBridgeRelayClient,
    private val phoneDeviceIdFactory: () -> String = ::newPhoneDeviceId,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : PcBridgeUiActions {
    private val mutableState = MutableStateFlow<PcBridgeUiState>(PcBridgeUiState.Unpaired)
    override val state: StateFlow<PcBridgeUiState> = mutableState.asStateFlow()
    private var invitationCode: String? = null
    private val recoveryOperationMutex = Mutex()
    private var recoveryOperationGeneration = 0L

    override fun updateInvitationCode(value: String) {
        invitationCode = value.takeIf { it.isNotBlank() }
        if (invitationCode == null) {
            mutableState.value = PcBridgeUiState.Unpaired
            return
        }
        val draft = runCatching { PcBridgeInvitationCodec.decode(value, nowMillis()) }
        mutableState.value = draft.fold(
            onSuccess = { invitation ->
                PcBridgeUiState.InvitationDraft(
                    preview = "${PcBridgeEndpointPolicy.requireExactRelayEndpoint(invitation.endpoint).host} · " +
                        invitation.expiresAt,
                    error = null,
                )
            },
            onFailure = { PcBridgeUiState.InvitationDraft(preview = null, error = "邀请码无效或已过期。") },
        )
    }

    override suspend fun confirmPairing() {
        val code = invitationCode ?: run {
            mutableState.value = PcBridgeUiState.Unavailable("请先粘贴电脑邀请码。")
            return
        }
        val invitation = try {
            PcBridgeInvitationCodec.decode(code, nowMillis())
        } catch (_: Exception) {
            mutableState.value = PcBridgeUiState.InvitationDraft(null, "邀请码无效或已过期。")
            return
        }
        mutableState.value = PcBridgeUiState.Pairing
        var envelopeKey: ByteArray? = null
        var pendingCredentialsSaved = false
        try {
            val phone = PcBridgeCrypto.generateEphemeralKeyPair()
            envelopeKey = PcBridgeCrypto.deriveAesBytes(
                phone.privateKey,
                invitation.pcPublicKey,
                invitation.pairingSecret,
            )
            val phoneDeviceId = phoneDeviceIdFactory()
            val relayToken = PcBridgeRelayClient.newRelayToken()
            secretStore.save(
                PcBridgeCredentials(
                    endpoint = invitation.endpoint,
                    bridgeId = invitation.bridgeId,
                    phoneDeviceId = phoneDeviceId,
                    pcDeviceId = invitation.pcDeviceId,
                    relayToken = relayToken,
                    envelopeKey = requireNotNull(envelopeKey),
                    pendingConfirmation = true,
                ),
            )
            pendingCredentialsSaved = true
            val paired = relayClient.pairJoin(invitation, phoneDeviceId, phone.publicKeySpki, relayToken)
            if (!paired) {
                secretStore.clear()
                mutableState.value = PcBridgeUiState.Unavailable("电脑尚未确认配对，请重试。")
                return
            }
            secretStore.save(
                PcBridgeCredentials(
                    endpoint = invitation.endpoint,
                    bridgeId = invitation.bridgeId,
                    phoneDeviceId = phoneDeviceId,
                    pcDeviceId = invitation.pcDeviceId,
                    relayToken = relayToken,
                    envelopeKey = requireNotNull(envelopeKey),
                ),
            )
            invitationCode = null
            mutableState.value = pairedState(invitation.pcDeviceId, "中继已连接")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            mutableState.value = if (pendingCredentialsSaved) {
                PcBridgeUiState.PendingRecovery
            } else {
                PcBridgeUiState.Unavailable("安全连接电脑失败，请重试。")
            }
        } finally {
            envelopeKey?.fill(0)
        }
    }

    override suspend fun refreshStatus() {
        val snapshot = recoveryOperationMutex.withLock {
            secretStore.load()?.let { credentials -> credentials to recoveryOperationGeneration }
        } ?: run {
            mutableState.value = PcBridgeUiState.Unpaired
            return
        }
        val (credentials, operationGeneration) = snapshot
        try {
            val relayState = relayClient.refreshStatus(credentials)
            recoveryOperationMutex.withLock {
                if (operationGeneration != recoveryOperationGeneration) return@withLock
                when (relayState) {
                    "active" -> {
                        if (credentials.pendingConfirmation) {
                            try {
                                secretStore.save(credentials.copy(pendingConfirmation = false))
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                // The active relay is authoritative. A later refresh will retry persisting confirmation.
                            }
                        }
                        mutableState.value = pairedState(credentials.pcDeviceId, "中继已连接")
                    }

                    "revoked" -> {
                        secretStore.clear()
                        mutableState.value = PcBridgeUiState.Unpaired
                    }

                    else -> {
                        mutableState.value = if (credentials.pendingConfirmation) {
                            PcBridgeUiState.PendingRecovery
                        } else {
                            PcBridgeUiState.Unavailable("无法刷新电脑状态，请重试。")
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            recoveryOperationMutex.withLock {
                if (operationGeneration == recoveryOperationGeneration) {
                    mutableState.value = if (credentials.pendingConfirmation) {
                        PcBridgeUiState.PendingRecovery
                    } else {
                        PcBridgeUiState.Unavailable("无法刷新电脑状态，请重试。")
                    }
                }
            }
        } finally {
            credentials.envelopeKey.fill(0)
        }
    }

    override suspend fun unlink() {
        val credentials = secretStore.load() ?: run {
            mutableState.value = PcBridgeUiState.Unpaired
            return
        }
        try {
            if (relayClient.revokeBridge(credentials)) {
                secretStore.clear()
                mutableState.value = PcBridgeUiState.Unpaired
            } else {
                mutableState.value = PcBridgeUiState.Unavailable("无法解除电脑配对，请重试。")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            mutableState.value = PcBridgeUiState.Unavailable("无法解除电脑配对，请重试。")
        } finally {
            credentials.envelopeKey.fill(0)
        }
    }

    override suspend fun abandonPendingPairing() {
        recoveryOperationMutex.withLock {
            recoveryOperationGeneration += 1
            val credentials = secretStore.load() ?: run {
                mutableState.value = PcBridgeUiState.Unpaired
                return@withLock
            }
            try {
                if (!credentials.pendingConfirmation) return@withLock
                secretStore.clear()
                mutableState.value = PcBridgeUiState.Unpaired
            } finally {
                credentials.envelopeKey.fill(0)
            }
        }
    }

    private fun pairedState(pcDeviceId: String, statusText: String) = PcBridgeUiState.Paired(
        deviceLabel = "电脑 $pcDeviceId",
        statusText = statusText,
        refreshedAtEpochMillis = nowMillis(),
    )

    companion object {
        private fun newPhoneDeviceId(): String = "phone_" + PcBridgeRelayClient.newRelayToken().take(22)
    }
}
