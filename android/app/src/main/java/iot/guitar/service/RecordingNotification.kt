package iot.guitar.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import iot.guitar.R
import iot.guitar.ui.RecordExternalActivity
import iot.guitar.ui.RecordInAppActivity

/** Foreground-service notification (recording / merging progress) and the one-shot merge result. */
object RecordingNotification {
    const val CHANNEL_ID = "umic_recording"
    const val NOTIFICATION_ID = 1
    const val RESULT_NOTIFICATION_ID = 2
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun createChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, state: RecorderState): Notification {
        val merging = state.phase == RecorderPhase.MERGING
        val title = context.getString(if (merging) R.string.notif_merging_title else R.string.notif_title)
        val text = when (state.phase) {
            RecorderPhase.RECORDING -> context.getString(R.string.notif_text, formatElapsed(state.elapsedMs), state.framesDropped)
            RecorderPhase.MERGING -> context.getString(R.string.notif_merging, state.mergePercent)
            RecorderPhase.STANDBY -> context.getString(R.string.notif_standby)
            else -> context.getString(R.string.notif_connecting)
        }
        val target = if (state.mode == RecordMode.IN_APP) RecordInAppActivity::class.java else RecordExternalActivity::class.java
        val openIntent = PendingIntent.getActivity(context, 0, Intent(context, target), FLAGS)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (merging) {
            builder.setProgress(100, state.mergePercent, false)
        } else {
            val stopIntent = PendingIntent.getService(context, 1,
                Intent(context, AudioReceiverService::class.java).setAction(RecorderCommands.ACTION_STOP), FLAGS)
            builder.addAction(0, context.getString(R.string.notif_action_stop), stopIntent)
        }
        return builder.build()
    }

    fun update(context: Context, state: RecorderState) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, build(context, state))
    }

    /** Non-ongoing result of an in-app merge; tapping opens the video when there is one. */
    fun showResult(context: Context, text: String, openUri: Uri?) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setSilent(true)
        if (openUri != null) {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(openUri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            builder.setContentIntent(PendingIntent.getActivity(context, 2, view, FLAGS))
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(RESULT_NOTIFICATION_ID, builder.build())
    }

    /** mm:ss or h:mm:ss. */
    fun formatElapsed(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
