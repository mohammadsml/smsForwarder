package com.freebuff.tejaratsmsfwd

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A single outgoing forward request for SMS. */
data class QueuedSms(
    val id: Long,
    val body: String,
    val from: String,
    val to: String = "",
    val ts: Long,
    var attempts: Int = 0,
    var inflight: Boolean = false,
    var sentAt: Long = 0L,
    var partsOk: Int = 0,
    var nextRetryAt: Long = 0L
)

/** A single outgoing forward request for HTTP POST. */
data class QueuedHttp(
    val id: Long,
    val url: String,
    val from: String,
    val text: String,
    val rawText: String,
    val ts: Long,
    var attempts: Int = 0,
    var inflight: Boolean = false,
    var sentAt: Long = 0L,
    var nextRetryAt: Long = 0L
)

enum class AckResult { REMOVED, RETRY_SCHEDULED, IGNORED }

/**
 * All app state lives here: settings, an append-only log, and the durable
 * outbound queues (SMS and HTTP). Everything is guarded by a single lock so
 * receivers, the service and alarm callbacks never deadlock or double-send.
 */
object Store {

    private const val PREFS = "tejarat_fwd"

    private const val K_ENABLED = "enabled"
    private const val K_SMS_ENABLED = "sms_enabled"
    private const val K_HTTP_ENABLED = "http_enabled"
    private const val K_TARGET = "target"
    private const val K_HEADERS = "headers"
    private const val K_SOURCES = "sources"
    private const val K_DESTS = "destinations"
    private const val K_WORKERS = "http_workers"
    private const val K_TEMPLATE = "template"
    const val DEFAULT_HTTP_JSON = "{\n  \"from\": \"{from}\",\n  \"text\": \"{text}\",\n  \"rawText\": \"{rawText}\",\n  \"timestamp\": {timestamp},\n  \"date\": \"{date}\"\n}"
    private const val K_HTTP_JSON = "http_json_template"
    private const val K_QUEUE = "queue"
    private const val K_HTTP_QUEUE = "http_queue"
    private const val K_LOG = "log"
    private const val K_HTTP_LOG = "http_traffic_log"
    private const val HTTP_LOG_DELIMITER = "\n===ENTRY===\n"
    private const val K_SEQ = "seq"
    private const val K_HTTP_SEQ = "http_seq"

    private val lock = Any()

    private fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- settings ----

    fun isSmsEnabled(c: Context): Boolean {
        val p = prefs(c)
        return if (p.contains(K_SMS_ENABLED)) {
            p.getBoolean(K_SMS_ENABLED, true)
        } else {
            p.getBoolean(K_ENABLED, true)
        }
    }

    fun setSmsEnabled(c: Context, value: Boolean) {
        prefs(c).edit().putBoolean(K_SMS_ENABLED, value).apply()
    }

    fun isHttpEnabled(c: Context): Boolean = prefs(c).getBoolean(K_HTTP_ENABLED, false)

    fun setHttpEnabled(c: Context, value: Boolean) {
        prefs(c).edit().putBoolean(K_HTTP_ENABLED, value).apply()
    }

    /** App is enabled if either SMS or HTTP forwarding is enabled. */
    fun isEnabled(c: Context): Boolean = isSmsEnabled(c) || isHttpEnabled(c)

    fun setEnabled(c: Context, value: Boolean) {
        prefs(c).edit()
            .putBoolean(K_ENABLED, value)
            .putBoolean(K_SMS_ENABLED, value)
            .apply()
    }

    fun template(c: Context): String = prefs(c).getString(K_TEMPLATE, "{text}") ?: "{text}"

    fun setTemplate(c: Context, value: String) {
        val clean = value.trim().ifEmpty { "{text}" }
        prefs(c).edit().putString(K_TEMPLATE, clean).apply()
    }

    fun applyTemplate(template: String, original: String): String {
        val t = template.trim()
        if (t.isEmpty() || t == "{text}") return original
        return if (t.contains("{text}")) {
            t.replace("{text}", original)
        } else {
            "$t\n$original"
        }
    }

    fun httpJsonTemplate(c: Context): String =
        prefs(c).getString(K_HTTP_JSON, DEFAULT_HTTP_JSON) ?: DEFAULT_HTTP_JSON

    fun setHttpJsonTemplate(c: Context, value: String) {
        val clean = value.trim().ifEmpty { DEFAULT_HTTP_JSON }
        prefs(c).edit().putString(K_HTTP_JSON, clean).apply()
    }

    fun formatHttpPayload(template: String, from: String, text: String, rawText: String, ts: Long): String {
        val t = template.trim().ifEmpty { DEFAULT_HTTP_JSON }
        val isoDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(ts))

        val quotedText = JSONObject.quote(text)
        val quotedRawText = JSONObject.quote(rawText)
        val quotedFrom = JSONObject.quote(from)
        val quotedDate = JSONObject.quote(isoDate)

        var result = t

        // Replace quoted placeholders first if written as "{placeholder}", then bare {placeholder}
        result = result.replace("\"{text}\"", quotedText)
        result = result.replace("{text}", quotedText)

        result = result.replace("\"{rawText}\"", quotedRawText)
        result = result.replace("{rawText}", quotedRawText)
        result = result.replace("\"{raw_text}\"", quotedRawText)
        result = result.replace("{raw_text}", quotedRawText)

        result = result.replace("\"{from}\"", quotedFrom)
        result = result.replace("{from}", quotedFrom)

        result = result.replace("\"{date}\"", quotedDate)
        result = result.replace("{date}", quotedDate)

        result = result.replace("\"{timestamp}\"", ts.toString())
        result = result.replace("{timestamp}", ts.toString())

        return result
    }

    fun sources(c: Context): List<String> = synchronized(lock) {
        ensureListsLocked(c)
        readStringList(c, K_SOURCES)
    }

    fun destinations(c: Context): List<String> = synchronized(lock) {
        ensureListsLocked(c)
        readStringList(c, K_DESTS)
    }

    fun httpWorkers(c: Context): List<String> = synchronized(lock) {
        readStringList(c, K_WORKERS)
    }

    /** Add one or more sources. Separators: comma, semicolon, newline. Returns how many were new. */
    fun addSources(c: Context, raw: String): Int = synchronized(lock) {
        ensureListsLocked(c)
        val current = readStringList(c, K_SOURCES).toMutableList()
        var added = 0
        for (token in splitEntries(raw)) {
            if (current.any { it.equals(token, ignoreCase = true) }) continue
            current.add(token)
            added++
        }
        if (added > 0) writeStringList(c, K_SOURCES, current)
        added
    }

    fun removeSource(c: Context, value: String) = synchronized(lock) {
        ensureListsLocked(c)
        val current = readStringList(c, K_SOURCES)
            .filterNot { it.equals(value, ignoreCase = true) }
        writeStringList(c, K_SOURCES, current)
    }

    /** Add one or more destination numbers. Invalid or duplicate numbers are skipped. */
    fun addDestinations(c: Context, raw: String): Int = synchronized(lock) {
        ensureListsLocked(c)
        val current = readStringList(c, K_DESTS).toMutableList()
        var added = 0
        for (token in splitEntries(raw)) {
            val number = Sender.normalizeNumber(token)
            if (number.length < 3) continue
            if (current.any { it == number }) continue
            current.add(number)
            added++
        }
        if (added > 0) writeStringList(c, K_DESTS, current)
        added
    }

    fun removeDestination(c: Context, value: String) = synchronized(lock) {
        ensureListsLocked(c)
        val number = Sender.normalizeNumber(value)
        val current = readStringList(c, K_DESTS).filterNot { it == number || it == value }
        writeStringList(c, K_DESTS, current)
    }

    /** Add one or more HTTP worker URLs. Prepends http:// if scheme is missing. */
    fun addHttpWorker(c: Context, raw: String): Int = synchronized(lock) {
        val current = readStringList(c, K_WORKERS).toMutableList()
        var added = 0
        for (token in splitEntries(raw)) {
            var url = token.trim()
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                url = "http://$url"
            }
            if (current.any { it.equals(url, ignoreCase = true) }) continue
            current.add(url)
            added++
        }
        if (added > 0) writeStringList(c, K_WORKERS, current)
        added
    }

    fun removeHttpWorker(c: Context, value: String) = synchronized(lock) {
        val current = readStringList(c, K_WORKERS).filterNot { it.equals(value, ignoreCase = true) }
        writeStringList(c, K_WORKERS, current)
    }

    /** Does the sender of an SMS match one of the configured sources? */
    fun matches(c: Context, sender: String): Boolean =
        matchesAny(sources(c), sender)

    /** A message matches when its sender contains any configured source. Empty list matches nothing. */
    fun matchesAny(sources: List<String>, sender: String): Boolean {
        if (sources.isEmpty()) return false
        val from = sender.lowercase(Locale.ROOT)
        return sources.any { token ->
            val needle = token.trim().lowercase(Locale.ROOT)
            needle.isNotEmpty() && from.contains(needle)
        }
    }

    /** Pure matching rule: comma/space separated tokens, case-insensitive contains. */
    fun matchesHeader(filter: String, sender: String): Boolean =
        matchesAny(splitLoose(filter), sender) || filter.isBlank()

    // ---- log ----

    fun log(c: Context, message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val cleanMsg = sanitizeLegacyLog(message)
        synchronized(lock) {
            val p = prefs(c)
            val lines = (p.getString(K_LOG, "") ?: "").lines().filter { it.isNotEmpty() }.toMutableList()
            lines.add("$time  $cleanMsg")
            while (lines.size > 50) lines.removeAt(0)
            p.edit().putString(K_LOG, lines.joinToString("\n")).apply()
        }
    }

    fun logText(c: Context): String = synchronized(lock) {
        val p = prefs(c)
        val raw = p.getString(K_LOG, "") ?: ""
        val clean = sanitizeLegacyLog(raw)
        if (clean != raw) {
            p.edit().putString(K_LOG, clean).apply()
        }
        clean
    }

    fun clearLog(c: Context) {
        prefs(c).edit().remove(K_LOG).apply()
    }

    // ---- HTTP traffic log ----

    fun logHttp(c: Context, entry: String) {
        val cleanEntry = sanitizeLegacyLog(entry)
        synchronized(lock) {
            val p = prefs(c)
            val raw = p.getString(K_HTTP_LOG, "") ?: ""
            val list = if (raw.isEmpty()) mutableListOf() else raw.split(HTTP_LOG_DELIMITER).filter { it.isNotBlank() }.toMutableList()
            list.add(0, cleanEntry) // Newest first
            while (list.size > 30) list.removeAt(list.size - 1)
            p.edit().putString(K_HTTP_LOG, list.joinToString(HTTP_LOG_DELIMITER)).apply()
        }
    }

    fun httpLogText(c: Context): String = synchronized(lock) {
        val p = prefs(c)
        val raw = p.getString(K_HTTP_LOG, "") ?: ""
        val clean = sanitizeLegacyLog(raw)
        if (clean != raw) {
            p.edit().putString(K_HTTP_LOG, clean).apply()
        }
        if (clean.isBlank()) "" else clean.split(HTTP_LOG_DELIMITER).joinToString("\n\n")
    }

    fun clearHttpLog(c: Context) {
        prefs(c).edit().remove(K_HTTP_LOG).apply()
    }

    fun sanitizeLegacyLog(raw: String): String {
        if (raw.isBlank()) return ""
        var s = raw
        s = s.replace("سرویس: ", "Service: ")
        s = s.replace("سرویس فعال شد", "Service enabled")
        s = s.replace("سرویس غیرفعال شد", "Service disabled")
        s = s.replace("سرویس متوقف شد", "Service stopped")
        s = s.replace("تنظیمات ذخیره شد", "Settings saved")
        s = s.replace("صف ارسال خالی شد", "Outbound queues cleared")
        s = s.replace("مجوزها داده شد", "Permissions granted")
        s = s.replace("همه مجوزها داده شده‌اند", "All permissions granted")
        s = s.replace("بعضی مجوزها رد شدند؛ فوروارد کار نمی‌کند", "Some permissions were denied; forwarding will not work")
        s = s.replace("تنظیمات باتری در دسترس نیست", "Battery settings not available")
        s = s.replace("خطای سرویس پیش‌زمینه:", "Foreground service error:")

        s = s.replace("پیام در صف ارسال", "message(s) in queue")
        s = s.replace("مبدأ یا مقصد تنظیم نشده", "Source or destination not configured")
        s = s.replace("مبدأ اضافه شد", "source(s) added")
        s = s.replace("مقصد اضافه شد", "destination(s) added")
        s = s.replace("مبدأ حذف شد:", "Source removed:")
        s = s.replace("مقصد حذف شد:", "Destination removed:")
        s = s.replace("مبدأها:", "Sources:")
        s = s.replace("مقصدها:", "Destinations:")
        s = s.replace("مبدأ", "source")
        s = s.replace("مقصد", "destination")
        s = s.replace("(تنظیم نشده)", "(Not configured)")
        s = s.replace("فعال", "Enabled")
        s = s.replace("غیرفعال", "Disabled")
        s = s.replace("داده شده", "Granted")
        s = s.replace("داده نشده", "Not granted")
        s = s.replace("در صف ارسال:", "Queued:")
        s = s.replace("بهینه‌سازی باتری:", "Battery optimization:")
        s = s.replace("مجوز پیامک:", "SMS permission:")
        s = s.replace("مجوز اعلان:", "Notification permission:")

        s = s.replace("ارسال شد", "Sent")
        s = s.replace("ارسال", "Sending")
        s = s.replace("ناموفق", "Failed")
        s = s.replace("تلاش مجدد", "Retrying")
        s = s.replace("تلاش", "attempt")
        s = s.replace("خطای ارسال:", "Send error:")
        s = s.replace("شماره مقصد نامعتبر است؛ پیام حذف شد", "Invalid destination number; message discarded")
        s = s.replace("مقصدی تنظیم نشده؛ پیام از", "No destination configured; message from")
        s = s.replace("فوروارد نشد", "not forwarded")
        s = s.replace("دریافت از", "Received from")
        s = s.replace("در صف", "in queue")
        s = s.replace("بازبینی صف:", "Queue check:")
        s = s.replace("پیام باقی‌مانده", "message(s) remaining")
        s = s.replace("زمان‌بند بیدار ماندن ناموفق:", "Keep-alive scheduler failed:")
        s = s.replace("شروع سرویس ممکن نشد:", "Could not start service:")
        s = s.replace("راه‌اندازی پس از", "Started after")
        s = s.replace("هنوز رویدادی ثبت نشده است", "No events logged yet")
        s = s.replace("، ", ", ")

        return buildString(s.length) {
            for (ch in s) {
                when (ch) {
                    in '۰'..'۹' -> append('0' + (ch - '۰'))
                    in '٠'..'٩' -> append('0' + (ch - '٠'))
                    else -> append(ch)
                }
            }
        }
    }

    // ---- SMS queue ----

    fun enqueue(c: Context, body: String, from: String, to: String = ""): Long = synchronized(lock) {
        val p = prefs(c)
        val id = p.getLong(K_SEQ, 0L) + 1
        val list = readLocked(c)
        list.add(
            QueuedSms(
                id = id,
                body = body,
                from = from,
                to = to,
                ts = System.currentTimeMillis()
            )
        )
        writeLocked(c, list)
        p.edit().putLong(K_SEQ, id).apply()
        id
    }

    /** Queue one copy of the message for every configured SMS destination. */
    fun enqueueAll(c: Context, body: String, from: String): Int {
        val dests = destinations(c)
        for (dest in dests) enqueue(c, body, from, dest)
        return dests.size
    }

    fun enqueueSmsAll(c: Context, body: String, from: String): Int = enqueueAll(c, body, from)

    fun queueSize(c: Context): Int = synchronized(lock) { readLocked(c).size }
    fun smsQueueSize(c: Context): Int = queueSize(c)

    /**
     * Claim the next message that is ready to be sent. Stale in-flight items
     * (process died before the delivery receipt arrived) are released so a
     * message can never get permanently stuck at the head of the queue.
     */
    fun claimNext(c: Context): QueuedSms? = synchronized(lock) {
        val now = System.currentTimeMillis()
        val list = readLocked(c)
        if (list.isEmpty()) return null

        var changed = false
        for (item in list) {
            if (item.inflight && now - item.sentAt > Sender.STALE_MS) {
                item.inflight = false
                item.partsOk = 0
                changed = true
            }
        }
        if (list.any { it.inflight }) {
            if (changed) writeLocked(c, list)
            return null
        }

        val next = list.firstOrNull { it.nextRetryAt <= now } ?: run {
            if (changed) writeLocked(c, list)
            return null
        }
        next.inflight = true
        next.sentAt = now
        next.attempts += 1
        next.partsOk = 0
        writeLocked(c, list)
        next
    }

    /** Apply a delivery/failed result for one part of a message. */
    fun applyAck(c: Context, id: Long, partOk: Boolean, totalParts: Int): AckResult =
        synchronized(lock) {
            val list = readLocked(c)
            val item = list.firstOrNull { it.id == id } ?: return AckResult.IGNORED
            if (!partOk) {
                item.inflight = false
                item.partsOk = 0
                // Exponential backoff: 30s, 60s, 120s ... capped at 15 min.
                val delay = (30_000L * (1 shl minOf(item.attempts - 1, 5).coerceAtLeast(0)))
                    .coerceAtMost(15 * 60_000L)
                item.nextRetryAt = System.currentTimeMillis() + delay
                writeLocked(c, list)
                return AckResult.RETRY_SCHEDULED
            }
            item.partsOk += 1
            return if (item.partsOk >= totalParts) {
                list.removeAll { it.id == id }
                writeLocked(c, list)
                AckResult.REMOVED
            } else {
                writeLocked(c, list)
                AckResult.IGNORED
            }
        }

    /** Drop a message that can never be sent (e.g. invalid destination). */
    fun drop(c: Context, id: Long) = synchronized(lock) {
        val list = readLocked(c)
        if (list.removeAll { it.id == id }) {
            writeLocked(c, list)
        }
    }

    // ---- HTTP queue ----

    fun enqueueHttp(c: Context, url: String, from: String, text: String, rawText: String): Long = synchronized(lock) {
        val p = prefs(c)
        val id = p.getLong(K_HTTP_SEQ, 0L) + 1
        val list = readHttpLocked(c)
        list.add(
            QueuedHttp(
                id = id,
                url = url,
                from = from,
                text = text,
                rawText = rawText,
                ts = System.currentTimeMillis()
            )
        )
        writeHttpLocked(c, list)
        p.edit().putLong(K_HTTP_SEQ, id).apply()
        id
    }

    fun enqueueHttpAll(c: Context, text: String, rawText: String, from: String): Int {
        val workers = httpWorkers(c)
        for (url in workers) enqueueHttp(c, url, from, text, rawText)
        return workers.size
    }

    fun httpQueueSize(c: Context): Int = synchronized(lock) { readHttpLocked(c).size }

    fun claimNextHttp(c: Context): QueuedHttp? = synchronized(lock) {
        val now = System.currentTimeMillis()
        val list = readHttpLocked(c)
        if (list.isEmpty()) return null

        var changed = false
        for (item in list) {
            // Stale claim timeout: 60s
            if (item.inflight && now - item.sentAt > 60_000L) {
                item.inflight = false
                changed = true
            }
        }
        if (list.any { it.inflight }) {
            if (changed) writeHttpLocked(c, list)
            return null
        }

        val next = list.firstOrNull { it.nextRetryAt <= now } ?: run {
            if (changed) writeHttpLocked(c, list)
            return null
        }
        next.inflight = true
        next.sentAt = now
        next.attempts += 1
        writeHttpLocked(c, list)
        next
    }

    fun removeHttp(c: Context, id: Long) = synchronized(lock) {
        val list = readHttpLocked(c)
        if (list.removeAll { it.id == id }) {
            writeHttpLocked(c, list)
        }
    }

    fun applyAckHttp(c: Context, id: Long, ok: Boolean) = synchronized(lock) {
        val list = readHttpLocked(c)
        val item = list.firstOrNull { it.id == id } ?: return@synchronized
        if (ok) {
            list.removeAll { it.id == id }
            writeHttpLocked(c, list)
        } else {
            item.inflight = false
            val delay = (30_000L * (1 shl minOf(item.attempts - 1, 5).coerceAtLeast(0)))
                .coerceAtMost(15 * 60_000L)
            item.nextRetryAt = System.currentTimeMillis() + delay
            writeHttpLocked(c, list)
        }
    }

    fun dropHttp(c: Context, id: Long) = synchronized(lock) {
        val list = readHttpLocked(c)
        if (list.removeAll { it.id == id }) {
            writeHttpLocked(c, list)
        }
    }

    fun resetAll(c: Context) = synchronized(lock) {
        writeLocked(c, emptyList())
        writeHttpLocked(c, emptyList())
    }

    // ---- json helpers (called only while holding the lock) ----

    private fun readLocked(c: Context): MutableList<QueuedSms> {
        val raw = prefs(c).getString(K_QUEUE, "[]") ?: "[]"
        val out = mutableListOf<QueuedSms>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    QueuedSms(
                        id = o.optLong("id"),
                        body = o.optString("body"),
                        from = o.optString("from"),
                        to = o.optString("to"),
                        ts = o.optLong("ts"),
                        attempts = o.optInt("attempts"),
                        inflight = o.optBoolean("inflight"),
                        sentAt = o.optLong("sentAt"),
                        partsOk = o.optInt("partsOk"),
                        nextRetryAt = o.optLong("nextRetryAt")
                    )
                )
            }
        } catch (e: Exception) {
            out.clear()
        }
        return out
    }

    private fun writeLocked(c: Context, list: List<QueuedSms>) {
        val arr = JSONArray()
        for (item in list) {
            val o = JSONObject()
            o.put("id", item.id)
            o.put("body", item.body)
            o.put("from", item.from)
            o.put("to", item.to)
            o.put("ts", item.ts)
            o.put("attempts", item.attempts)
            o.put("inflight", item.inflight)
            o.put("sentAt", item.sentAt)
            o.put("partsOk", item.partsOk)
            o.put("nextRetryAt", item.nextRetryAt)
            arr.put(o)
        }
        prefs(c).edit().putString(K_QUEUE, arr.toString()).apply()
    }

    private fun readHttpLocked(c: Context): MutableList<QueuedHttp> {
        val raw = prefs(c).getString(K_HTTP_QUEUE, "[]") ?: "[]"
        val out = mutableListOf<QueuedHttp>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    QueuedHttp(
                        id = o.optLong("id"),
                        url = o.optString("url"),
                        from = o.optString("from"),
                        text = o.optString("text"),
                        rawText = o.optString("rawText"),
                        ts = o.optLong("ts"),
                        attempts = o.optInt("attempts"),
                        inflight = o.optBoolean("inflight"),
                        sentAt = o.optLong("sentAt"),
                        nextRetryAt = o.optLong("nextRetryAt")
                    )
                )
            }
        } catch (e: Exception) {
            out.clear()
        }
        return out
    }

    private fun writeHttpLocked(c: Context, list: List<QueuedHttp>) {
        val arr = JSONArray()
        for (item in list) {
            val o = JSONObject()
            o.put("id", item.id)
            o.put("url", item.url)
            o.put("from", item.from)
            o.put("text", item.text)
            o.put("rawText", item.rawText)
            o.put("ts", item.ts)
            o.put("attempts", item.attempts)
            o.put("inflight", item.inflight)
            o.put("sentAt", item.sentAt)
            o.put("nextRetryAt", item.nextRetryAt)
            arr.put(o)
        }
        prefs(c).edit().putString(K_HTTP_QUEUE, arr.toString()).apply()
    }

    private fun ensureListsLocked(c: Context) {
        val p = prefs(c)
        if (p.contains(K_SOURCES) || p.contains(K_DESTS)) {
            if (!p.contains(K_SOURCES)) writeStringList(c, K_SOURCES, emptyList())
            if (!p.contains(K_DESTS)) writeStringList(c, K_DESTS, emptyList())
            return
        }
        val sources = if (p.contains(K_HEADERS)) splitLoose(p.getString(K_HEADERS, "") ?: "") else emptyList()
        val rawTarget = if (p.contains(K_TARGET)) p.getString(K_TARGET, "") ?: "" else ""
        val number = Sender.normalizeNumber(rawTarget)
        val dests = if (number.length >= 3) listOf(number) else emptyList()
        writeStringList(c, K_SOURCES, sources)
        writeStringList(c, K_DESTS, dests)
    }

    private fun splitEntries(raw: String): List<String> =
        raw.split(',', '،', ';', '\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun splitLoose(filter: String): List<String> =
        filter.split(',', '،', ';', '\n', '\r', ' ', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }

    private fun readStringList(c: Context, key: String): List<String> {
        val raw = prefs(c).getString(key, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val value = arr.optString(i).trim()
                    if (value.isNotEmpty()) add(value)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeStringList(c: Context, key: String, values: List<String>) {
        val arr = JSONArray()
        for (value in values) arr.put(value)
        prefs(c).edit().putString(key, arr.toString()).apply()
    }
}
