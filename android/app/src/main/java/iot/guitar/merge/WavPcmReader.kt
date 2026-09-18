package iot.guitar.merge

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Seekable PCM16 WAV reader over a [FileChannel] (MediaStore fd in the app, a temp file in
 * JVM tests). Walks the RIFF chunk list to find `fmt ` and `data`, so files with extra
 * chunks (LIST, etc.) are fine. Only integer PCM is accepted.
 */
class WavPcmReader(private val channel: FileChannel) : Closeable {

    val sampleRate: Int
    val channels: Int
    val bitsPerSample: Int
    val dataOffset: Long
    val dataBytes: Long

    /** Bytes per sample frame (all channels). */
    val bytesPerFrame: Int
        get() = channels * bitsPerSample / 8

    /** Total sample frames in the data chunk. */
    val totalSamples: Long
        get() = dataBytes / bytesPerFrame

    /** Current read position in sample frames. */
    var positionSamples: Long = 0
        private set

    init {
        val riff = readBytes(0, 12)
        if (riff.size < 12 || String(riff, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(riff, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) throw IOException("not a RIFF/WAVE file")

        var sr = 0
        var ch = 0
        var bits = 0
        var format = 0
        var dOff = -1L
        var dLen = 0L
        var pos = 12L
        val fileSize = channel.size()
        while (pos + 8 <= fileSize && dOff < 0) {
            val head = readBytes(pos, 8)
            val id = String(head, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt(4).toLong() and 0xFFFFFFFFL
            when (id) {
                "fmt " -> {
                    val fmt = ByteBuffer.wrap(readBytes(pos + 8, 16)).order(ByteOrder.LITTLE_ENDIAN)
                    format = fmt.getShort(0).toInt() and 0xFFFF
                    ch = fmt.getShort(2).toInt() and 0xFFFF
                    sr = fmt.getInt(4)
                    bits = fmt.getShort(14).toInt() and 0xFFFF
                }
                "data" -> {
                    dOff = pos + 8
                    // Streaming writers may leave the size unpatched: fall back to the file size.
                    dLen = if (size == 0L || size == 0xFFFFFFFFL || dOff + size > fileSize) fileSize - dOff else size
                }
            }
            pos += 8 + size + (size and 1L)  // chunks are word-aligned
        }
        if (dOff < 0) throw IOException("no data chunk")
        if (format != FORMAT_PCM && format != FORMAT_EXTENSIBLE) throw IOException("unsupported WAV format $format")
        if (bits != 16 || ch < 1 || sr <= 0) throw IOException("only PCM16 supported (bits=$bits ch=$ch sr=$sr)")

        sampleRate = sr
        channels = ch
        bitsPerSample = bits
        dataOffset = dOff
        dataBytes = dLen
        seekToSample(0)
    }

    /** Positions the reader at sample frame [n] (clamped to the data range). */
    fun seekToSample(n: Long) {
        positionSamples = n.coerceIn(0, totalSamples)
        channel.position(dataOffset + positionSamples * bytesPerFrame)
    }

    /**
     * Reads up to [length] bytes (rounded down to whole frames) into [buf].
     * @return bytes read, or -1 at end of data.
     */
    fun read(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): Int {
        val remainingBytes = dataBytes - positionSamples * bytesPerFrame
        if (remainingBytes <= 0) return -1
        val want = minOf(length.toLong(), remainingBytes).toInt() / bytesPerFrame * bytesPerFrame
        if (want <= 0) return -1
        val bb = ByteBuffer.wrap(buf, offset, want)
        var total = 0
        while (bb.hasRemaining()) {
            val n = channel.read(bb)
            if (n < 0) break
            total += n
        }
        val frames = total / bytesPerFrame
        positionSamples += frames
        return if (frames == 0) -1 else frames * bytesPerFrame
    }

    override fun close() = channel.close()

    private fun readBytes(position: Long, length: Int): ByteArray {
        val bb = ByteBuffer.allocate(length)
        var pos = position
        while (bb.hasRemaining()) {
            val n = channel.read(bb, pos)
            if (n < 0) break
            pos += n
        }
        return if (bb.hasRemaining()) bb.array().copyOf(bb.position()) else bb.array()
    }

    companion object {
        private const val FORMAT_PCM = 1
        private const val FORMAT_EXTENSIBLE = 0xFFFE

        fun open(file: File): WavPcmReader = WavPcmReader(RandomAccessFile(file, "r").channel)
    }
}
