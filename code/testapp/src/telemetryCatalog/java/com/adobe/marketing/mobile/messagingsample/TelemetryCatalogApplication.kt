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

import android.app.Application
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

/** Separate debug-only host; no Firebase, remote Tags configuration, or customer data. */
class TelemetryCatalogApplication : Application() {
    @Volatile var ready = false
    @Volatile var message = "Registering SDKs"
    private val sequence = AtomicInteger()
    private val fileLock = Any()

    override fun onCreate() {
        super.onCreate()
        MobileCore.setApplication(this)
        val service = ServiceProvider.getInstance()
        val delegate = service.networkService
        service.networkService = object : Networking {
            override fun connectAsync(request: NetworkRequest, callback: NetworkCallback) {
                if (request.url != BuildConfig.TELEMETRY_POC_ENDPOINT) {
                    delegate.connectAsync(request, callback)
                    return
                }
                val index = sequence.incrementAndGet()
                if (index <= 128) {
                    save("request-$index.json", JSONObject(String(request.body, Charsets.UTF_8)))
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
        MobileCore.registerExtensions(listOf(Messaging.EXTENSION, Identity.EXTENSION, Lifecycle.EXTENSION, Edge.EXTENSION, Consent.EXTENSION)) {
            Telemetry.applyConfig(policy())
            // Deliberately no business datastream: real SDK events, without remote message delivery.
            MobileCore.updateConfiguration(mapOf("experienceCloud.org" to BuildConfig.TELEMETRY_POC_ORG))
            ready = true
            message = if (BuildConfig.TELEMETRY_POC_ENDPOINT.isBlank()) "Capture only; endpoint not supplied" else "Ready: Adobe stage delivery"
            snapshot()
        }
    }

    fun policy(enabled: Boolean = true) = TelemetryConfig(
        enabled = enabled,
        endpoint = BuildConfig.TELEMETRY_POC_ENDPOINT,
        optimizedPayload = true,
        orgId = BuildConfig.TELEMETRY_POC_ORG.ifBlank { "TEST@AdobeOrg" },
        captureDirectHooks = false,
        maxQueueSize = 128
    )

    fun save(name: String, data: JSONObject) {
        synchronized(fileLock) {
            runCatching {
                val directory = File(filesDir, "telemetry-catalog-poc").apply { mkdirs() }
                File(directory, name).writeText(data.toString(2))
            }
        }
    }

    fun snapshot() {
        save("status.json", JSONObject(Telemetry.stats()).put("sampleMessage", message)
            .put("registeredSdkVersions", JSONObject().apply {
                com.adobe.marketing.mobile.telemetrybridge.TelemetryBridge.registeredSdkVersions().forEach { (name, version) -> put(name, version) }
            })
            .put("pendingRecords", JSONArray(Telemetry.pendingRecords().map { it.toJson() })))
    }
}
