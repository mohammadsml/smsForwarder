package com.freebuff.tejaratsmsfwd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fired by the 15-minute watchdog alarm (and whenever the service is torn
 * down): revives the foreground service, flushes anything left in the queues
 * and re-arms itself.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        if (!Store.isEnabled(ctx)) return

        if (Store.isSmsEnabled(ctx)) Sender.pump(ctx)
        if (Store.isHttpEnabled(ctx)) HttpSender.pump(ctx)

        Scheduler.startServiceSafely(ctx)
        Scheduler.scheduleWatchdog(ctx)

        val smsQueue = Store.smsQueueSize(ctx)
        val httpQueue = Store.httpQueueSize(ctx)
        val total = smsQueue + httpQueue
        if (total > 0) {
            Store.log(ctx, "Queue check: $total message(s) remaining ($smsQueue SMS, $httpQueue HTTP)")
        }
    }
}
