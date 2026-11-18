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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
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

class TelemetryCatalogActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private val host get() = application as TelemetryCatalogApplication
    private var scenarioRun = false
    private val refreshStatus = object : Runnable {
        override fun run() {
            status.text = "Part A: file-mapped EventHub observations\n\n${host.message}\n\n${JSONObject(Telemetry.stats()).toString(2)}\n\nTIMELINE is an event observation, not an API call or success."
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        fun button(label: String, action: () -> Unit) {
            layout.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        button("Run synthetic mapped calls") { runScenarioWhenReady() }
        button("Send pending") { Telemetry.flush(); host.snapshot() }
        button("Disable collection and purge") { Telemetry.applyConfig(host.policy(false)); host.message = "Collection disabled and queue purged"; host.snapshot() }
        status = TextView(this)
        layout.addView(status)
        setContentView(layout)
        handler.post(refreshStatus)
        if (intent.getBooleanExtra("run_poc", false)) runScenarioWhenReady()
    }

    private fun runScenarioWhenReady() {
        if (scenarioRun) return
        if (!host.ready) { handler.postDelayed({ runScenarioWhenReady() }, 100); return }
        scenarioRun = true
        MobileCore.lifecycleStart(null)
        MobileCore.trackAction("catalog.synthetic.action", mapOf("privateMarker" to "MUST_NOT_EXPORT_ACTION_PAYLOAD"))
        MobileCore.trackState("catalog.synthetic.state", mapOf("privateMarker" to "MUST_NOT_EXPORT_STATE_PAYLOAD"))
        val surfaces = listOf(Surface("mobileapp://$packageName"))
        Messaging.refreshInAppMessages()
        Messaging.updatePropositionsForSurfaces(surfaces)
        Messaging.getPropositionsForSurfaces(surfaces, object : AdobeCallbackWithError<Map<Surface, List<Proposition>>> {
            override fun call(value: Map<Surface, List<Proposition>>) { host.message = "Cache callback: ${value.size} surfaces"; host.snapshot() }
            override fun fail(error: AdobeError) { host.message = "Cache callback error (not exported as API_FAIL)"; host.snapshot() }
        })
        val wrongBoolean = Event.Builder("Refresh in-app messages", EventType.MESSAGING, EventSource.REQUEST_CONTENT)
            .setEventData(mapOf("refreshmessages" to "true")).build()
        val ambiguous = Event.Builder("Analytics Track", EventType.GENERIC_TRACK, EventSource.REQUEST_CONTENT)
            .setEventData(mapOf("action" to "MUST_NOT_EXPORT_AMBIGUOUS", "state" to "MUST_NOT_EXPORT_AMBIGUOUS")).build()
        host.save("negative-controls.json", JSONObject().put("wrongBooleanEventId", wrongBoolean.uniqueIdentifier)
            .put("ambiguousTrackingEventId", ambiguous.uniqueIdentifier))
        MobileCore.dispatchEvent(wrongBoolean)
        MobileCore.dispatchEvent(ambiguous)
        handler.postDelayed({
            host.snapshot()
            host.save("capture.json", JSONObject().put("records", org.json.JSONArray(Telemetry.pendingRecords().map { it.toJson() })))
            Telemetry.flush()
        }, 2000)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
