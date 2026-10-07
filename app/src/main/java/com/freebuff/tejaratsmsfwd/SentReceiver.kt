package com.freebuff.tejaratsmsfwd

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager

/** Handles the per-message delivery receipt and drives retries / the next send. */
class SentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val id = intent.getLongExtra("id", -1L)
        if (id < 0) return
        val part = intent.getIntExtra("part", 1)
        val total = intent.getIntExtra("total", 1).coerceAtLeast(1)
        val ok = resultCode == Activity.RESULT_OK

        val result = Store.applyAck(ctx, id, partOk = ok, totalParts = total)
        when {
            result == AckResult.REMOVED ->
                Store.log(ctx, "Sent #$id ✓")

            result == AckResult.RETRY_SCHEDULED -> {
                val code = if (resultCode == SmsManager.RESULT_ERROR_GENERIC_FAILURE) "generic" else "code=$resultCode"
                Store.log(ctx, "Failed #$id ($code) → Retrying")
                // Ask for a retry sooner than the regular watchdog tick.
                val wait = 30_000L
                Scheduler.scheduleWatchdog(ctx, wait)
            }

            ok && part < total -> return
        }

        if (result == AckResult.REMOVED || result == AckResult.IGNORED) {
            Scheduler.startServiceSafely(ctx)
            Sender.pump(ctx)
        }
    }
}
