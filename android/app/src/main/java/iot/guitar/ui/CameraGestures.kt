package iot.guitar.ui

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ZoomState
import androidx.camera.view.PreviewView
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Touch controls for the in-app camera preview:
 *  - pinch = zoom, double-tap = back to 1x, label button = cycle presets (0.5x ultra-wide if the
 *    phone has that lens, then 1x / 2x / 3x)
 *  - single tap = focus + exposure on that spot, with a short ring animation
 * Zoom ratios shown and cycled are *effective* (lens factor x digital zoom), so the ultra-wide
 * lens reads 0.5x. Picking a preset on the other back lens re-binds the camera, hence only while
 * idle; during a take the presets stay on the current lens. [attach] must run after every bind
 * because the ZoomState LiveData belongs to the camera that was bound.
 */
@SuppressLint("ClickableViewAccessibility")   // pinch/double-tap on a live preview has no click equivalent
class CameraGestures(
    private val activity: AppCompatActivity,
    private val previewView: PreviewView,
    private val camera: CameraRecorderController,
    private val label: TextView,
    private val focusRing: View,
) {
    private var observed: LiveData<ZoomState>? = null
    private var pinchRatio = 1f   // accumulated locally: ZoomState lags one frame behind setZoomRatio
    private var switching = false // lens re-bind in flight

    private val pinch = ScaleGestureDetector(activity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            pinchRatio = current()?.zoomRatio ?: return false
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val range = current() ?: return false
            pinchRatio = (pinchRatio * detector.scaleFactor).coerceIn(range.minZoomRatio, range.maxZoomRatio)
            camera.setZoomRatio(pinchRatio)
            return true
        }
    })
    private val taps = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean { applyEffective(1f); return true }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            camera.focusAt(previewView.meteringPointFactory.createPoint(e.x, e.y))
            showFocusRing(e.x, e.y)
            return true
        }
    })

    init {
        previewView.setOnTouchListener { _, event -> pinch.onTouchEvent(event); taps.onTouchEvent(event); true }
        label.setOnClickListener { cyclePreset() }
        label.text = format(1f)
    }

    /** Follows the freshly bound camera's zoom state and drops the previous camera's. */
    fun attach() {
        observed?.removeObservers(activity)
        observed = camera.zoomState?.also { live -> live.observe(activity) { label.text = format(it.zoomRatio * camera.intrinsicZoom) } }
    }

    private fun current(): ZoomState? = camera.zoomState?.value

    /** Jumps to the next preset above the current effective zoom, wrapping around to the widest one. */
    private fun cyclePreset() {
        if (switching) return
        val range = current() ?: return
        val presets = presets(range)
        val now = range.zoomRatio * camera.intrinsicZoom
        applyEffective(presets.firstOrNull { it > now + 0.05f } ?: presets.first())
    }

    private fun presets(range: ZoomState): List<Float> {
        val uw = camera.ultraWideZoom
        // Idle with an ultra-wide lens around: 0.5x plus the default lens' presets (may re-bind).
        if (uw != null && !camera.isRecording) return (listOf(uw) + PRESETS).sorted()
        val k = camera.intrinsicZoom
        return PRESETS.filter { it in range.minZoomRatio..range.maxZoomRatio }.map { it * k }
    }

    /** Sets an effective zoom, switching back lens when the value belongs to the other one. */
    private fun applyEffective(effective: Float) {
        val wantUltraWide = effective < 0.95f
        if (wantUltraWide == camera.isUltraWide) { camera.setZoomRatio(effective / camera.intrinsicZoom); return }
        if (camera.isRecording || switching) return
        switching = true
        activity.lifecycleScope.launch {
            try {
                camera.setUltraWide(wantUltraWide)
                attach()
                camera.setZoomRatio(effective / camera.intrinsicZoom)
            } finally {
                switching = false
            }
        }
    }

    private fun showFocusRing(x: Float, y: Float) {
        focusRing.animate().cancel()
        focusRing.x = x - focusRing.width / 2f
        focusRing.y = y - focusRing.height / 2f
        focusRing.alpha = 1f; focusRing.scaleX = 1.3f; focusRing.scaleY = 1.3f
        focusRing.visibility = View.VISIBLE
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(150).withEndAction {
            focusRing.animate().alpha(0f).setStartDelay(500).setDuration(300).withEndAction { focusRing.visibility = View.INVISIBLE }
        }
    }

    private fun format(ratio: Float): String {
        val tenths = Math.round(ratio * 10) / 10f
        return if (tenths == tenths.toInt().toFloat()) "${tenths.toInt()}×" else String.format(Locale.US, "%.1f×", tenths)
    }

    private companion object {
        val PRESETS = listOf(1f, 2f, 3f)
    }
}
