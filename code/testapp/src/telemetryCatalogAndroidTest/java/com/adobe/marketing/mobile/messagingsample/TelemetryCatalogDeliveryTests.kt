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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.services.HttpConnecting
import com.adobe.marketing.mobile.services.NetworkCallback
import com.adobe.marketing.mobile.services.NetworkRequest
import com.adobe.marketing.mobile.services.Networking
import com.adobe.marketing.mobile.services.ServiceProvider
import com.adobe.marketing.mobile.telemetry.Telemetry
import com.adobe.marketing.mobile.telemetry.TelemetryConfig
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class TelemetryCatalogDeliveryTests {
    private lateinit var original: Networking
    private val calls = CopyOnWriteArrayList<JSONObject>()
    private val callbacks = CopyOnWriteArrayList<NetworkCallback>()
    private val endpoint = "https://edge-int.adobedc.net/ee/v1/interact?configId=synthetic-test-intercepted"
    private fun policy(org: String = "TEST@AdobeOrg", enabled: Boolean = true, interval: Long = 60_000) =
        TelemetryConfig(enabled = enabled, optimizedPayload = true, orgId = org, endpoint = endpoint,
            captureDirectHooks = false, flushIntervalMs = interval)

    @Before fun prepare() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TelemetryCatalogApplication
        await { app.ready }
        Telemetry.applyConfig(policy(enabled = false))
        original = ServiceProvider.getInstance().networkService
        ServiceProvider.getInstance().networkService = Networking { request: NetworkRequest, callback: NetworkCallback ->
            if (request.url == endpoint) {
                calls.add(JSONObject(String(request.body, Charsets.UTF_8)))
                callbacks.add(callback)
            } else {
                original.connectAsync(request, callback)
            }
        }
    }

    @After fun cleanup() {
        Telemetry.applyConfig(policy(enabled = false))
        callbacks.toList().forEach { runCatching { it.call(response()) } }
        ServiceProvider.getInstance().networkService = original
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertTrue("Condition did not become true", condition())
    }

    private fun recordAction() {
        MobileCore.trackAction("synthetic.catalog.delivery", mapOf("privateMarker" to "MUST_NOT_EXPORT"))
        await { Telemetry.pendingRecords().any { it.eventName == "trackAction.requestObserved" } }
    }

    private fun response(body: String = "{}") = object : HttpConnecting {
        override fun getResponseCode() = 200
        override fun getResponseMessage() = "OK"
        override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream(): InputStream? = null
        override fun getResponsePropertyValue(key: String): String? = null
        override fun close() {}
    }

    private fun type(payload: JSONObject) = payload.getJSONArray("events").getJSONObject(0).getJSONObject("xdm").getString("eventType")
    private fun tenant(payload: JSONObject) = payload.getJSONArray("events").getJSONObject(0).getJSONObject("xdm").getJSONObject("_cjmstage")

    @Test fun oneInFlightRequestAndOptOutFencePreventFollowOnDelivery() {
        Telemetry.applyConfig(policy())
        recordAction()
        repeat(3) { Telemetry.flush() }
        assertEquals(1, calls.size)
        assertEquals("sdk.adoption", type(calls[0]))
        Telemetry.applyConfig(policy(enabled = false))
        Telemetry.applyConfig(policy(org = "NEW@AdobeOrg"))
        recordAction()
        Telemetry.flush()
        assertEquals("Old connection must finish before the next flight", 1, calls.size)
        callbacks.removeAt(0).call(response())
        assertEquals("Old callback must not remove new-generation records", 1, Telemetry.pendingCount())
        Telemetry.flush()
        assertEquals(2, calls.size)
        assertEquals("sdk.adoption", type(calls[1]))
        assertEquals("NEW@AdobeOrg", tenant(calls[1]).getString("orgId"))
        callbacks.removeAt(0).call(response())
        assertEquals("sdk.usage", type(calls[2]))
        callbacks.removeAt(0).call(response())
        assertEquals(0, Telemetry.pendingCount())
    }

    @Test fun edgeErrorsInsideHttp200DoNotAcknowledgeAdoptionOrSendUsage() {
        Telemetry.applyConfig(policy())
        recordAction()
        Telemetry.flush()
        callbacks.removeAt(0).call(response("""{"errors":[{"type":"synthetic-rejection"}]}"""))
        assertEquals(1, calls.size)
        assertEquals(0, Telemetry.pendingCount())
        assertTrue(Telemetry.stats().getValue("lastUpload").toString().contains("REJECTED"))
        assertTrue((Telemetry.stats().getValue("rejectedRecords") as Long) > 0)
    }

    @Test fun lateEnablementRunsPeriodicSenderAndExportsOnlyReviewedFields() {
        Telemetry.applyConfig(policy(interval = 1000))
        recordAction()
        await { calls.size == 1 }
        callbacks.removeAt(0).call(response())
        await { calls.size == 2 }
        val usage = calls[1]
        assertEquals("sdk.usage", type(usage))
        val data = tenant(usage)
        assertEquals(setOf("uuid"), data.getJSONObject("metadata").keys().asSequence().toSet())
        assertEquals("TIMELINE", data.getJSONObject("telemetry").getJSONArray("events").getJSONObject(0).getString("apiEventType"))
        assertEquals(0, usage.getJSONObject("xdm").getJSONObject("identityMap").length())
        assertFalse(usage.toString().contains("MUST_NOT_EXPORT"))
        assertFalse(usage.toString().contains("operationId"))
        assertFalse(usage.toString().contains("requestId"))
        callbacks.removeAt(0).call(response())
        await { Telemetry.pendingCount() == 0 }
    }

    @Test fun destinationChangesPurgeOldContextRatherThanRelabelIt() {
        Telemetry.applyConfig(policy())
        recordAction()
        assertEquals("TEST@AdobeOrg", JSONObject(Telemetry.pendingRecords().single().optimizedContext!!).getString("orgId"))
        Telemetry.applyConfig(policy(org = "OTHER@AdobeOrg"))
        assertEquals(0, Telemetry.pendingCount())
        recordAction()
        Telemetry.flush()
        assertEquals("OTHER@AdobeOrg", tenant(calls.single()).getString("orgId"))
        assertFalse(calls.single().toString().contains("TEST@AdobeOrg"))
    }
}
