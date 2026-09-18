package iot.guitar.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import androidx.core.content.ContextCompat
import iot.guitar.R
import iot.guitar.databinding.ActivityRecordInAppBinding
import iot.guitar.service.RecorderPhase
import iot.guitar.service.RecorderState
import iot.guitar.service.RecordingNotification
import iot.guitar.sync.EnvelopeCrossCorrelator

/** Pure view binding for the in-app record screen: (service state, local camera state) -> widgets. */
class RecordInAppUiBinder(private val binding: ActivityRecordInAppBinding) {

    enum class Local { IDLE, ARMING, CAM_STARTING, RECORDING, FINALIZING }

    private val ctx = binding.root.context
    private val pulse = ObjectAnimator.ofFloat(binding.recIndicator, View.ALPHA, 1f, 0.2f).apply {
        duration = 500
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
    }

    fun render(state: RecorderState, local: Local, message: String?, cameraReady: Boolean, cameraDenied: Boolean = false) {
        val recording = local == Local.RECORDING
        val merging = state.phase == RecorderPhase.MERGING
        val busy = local != Local.IDLE || state.isBusy

        binding.chipStatus.bindRecorderState(state)
        if (local == Local.ARMING) binding.chipStatus.setText(R.string.status_arming)
        if (local == Local.CAM_STARTING) binding.chipStatus.setText(R.string.status_cam_starting)
        if (local == Local.FINALIZING) binding.chipStatus.setText(R.string.status_finalizing)

        binding.timerGroup.visibility = if (recording) View.VISIBLE else View.GONE
        binding.textTimer.text = RecordingNotification.formatElapsed(state.elapsedMs)
        if (recording && !pulse.isStarted) pulse.start() else if (!recording && pulse.isStarted) { pulse.cancel(); binding.recIndicator.alpha = 1f }
        binding.textDrops.visibility = if (recording) View.VISIBLE else View.GONE
        binding.textDrops.text = ctx.getString(R.string.label_drops_short, state.framesDropped)
        if (state.phase == RecorderPhase.RECORDING) binding.levelMeter.setLevel(state.rmsDb, state.peakDb) else binding.levelMeter.reset()

        binding.btnRecord.isEnabled = cameraReady && (local == Local.IDLE && !state.isBusy || recording)
        binding.btnRecord.setImageResource(if (recording) R.drawable.ic_stop else R.drawable.ic_rec)
        binding.btnRecord.backgroundTintList = ContextCompat.getColorStateList(ctx, if (recording) R.color.umic_red else R.color.umic_amber)
        binding.btnRecord.imageTintList = ContextCompat.getColorStateList(ctx, if (recording) R.color.umic_text else R.color.umic_on_amber)
        binding.btnRecord.contentDescription = ctx.getString(if (recording) R.string.cd_stop else R.string.cd_record)
        binding.textRecLabel.text = ctx.getString(when {
            recording && state.controlConnected -> R.string.hint_button_stop
            recording -> R.string.cd_stop
            state.controlConnected && local == Local.IDLE -> R.string.hint_button_ready
            else -> R.string.cd_record
        })
        binding.btnFlipCamera.visibility = if (busy) View.INVISIBLE else View.VISIBLE
        binding.btnBack.visibility = if (local == Local.IDLE) View.VISIBLE else View.INVISIBLE
        binding.switchManualWifi.visibility = if (busy) View.GONE else View.VISIBLE

        binding.progressMerge.visibility = if (merging) View.VISIBLE else View.GONE
        if (merging) binding.progressMerge.setProgressCompat(state.mergePercent, true)

        val text = message ?: ctx.getString(R.string.warn_mic_no_signal).takeIf { !state.micSignal && state.isConnected } ?: mergeResultText(state)
        binding.textMessage.text = text ?: ""
        binding.textMessage.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        binding.btnOpenResult.setText(if (cameraDenied) R.string.btn_open_settings else R.string.btn_open_result)
        binding.btnOpenResult.visibility =
            if (cameraDenied || (!busy && state.mergedVideoUri != null && message == null)) View.VISIBLE else View.GONE
    }

    /** Same wording as the service's result notification, derived from the terminal state. */
    private fun mergeResultText(state: RecorderState): String? {
        if (state.phase == RecorderPhase.ERROR) return state.errorMessage
        if (state.phase == RecorderPhase.MERGING) return ctx.getString(R.string.notif_merging, state.mergePercent)
        val uri = state.mergedVideoUri ?: return null
        val name = state.fileName ?: uri.lastPathSegment ?: ""
        val verdict = ctx.getString(when {
            state.lastSyncConfidence >= EnvelopeCrossCorrelator.LOW_CONFIDENCE -> R.string.inapp_sync_good
            state.lastSyncConfidence <= 0f -> R.string.inapp_sync_clock_only
            else -> R.string.inapp_sync_unsure_kept_raw
        })
        return "$verdict ${ctx.getString(R.string.inapp_merge_done, name)}"
    }
}
