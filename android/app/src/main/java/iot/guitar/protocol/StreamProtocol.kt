package iot.guitar.protocol

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stream protocol v1 constants and parsers. Must stay byte-identical to the firmware's
 * `stream-protocol.h` and to `firmware/tools/stream-to-wav.py` (the reference receiver).
 *
 * Per TCP connection: 8-byte header once (`uMIC` + u32 LE sample rate), then 1928-byte
 * frames (u32 LE seq, u32 LE timestamp_us, 960 x int16 LE mono PCM) until close.
 */
object StreamProtocol {
    const val MAGIC = "uMIC"
    const val SAMPLE_RATE = 48000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val FRAME_SAMPLES = 960
    const val FRAME_MS = 20
    const val FRAME_HEADER_BYTES = 8
    const val FRAME_PCM_BYTES = FRAME_SAMPLES * 2          // 1920
    const val FRAME_BYTES = FRAME_HEADER_BYTES + FRAME_PCM_BYTES // 1928
    const val HEADER_BYTES = 8

    // Network defaults (mirror firmware platformio.ini build flags).
    const val AP_SSID = "uMIC"
    const val AP_PASSPHRASE = "umic12345"
    const val HOST = "192.168.4.1"
    const val PORT = 5000
    /** Text control channel: `BTN <n>` from the ESP32 button, `REC 1|0` to its LED. */
    const val CONTROL_PORT = 5001

    private val magicBytes = MAGIC.toByteArray(Charsets.US_ASCII)

    /** Parses the stream header. Returns the sample rate. Throws on wrong length or magic. */
    fun parseHeader(header: ByteArray): Long {
        if (header.size < HEADER_BYTES) {
            throw ProtocolException("header too short: ${header.size} bytes")
        }
        for (i in 0 until 4) {
            if (header[i] != magicBytes[i]) {
                throw ProtocolException("bad magic: expected $MAGIC")
            }
        }
        return readU32(header, 4)
    }

    /** Parses seq + timestamp from the first 8 bytes of a frame (unsigned 32-bit values). */
    fun parseFrameMeta(frame: ByteArray): FrameMeta {
        if (frame.size < FRAME_HEADER_BYTES) {
            throw ProtocolException("frame too short: ${frame.size} bytes")
        }
        return FrameMeta(seq = readU32(frame, 0), timestampUs = readU32(frame, 4))
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(offset).toLong() and 0xFFFFFFFFL
}

data class FrameMeta(val seq: Long, val timestampUs: Long)

class ProtocolException(message: String) : IOException(message)
