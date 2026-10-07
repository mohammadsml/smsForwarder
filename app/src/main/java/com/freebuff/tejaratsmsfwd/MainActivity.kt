package com.freebuff.tejaratsmsfwd

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var switchSmsEnabled: Switch
    private lateinit var switchHttpEnabled: Switch
    private lateinit var inputTemplate: EditText
    private lateinit var inputSource: EditText
    private lateinit var inputDestination: EditText
    private lateinit var inputWorker: EditText
    private lateinit var listSources: LinearLayout
    private lateinit var listDestinations: LinearLayout
    private lateinit var listWorkers: LinearLayout
    private lateinit var textDestPreview: TextView
    private lateinit var textStatus: TextView
    private lateinit var textLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        switchSmsEnabled = findViewById(R.id.switch_sms_enabled)
        switchHttpEnabled = findViewById(R.id.switch_http_enabled)
        inputTemplate = findViewById(R.id.input_template)
        inputSource = findViewById(R.id.input_source)
        inputDestination = findViewById(R.id.input_destination)
        inputWorker = findViewById(R.id.input_worker)
        listSources = findViewById(R.id.list_sources)
        listDestinations = findViewById(R.id.list_destinations)
        listWorkers = findViewById(R.id.list_workers)
        textDestPreview = findViewById(R.id.text_dest_preview)
        textStatus = findViewById(R.id.text_status)
        textLog = findViewById(R.id.text_log)

        switchSmsEnabled.isChecked = Store.isSmsEnabled(this)
        switchSmsEnabled.setOnCheckedChangeListener { _, checked -> onToggleSms(checked) }

        switchHttpEnabled.isChecked = Store.isHttpEnabled(this)
        switchHttpEnabled.setOnCheckedChangeListener { _, checked -> onToggleHttp(checked) }

        inputTemplate.setText(Store.template(this))

        findViewById<Button>(R.id.btn_add_source).setOnClickListener { addSource() }
        findViewById<Button>(R.id.btn_add_destination).setOnClickListener { addDestination() }
        findViewById<Button>(R.id.btn_add_worker).setOnClickListener { addWorker() }

        inputSource.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addSource()
                true
            } else {
                false
            }
        }
        inputDestination.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addDestination()
                true
            } else {
                false
            }
        }
        inputWorker.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addWorker()
                true
            } else {
                false
            }
        }
        inputDestination.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val number = Sender.normalizeNumber(s?.toString().orEmpty())
                textDestPreview.text = if (number.length < 8) "" else "Will be saved as: $number"
            }
        })

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            Store.setTemplate(this, inputTemplate.text.toString())
            Store.log(this, "Settings saved")
            renderLists()
            refresh()
        }

        findViewById<Button>(R.id.btn_perms).setOnClickListener {
            if (!requestMissing()) {
                Toast.makeText(this, "All permissions have been granted", Toast.LENGTH_SHORT).show()
            }
            refresh()
        }

        findViewById<Button>(R.id.btn_battery).setOnClickListener { openBatterySettings() }

        findViewById<Button>(R.id.btn_reset).setOnClickListener {
            Store.resetAll(this)
            Store.log(this, "Outbound queues cleared")
            refresh()
        }

        findViewById<Button>(R.id.btn_view_logs).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        renderLists()
    }

    override fun onResume() {
        super.onResume()
        renderLists()
        refresh()
        if (Store.isEnabled(this) && missingPermissions().isEmpty()) {
            Scheduler.scheduleWatchdog(this)
            Scheduler.startServiceSafely(this)
        }
    }

    // ---- actions ----

    private fun onToggleSms(enabled: Boolean) {
        Store.setSmsEnabled(this, enabled)
        Store.log(this, if (enabled) "SMS forwarding enabled" else "SMS forwarding disabled")
        checkServiceState()
        refresh()
    }

    private fun onToggleHttp(enabled: Boolean) {
        Store.setHttpEnabled(this, enabled)
        Store.log(this, if (enabled) "HTTP forwarding enabled" else "HTTP forwarding disabled")
        checkServiceState()
        refresh()
    }

    private fun checkServiceState() {
        if (Store.isEnabled(this)) {
            val missing = requestMissing()
            if (!missing) {
                Scheduler.scheduleWatchdog(this)
                Scheduler.startServiceSafely(this)
            }
        } else {
            stopService(Intent(this, ForwardService::class.java))
            Store.log(this, "Service stopped (all forwarding disabled)")
        }
    }

    private fun addSource() {
        val added = Store.addSources(this, inputSource.text.toString())
        if (added == 0) {
            Toast.makeText(this, R.string.toast_source_rejected, Toast.LENGTH_SHORT).show()
            return
        }
        inputSource.text.clear()
        Store.log(this, if (added == 1) "1 source added" else "$added sources added")
        renderLists()
        refresh()
    }

    private fun addDestination() {
        val added = Store.addDestinations(this, inputDestination.text.toString())
        if (added == 0) {
            Toast.makeText(this, R.string.toast_dest_rejected, Toast.LENGTH_SHORT).show()
            return
        }
        inputDestination.text.clear()
        textDestPreview.text = ""
        Store.log(this, if (added == 1) "1 destination added" else "$added destinations added")
        renderLists()
        refresh()
    }

    private fun addWorker() {
        val added = Store.addHttpWorker(this, inputWorker.text.toString())
        if (added == 0) {
            Toast.makeText(this, R.string.toast_worker_rejected, Toast.LENGTH_SHORT).show()
            return
        }
        inputWorker.text.clear()
        Store.log(this, if (added == 1) "1 HTTP worker added" else "$added HTTP workers added")
        renderLists()
        refresh()
    }

    private fun renderLists() {
        renderRows(listSources, Store.sources(this)) { value ->
            Store.removeSource(this, value)
            Store.log(this, "Source removed: $value")
            renderLists()
            refresh()
        }
        renderRows(listDestinations, Store.destinations(this)) { value ->
            Store.removeDestination(this, value)
            Store.log(this, "Destination removed: $value")
            renderLists()
            refresh()
        }
        renderRows(listWorkers, Store.httpWorkers(this)) { value ->
            Store.removeHttpWorker(this, value)
            Store.log(this, "HTTP worker removed: $value")
            renderLists()
            refresh()
        }
    }

    private fun renderRows(
        container: LinearLayout,
        items: List<String>,
        onRemove: (String) -> Unit
    ) {
        container.removeAllViews()
        if (items.isEmpty()) {
            val empty = TextView(this)
            empty.setText(R.string.list_empty)
            empty.setTextColor(getColor(R.color.muted))
            empty.textSize = 13f
            container.addView(empty)
            return
        }
        for (item in items) {
            val row = layoutInflater.inflate(R.layout.item_entry, container, false)
            row.findViewById<TextView>(R.id.text_value).text = item
            row.findViewById<Button>(R.id.btn_remove).setOnClickListener { onRemove(item) }
            container.addView(row)
        }
    }

    private fun wantedPermissions(): List<String> {
        val list = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list
    }

    private fun missingPermissions(): Array<String> =
        wantedPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

    /** Returns true when a permission dialog was shown. */
    private fun requestMissing(): Boolean {
        val missing = missingPermissions()
        if (missing.isEmpty()) return false
        requestPermissions(missing, REQ_PERMISSIONS)
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return

        if (missingPermissions().isEmpty()) {
            Store.log(this, "Permissions granted")
            if (Store.isEnabled(this)) {
                Scheduler.scheduleWatchdog(this)
                Scheduler.startServiceSafely(this)
            }
        } else {
            Store.log(this, "Some permissions were denied; forwarding will not work")
        }
        refresh()
    }

    private fun openBatterySettings() {
        try {
            val i = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(i)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "Battery settings not available", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---- ui ----

    private fun refresh() {
        if (switchSmsEnabled.isChecked != Store.isSmsEnabled(this)) {
            switchSmsEnabled.isChecked = Store.isSmsEnabled(this)
        }
        if (switchHttpEnabled.isChecked != Store.isHttpEnabled(this)) {
            switchHttpEnabled.isChecked = Store.isHttpEnabled(this)
        }

        val smsMissing = missingPermissions().any { it != Manifest.permission.POST_NOTIFICATIONS }
        val notifMissing = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

        val ignoringBattery = try {
            val pm = getSystemService(PowerManager::class.java)
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (e: Exception) {
            false
        }

        val sources = Store.sources(this)
        val dests = Store.destinations(this)
        val workers = Store.httpWorkers(this)
        textStatus.text = buildString {
            append("Service: ")
            append(if (Store.isEnabled(this@MainActivity)) "Active ✓" else "Disabled ✗").append('\n')
            append("SMS Forwarding: ")
            append(if (Store.isSmsEnabled(this@MainActivity)) "Enabled ✓" else "Disabled ✗").append('\n')
            append("HTTP Forwarding: ")
            append(if (Store.isHttpEnabled(this@MainActivity)) "Enabled ✓" else "Disabled ✗").append('\n')
            append("SMS Permission: ")
            append(if (smsMissing) "Not granted ✗" else "Granted ✓").append('\n')
            append("Notification Permission: ")
            append(if (notifMissing) "Not granted ✗" else "Granted ✓").append('\n')
            append("Battery Optimization: ")
            append(if (ignoringBattery) "Disabled ✓" else "Enabled ✗ (Recommended: disable)").append('\n')
            append("Queued SMS: ").append(Store.smsQueueSize(this@MainActivity)).append('\n')
            append("Queued HTTP: ").append(Store.httpQueueSize(this@MainActivity)).append('\n')
            append("Sources: ")
            append(if (sources.isEmpty()) "(Not configured)" else sources.joinToString(", ")).append('\n')
            append("SMS Destinations: ")
            append(if (dests.isEmpty()) "(Not configured)" else dests.joinToString(", ")).append('\n')
            append("HTTP Workers: ")
            append(if (workers.isEmpty()) "(Not configured)" else workers.joinToString(", "))
        }

        textLog.text = Store.logText(this).ifBlank { getString(R.string.log_empty) }
    }

    companion object {
        private const val REQ_PERMISSIONS = 41
    }
}
