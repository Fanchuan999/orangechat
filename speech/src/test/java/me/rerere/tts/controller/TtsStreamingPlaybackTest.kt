package me.rerere.tts.controller

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TtsStreamingPlaybackTest {
    @Test
    fun `mp3 begins playback before the provider flow has finished`() = runBlocking {
        var startedBuffer: StreamingAudioBuffer? = null

        val response = TtsSynthesizer.consumeForStreamingPlayback(
            flow {
                emit(AudioChunk(data = byteArrayOf(1, 2), format = AudioFormat.MP3))
                assertNotNull("The first MP3 chunk must start playback immediately", startedBuffer)
                emit(AudioChunk(data = byteArrayOf(3), format = AudioFormat.MP3, isLast = true))
            },
        ) { startedBuffer = it }

        assertNull(response)
        val bytes = ByteArray(3)
        assertEquals(2, startedBuffer!!.read(bytes, 0, 3))
        assertEquals(1, startedBuffer!!.read(bytes, 2, 1))
        assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
    }
}
