package iot.guitar.ui

import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import iot.guitar.R
import iot.guitar.service.RecorderPhase
import iot.guitar.service.RecorderState
import iot.guitar.service.RecordingNotification

/**
 * Renders a [RecorderState] as chip text + coloured dot (idle dim, connecting yellow, recording red,
 * error red). A mic wiring fault reported by the ESP32 overrides the text while connected.
 */
fun Chip.bindRecorderState(state: RecorderState, showIdle: Boolean = true) {
    val (textRes, colorRes) = when (state.phase) {
        RecorderPhase.IDLE -> R.string.chip_idle to R.color.umic_text_dim
        RecorderPhase.CONNECTING_WIFI, RecorderPhase.CONNECTING_TCP -> R.string.chip_connecting to R.color.umic_yellow
        RecorderPhase.STANDBY -> R.string.chip_standby to R.color.umic_green
        RecorderPhase.RECORDING -> R.string.chip_recording to R.color.umic_red
        RecorderPhase.STOPPING -> R.string.chip_stopping to R.color.umic_yellow
        RecorderPhase.MERGING -> R.string.chip_merging to R.color.umic_amber
        RecorderPhase.ERROR -> R.string.chip_error to R.color.umic_red
    }
    val micFault = !state.micSignal && state.isConnected
    text = when {
        micFault -> context.getString(R.string.chip_mic_fault)
        state.phase == RecorderPhase.RECORDING -> context.getString(textRes, RecordingNotification.formatElapsed(state.elapsedMs))
        state.phase == RecorderPhase.MERGING -> context.getString(textRes, state.mergePercent)
        else -> context.getString(textRes)
    }
    chipIconTint = ColorStateList.valueOf(ContextCompat.getColor(context, if (micFault) R.color.umic_yellow else colorRes))
    visibility = if (!showIdle && state.phase == RecorderPhase.IDLE) android.view.View.GONE else android.view.View.VISIBLE
}
