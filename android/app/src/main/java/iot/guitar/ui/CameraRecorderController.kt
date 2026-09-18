package iot.guitar.ui

import android.content.ContentValues
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import iot.guitar.merge.MediaStoreVideoSaver
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CameraX preview + video recording bound to the activity lifecycle. The recording is
 * written straight to a pending MediaStore entry in Movies/uMIC; [Listener.onStarted]
 * carries the monotonic start time used as the in-app sync anchor.
 */
class CameraRecorderController(
    private val activity: AppCompatActivity,
    private val previewView: PreviewView,
    private val listener: Listener,
) {
    interface Listener {
        /** `VideoRecordEvent.Start` received; [videoStartElapsedMs] = SystemClock.elapsedRealtime(). */
        fun onStarted(videoStartElapsedMs: Long)
        /** Recording finalized. [uri] null when no usable file was produced. */
        fun onFinalized(uri: Uri?, error: Int, cause: Throwable?)
    }

    private var boundCamera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var ultraWideInfo: CameraInfo? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    /**
     * Best estimate of when video frame 0 was captured (SystemClock.elapsedRealtime, ms).
     * `Start` fires several hundred ms after frame 0 on this pipeline, so every `Status`
     * event refines it as `now - recordedDuration`; dispatch delay only makes an estimate
     * later, never earlier, hence the running minimum.
     */
    var videoStartEstimateMs: Long = Long.MAX_VALUE
        private set

    val isRecording: Boolean
        get() = recording != null

    /** Field-of-view factor of the bound lens vs. the default back camera (~0.5-0.6 on an ultra-wide). */
    val intrinsicZoom: Float
        get() = boundCamera?.cameraInfo?.intrinsicZoomRatio ?: 1f
    val isUltraWide: Boolean
        get() = intrinsicZoom < ULTRA_WIDE_MAX
    /** Effective zoom of the ultra-wide back lens at 1x, or null when the phone exposes none (or front lens is up). */
    val ultraWideZoom: Float?
        get() = ultraWideInfo?.intrinsicZoomRatio?.takeIf { lensFacing == CameraSelector.LENS_FACING_BACK }

    /**
     * (Re)binds preview + recorder for [lensFacing]; [ultraWide] picks the ultra-wide back camera when
     * the phone exposes one as its own camera (Samsung, Pixel). Idle only: re-binding ends a recording.
     */
    suspend fun bind(lensFacing: Int = this.lensFacing, ultraWide: Boolean = false) {
        val provider = awaitProvider()
        this.lensFacing = lensFacing
        ultraWideInfo = provider.availableCameraInfos
            .filter { it.lensFacing == CameraSelector.LENS_FACING_BACK && it.intrinsicZoomRatio < ULTRA_WIDE_MAX }
            .minByOrNull { it.intrinsicZoomRatio }
        val selector = ultraWideInfo?.cameraSelector?.takeIf { ultraWide && lensFacing == CameraSelector.LENS_FACING_BACK }
            ?: CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.fromOrderedList(listOf(Quality.FHD, Quality.HD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)))
            .build()
        val capture = VideoCapture.withOutput(recorder)
        provider.unbindAll()
        boundCamera = provider.bindToLifecycle(activity, selector, preview, capture)
        videoCapture = capture
        Log.i(TAG, "bound facing=$lensFacing ultraWide=$isUltraWide intrinsic=$intrinsicZoom lenses=" +
            provider.availableCameraInfos.map { "${it.lensFacing}:${it.intrinsicZoomRatio}" })
    }

    /** Swaps default <-> ultra-wide back lens; falls back to the default lens if the swap fails. */
    suspend fun setUltraWide(on: Boolean) {
        if (isRecording || on == isUltraWide) return
        try {
            bind(lensFacing, on)
        } catch (e: Exception) {
            Log.w(TAG, "lens switch failed", e)
            bind(lensFacing, false)
        }
    }

    /** Zoom range + current ratio of the bound camera; a new LiveData after every [bind]. */
    val zoomState: LiveData<ZoomState>?
        get() = boundCamera?.cameraInfo?.zoomState

    /** One-shot AF + AE on [point]; the camera returns to continuous mode by itself after a few seconds. */
    fun focusAt(point: MeteringPoint) {
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(FOCUS_HOLD_S, TimeUnit.SECONDS).build()
        boundCamera?.cameraControl?.startFocusAndMetering(action)
    }

    /** Clamped to the camera's range; no-op until bound. Allowed while recording. */
    fun setZoomRatio(ratio: Float) {
        val cam = boundCamera ?: return
        val range = cam.cameraInfo.zoomState.value ?: return
        cam.cameraControl.setZoomRatio(ratio.coerceIn(range.minZoomRatio, range.maxZoomRatio))
    }

    /** Starts recording to `Movies/uMIC/<displayName>`; [withAudio] needs RECORD_AUDIO (sync-only track). */
    fun start(displayName: String, withAudio: Boolean) {
        val capture = videoCapture ?: return
        videoStartEstimateMs = Long.MAX_VALUE
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, MediaStoreVideoSaver.RELATIVE_PATH)
        }
        val options = MediaStoreOutputOptions.Builder(activity.contentResolver,
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)).setContentValues(values).build()
        var pending = capture.output.prepareRecording(activity, options)
        if (withAudio) pending = pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(activity)) { event -> onEvent(event) }
    }

    fun stop() {
        recording?.stop()
        recording = null
    }

    fun updateTargetRotation(rotation: Int) {
        videoCapture?.targetRotation = rotation
    }

    private fun onEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                val now = SystemClock.elapsedRealtime()
                videoStartEstimateMs = minOf(videoStartEstimateMs, now)
                Log.i(TAG, "recording started elapsed=$now")
                listener.onStarted(now)
            }
            is VideoRecordEvent.Status -> {
                val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000
                if (durationMs > 0) videoStartEstimateMs = minOf(videoStartEstimateMs, SystemClock.elapsedRealtime() - durationMs)
            }
            is VideoRecordEvent.Finalize -> {
                recording = null
                val uri = event.outputResults.outputUri.takeIf { it != Uri.EMPTY }
                Log.i(TAG, "finalized uri=$uri error=${event.error} videoStartEstimate=$videoStartEstimateMs")
                listener.onFinalized(uri, event.error, event.cause)
            }
            else -> Unit   // Pause/Resume unused
        }
    }

    private suspend fun awaitProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            try {
                cont.resume(future.get())
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    companion object {
        private const val TAG = "CameraRecorder"
        private const val FOCUS_HOLD_S = 5L
        private const val ULTRA_WIDE_MAX = 0.95f   // intrinsic zoom below this = ultra-wide lens
    }
}
