/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.tts.controller

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import java.io.ByteArrayOutputStream

/**
 * Bridge TTS provider flow to a single audio buffer.
 */
class TtsSynthesizer(
    private val ttsManager: TTSManager
) {
    suspend fun synthesize(
        setting: TTSProviderSetting,
        chunk: TtsChunk
    ): TTSResponse = withContext(Dispatchers.IO) {
        collectToResponse(
            ttsManager.generateSpeech(setting, TTSRequest(text = chunk.text))
        )
    }

    /**
     * Streams MP3 providers straight into a player buffer. Other formats keep
     * the existing complete-response behavior, which is the safe fallback for
     * PCM, WAV, OGG, and providers that only return one complete file.
     */
    suspend fun synthesizeForStreamingPlayback(
        setting: TTSProviderSetting,
        chunk: TtsChunk,
        onStreamingMp3: (StreamingAudioBuffer) -> Unit,
    ): TTSResponse? = withContext(Dispatchers.IO) {
        consumeForStreamingPlayback(
            flow = ttsManager.generateSpeech(setting, TTSRequest(text = chunk.text)),
            onStreamingMp3 = onStreamingMp3,
        )
    }

    companion object {
        internal suspend fun consumeForStreamingPlayback(
            flow: Flow<AudioChunk>,
            onStreamingMp3: (StreamingAudioBuffer) -> Unit,
        ): TTSResponse? {
            var format: AudioFormat? = null
            var sampleRate: Int? = null
            var streamingBuffer: StreamingAudioBuffer? = null
            val output = ByteArrayOutputStream()

            try {
                flow.collect { chunk ->
                    if (format == null) format = chunk.format
                    if (sampleRate == null) sampleRate = chunk.sampleRate

                    if (streamingBuffer == null && chunk.format == AudioFormat.MP3 && chunk.data.isNotEmpty()) {
                        streamingBuffer = StreamingAudioBuffer().also(onStreamingMp3)
                    }

                    streamingBuffer?.append(chunk.data) ?: output.write(chunk.data)
                }
                streamingBuffer?.finish()
            } catch (error: Throwable) {
                streamingBuffer?.fail(error)
                throw error
            }

            return if (streamingBuffer != null) {
                null
            } else {
                TTSResponse(
                    audioData = output.toByteArray(),
                    format = format ?: AudioFormat.MP3,
                    sampleRate = sampleRate,
                )
            }
        }
    }

    private suspend fun collectToResponse(flow: Flow<AudioChunk>): TTSResponse {
        var format: AudioFormat? = null
        var sampleRate: Int? = null
        val output = ByteArrayOutputStream()
        flow.collect { chunk ->
            if (format == null) format = chunk.format
            if (sampleRate == null) sampleRate = chunk.sampleRate
            output.write(chunk.data)
        }
        return TTSResponse(
            audioData = output.toByteArray(),
            format = format ?: AudioFormat.MP3,
            sampleRate = sampleRate
        )
    }
}

