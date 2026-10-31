package dev.finevolume.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FineVolumeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Hidden API exemptions are deliberately not enabled in the normal app
        // process. They are applied only inside FineVolumeShellServer after it
        // has been launched by the user's own ADB Wireless Debugging session.
    }
}
