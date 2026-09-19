package me.rerere.rikkahub.service

import me.rerere.asr.ASRStatus
import me.rerere.asr.ASRConnectionMode
import me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCallTurnPolicyTest {
    @Test
    fun `batch ASR becomes listenable without a realtime handshake`() {
        assertEquals(
            VoiceCallStatus.Listening,
            VoiceCallTurnPolicy.statusAfterStart(
                asrMode = ASRConnectionMode.Batch,
                asrStatus = ASRStatus.Idle,
            ),
        )
    }

    @Test
    fun `realtime ASR stays connecting until the recognizer is listening`() {
        assertEquals(
            VoiceCallStatus.Connecting,
            VoiceCallTurnPolicy.statusAfterStart(
                asrMode = ASRConnectionMode.Realtime,
                asrStatus = ASRStatus.Connecting,
            ),
        )
    }

    @Test
    fun `only an ASR listener makes a connecting call ready for speech`() {
        assertEquals(
            VoiceCallStatus.Listening,
            VoiceCallTurnPolicy.callStatusForAsr(
                currentCallStatus = VoiceCallStatus.Connecting,
                asrStatus = ASRStatus.Listening,
            ),
        )
    }

    @Test
    fun `an ASR connection in progress keeps the call out of listening`() {
        assertEquals(
            VoiceCallStatus.Connecting,
            VoiceCallTurnPolicy.callStatusForAsr(
                currentCallStatus = VoiceCallStatus.Connecting,
                asrStatus = ASRStatus.Connecting,
            ),
        )
    }

    @Test
    fun `ASR updates never replace an active assistant speaking state`() {
        assertNull(
            VoiceCallTurnPolicy.callStatusForAsr(
                currentCallStatus = VoiceCallStatus.Speaking,
                asrStatus = ASRStatus.Listening,
            ),
        )
    }
}
