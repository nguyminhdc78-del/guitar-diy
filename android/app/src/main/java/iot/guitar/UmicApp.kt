package iot.guitar

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import iot.guitar.service.RecordingNotification

class UmicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Dark-only design system: framework dialogs and pickers must match.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        RecordingNotification.createChannel(this)
    }
}
