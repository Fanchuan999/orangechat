package me.rerere.tts.controller

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingAudioBufferTest {
    @Test
    fun `reads emitted chunks in order and ends after finish`() {
        val buffer = StreamingAudioBuffer(maxBufferedBytes = 8)
        buffer.append(byteArrayOf(1, 2))
        buffer.append(byteArrayOf(3, 4, 5))
        buffer.finish()

        val destination = ByteArray(5)
        assertEquals(2, buffer.read(destination, 0, 2))
        assertEquals(3, buffer.read(destination, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), destination)
        assertEquals(-1, buffer.read(ByteArray(1), 0, 1))
    }

    @Test
    fun `close returns end of stream without inventing audio`() {
        val buffer = StreamingAudioBuffer()
        val result = AtomicInteger(Int.MIN_VALUE)
        val readerStarted = CountDownLatch(1)
        val reader = Thread {
            readerStarted.countDown()
            result.set(buffer.read(ByteArray(1), 0, 1))
        }

        reader.start()
        readerStarted.await()
        buffer.close()
        reader.join(1_000)

        assertEquals(-1, result.get())
    }
}
