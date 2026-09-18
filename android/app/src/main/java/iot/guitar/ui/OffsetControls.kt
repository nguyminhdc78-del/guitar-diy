package iot.guitar.ui

import android.widget.Button
import android.widget.TextView
import iot.guitar.R

/**
 * Nudge-button offset editor: each button adds a fixed delta (ms) to `offsetMs`, the
 * label shows the value. Auto-sync sets the value programmatically with [set]; the user
 * only fine-tunes by ear (no slider: +-60 s on a slider is 100+ ms per pixel, unusable).
 * [onChanged] fires for every user-driven change.
 */
class OffsetControls(
    private val label: TextView,
    private val nudges: Map<Button, Long>,
    private val onChanged: (offsetMs: Long) -> Unit,
) {
    var offsetMs: Long = 0
        private set

    init {
        nudges.forEach { (button, delta) -> button.setOnClickListener { set(offsetMs + delta, notify = true) } }
        set(0, notify = false)
    }

    /** Programmatic update (e.g. from auto-sync); does not trigger [onChanged] unless [notify]. */
    fun set(valueMs: Long, notify: Boolean = false) {
        val clamped = (valueMs / STEP_MS * STEP_MS).coerceIn(MIN_MS, MAX_MS)
        offsetMs = clamped
        label.text = label.context.getString(R.string.label_offset, clamped)
        if (notify) onChanged(clamped)
    }

    fun setEnabled(enabled: Boolean) {
        nudges.keys.forEach { it.isEnabled = enabled }
    }

    companion object {
        const val MIN_MS = -60_000L
        const val MAX_MS = 60_000L
        const val STEP_MS = 10L
    }
}
