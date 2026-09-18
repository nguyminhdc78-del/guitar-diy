package iot.guitar.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameGapTrackerTest {

    @Test
    fun inOrderFramesHaveNoGap() {
        val t = FrameGapTracker()
        assertEquals(0, t.onFrame(10))
        assertEquals(0, t.onFrame(11))
        assertEquals(0, t.onFrame(12))
        assertEquals(0L, t.totalDropped)
        assertEquals(3L, t.totalAccepted)
    }

    @Test
    fun forwardJumpReportsGapAndAccumulates() {
        val t = FrameGapTracker()
        t.onFrame(0)
        assertEquals(3, t.onFrame(4))     // 1,2,3 missing
        assertEquals(0, t.onFrame(5))
        assertEquals(1, t.onFrame(7))     // 6 missing
        assertEquals(4L, t.totalDropped)
        assertEquals(4L, t.totalAccepted)
    }

    @Test
    fun duplicateOrStaleFrameIsIgnored() {
        val t = FrameGapTracker()
        t.onFrame(100)
        t.onFrame(101)
        assertEquals(-1, t.onFrame(101))
        assertEquals(-1, t.onFrame(50))
        assertEquals(0, t.onFrame(102))
        assertEquals(0L, t.totalDropped)
        assertEquals(3L, t.totalAccepted)
    }

    @Test
    fun firstFrameCanStartAnywhere() {
        val t = FrameGapTracker()
        assertEquals(0, t.onFrame(123456))
        assertEquals(0, t.onFrame(123457))
    }

    @Test
    fun resetForgetsSequence() {
        val t = FrameGapTracker()
        t.onFrame(5)
        t.reset()
        assertEquals(0, t.onFrame(1))
        assertEquals(0L, t.totalDropped)
    }
}
