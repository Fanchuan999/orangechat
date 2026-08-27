package me.rerere.rikkahub.ui.pages.codehut

import me.rerere.rikkahub.data.codehut.HarnessImagePolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class CodeHutImageReaderTest {
    @Test
    fun returnsBytesAtLimitAndBoundsOversizedRead() {
        val atLimit = ByteArray(HarnessImagePolicy.MAX_BYTES) { (it % 251).toByte() }
        assertArrayEquals(atLimit, readImage(ByteArrayInputStream(atLimit)))

        val oversized = ByteArray(HarnessImagePolicy.MAX_BYTES + 1) { (it % 251).toByte() }
        val countingInput = CountingInputStream(oversized)
        assertNull(readImage(countingInput))
        assertTrue(countingInput.requestedBytes <= HarnessImagePolicy.MAX_BYTES + 1)
    }

    private fun readImage(input: InputStream): ByteArray? {
        val method = Class.forName("me.rerere.rikkahub.ui.pages.codehut.CodeHutPageKt")
            .getDeclaredMethod("readImageAtMostHarnessLimit", InputStream::class.java)
        method.isAccessible = true
        return method.invoke(null, input) as ByteArray?
    }

    private class CountingInputStream(private val bytes: ByteArray) : InputStream() {
        private var position = 0
        var requestedBytes = 0
            private set

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            requestedBytes += length
            if (position == bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }

        override fun read(): Int = if (position == bytes.size) -1 else bytes[position++].toInt()
    }
}
