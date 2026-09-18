package iot.guitar.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import iot.guitar.R
import iot.guitar.audio.WavStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Simple list dialog over [WavStore.listRecordings] (newest first). */
object WavFilePickerDialog {

    /** [entries] must be fetched off the main thread (see MergeActivity). */
    fun show(context: Context, entries: List<WavStore.WavEntry>, onPicked: (WavStore.WavEntry) -> Unit) {
        val builder = AlertDialog.Builder(context).setTitle(R.string.wav_picker_title)
        if (entries.isEmpty()) {
            builder.setMessage(R.string.wav_picker_empty).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val labels = entries.map { describe(it) }.toTypedArray()
        builder.setItems(labels) { _, which -> onPicked(entries[which]) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun describe(e: WavStore.WavEntry): String {
        val date = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(e.dateAddedSec * 1000))
        // 48 kHz x 16-bit mono = 96 000 B/s
        val seconds = (e.sizeBytes - 44).coerceAtLeast(0) / 96_000
        return "${e.displayName}\n$date  •  ${seconds / 60}:${"%02d".format(seconds % 60)}  •  ${e.sizeBytes / 1_000_000} MB"
    }
}
