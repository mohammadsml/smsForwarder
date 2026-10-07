package com.freebuff.tejaratsmsfwd

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager

/** Turns queue entries into actual SMS sends and reacts to their receipts. */
object Sender {

    /** An in-flight item older than this is considered lost and is retried. */
    const val STALE_MS = 10 * 60 * 1000L

    /** Normalize any international or local phone number worldwide. */
    fun normalizeNumber(raw: String): String {
        val folded = foldDigits(raw).trim()
        if (folded.isEmpty()) return ""

        val hasPlus = folded.startsWith("+")
        val cleanDigits = folded.filter { it.isDigit() }
        if (cleanDigits.isEmpty()) return ""

        return when {
            hasPlus -> "+$cleanDigits"
            cleanDigits.startsWith("00") && cleanDigits.length > 4 -> "+${cleanDigits.substring(2)}"
            else -> cleanDigits
        }
    }

    private fun foldDigits(raw: String): String {
        val persian = "۰۱۲۳۴۵۶۷۸۹"
        val arabic = "٠١٢٣٤٥٦٧٨٩"
        return buildString(raw.length) {
            for (ch in raw) {
                val persianIndex = persian.indexOf(ch)
                val arabicIndex = arabic.indexOf(ch)
                when {
                    persianIndex >= 0 -> append('0' + persianIndex)
                    arabicIndex >= 0 -> append('0' + arabicIndex)
                    else -> append(ch)
                }
            }
        }
    }

    /**
     * Claim and send the next ready message. Safe to call from any entry
     * point (receiver, service, alarm) — the store's lock guarantees only
     * one dispatch is in flight.
     */
    fun pump(c: Context) {
        val app = c.applicationContext
        if (!Store.isEnabled(app)) return

        val item = Store.claimNext(app) ?: return
        val number = normalizeNumber(item.to)

        if (number.length < 3) {
            Store.drop(app, item.id)
            Store.log(app, "Invalid destination number; message discarded")
            pump(c)
            return
        }

        val mgr = SmsManager.getDefault()
        val parts: ArrayList<String> = try {
            mgr.divideMessage(item.body) ?: arrayListOf(item.body)
        } catch (e: Exception) {
            arrayListOf(item.body)
        }
        val total = parts.size.coerceAtLeast(1)

        try {
            if (total > 1) {
                val intents = ArrayList<PendingIntent>(total)
                for (i in 0 until total) intents.add(sentIntent(app, item.id, i + 1, total))
                mgr.sendMultipartTextMessage(number, null, parts, intents, null)
            } else {
                mgr.sendTextMessage(number, null, item.body, sentIntent(app, item.id, 1, 1), null)
            }
            Store.log(app, "Sending #${item.id} to $number (attempt ${item.attempts})")
        } catch (e: Exception) {
            Store.log(app, "Send error: ${e.javaClass.simpleName}")
            Store.applyAck(app, item.id, partOk = false, totalParts = total)
        }
    }

    private fun sentIntent(c: Context, id: Long, part: Int, total: Int): PendingIntent {
        val intent = Intent(c, SentReceiver::class.java).apply {
            putExtra("id", id)
            putExtra("part", part)
            putExtra("total", total)
        }
        val requestCode = (id * 64 + part).toInt()
        return PendingIntent.getBroadcast(
            c,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
