package iot.guitar.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StreamProtocolTest {

    private fun header(magic: String = "uMIC", rate: Long = 48000): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(magic.toByteArray(Charsets.US_ASCII)).putInt(rate.toInt()).array()

    @Test
    fun constantsMatchWireLayout() {
        assertEquals(1920, StreamProtocol.FRAME_PCM_BYTES)
        assertEquals(1928, StreamProtocol.FRAME_BYTES)
        assertEquals(8, StreamProtocol.HEADER_BYTES)
        assertEquals(20, StreamProtocol.FRAME_MS)
        assertEquals(960 * 1000 / 48000, StreamProtocol.FRAME_MS)
    }

    @Test
    fun parseHeaderReturnsSampleRate() {
        assertEquals(48000L, StreamProtocol.parseHeader(header()))
    }

    @Test
    fun parseHeaderRejectsBadMagic() {
        assertThrows(ProtocolException::class.java) { StreamProtocol.parseHeader(header(magic = "uMI2")) }
    }

    @Test
    fun parseHeaderRejectsShortBuffer() {
        assertThrows(ProtocolException::class.java) { StreamProtocol.parseHeader(ByteArray(4)) }
    }

    @Test
    fun parseFrameMetaIsUnsigned() {
        val frame = ByteBuffer.allocate(StreamProtocol.FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(-1)            // seq = 0xFFFFFFFF
            .putInt(0x80000000.toInt())  // ts = 2^31
            .array()
        val meta = StreamProtocol.parseFrameMeta(frame)
        assertEquals(0xFFFFFFFFL, meta.seq)
        assertEquals(0x80000000L, meta.timestampUs)
    }

    @Test
    fun parseFrameMetaLittleEndian() {
        val frame = ByteArray(StreamProtocol.FRAME_BYTES)
        frame[0] = 0x34; frame[1] = 0x12   // seq = 0x1234
        frame[4] = 0x78; frame[5] = 0x56   // ts = 0x5678
        val meta = StreamProtocol.parseFrameMeta(frame)
        assertEquals(0x1234L, meta.seq)
        assertEquals(0x5678L, meta.timestampUs)
    }
}
