/*
 * Copyright 2026 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.adobe.marketing.mobile.messagingsample

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.adobe.marketing.mobile.Edge
import com.adobe.marketing.mobile.ExperienceEvent
import com.adobe.marketing.mobile.Messaging
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.edge.consent.Consent
import com.adobe.marketing.mobile.internal.telemetry.SdkTelemetry
import com.adobe.marketing.mobile.services.HttpConnecting
import com.adobe.marketing.mobile.services.NetworkCallback
import com.adobe.marketing.mobile.services.NetworkRequest
import com.adobe.marketing.mobile.services.Networking
import com.adobe.marketing.mobile.services.ServiceProvider
import com.adobe.marketing.mobile.telemetry.SdkTelemetryPlugin
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

internal object TelemetryValidation {
    private const val CONFIG_ID = "6308f4cc-1c6b-4700-aaa8-d5622404f6c3"
    private val plugin = SdkTelemetryPlugin("_cjmstage", 1024)
    private lateinit var directory: File
    private val captureLock = Any()

    fun start(application: Application) {
        directory = File(application.noBackupFilesDir, "telemetry-validation").also { it.mkdirs() }
        val provider = ServiceProvider.getInstance()
        val actual = provider.networkService
        provider.setNetworkService(object : Networking {
            override fun connectAsync(request: NetworkRequest, callback: NetworkCallback) {
                val telemetry = request.url.contains("configId=$CONFIG_ID") &&
                    request.body?.toString(Charsets.UTF_8)?.contains("\"sdk.") == true
                val body = request.body?.toString(Charsets.UTF_8) ?: ""
                if (telemetry || body.contains("validation.business")) {
                    capture(
                        JSONObject().put("kind", if (telemetry) "telemetry" else "business")
                            .put("url", request.url).put("body", JSONObject(body))
                            .put("at", System.currentTimeMillis())
                    )
                }
                if (telemetry && File(directory, "offline").exists()) {
                    capture(JSONObject().put("kind", "simulatedOffline"))
                    callback.call(null)
                    return
                }
                actual.connectAsync(request) { connection: HttpConnecting? ->
                    if (telemetry || body.contains("validation.business")) {
                        capture(
                            JSONObject().put("kind", "response")
                                .put("telemetry", telemetry).put("status", connection?.responseCode ?: 0)
                                .put("at", System.currentTimeMillis())
                        )
                    }
                    callback.call(connection)
                }
            }
        })
        MobileCore.addPlugins(plugin)
    }

    fun configure() {
        MobileCore.updateConfiguration(
            mapOf(
                "experienceCloud.org" to "745F37C35E4B776E0A49421B@AdobeOrg",
                "edge.configId" to CONFIG_ID,
                "edge.environment" to "int",
                "global.privacy" to "optedin",
                SdkTelemetry.ENABLED to true,
                "sdk.telemetry.edge.configId" to CONFIG_ID,
                "sdk.telemetry.edge.endpoint" to "https://edge-int.adobedc.net/ee/v1/interact"
            )
        )
        Consent.update(mapOf("consents" to mapOf("collect" to mapOf("val" to "y"))))
    }

    fun setOffline(offline: Boolean) {
        if (offline) File(directory, "offline").writeText("offline")
        else File(directory, "offline").delete()
    }

    fun diagnostics() {
        plugin.diagnostics { counters ->
            capture(JSONObject(counters).put("kind", "diagnostics").put("at", System.currentTimeMillis()))
            Log.i("TelemetryValidation", counters.toString())
        }
    }

    private fun capture(value: JSONObject) {
        synchronized(captureLock) {
            runCatching {
                FileOutputStream(File(directory, "capture.jsonl"), true).bufferedWriter().use {
                    it.append(value.toString()).append('\n')
                }
            }
        }
    }
}

/** Available only in the opt-in debug validation application, never the SDK artifact. */
class TelemetryValidationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "SDK telemetry validation" })
        action(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        action(intent)
    }

    private fun action(intent: Intent) {
        when (intent.getStringExtra("action") ?: "status") {
            "configure" -> TelemetryValidation.configure()
            "generate" -> repeat(intent.getIntExtra("count", 20).coerceIn(1, 200)) {
                Messaging.refreshInAppMessages()
            }
            "pause" -> MobileCore.updateConfiguration(mapOf(SdkTelemetry.ENABLED to false))
            "resume" -> MobileCore.updateConfiguration(mapOf(SdkTelemetry.ENABLED to true))
            "deny" -> Consent.update(mapOf("consents" to mapOf("collect" to mapOf("val" to "n"))))
            "allow" -> Consent.update(mapOf("consents" to mapOf("collect" to mapOf("val" to "y"))))
            "offline" -> TelemetryValidation.setOffline(true)
            "online" -> TelemetryValidation.setOffline(false)
            "business" -> Edge.sendEvent(
                ExperienceEvent.Builder().setXdmSchema(mapOf("eventType" to "validation.business")).build(),
                null
            )
            "status" -> TelemetryValidation.diagnostics()
        }
    }
}
