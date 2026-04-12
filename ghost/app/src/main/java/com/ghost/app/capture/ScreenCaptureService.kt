package com.ghost.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ghost.app.R

/**
 * Foreground service required for MediaProjection on Android 10+ (API 29+).
 * This service runs while screen capture is active.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "GhostCaptureService"
        private const val NOTIFICATION_CHANNEL_ID = "ghost_capture_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.ghost.app.ACTION_START_CAPTURE"
        const val ACTION_STOP = "com.ghost.app.ACTION_STOP_CAPTURE"
    }

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Ghost Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active while capturing screen"
                setSound(null, null)
                enableVibration(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Ghost")
            .setContentText("Capturing screen for analysis...")
            .setSmallIcon(android.R.drawable.ic_menu_crop) // Using system icon
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
