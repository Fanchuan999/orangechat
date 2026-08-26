package me.rerere.rikkahub.data.pcbridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    data class Unavailable(val message: String) : PcBridgeUiState
}

class PcBridgePairingService(
    private val secretStore: PcBridgeSecretStore,
    private val relayClient: PcBridgeRelayClient,
    private val phoneDeviceIdFactory: () -> String = ::newPhoneDeviceId,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow<PcBridgeUiState>(PcBridgeUiState.Unpaired)
    val state: StateFlow<PcBridgeUiState> = mutableState.asStateFlow()
    private var invitationCode: String? = null

    fun updateInvitationCode(value: String) {
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

    suspend fun confirmPairing() {
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
        try {
            val phone = PcBridgeCrypto.generateEphemeralKeyPair()
            envelopeKey = PcBridgeCrypto.deriveAesBytes(
                phone.privateKey,
                invitation.pcPublicKey,
                invitation.pairingSecret,
            )
            val phoneDeviceId = phoneDeviceIdFactory()
            val relayToken = PcBridgeRelayClient.newRelayToken()
            val paired = relayClient.pairJoin(invitation, phoneDeviceId, phone.publicKeySpki, relayToken)
            if (!paired) {
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
            mutableState.value = PcBridgeUiState.Unavailable("安全连接电脑失败，请重试。")
        } finally {
            envelopeKey?.fill(0)
        }
    }

    suspend fun refreshStatus() {
        val credentials = secretStore.load() ?: run {
            mutableState.value = PcBridgeUiState.Unpaired
            return
        }
        try {
            val relayState = relayClient.refreshStatus(credentials)
            val status = when (relayState) {
                "active" -> "中继已连接"
                "revoked" -> "电脑已解除配对"
                else -> "中继状态暂不可用"
            }
            if (relayState == "revoked") {
                secretStore.clear()
                mutableState.value = PcBridgeUiState.Unpaired
            } else {
                mutableState.value = pairedState(credentials.pcDeviceId, status)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            mutableState.value = PcBridgeUiState.Unavailable("无法刷新电脑状态，请重试。")
        } finally {
            credentials.envelopeKey.fill(0)
        }
    }

    suspend fun unlink() {
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

    private fun pairedState(pcDeviceId: String, statusText: String) = PcBridgeUiState.Paired(
        deviceLabel = "电脑 $pcDeviceId",
        statusText = statusText,
        refreshedAtEpochMillis = nowMillis(),
    )

    companion object {
        private fun newPhoneDeviceId(): String = "phone_" + PcBridgeRelayClient.newRelayToken().take(22)
    }
}
