package com.freebuff.tejaratsmsfwd

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Persistent foreground service (type: remoteMessaging). Keeps the process at
 * high priority so the SMS receiver is not killed between messages, drains the
 * outbound queue and re-arms the watchdog alarm.
 */
class ForwardService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            if (!Store.isEnabled(this@ForwardService)) {
                Store.log(this@ForwardService, "Service stopped")
                stopSelf()
                return
            }
            refreshNotification()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        App.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        handler.removeCallbacks(ticker)
        handler.post(ticker)

        if (Store.isEnabled(this)) {
            Scheduler.scheduleWatchdog(this)
            if (Store.isSmsEnabled(this)) Sender.pump(this)
            if (Store.isHttpEnabled(this)) HttpSender.pump(this)
        } else {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        // Come back quickly in case we were killed or stopped by mistake.
        if (Store.isEnabled(this)) Scheduler.scheduleWatchdog(this, 5_000L)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- notification ----

    private fun startAsForeground() {
        val notif = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    App.NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
                )
            } else {
                startForeground(App.NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            // Manifest-declared type is used by the 2-arg overload; never crash.
            try {
                startForeground(App.NOTIF_ID, notif)
            } catch (e2: Exception) {
                Store.log(this, "Foreground service error: ${e2.javaClass.simpleName}")
                stopSelf()
            }
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val queuedSms = Store.smsQueueSize(this)
        val queuedHttp = Store.httpQueueSize(this)
        val totalQueued = queuedSms + queuedHttp
        val text = if (totalQueued > 0) {
            "$totalQueued message(s) queued ($queuedSms SMS, $queuedHttp HTTP)"
        } else {
            val sources = Store.sources(this)
            val dests = Store.destinations(this)
            val workers = Store.httpWorkers(this)
            if (sources.isEmpty() || (dests.isEmpty() && workers.isEmpty())) {
                "Source or targets not configured"
            } else {
                "${sources.size} source(s) → ${dests.size} SMS, ${workers.size} HTTP"
            }
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, App.CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(System.currentTimeMillis())
            .build()
    }

    private fun refreshNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(App.NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            // Missing POST_NOTIFICATIONS: the service still runs, it is just
            // only visible in the task manager.
        }
    }

    companion object {
        private const val TICK_MS = 30_000L
    }
}
