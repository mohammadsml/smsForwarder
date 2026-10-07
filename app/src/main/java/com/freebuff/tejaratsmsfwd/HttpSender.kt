package com.freebuff.tejaratsmsfwd

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Dispatches queued HTTP requests as JSON POST in a background executor,
 * captures full request and response traffic for inspection, logs status
 * and schedules retries on network failures.
 */
object HttpSender {

    private val executor = Executors.newSingleThreadExecutor()

    fun pump(c: Context) {
        val app = c.applicationContext
        if (!Store.isHttpEnabled(app)) return

        executor.execute {
            val item = Store.claimNextHttp(app) ?: return@execute

            val targetUrl = try {
                URL(item.url)
            } catch (e: Exception) {
                Store.dropHttp(app, item.id)
                Store.log(app, "Invalid worker URL: ${item.url}; request dropped")
                val errEntry = "[${timestamp()}] POST ${item.url}\nError: Invalid URL scheme or format. Dropped."
                Store.logHttp(app, errEntry)
                pump(app)
                return@execute
            }

            val startMs = System.currentTimeMillis()
            val timeStr = timestamp()
            var prettyJson = ""
            var conn: HttpURLConnection? = null
            try {
                conn = (targetUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json, text/plain, */*")
                    setRequestProperty("User-Agent", "SmsForwarder/1.0")
                }

                val isoDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date(item.ts))

                val json = JSONObject().apply {
                    put("from", item.from)
                    put("text", item.text)
                    put("rawText", item.rawText)
                    put("timestamp", item.ts)
                    put("date", isoDate)
                }

                prettyJson = json.toString(2)
                val payload = json.toString().toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(payload.size)
                conn.outputStream.use { os ->
                    os.write(payload)
                    os.flush()
                }

                val code = conn.responseCode
                val duration = System.currentTimeMillis() - startMs
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val responseBody = try {
                    stream?.bufferedReader()?.use { it.readText() }?.trim().orEmpty()
                } catch (e: Exception) {
                    ""
                }.ifBlank { "(empty response body)" }

                val logEntry = buildString {
                    append("[$timeStr] POST ${item.url}\n")
                    append("Status: $code (${duration}ms)\n")
                    append("--- REQUEST BODY ---\n")
                    append(prettyJson).append('\n')
                    append("--- RESPONSE BODY ---\n")
                    append(responseBody)
                }
                Store.logHttp(app, logEntry)

                if (code in 200..299) {
                    Store.removeHttp(app, item.id)
                    Store.log(app, "HTTP POST #${item.id} to ${item.url} succeeded ($code) ✓")
                    pump(app)
                } else {
                    Store.applyAckHttp(app, item.id, ok = false)
                    Store.log(app, "HTTP POST #${item.id} to ${item.url} failed (code $code) → Retrying")
                    Scheduler.scheduleWatchdog(app, 30_000L)
                }
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startMs
                Store.applyAckHttp(app, item.id, ok = false)
                val detail = e.message ?: e.javaClass.simpleName
                Store.log(app, "HTTP POST #${item.id} error: $detail → Retrying")

                val logEntry = buildString {
                    append("[$timeStr] POST ${item.url}\n")
                    append("Status: FAILED after ${duration}ms\n")
                    append("Error: ${e.javaClass.simpleName}: $detail\n")
                    if (prettyJson.isNotEmpty()) {
                        append("--- REQUEST BODY ---\n")
                        append(prettyJson)
                    }
                }
                Store.logHttp(app, logEntry)

                Scheduler.scheduleWatchdog(app, 30_000L)
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
