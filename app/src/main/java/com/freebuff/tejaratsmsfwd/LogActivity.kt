package com.freebuff.tejaratsmsfwd

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class LogActivity : Activity() {

    private enum class Tab { HTTP, EVENTS }

    private var currentTab = Tab.HTTP
    private lateinit var tabHttp: Button
    private lateinit var tabEvents: Button
    private lateinit var textLogContent: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        tabHttp = findViewById(R.id.tab_http)
        tabEvents = findViewById(R.id.tab_events)
        textLogContent = findViewById(R.id.text_log_content)

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            if (currentTab == Tab.HTTP) {
                Store.clearHttpLog(this)
            } else {
                Store.clearLog(this)
            }
            render()
        }

        findViewById<Button>(R.id.btn_copy).setOnClickListener {
            val text = textLogContent.text.toString()
            if (text.isNotBlank()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("logs", text)
                cm.setPrimaryClip(clip)
                Toast.makeText(this, R.string.toast_logs_copied, Toast.LENGTH_SHORT).show()
            }
        }

        tabHttp.setOnClickListener {
            currentTab = Tab.HTTP
            render()
        }

        tabEvents.setOnClickListener {
            currentTab = Tab.EVENTS
            render()
        }

        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        if (currentTab == Tab.HTTP) {
            tabHttp.setBackgroundColor(getColor(R.color.primary))
            tabHttp.setTextColor(getColor(R.color.surface))
            tabEvents.setBackgroundColor(getColor(R.color.surface))
            tabEvents.setTextColor(getColor(R.color.muted))

            val content = Store.httpLogText(this)
            textLogContent.text = content.ifBlank { getString(R.string.http_log_empty) }
        } else {
            tabEvents.setBackgroundColor(getColor(R.color.primary))
            tabEvents.setTextColor(getColor(R.color.surface))
            tabHttp.setBackgroundColor(getColor(R.color.surface))
            tabHttp.setTextColor(getColor(R.color.muted))

            val content = Store.logText(this)
            textLogContent.text = content.ifBlank { getString(R.string.log_empty) }
        }
    }
}
