package dev.finevolume.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.finevolume.app.adb.WirelessAdbController
import javax.inject.Inject

/**
 * Foreground lifecycle owner for FineVolume's self-hosted Wireless ADB shell
 * connection. The app reconnects only to a user-paired local Wireless Debugging
 * endpoint. It never enables Wireless Debugging or grants itself shell access.
 */
@AndroidEntryPoint
class VolumeControlForegroundService : Service() {

    @Inject lateinit var wirelessAdbController: WirelessAdbController

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        wirelessAdbController.startWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        wirelessAdbController.startWatchdog()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wirelessAdbController.stopWatchdog()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "FineVolume Control",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "FineVolume maintains its user-authorized local Wireless ADB audio helper"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FineVolume active")
            .setContentText("Monitoring local Wireless Debugging connection")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "finevolume_control"
    }
}
