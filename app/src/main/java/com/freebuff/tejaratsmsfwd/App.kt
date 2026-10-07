package com.freebuff.tejaratsmsfwd

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    companion object {
        const val CHANNEL_ID = "fwd_channel"
        const val NOTIF_ID = 4213

        /** Creates the low-importance ongoing channel (idempotent). */
        fun ensureChannel(c: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = c.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SMS Forwarding Service",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            channel.description = "Displays SMS forwarding status"
            manager.createNotificationChannel(channel)
        }
    }
}
