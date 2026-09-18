package iot.guitar.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import iot.guitar.R
import iot.guitar.audio.RecentRecordings
import iot.guitar.databinding.ItemRecentRecordingBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Inflates recent-recording rows into a plain LinearLayout (<= 10 rows, no RecyclerView needed). */
class RecentRecordingsView(private val container: LinearLayout, private val emptyView: View) {

    fun bind(items: List<RecentRecordings.Item>) {
        container.removeAllViews()
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(container.context)
        items.forEach { item ->
            val row = ItemRecentRecordingBinding.inflate(inflater, container, false)
            row.icon.setImageResource(if (item.isVideo) R.drawable.ic_movie else R.drawable.ic_audio_file)
            row.icon.imageTintList = ContextCompat.getColorStateList(container.context,
                if (item.isVideo) R.color.umic_amber else R.color.umic_text_dim)
            row.name.text = item.displayName
            row.meta.text = describe(item)
            row.root.setOnClickListener { open(item) }
            container.addView(row.root)
        }
    }

    private fun describe(item: RecentRecordings.Item): String {
        val date = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(item.dateAddedSec * 1000))
        val totalSec = item.durationMs / 1000
        val duration = "%d:%02d".format(totalSec / 60, totalSec % 60)
        val size = "%d MB".format((item.sizeBytes / 1_000_000).coerceAtLeast(1))
        return "$date  •  $duration  •  $size"
    }

    private fun open(item: RecentRecordings.Item) = container.context.openMedia(item.uri, if (item.isVideo) "video/mp4" else "audio/x-wav")
}
