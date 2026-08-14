/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.tts.provider.providers

import me.rerere.common.http.SseEvent
import me.rerere.tts.model.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MiMoTTSProviderTest {
    @Test
    fun speech_messages_keep_reply_as_only_message_when_style_instruction_is_blank() {
        val messages = buildMiMoTtsMessages("", "晚安。")

        assertEquals(1, messages.size)
        assertEquals("assistant", messages.single().jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("晚安。", messages.single().jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun speech_messages_keep_style_as_system_instruction_not_spoken_content() {
        val messages = buildMiMoTtsMessages("语气提示：温柔、低声。", "晚安。")

        assertEquals(
            listOf("system", "assistant"),
            messages.map { it.jsonObject["role"]?.jsonPrimitive?.content }
        )
        assertEquals("语气提示：温柔、低声。", messages.first().jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals("晚安。", messages.last().jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun decode_audio_data_from_sse_chunk() {
        val expected = byteArrayOf(1, 2, 3, 4)
        val encoded = Base64.getEncoder().encodeToString(expected)
        val data = """{"choices":[{"delta":{"audio":{"data":"$encoded"}}}]}"""

        val actual = decodeMiMoAudioData(data)

        assertNotNull(actual)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun ignore_sse_chunk_without_audio_data() {
        val data = """{"choices":[{"delta":{"content":"hello"}}]}"""
        assertNull(decodeMiMoAudioData(data))
    }

    @Test
    fun emits_single_terminal_chunk_on_done_and_closed() {
        val processor = MiMoSseProcessor(model = "mimo-v2-tts", voice = "mimo_default")
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(9, 8, 7))
        val audioData = """{"choices":[{"delta":{"audio":{"data":"$encoded"}}}]}"""

        val first = processor.process(SseEvent.Event(id = null, type = null, data = audioData))
        val done = processor.process(SseEvent.Event(id = null, type = null, data = "[DONE]"))
        val terminal = processor.process(SseEvent.Closed)

        assertNotNull(first)
        assertEquals(AudioFormat.PCM, first?.format)
        assertFalse(first?.isLast ?: true)
        assertNull(done)
        assertNotNull(terminal)
        assertTrue(terminal?.isLast ?: false)
    }

    @Test
    fun throws_when_stream_closed_without_audio() {
        val processor = MiMoSseProcessor(model = "mimo-v2-tts", voice = "mimo_default")

        var thrown: Throwable? = null
        try {
            processor.process(SseEvent.Event(id = null, type = null, data = "[DONE]"))
            processor.process(SseEvent.Closed)
        } catch (t: Throwable) {
            thrown = t
        }

        assertNotNull(thrown)
        assertTrue(thrown is IllegalStateException)
    }
}
