package iot.guitar.sync

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Normalised cross-correlation of two onset curves over a bounded lag range.
 *
 * `bestLag(video, wav, maxLag)` returns the lag `L` maximising `sum_i video[i+L] * wav[i]`,
 * i.e. how many hops later the shared event (clap) occurs in the video than in the WAV.
 * With 10 ms hops, `offsetMs = L * 10` matches the merge offset convention
 * (`tClapVideo - tClapWav`).
 *
 * Confidence = peak-to-sidelobe ratio: peak divided by the largest |correlation| found
 * more than [SIDELOBE_EXCLUSION_HOPS] away from the peak. Random noise gives ~1-1.5,
 * a real shared clap gives >> 4.
 *
 * Feed [sharpen]ed onset curves: squaring makes the clap dominate speech onsets and the
 * short box smoothing lets spikes that differ by a few hops between the two microphones
 * (AGC, distance, envelope smearing) still overlap. Measured on real takes: a single
 * clear clap went from confidence 4.4 to ~30, mismatched recordings stayed below 2.5.
 */
object EnvelopeCrossCorrelator {

    /**
     * [confidence] = peak-to-sidelobe ratio; [correlation] = Pearson r of the two curves at
     * the best lag (scale-free; 0.3+ over a long overlap is unmistakable even when PSR is low
     * because speech makes several nearby lags plausible).
     */
    data class LagResult(val lagHops: Int, val confidence: Float, val peak: Float, val correlation: Float = 0f)

    const val LOW_CONFIDENCE = 4f
    const val MIN_CORRELATION = 0.3f

    /** A match is trusted when the peak is isolated (PSR) or the curves agree linearly (Pearson). */
    fun LagResult.isTrusted(): Boolean = confidence >= LOW_CONFIDENCE || correlation >= MIN_CORRELATION
    const val SHARPEN_SMOOTH_HOPS = 5           // +-20 ms tolerance at 10 ms hops
    const val ANCHOR_SIGMA_HOPS = 100           // clock anchors are good to about +-1 s
    private const val SIDELOBE_EXCLUSION_HOPS = 10
    private const val MIN_OVERLAP_HOPS = 100

    /** Squares a normalised onset curve and box-smooths it over [smoothHops] hops. */
    fun sharpen(onset: FloatArray, smoothHops: Int = SHARPEN_SMOOTH_HOPS): FloatArray {
        val sq = FloatArray(onset.size) { onset[it] * onset[it] }
        if (smoothHops <= 1 || sq.isEmpty()) return sq
        val half = smoothHops / 2
        val out = FloatArray(sq.size)
        var sum = 0f
        var lo = 0
        var hi = -1
        for (i in sq.indices) {
            while (hi < minOf(sq.size - 1, i + half)) sum += sq[++hi]
            while (lo < i - half) sum -= sq[lo++]
            out[i] = sum / smoothHops
        }
        return out
    }

    fun bestLag(a: FloatArray, b: FloatArray, maxLag: Int): LagResult = bestLag(a, b, -maxLag, maxLag)

    /** Same as above but over an arbitrary lag range [minLag, maxLag] (hops). */
    fun bestLag(a: FloatArray, b: FloatArray, minLag: Int, maxLag: Int): LagResult =
        search(a, b, minLag, maxLag) { 1f }

    /**
     * Search within +-[windowHops] of a wall-clock [anchorLag], weighting each lag by
     * `1 / (1 + ((lag - anchor) / sigma)^2)` so that, when several events fit (repeated
     * claps), the one closest to the clock estimate wins and the confidence reflects it.
     */
    fun bestLagAnchored(a: FloatArray, b: FloatArray, anchorLag: Int, windowHops: Int, sigmaHops: Int = ANCHOR_SIGMA_HOPS): LagResult =
        search(a, b, anchorLag - windowHops, anchorLag + windowHops) { lag ->
            val d = (lag - anchorLag).toFloat() / sigmaHops
            1f / (1f + d * d)
        }

    private fun search(a: FloatArray, b: FloatArray, minLag: Int, maxLag: Int, weight: (Int) -> Float): LagResult {
        if (a.isEmpty() || b.isEmpty() || minLag > maxLag) return LagResult(0, 0f, 0f)
        val am = meanRemoved(a)
        val bm = meanRemoved(b)
        val corr = FloatArray(maxLag - minLag + 1) { Float.NaN }   // unweighted, for the PSR

        var best = Float.NEGATIVE_INFINITY
        var bestLag = 0
        for (lag in minLag..maxLag) {
            val start = max(0, -lag)
            val end = min(bm.size, am.size - lag)
            val overlap = end - start
            if (overlap < MIN_OVERLAP_HOPS) continue
            var s = 0.0
            for (i in start until end) s += am[i + lag].toDouble() * bm[i]
            val raw = (s / overlap).toFloat()
            corr[lag - minLag] = raw
            val v = raw * weight(lag)   // the anchor prior only decides WHICH peak wins
            if (v > best) {
                best = v
                bestLag = lag
            }
        }
        if (best == Float.NEGATIVE_INFINITY) return LagResult(0, 0f, 0f)

        // PSR on the unweighted correlation so the prior cannot inflate confidence near the anchor.
        val peak = corr[bestLag - minLag]
        var sidelobe = 0f
        for (lag in minLag..maxLag) {
            val v = corr[lag - minLag]
            if (v.isNaN() || abs(lag - bestLag) <= SIDELOBE_EXCLUSION_HOPS) continue
            if (abs(v) > sidelobe) sidelobe = abs(v)
        }
        val confidence = if (peak <= 0f) 0f else peak / (sidelobe + 1e-9f)
        return LagResult(bestLag, confidence, peak, pearson(a, b, bestLag))
    }

    /** Pearson correlation of a[i+lag] vs b[i] over the overlap, with means taken over that overlap. */
    private fun pearson(a: FloatArray, b: FloatArray, lag: Int): Float {
        val start = max(0, -lag)
        val end = min(b.size, a.size - lag)
        val n = end - start
        if (n < MIN_OVERLAP_HOPS) return 0f
        var sx = 0.0
        var sy = 0.0
        for (i in start until end) { sx += a[i + lag]; sy += b[i] }
        val mx = sx / n
        val my = sy / n
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in start until end) {
            val x = a[i + lag] - mx
            val y = b[i] - my
            sxy += x * y; sxx += x * x; syy += y * y
        }
        val denom = Math.sqrt(sxx * syy)
        return if (denom <= 0.0) 0f else (sxy / denom).toFloat()
    }

    private fun meanRemoved(x: FloatArray): FloatArray {
        var sum = 0.0
        for (v in x) sum += v
        val mean = (sum / x.size).toFloat()
        return FloatArray(x.size) { x[it] - mean }
    }
}
