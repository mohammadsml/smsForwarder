package com.freebuff.tejaratsmsfwd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores the forwarder after reboot, app update or OEM quick-boot. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val ctx = context.applicationContext
        if (!Store.isEnabled(ctx)) return

        Store.log(ctx, "Started after $action")
        Scheduler.scheduleWatchdog(ctx)
        Scheduler.startServiceSafely(ctx)
        if (Store.isSmsEnabled(ctx)) Sender.pump(ctx)
        if (Store.isHttpEnabled(ctx)) HttpSender.pump(ctx)
    }
}
