/*
 Copyright 2026 Adobe. All rights reserved.
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 Unless required by applicable law or agreed to in writing, software distributed
 under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 CONDITIONS OF ANY KIND, either express or implied.
 */
package com.adobe.marketing.mobile.messagingsample

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.adobe.marketing.mobile.AdobeCallbackWithError
import com.adobe.marketing.mobile.AdobeError
import com.adobe.marketing.mobile.Event
import com.adobe.marketing.mobile.EventSource
import com.adobe.marketing.mobile.EventType
import com.adobe.marketing.mobile.Messaging
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.messaging.Proposition
import com.adobe.marketing.mobile.messaging.Surface
import com.adobe.marketing.mobile.telemetry.Telemetry
import org.json.JSONObject
import java.util.concurrent.Executors

class TelemetryCatalogActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var enableUploads: Button
    private lateinit var disableUploads: Button
    private lateinit var sendPending: Button
    private lateinit var uploadStatus: TextView
    private val databaseWorker = Executors.newSingleThreadExecutor()
    private val host get() = application as TelemetryCatalogApplication
    private val refreshStatus = object : Runnable {
        override fun run() {
            val stats = Telemetry.stats()
            val uploadsEnabled = stats["uploadsEnabled"] == true
            enableUploads.isEnabled = !uploadsEnabled
            disableUploads.isEnabled = uploadsEnabled
            sendPending.isEnabled = uploadsEnabled
            uploadStatus.text = "Network flushing: ${if (uploadsEnabled) "ENABLED" else "DISABLED"}\n" +
                "Collection continues. Uploads start paused on each process launch."
            status.text = "${host.message}\n\n" +
                "Enabled: ${stats["enabled"]}  Recorded: ${stats["recorded"]}  Pending: ${stats["pending"]}\n" +
                "Upload: ${stats["lastUpload"]}\n\n" +
                "Recent events (newest first)\n\n${host.traceText()}"
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        layout.addView(TextView(this).apply {
            text = "Event name → API mapper POC\n\n" +
                "The catalog matches exact name plus type/source guards. Shared, internal or unknown signatures are WILD_EVENT.\n" +
                "These are event-inferred labels, not proof of a public call or success.\n" +
                if (BuildConfig.TELEMETRY_ISOLATED_VERIFICATION) "Isolated verification; no campaign datastream is configured."
                else "Collecting events from the main Messaging sample."
        })
        fun button(label: String, action: () -> Unit): Button {
            return Button(this).apply { text = label; setOnClickListener { action() }; layout.addView(this) }
        }
        uploadStatus = TextView(this)
        layout.addView(uploadStatus)
        button("View stored API events") { showDatabaseEvents(automatic = false) }
        button("View stored automatic events") { showDatabaseEvents(automatic = true) }
        enableUploads = button("Enable network flushing") { setNetworkFlushing(true) }
        disableUploads = button("Disable network flushing") { setNetworkFlushing(false) }
        button("Messaging.refreshInAppMessages()") {
            invokeApi("refreshInAppMessages") { Messaging.refreshInAppMessages() }
        }
        button("Messaging.updatePropositionsForSurfaces()") {
            invokeApi("updatePropositionsForSurfaces") { Messaging.updatePropositionsForSurfaces(surfaces()) }
        }
        button("Messaging.getPropositionsForSurfaces()") {
            invokeApi("getPropositionsForSurfaces") { getPropositions() }
        }
        button("Emit unknown event → WILD_EVENT") {
            invokeApi("Unmapped event") { emitWildEvent() }
        }
        button("Run all sample calls") { runScenarioWhenReady() }
        button("Enable mapper") { Telemetry.applyConfig(host.policy()); host.message = "Mapper enabled"; host.snapshot() }
        sendPending = button("Send pending") { Telemetry.flush(); host.snapshot() }
        button("Disable collection and purge") { Telemetry.applyConfig(host.policy(false)); host.message = "Collection disabled and queue purged"; host.snapshot() }
        status = TextView(this).apply { typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f }
        layout.addView(status)
        setContentView(ScrollView(this).apply { addView(layout) })
        handler.post(refreshStatus)
        if (intent.getBooleanExtra("run_poc", false)) runScenarioWhenReady()
    }

    override fun onResume() {
        super.onResume()
        host.snapshot()
    }

    private fun runScenarioWhenReady() {
        if (!host.ready) { handler.postDelayed({ runScenarioWhenReady() }, 100); return }
        MobileCore.lifecycleStart(null)
        MobileCore.trackAction("catalog.synthetic.action", mapOf("privateMarker" to "MUST_NOT_EXPORT_ACTION_PAYLOAD"))
        MobileCore.trackState("catalog.synthetic.state", mapOf("privateMarker" to "MUST_NOT_EXPORT_STATE_PAYLOAD"))
        Messaging.refreshInAppMessages()
        Messaging.updatePropositionsForSurfaces(surfaces())
        getPropositions()
        emitWildEvent()
        host.message = "Invoked Core + Messaging sample APIs. Inspect the actual emitted events below."
        snapshotAndFlush()
    }

    private fun surfaces() = listOf(Surface("mobileapp://$packageName"))

    private fun getPropositions() {
        Messaging.getPropositionsForSurfaces(surfaces(), object : AdobeCallbackWithError<Map<Surface, List<Proposition>>> {
            override fun call(value: Map<Surface, List<Proposition>>) { host.message = "Cache callback: ${value.size} surfaces"; host.snapshot() }
            override fun fail(error: AdobeError) { host.message = "Cache callback error; response is not a success/failure metric"; host.snapshot() }
        })
    }

    private fun emitWildEvent() {
        MobileCore.dispatchEvent(Event.Builder("Catalog unmapped example", EventType.MESSAGING, EventSource.REQUEST_CONTENT)
            .setEventData(mapOf("privateMarker" to "MUST_NOT_EXPORT_EVENT_DATA")).build())
    }

    private fun invokeApi(name: String, action: () -> Unit) {
        if (!host.ready) { host.message = "SDKs still registering"; return }
        action()
        host.message = "Invoked $name; only events actually dispatched will appear."
        snapshotAndFlush()
    }

    private fun snapshotAndFlush() {
        handler.postDelayed({
            host.snapshot()
            host.save("capture.json", JSONObject().put("records", org.json.JSONArray(Telemetry.pendingRecords().map { it.toJson() })))
            Telemetry.flush()
        }, 500)
    }

    private fun setNetworkFlushing(enabled: Boolean) {
        Telemetry.setUploadsEnabled(enabled)
        host.message = if (enabled) "Network flushing enabled; existing delivery/consent gates still apply."
        else "Network flushing disabled; collection continues and stored events are retained."
        enableUploads.isEnabled = !enabled
        disableUploads.isEnabled = enabled
        sendPending.isEnabled = enabled
        host.snapshot()
    }

    private fun showDatabaseEvents(automatic: Boolean) {
        val output = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setPadding(24, 16, 24, 16)
            setTextIsSelectable(true)
        }
        fun refresh() {
            output.text = "Reading stored events..."
            databaseWorker.execute {
                val snapshot = TelemetryDatabaseSnapshot.read(applicationContext, automatic)
                handler.post {
                    if (!isFinishing && !isDestroyed) {
                        output.text = snapshot
                        android.util.Log.w("AEPTelemetryPOC", "DATABASE_VIEW_LOADED; read_only=true; per_database_limit=100")
                    }
                }
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (automatic) "Stored automatic events" else "Stored API events")
            .setView(ScrollView(this).apply { addView(output) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Refresh", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { refresh() }
        }
        dialog.show()
        refresh()
    }

    override fun onDestroy() {
        databaseWorker.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
