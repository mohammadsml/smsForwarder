package com.freebuff.tejaratsmsfwd

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Verifies the forwarding rules and the durable outbound queue state machine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForwarderLogicTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    // ---- destination number normalization ----

    @Test
    fun normalizesGlobalNumberFormats() {
        // International format with +
        assertEquals("+14155552671", Sender.normalizeNumber("+1 415 555-2671"))
        assertEquals("+447911123456", Sender.normalizeNumber("+44 7911 123456"))
        assertEquals("+989928927408", Sender.normalizeNumber("+989928927408"))

        // Leading 00 international prefix
        assertEquals("+14155552671", Sender.normalizeNumber("0014155552671"))
        assertEquals("+989928927408", Sender.normalizeNumber("00989928927408"))

        // Local formats (preserved without forcing foreign country code)
        assertEquals("09928927408", Sender.normalizeNumber("09928927408"))
        assertEquals("07911123456", Sender.normalizeNumber("07911 123456"))
        assertEquals("09928927408", Sender.normalizeNumber("۰۹۹۲۸۹۲۷۴۰۸"))
        assertEquals("1234", Sender.normalizeNumber("1234"))
        assertEquals("", Sender.normalizeNumber("   "))
    }

    // ---- Tejarat header filter ----

    @Test
    fun tejaratHeaderIsMatchedCaseInsensitively() {
        assertTrue(Store.matchesHeader("tejarat", "TejaratBank"))
        assertTrue(Store.matchesHeader("tejarat", "bTejarat"))
        assertTrue(Store.matchesHeader("TEJARAT,3000777", "3000777"))
        assertTrue(Store.matchesHeader("tejarat، 3000777", "TejaratBank"))
    }

    @Test
    fun nonTejaratSenderIsFilteredOut() {
        assertFalse(Store.matchesHeader("tejarat", "MCI"))
        assertFalse(Store.matchesHeader("tejarat", "1414"))
        assertTrue("empty filter forwards everything", Store.matchesHeader("", "anything"))
    }

    // ---- queue state machine ----

    @Test
    fun happyPathEnqueueClaimAck() {
        val id = Store.enqueue(context, "Your balance is 1,000,000 Rials", "TejaratBank")
        assertEquals(1, Store.queueSize(context))

        val claimed = Store.claimNext(context)
        assertNotNull(claimed)
        assertEquals(id, claimed!!.id)
        assertEquals(1, claimed.attempts)

        assertNull("only one message may be in flight", Store.claimNext(context))
        assertEquals(1, Store.queueSize(context))

        assertEquals(AckResult.REMOVED, Store.applyAck(context, id, partOk = true, totalParts = 1))
        assertEquals(0, Store.queueSize(context))
        assertNull(Store.claimNext(context))
    }

    @Test
    fun failedSendIsRetriedAfterBackoff() {
        val id = Store.enqueue(context, "otp 12345", "TejaratBank")
        Store.claimNext(context)

        assertEquals(
            AckResult.RETRY_SCHEDULED,
            Store.applyAck(context, id, partOk = false, totalParts = 1)
        )
        assertEquals("failed message stays queued", 1, Store.queueSize(context))
        assertNull("must wait for the backoff window", Store.claimNext(context))
    }

    @Test
    fun multipartWaitsForEveryPart() {
        val id = Store.enqueue(context, "x".repeat(400), "TejaratBank")
        Store.claimNext(context)

        assertEquals(AckResult.IGNORED, Store.applyAck(context, id, true, totalParts = 3))
        assertEquals(AckResult.IGNORED, Store.applyAck(context, id, true, totalParts = 3))
        assertEquals(1, Store.queueSize(context))
        assertEquals(AckResult.REMOVED, Store.applyAck(context, id, true, totalParts = 3))
        assertEquals(0, Store.queueSize(context))
    }

    @Test
    fun staleInFlightMessageIsRecoveredAfterProcessDeath() {
        val id = Store.enqueue(context, "stuck message", "TejaratBank")
        Store.claimNext(context)

        // Simulate the process dying mid-send: in-flight flag left behind with
        // an old timestamp.
        val prefs = context.getSharedPreferences("tejarat_fwd", Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("queue", "[]"))
        val first = arr.getJSONObject(0)
        first.put("sentAt", 1L)
        prefs.edit().putString("queue", arr.toString()).apply()

        val recovered = Store.claimNext(context)
        assertNotNull("stale item must be released", recovered)
        assertEquals(id, recovered!!.id)
        assertEquals(2, recovered.attempts)
    }

    @Test
    fun severalSourcesAndDestinationsCanBeAdded() {
        assertEquals(2, Store.addSources(context, "tejarat, 3000777"))
        assertEquals("duplicate source is ignored", 0, Store.addSources(context, "Tejarat"))
        assertEquals(listOf("tejarat", "3000777"), Store.sources(context))

        assertEquals(2, Store.addDestinations(context, "+14155552671\n09928927408"))
        assertEquals("duplicate destination is ignored", 0, Store.addDestinations(context, "+14155552671"))
        assertEquals("short number is rejected", 0, Store.addDestinations(context, "12"))
        assertEquals(listOf("+14155552671", "09928927408"), Store.destinations(context))

        assertTrue(Store.matches(context, "TejaratBank"))
        assertTrue(Store.matches(context, "3000777"))
        assertFalse(Store.matches(context, "MCI"))

        assertEquals(2, Store.enqueueAll(context, "Balance", "TejaratBank"))
        assertEquals(2, Store.queueSize(context))

        val first = Store.claimNext(context)
        assertEquals("+14155552671", first!!.to)
        Store.applyAck(context, first.id, partOk = true, totalParts = 1)
        val second = Store.claimNext(context)
        assertEquals("09928927408", second!!.to)

        Store.removeSource(context, "tejarat")
        Store.removeDestination(context, "+14155552671")
        assertEquals(listOf("3000777"), Store.sources(context))
        assertEquals(listOf("09928927408"), Store.destinations(context))
        assertFalse(Store.matches(context, "TejaratBank"))
    }

    @Test
    fun freshInstallHasNoSourcesOrDestinations() {
        assertTrue(Store.sources(context).isEmpty())
        assertTrue(Store.destinations(context).isEmpty())
        assertFalse(Store.matches(context, "TejaratBank"))
        assertEquals(0, Store.enqueueAll(context, "ignored", "TejaratBank"))
    }

    @Test
    fun oldSingleTargetAndHeaderAreMigrated() {
        val prefs = context.getSharedPreferences("tejarat_fwd", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("headers", "tejarat, MCI")
            .putString("target", "+14155552671")
            .commit()

        assertEquals(listOf("tejarat", "MCI"), Store.sources(context))
        assertEquals(listOf("+14155552671"), Store.destinations(context))
    }

    @Test
    fun legacyPersianLogTextIsSanitized() {
        val persianSample = "دریافت پیام از: بانک تجارت | وضعیت: موفق | ارسال مجدد"
        val sanitized = Store.sanitizeLegacyLog(persianSample)
        assertFalse("Persian characters must be replaced", sanitized.contains("دریافت"))
        assertFalse("Persian characters must be replaced", sanitized.contains("وضعیت"))
        assertTrue(sanitized.contains("Received SMS from:"))
        assertTrue(sanitized.contains("Status:"))
        assertTrue(sanitized.contains("Sent successfully"))
    }

    @Test
    fun disabledForwarderNeverTouchesTheQueue() {
        Store.setEnabled(context, false)
        Store.enqueue(context, "should not move", "TejaratBank")

        Sender.pump(context)

        assertEquals(1, Store.queueSize(context))
        val claim = Store.claimNext(context)
        // claimNext itself is only reached via pump(); queue is untouched and
        // the enabled flag is what gates every entry point.
        assertFalse(Store.isEnabled(context))
        assertNotNull(claim)
    }

    // ---- template formatting ----

    @Test
    fun templateFormattingReplacesPlaceholder() {
        assertEquals("Original SMS", Store.applyTemplate("{text}", "Original SMS"))
        assertEquals("Alert: Original SMS", Store.applyTemplate("Alert: {text}", "Original SMS"))
        assertEquals("Original SMS [End]", Store.applyTemplate("{text} [End]", "Original SMS"))
        assertEquals(">> Original SMS <<", Store.applyTemplate(">> {text} <<", "Original SMS"))
        assertEquals("Prefix\nOriginal SMS", Store.applyTemplate("Prefix", "Original SMS"))
        assertEquals("Original SMS", Store.applyTemplate("", "Original SMS"))
    }

    // ---- HTTP workers and queue ----

    @Test
    fun httpWorkersCanBeAddedAndNormalized() {
        assertEquals(2, Store.addHttpWorker(context, "https://example.com/sms\n192.168.1.50:8080/api"))
        assertEquals(0, Store.addHttpWorker(context, "https://example.com/sms"))
        assertEquals(listOf("https://example.com/sms", "http://192.168.1.50:8080/api"), Store.httpWorkers(context))

        Store.removeHttpWorker(context, "https://example.com/sms")
        assertEquals(listOf("http://192.168.1.50:8080/api"), Store.httpWorkers(context))
    }

    @Test
    fun httpQueueEnqueueClaimAndAck() {
        val id = Store.enqueueHttp(context, "https://example.com/api", "TejaratBank", "Formatted", "Raw")
        assertEquals(1, Store.httpQueueSize(context))

        val claimed = Store.claimNextHttp(context)
        assertNotNull(claimed)
        assertEquals(id, claimed!!.id)
        assertEquals("https://example.com/api", claimed.url)
        assertEquals("Formatted", claimed.text)
        assertEquals("Raw", claimed.rawText)

        assertNull("in-flight item is locked", Store.claimNextHttp(context))

        Store.removeHttp(context, id)
        assertEquals(0, Store.httpQueueSize(context))
        assertNull(Store.claimNextHttp(context))
    }

    @Test
    fun independentTogglesControlAppEnabledState() {
        Store.setSmsEnabled(context, true)
        Store.setHttpEnabled(context, false)
        assertTrue(Store.isSmsEnabled(context))
        assertFalse(Store.isHttpEnabled(context))
        assertTrue(Store.isEnabled(context))

        Store.setSmsEnabled(context, false)
        Store.setHttpEnabled(context, true)
        assertFalse(Store.isSmsEnabled(context))
        assertTrue(Store.isHttpEnabled(context))
        assertTrue(Store.isEnabled(context))

        Store.setSmsEnabled(context, false)
        Store.setHttpEnabled(context, false)
        assertFalse(Store.isEnabled(context))
    }

    @Test
    fun httpTrafficLogCanBeSavedAndCleared() {
        Store.clearHttpLog(context)
        assertEquals("", Store.httpLogText(context))

        Store.logHttp(context, "POST https://api.com 200 OK")
        Store.logHttp(context, "POST https://api.com/v2 500 ERR")

        val logs = Store.httpLogText(context)
        assertTrue(logs.contains("POST https://api.com/v2 500 ERR"))
        assertTrue(logs.contains("POST https://api.com 200 OK"))

        Store.clearHttpLog(context)
        assertEquals("", Store.httpLogText(context))
    }

    @Test
    fun httpJsonTemplateCanBeConfiguredAndReset() {
        val initial = Store.httpJsonTemplate(context)
        assertTrue(initial.contains("{text}"))
        assertTrue(initial.contains("{from}"))

        Store.setHttpJsonTemplate(context, """{"message":"{text}"}""")
        assertEquals("""{"message":"{text}"}""", Store.httpJsonTemplate(context))

        Store.setHttpJsonTemplate(context, "")
        assertEquals(Store.DEFAULT_HTTP_JSON, Store.httpJsonTemplate(context))
    }

    @Test
    fun formatHttpPayloadSubstitutesPlaceholdersAndQuotesSafely() {
        val template = """{"sender":"{from}","content":"{text}","raw":"{rawText}","ts":{timestamp}}"""
        val complexSms = "Line 1\nCard \"6037\" transferred:\n100,000 Rials"

        val payload = Store.formatHttpPayload(
            template = template,
            from = "TejaratBank",
            text = complexSms,
            rawText = complexSms,
            ts = 1728325491000L
        )

        val json = JSONObject(payload)
        assertEquals("TejaratBank", json.getString("sender"))
        assertEquals(complexSms, json.getString("content"))
        assertEquals(complexSms, json.getString("raw"))
        assertEquals(1728325491000L, json.getLong("ts"))
    }

    @Test
    fun formatHttpPayloadWorksWhenUserOmitsQuotesAroundPlaceholder() {
        val template = """{"content":{text},"from":{from}}"""
        val payload = Store.formatHttpPayload(template, "Bank", "Hello World", "Hello World", 1000L)
        val json = JSONObject(payload)
        assertEquals("Hello World", json.getString("content"))
        assertEquals("Bank", json.getString("from"))
    }

    @Test
    fun defaultHttpPayloadMatchesStandardSchema() {
        val payload = Store.formatHttpPayload(Store.DEFAULT_HTTP_JSON, "Bank", "Formatted", "Raw", 1728325491000L)
        val json = JSONObject(payload)
        assertEquals("Bank", json.getString("from"))
        assertEquals("Formatted", json.getString("text"))
        assertEquals("Raw", json.getString("rawText"))
        assertEquals(1728325491000L, json.getLong("timestamp"))
        assertTrue(json.getString("date").endsWith("Z"))
    }
}
