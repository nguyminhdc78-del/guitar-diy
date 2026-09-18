package iot.guitar.audio

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Streaming PCM16 WAV writer over a seekable [FileChannel].
 *
 * Writes a 44-byte header at position 0, buffers PCM writes, and re-patches the RIFF/data
 * sizes on every buffer flush (~every 32 KB) and on [close], so a recording survives
 * process death as a valid WAV up to the last flush. Thread-safe (all methods synchronized) so the
 * service can close it from the main thread while the receive thread is still writing.
 * In production the channel comes from a MediaStore [android.os.ParcelFileDescriptor];
 * JVM tests use `RandomAccessFile(file, "rw").channel`.
 */
class WavFileWriter(
    private val channel: FileChannel,
    private val sampleRate: Int = 48000,
    private val channels: Int = 1,
    private val bitsPerSample: Int = 16,
) : Closeable {

    /** PCM bytes written so far (excluding header). */
    var dataBytes: Long = 0
        private set

    private val buffer = ByteBuffer.allocate(BUFFER_BYTES)
    private var closed = false

    init {
        channel.truncate(0)
        channel.position(0)
        writeFully(buildHeader(0))
    }

    @Synchronized
    fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (closed || length <= 0) return
        var off = offset
        var remaining = length
        while (remaining > 0) {
            val chunk = minOf(remaining, buffer.remaining())
            buffer.put(bytes, off, chunk)
            off += chunk
            remaining -= chunk
            dataBytes += chunk   // counted before flushing so the patched header matches the disk
            if (!buffer.hasRemaining()) flushBuffer()
        }
    }

    /** Appends [bytes] zero bytes (silence) in 4 KB chunks. */
    @Synchronized
    fun writeSilence(bytes: Long) {
        if (closed || bytes <= 0) return
        var remaining = bytes
        while (remaining > 0) {
            val chunk = minOf(remaining, SILENCE.size.toLong()).toInt()
            write(SILENCE, 0, chunk)
            remaining -= chunk
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            flushBuffer()
            channel.force(true)
        } finally {
            channel.close()
        }
    }

    private fun flushBuffer() {
        buffer.flip()
        while (buffer.hasRemaining()) channel.write(buffer)
        buffer.clear()
        patchHeader()
    }

    /** Positional write: keeps the sizes current without moving the channel position. */
    private fun patchHeader() {
        val header = buildHeader(dataBytes)
        var pos = 0L
        while (header.hasRemaining()) pos += channel.write(header, pos)
    }

    private fun writeFully(bytes: ByteBuffer) {
        while (bytes.hasRemaining()) channel.write(bytes)
    }

    private fun buildHeader(dataSize: Long): ByteBuffer {
        val clamped = minOf(dataSize, 0xFFFFFFFFL - 36).toInt()
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + clamped)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)                       // PCM fmt chunk size
        header.putShort(1)                      // audio format = PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * blockAlign)  // byte rate
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(clamped)
        header.flip()
        return header
    }

    companion object {
        const val HEADER_BYTES = 44
        private const val BUFFER_BYTES = 32 * 1024
        private val SILENCE = ByteArray(4096)
    }
}
