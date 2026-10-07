package com.freebuff.tejaratsmsfwd

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** Alarm-based keep-alive so the service and pending sends survive process death. */
object Scheduler {

    const val WATCHDOG_MS = 15 * 60 * 1000L
    private const val REQUEST_CODE = 9001

    /** (Re)schedule the periodic watchdog. Inexact: no SCHEDULE_EXACT_ALARM needed. */
    fun scheduleWatchdog(c: Context, delayMs: Long = WATCHDOG_MS) {
        try {
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                c,
                REQUEST_CODE,
                Intent(c, WatchdogReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + delayMs,
                pi
            )
        } catch (e: Exception) {
            Store.log(c, "Keep-alive scheduler failed: ${e.javaClass.simpleName}")
        }
    }

    /** Best-effort foreground service start; background starts can be refused by the OS. */
    fun startServiceSafely(c: Context) {
        try {
            val intent = Intent(c, ForwardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(intent)
            } else {
                c.startService(intent)
            }
        } catch (e: Exception) {
            Store.log(c, "Could not start service: ${e.javaClass.simpleName}")
        }
    }
}
