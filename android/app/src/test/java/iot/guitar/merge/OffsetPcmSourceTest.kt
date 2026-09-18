package iot.guitar.merge

import iot.guitar.audio.WavFileWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class OffsetPcmSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 1 s mono WAV at 48 kHz where sample i == i (fits in int16 for i < 32768). */
    private fun reader(samples: Int = 48000): WavPcmReader {
        val file = tmp.newFile()
        val writer = WavFileWriter(RandomAccessFile(file, "rw").channel, 48000, 1, 16)
        val pcm = ByteArray(samples * 2)
        for (i in 0 until samples) {
            pcm[2 * i] = (i and 0xFF).toByte()
            pcm[2 * i + 1] = ((i shr 8) and 0xFF).toByte()
        }
        writer.write(pcm, 0, pcm.size)
        writer.close()
        return WavPcmReader.open(file)
    }

    private fun sample(chunk: OffsetPcmSource.Chunk, index: Int): Int =
        (chunk.data[2 * index].toInt() and 0xFF) or (chunk.data[2 * index + 1].toInt() shl 8)

    private fun drain(src: OffsetPcmSource): List<Triple<Long, Int, Int>> {
        val out = mutableListOf<Triple<Long, Int, Int>>()   // (ptsUs, length, firstSample)
        while (true) {
            val c = src.next() ?: break
            out += Triple(c.ptsUs, c.length, sample(c, 0))
        }
        return out
    }

    @Test
    fun zeroOffsetStreamsWholeFileWithMonotonicPts() {
        val chunks = drain(OffsetPcmSource(reader(), 0, -1, chunkBytes = 9600))   // 100 ms chunks
        assertEquals(10, chunks.size)
        assertEquals(0L, chunks[0].first)
        assertEquals(100_000L, chunks[1].first)
        assertEquals(900_000L, chunks[9].first)
        assertEquals(4800, chunks[1].third)                  // first sample of second chunk
        assertEquals(96_000, chunks.sumOf { it.second })
    }

    @Test
    fun positiveOffsetPrependsSilence() {
        val src = OffsetPcmSource(reader(), 250, -1, chunkBytes = 9600)
        val chunks = drain(src)
        // 250 ms = 12000 samples = 24000 bytes silence = 2.5 chunks
        assertEquals(0, chunks[0].third)
        assertEquals(0, chunks[1].third)
        assertEquals(9600, chunks[0].second)
        assertEquals(4800, chunks[2].second)                 // remaining 2400 samples of silence
        assertEquals(200_000L, chunks[2].first)
        assertEquals(250_000L, chunks[3].first)              // first WAV chunk lands at +250 ms
        assertEquals(0, chunks[3].third)                     // WAV sample 0
        assertEquals(24_000 + 96_000, chunks.sumOf { it.second })
    }

    @Test
    fun negativeOffsetSkipsWavStart() {
        val chunks = drain(OffsetPcmSource(reader(), -300, -1, chunkBytes = 9600))
        assertEquals(0L, chunks[0].first)
        assertEquals(14_400, chunks[0].third)                // 300 ms * 48 = sample 14400
        assertEquals(96_000 - 28_800, chunks.sumOf { it.second })
    }

    @Test
    fun limitTruncatesToVideoDuration() {
        val src = OffsetPcmSource(reader(), 0, 350_000, chunkBytes = 9600)
        val chunks = drain(src)
        assertEquals(350 * 48 * 2, chunks.sumOf { it.second })   // exactly 350 ms
        assertEquals(4800, chunks.last().second)                  // last chunk shortened
        assertNull(src.next())
    }

    @Test
    fun limitWithSilenceOnly() {
        val src = OffsetPcmSource(reader(), 5000, 100_000, chunkBytes = 9600)
        val chunks = drain(src)
        assertEquals(100 * 48 * 2, chunks.sumOf { it.second })
        assertEquals(0, chunks.last().third)
    }

    @Test
    fun leadSkipShiftsStreamEarlierAndExtendsLimit() {
        // offset +100 ms (4800 samples of silence) minus 2048 pre-roll => 2752 samples of silence first
        val src = OffsetPcmSource(reader(), 100, 200_000, chunkBytes = 9600, leadSkipSamples = 2048)
        val chunks = drain(src)
        assertEquals(2752 * 2, chunks[0].second)
        assertEquals(0, chunks[0].third)
        assertEquals(0, chunks[1].third)                     // WAV sample 0 starts right after the silence
        assertEquals((200 * 48 + 2048) * 2, chunks.sumOf { it.second })   // limit grows by the skip
        // negative start: offset 0 minus pre-roll => WAV starts at sample 2048
        val skipped = drain(OffsetPcmSource(reader(), 0, -1, chunkBytes = 9600, leadSkipSamples = 2048))
        assertEquals(2048, skipped[0].third)
    }

    @Test
    fun emptyWavEndsImmediately() {
        assertNull(OffsetPcmSource(reader(0), 0, -1).next())
    }
}
