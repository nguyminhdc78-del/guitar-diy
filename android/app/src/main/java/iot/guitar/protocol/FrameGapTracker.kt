package iot.guitar.protocol

/**
 * Reconstructs a gap-free timeline from frame sequence numbers.
 *
 * Receiver rule (protocol v1): `gap = seq - expectedSeq`. `gap > 0` => the ESP32 dropped
 * `gap` frames, caller writes `gap * FRAME_PCM_BYTES` of silence; `gap < 0` => stale or
 * duplicate frame, caller ignores it. Not thread-safe: use from the receive thread only.
 */
class FrameGapTracker {
    private var expected: Long? = null

    /** Total frames lost so far (sum of positive gaps). */
    var totalDropped: Long = 0
        private set

    /** Frames accepted so far. */
    var totalAccepted: Long = 0
        private set

    /**
     * Registers an incoming frame.
     * @return number of missing frames before this one (>= 0), or -1 if the frame is stale.
     */
    fun onFrame(seq: Long): Int {
        val exp = expected
        if (exp == null) {
            expected = seq + 1
            totalAccepted++
            return 0
        }
        val gap = seq - exp
        return when {
            gap < 0 -> -1
            gap > 0 -> {
                totalDropped += gap
                totalAccepted++
                expected = seq + 1
                gap.toInt()
            }
            else -> {
                totalAccepted++
                expected = exp + 1
                0
            }
        }
    }

    fun reset() {
        expected = null
        totalDropped = 0
        totalAccepted = 0
    }
}
