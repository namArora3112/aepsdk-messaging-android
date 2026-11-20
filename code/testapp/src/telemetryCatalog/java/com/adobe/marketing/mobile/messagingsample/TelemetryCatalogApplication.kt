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

import com.adobe.marketing.mobile.Edge
import com.adobe.marketing.mobile.Lifecycle
import com.adobe.marketing.mobile.Messaging
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.edge.consent.Consent
import com.adobe.marketing.mobile.edge.identity.Identity
import com.adobe.marketing.mobile.services.NetworkCallback
import com.adobe.marketing.mobile.services.NetworkRequest
import com.adobe.marketing.mobile.services.Networking
import com.adobe.marketing.mobile.services.ServiceProvider
import com.adobe.marketing.mobile.telemetry.Telemetry
import com.adobe.marketing.mobile.telemetry.TelemetryConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.CookieHandler
import java.util.concurrent.atomic.AtomicInteger

/** Adds debug telemetry to the sample bootstrap; verification builds retain their isolated host. */
class TelemetryCatalogApplication : MessagingApplication() {
    @Volatile var ready = false
    @Volatile var message = "Registering SDKs"
    private val sequence = AtomicInteger()
    private val fileLock = Any()
    private val trace = LinkedHashMap<String, JSONObject>()

    override fun initializeSdk() {
        if (BuildConfig.TELEMETRY_AUTO_INIT_VERIFICATION) {
            android.util.Log.i("TelemetryAutoInitTest", if (BuildConfig.TELEMETRY_AUTO_INIT_FALLBACK)
                "Application.onCreate; explicit init fallback; no Telemetry.install or Core registration"
            else "Application.onCreate; no Telemetry.install/init or Core registration")
            if (BuildConfig.TELEMETRY_PHASE2_VERIFICATION) {
                TelemetryPhase2Network.install(this)
            } else {
                ServiceProvider.getInstance().networkService = Networking { _, callback ->
                    android.util.Log.e("TelemetryAutoInitTest", "UNEXPECTED_NETWORK_REQUEST")
                    callback.call(null)
                }
            }
            if (BuildConfig.TELEMETRY_AUTO_INIT_FALLBACK) {
                android.util.Log.i("TelemetryAutoInitTest", "Explicit init fallback; intentionally invalid config; no network")
                Telemetry.init(this, com.adobe.marketing.mobile.telemetry.SyncSettings(
                    endpoint = "http://invalid.local", apiKey = ""
                ))
            }
            ready = true
            message = "Auto-init verification: disk-only; manual SDK bootstrap disabled"
            return
        }
        if (!BuildConfig.TELEMETRY_ISOLATED_VERIFICATION) {
            super.initializeSdk()
            return
        }
        MobileCore.setApplication(this)
        installTelemetry()
        MobileCore.registerExtensions(listOf(Messaging.EXTENSION, Identity.EXTENSION, Lifecycle.EXTENSION, Edge.EXTENSION, Consent.EXTENSION)) {
            onSdkInitialized()
        }
    }

    override fun onCoreApplicationSet() {
        installTelemetry()
        Telemetry.applyConfig(policy())
    }

    private fun installTelemetry() {
        val telemetryConfig = policy()
        val service = ServiceProvider.getInstance()
        val delegate = service.networkService
        service.networkService = object : Networking {
            override fun connectAsync(request: NetworkRequest, callback: NetworkCallback) {
                if (request.url != telemetryConfig.endpoint) {
                    delegate.connectAsync(request, callback)
                    return
                }
                val index = sequence.incrementAndGet()
                if (index <= 128) {
                    val payload = JSONObject(String(request.body, Charsets.UTF_8))
                    save("request-$index.json", payload)
                    val events = payload.optJSONArray("events")?.optJSONObject(0)?.optJSONObject("xdm")
                        ?.optJSONObject("_cjmstage")?.optJSONObject("telemetry")?.optJSONArray("events")
                    if (events != null) {
                        for (i in 0 until events.length()) remember(events.getJSONObject(i))
                    }
                }
                delegate.connectAsync(request) { connection ->
                    if (index <= 128) {
                        save("response-$index.json", JSONObject().put("httpStatus", connection?.responseCode ?: -1)
                            .put("cookieHandlerPresent", CookieHandler.getDefault() != null)
                            .put("requestHeaderNames", JSONArray(request.headers.keys.toList())))
                    }
                    callback.call(connection)
                    snapshot()
                }
            }
        }
        Telemetry.install(this)
    }

    override fun onSdkInitialized() {
        val telemetryConfig = policy()
        Telemetry.applyConfig(telemetryConfig)
        if (BuildConfig.TELEMETRY_ISOLATED_VERIFICATION) {
            MobileCore.updateConfiguration(mapOf("experienceCloud.org" to telemetryConfig.orgId))
        }
        ready = true
        message = if (Telemetry.stats()["uploadsEnabled"] != true) "Ready: collecting locally; network flushing disabled"
        else if (telemetryConfig.endpoint.isBlank()) "Capture only" else "Ready: Adobe stage delivery"
        snapshot()
    }

    fun policy(enabled: Boolean = true): TelemetryConfig {
        val defaults = TelemetryConfig.optimizedDefaults()
        return defaults.copy(
            enabled = enabled,
            endpoint = BuildConfig.TELEMETRY_POC_ENDPOINT.ifBlank { defaults.endpoint },
            orgId = BuildConfig.TELEMETRY_POC_ORG.ifBlank { defaults.orgId },
            captureDirectHooks = false,
            eventNameMapperEnabled = true,
            flushIntervalMs = 2000,
            maxQueueSize = 128
        )
    }

    fun save(name: String, data: JSONObject) {
        synchronized(fileLock) {
            runCatching {
                val directory = File(filesDir, "telemetry-catalog-poc").apply { mkdirs() }
                File(directory, name).writeText(data.toString(2))
            }
        }
    }

    fun snapshot() {
        Telemetry.pendingRecords().forEach { record ->
            record.hubEvent?.let { event ->
                remember(JSONObject().put("eventId", event.eventId).put("eventName", event.name)
                    .put("apiEventType", record.apiEventType).put("mappingStatus", record.mappingStatus)
                    .put("apiName", if (record.eventName == "unmapped") JSONObject.NULL else record.eventName)
                    .put("parentId", event.parentId ?: JSONObject.NULL)
                    .put("responseId", event.responseId ?: JSONObject.NULL).put("timestamp", event.timestamp))
            }
        }
        save("status.json", JSONObject(Telemetry.stats()).put("sampleMessage", message)
            .put("registeredSdkVersions", JSONObject().apply {
                com.adobe.marketing.mobile.telemetrybridge.TelemetryBridge.registeredSdkVersions().forEach { (name, version) -> put(name, version) }
            })
            .put("pendingRecords", JSONArray(Telemetry.pendingRecords().map { it.toJson() })))
        synchronized(fileLock) { save("mapped-trace.json", JSONObject().put("events", JSONArray(trace.values.toList()))) }
    }

    private fun remember(event: JSONObject) {
        val id = event.optString("eventId")
        if (id.isBlank()) return
        synchronized(fileLock) {
            trace[id] = JSONObject(event.toString())
            while (trace.size > 40) trace.remove(trace.keys.first())
        }
    }

    fun traceText(): String = synchronized(fileLock) {
        trace.values.toList().asReversed().joinToString("\n\n") { event ->
            val api = if (event.isNull("apiName")) "(no unique API)" else event.optString("apiName", "(no unique API)")
            "${event.optString("apiEventType")} → $api\n" +
                "event: ${event.optString("eventName")}\n" +
                "mapping: ${event.optString("mappingStatus")}; time: ${event.optLong("timestamp")}\n" +
                "id: ${event.optString("eventId")}\n" +
                "parent: ${event.optString("parentId", "null")}\n" +
                "response: ${event.optString("responseId", "null")}"
        }
    }
}
