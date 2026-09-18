package iot.guitar.merge

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Wraps [MediaExtractor] for the first video track of a file: exposes format, duration,
 * rotation and a sample-by-sample reader whose [BufferInfo] can go straight to a muxer.
 */
class VideoTrackReader(context: Context, uri: Uri) : Closeable {

    private val extractor = MediaExtractor()
    val format: MediaFormat
    val durationUs: Long
    val rotationDegrees: Int
    private val buffer: ByteBuffer

    init {
        extractor.setDataSource(context, uri, null)
        var index = -1
        var fmt: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                index = i
                fmt = f
                break
            }
        }
        if (index < 0 || fmt == null) {
            extractor.release()
            throw IOException("no video track")
        }
        extractor.selectTrack(index)
        format = fmt
        durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION)
        else durationFromRetriever(context, uri)
        rotationDegrees = if (fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.getInteger(MediaFormat.KEY_ROTATION) else 0
        val maxInput = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
        buffer = ByteBuffer.allocateDirect(maxOf(maxInput, MIN_BUFFER_BYTES))
    }

    /** True for 10-bit HDR (HLG / PQ) or Dolby Vision tracks, which MediaMuxer may reject. */
    val looksHdr: Boolean
        get() {
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.contains("dolby-vision")) return true
            if (!format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) return false
            val transfer = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
            return transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG
        }

    /**
     * Reads the next sample into the internal buffer and advances.
     * @return the sample's BufferInfo (offset 0) or null at end of track.
     */
    fun readSample(): MediaCodec.BufferInfo? {
        buffer.clear()
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) return null
        val info = MediaCodec.BufferInfo().apply {
            set(0, size, extractor.sampleTime, extractorFlagsToBufferFlags(extractor.sampleFlags))
        }
        extractor.advance()
        return info
    }

    /** The buffer holding the sample returned by the last [readSample]. */
    val sampleBuffer: ByteBuffer
        get() = buffer

    override fun close() = extractor.release()

    private fun extractorFlagsToBufferFlags(flags: Int): Int {
        var out = 0
        if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) out = out or MediaCodec.BUFFER_FLAG_KEY_FRAME
        if (flags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) out = out or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        return out
    }

    private fun durationFromRetriever(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            return ms * 1000
        } finally {
            retriever.release()
        }
    }

    companion object {
        private const val MIN_BUFFER_BYTES = 4 * 1024 * 1024
    }
}
