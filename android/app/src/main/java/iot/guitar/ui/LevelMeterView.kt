package iot.guitar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import iot.guitar.R

/**
 * Horizontal dBFS bar (-60..0 dB) with green/yellow/red zones and a peak-hold line that
 * decays at 20 dB/s. Call [setLevel] from the UI thread (~10 Hz).
 */
class LevelMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val bgPaint = Paint().apply { color = ContextCompat.getColor(context, R.color.umic_surface_high) }
    private val greenPaint = Paint().apply { color = ContextCompat.getColor(context, R.color.umic_green) }
    private val yellowPaint = Paint().apply { color = ContextCompat.getColor(context, R.color.umic_yellow) }
    private val redPaint = Paint().apply { color = ContextCompat.getColor(context, R.color.umic_red) }
    private val peakPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.umic_text)
        strokeWidth = 4f
    }
    private val rect = RectF()
    private val clipPath = android.graphics.Path()

    private var rmsDb = MIN_DB
    private var peakHoldDb = MIN_DB
    private var peakHoldAtMs = 0L

    fun setLevel(rmsDb: Float, peakDb: Float) {
        this.rmsDb = rmsDb.coerceIn(MIN_DB, MAX_DB)
        val now = SystemClock.elapsedRealtime()
        val decayed = peakHoldDb - PEAK_DECAY_DB_PER_S * (now - peakHoldAtMs) / 1000f
        val incoming = peakDb.coerceIn(MIN_DB, MAX_DB)
        if (incoming >= decayed) {
            peakHoldDb = incoming
            peakHoldAtMs = now
        }
        invalidate()
    }

    fun reset() = setLevel(MIN_DB, MIN_DB)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        val corner = h / 2f   // pill shape at any height (56dp card meter, 12dp camera overlay)
        canvas.drawRoundRect(rect, corner, corner, bgPaint)
        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(rect, corner, corner, android.graphics.Path.Direction.CW)
        canvas.clipPath(clipPath)

        val fill = w * fraction(rmsDb)
        // Zones: green < -18 dB, yellow -18..-6 dB, red > -6 dB.
        val yellowStart = w * fraction(YELLOW_DB)
        val redStart = w * fraction(RED_DB)
        if (fill > 0f) canvas.drawRect(0f, 0f, minOf(fill, yellowStart), h, greenPaint)
        if (fill > yellowStart) canvas.drawRect(yellowStart, 0f, minOf(fill, redStart), h, yellowPaint)
        if (fill > redStart) canvas.drawRect(redStart, 0f, fill, h, redPaint)

        val now = SystemClock.elapsedRealtime()
        val peakNow = peakHoldDb - PEAK_DECAY_DB_PER_S * (now - peakHoldAtMs) / 1000f
        if (peakNow > MIN_DB) {
            val x = w * fraction(peakNow)
            canvas.drawLine(x, 0f, x, h, peakPaint)
            postInvalidateOnAnimation()
        }
        canvas.restore()
    }

    private fun fraction(db: Float): Float = ((db - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f)

    companion object {
        const val MIN_DB = -60f
        const val MAX_DB = 0f
        private const val YELLOW_DB = -18f
        private const val RED_DB = -6f
        private const val PEAK_DECAY_DB_PER_S = 20f
    }
}
