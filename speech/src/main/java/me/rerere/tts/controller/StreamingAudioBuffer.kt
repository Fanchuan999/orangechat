package me.rerere.tts.controller

import java.io.IOException
import java.util.ArrayDeque

/**
 * A small blocking bridge between an incremental TTS provider flow and Media3.
 * It owns only in-memory audio for the current utterance and discards it as
 * soon as the player consumes it.
 */
class StreamingAudioBuffer(
    private val maxBufferedBytes: Int = 512 * 1024,
) {
    private val lock = Object()
    private val chunks = ArrayDeque<ByteArray>()
    private var currentChunk: ByteArray? = null
    private var currentOffset = 0
    private var bufferedBytes = 0
    private var finished = false
    private var closed = false
    private var failure: IOException? = null

    init {
        require(maxBufferedBytes > 0) { "maxBufferedBytes must be positive" }
    }

    fun append(data: ByteArray) {
        if (data.isEmpty()) return
        val copy = data.copyOf()
        synchronized(lock) {
            while (!closed && !finished && bufferedBytes > 0 && bufferedBytes + copy.size > maxBufferedBytes) {
                lock.wait()
            }
            if (closed || finished) return
            chunks.addLast(copy)
            bufferedBytes += copy.size
            lock.notifyAll()
        }
    }

    fun finish() {
        synchronized(lock) {
            if (closed) return
            finished = true
            lock.notifyAll()
        }
    }

    fun fail(cause: Throwable) {
        synchronized(lock) {
            if (closed || finished) return
            failure = cause as? IOException ?: IOException("Streaming TTS failed", cause)
            finished = true
            lock.notifyAll()
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            chunks.clear()
            currentChunk = null
            currentOffset = 0
            bufferedBytes = 0
            lock.notifyAll()
        }
    }

    @Throws(IOException::class)
    fun read(destination: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset + length <= destination.size) { "Invalid read range" }
        if (length == 0) return 0

        synchronized(lock) {
            while (currentChunk == null && chunks.isEmpty() && !finished && !closed && failure == null) {
                lock.wait()
            }

            if (closed) return -1
            if (currentChunk == null) currentChunk = chunks.pollFirst()
            val source = currentChunk
            if (source == null) {
                failure?.let { throw it }
                return -1
            }

            val count = minOf(length, source.size - currentOffset)
            source.copyInto(destination, offset, currentOffset, currentOffset + count)
            currentOffset += count
            bufferedBytes -= count
            if (currentOffset == source.size) {
                currentChunk = null
                currentOffset = 0
            }
            lock.notifyAll()
            return count
        }
    }
}
