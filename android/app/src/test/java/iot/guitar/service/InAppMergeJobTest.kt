package iot.guitar.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppMergeJobTest {

    @Test
    fun anchorIsWavStartMinusVideoStart() {
        // Camera starts 1.7 s after the first WAV frame => WAV sample 0 sits at -1.7 s on the video timeline.
        assertEquals(-1_700L, InAppMergeJob.anchorOffsetMs(wavStartElapsedMs = 100_000L, videoStartElapsedMs = 101_700L))
        assertEquals(250L, InAppMergeJob.anchorOffsetMs(wavStartElapsedMs = 5_250L, videoStartElapsedMs = 5_000L))
    }

    @Test
    fun fileNamesDeriveFromTheWavName() {
        assertEquals("umic-20260916-140000-raw.mp4", InAppMergeJob.rawVideoName("umic-20260916-140000.wav"))
        assertEquals("umic-20260916-140000.mp4", InAppMergeJob.finalVideoName("umic-20260916-140000.wav"))
    }

    @Test
    fun rawIsDeletedOnlyAfterAConfidentMatch() {
        assertTrue(InAppMergeJob.shouldDeleteRaw(4f))
        assertTrue(InAppMergeJob.shouldDeleteRaw(12.5f))
        assertFalse(InAppMergeJob.shouldDeleteRaw(3.9f))
        assertFalse(InAppMergeJob.shouldDeleteRaw(0f))
    }
}
