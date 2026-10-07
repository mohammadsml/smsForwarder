package com.freebuff.tejaratsmsfwd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** Receives SMS, filters by source, applies template, queues for SMS/HTTP and pumps senders. */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val ctx = context.applicationContext
        if (!Store.isEnabled(ctx)) return

        val messages = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (e: Exception) {
            null
        }
        if (messages == null || messages.isEmpty()) return

        val from = messages[0].displayOriginatingAddress ?: ""
        val body = messages.joinToString("") { it.displayMessageBody ?: "" }
        if (body.isBlank()) return

        if (!Store.matches(ctx, from)) return

        val template = Store.template(ctx)
        val formatted = Store.applyTemplate(template, body)

        val queuedSms = if (Store.isSmsEnabled(ctx)) Store.enqueueSmsAll(ctx, formatted, from) else 0
        val queuedHttp = if (Store.isHttpEnabled(ctx)) Store.enqueueHttpAll(ctx, formatted, body, from) else 0

        if (queuedSms == 0 && queuedHttp == 0) {
            Store.log(ctx, "No active SMS destination or HTTP worker; message from $from not forwarded")
            return
        }

        Store.log(ctx, "Received from $from → $queuedSms SMS, $queuedHttp HTTP queued")

        // Wake the persistent service; when the OS refuses (background start
        // restrictions) pump() still sends the message right here.
        Scheduler.startServiceSafely(ctx)
        if (queuedSms > 0) Sender.pump(ctx)
        if (queuedHttp > 0) HttpSender.pump(ctx)
    }
}
