package me.rerere.rikkahub.service

import me.rerere.asr.ASRStatus
import me.rerere.asr.ASRConnectionMode
import me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus

/**
 * Keeps the call UI from claiming it is listening before the selected ASR has
 * actually become ready. This is deliberately pure so the service can use the
 * same rule for both batch and realtime ASR providers.
 */
object VoiceCallTurnPolicy {
    /**
     * Batch ASR starts recording on-device immediately. It must not be held in
     * a fake "connecting" state while it waits to upload audio after a pause.
     */
    fun statusAfterStart(
        asrMode: ASRConnectionMode,
        asrStatus: ASRStatus,
    ): VoiceCallStatus {
        return when (asrMode) {
            ASRConnectionMode.Batch -> VoiceCallStatus.Listening
            ASRConnectionMode.Realtime -> {
                if (asrStatus == ASRStatus.Listening) {
                    VoiceCallStatus.Listening
                } else {
                    VoiceCallStatus.Connecting
                }
            }
        }
    }

    fun callStatusForAsr(
        currentCallStatus: VoiceCallStatus,
        asrStatus: ASRStatus,
    ): VoiceCallStatus? = callStatusForAsr(
        currentCallStatus = currentCallStatus,
        asrMode = ASRConnectionMode.Realtime,
        asrStatus = asrStatus,
    )

    fun callStatusForAsr(
        currentCallStatus: VoiceCallStatus,
        asrMode: ASRConnectionMode,
        asrStatus: ASRStatus,
    ): VoiceCallStatus? {
        if (currentCallStatus != VoiceCallStatus.Connecting) return null

        if (asrStatus == ASRStatus.Error) return null

        return statusAfterStart(asrMode, asrStatus)
    }
}
