package me.rerere.tts.controller

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** Media3 source for a [StreamingAudioBuffer]. Only used for incremental MP3. */
class StreamingAudioDataSource(
    private val buffer: StreamingAudioBuffer,
) : DataSource {
    private var uri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) = Unit

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int = buffer.read(target, offset, length)

    override fun getUri(): Uri? = uri

    override fun close() {
        buffer.close()
        uri = null
    }
}
