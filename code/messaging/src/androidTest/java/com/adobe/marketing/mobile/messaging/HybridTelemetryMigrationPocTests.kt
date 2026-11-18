/*
  Copyright 2026 Adobe. All rights reserved.
  This file is licensed to you under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License. You may obtain a copy
  of the License at http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software distributed under
  the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
  OF ANY KIND, either express or implied. See the License for the specific language
  governing permissions and limitations under the License.
*/

package com.adobe.marketing.mobile.messaging

import android.app.Application
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adobe.marketing.mobile.AdobeCallback
import com.adobe.marketing.mobile.EventSource
import com.adobe.marketing.mobile.EventType
import com.adobe.marketing.mobile.Messaging
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.plugin.TelemetryApiCapability
import com.adobe.marketing.mobile.plugin.TelemetryEventMapping
import com.adobe.marketing.mobile.telemetry.Telemetry
import com.adobe.marketing.mobile.telemetry.TelemetryConfig
import com.adobe.marketing.mobile.telemetry.TelemetryRecord
import com.adobe.marketing.mobile.telemetrybridge.TelemetryBridge
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real Messaging APIs plus explicitly labelled protocol simulations; synthetic network only. */
@RunWith(AndroidJUnit4::class)
class HybridTelemetryMigrationPocTests {
    private val flow = RefreshEventCorrelationPocTests()
    @get:Rule val coreRule = flow.coreRule
    private val refresh = "Messaging.refreshInAppMessages"
    private val get = "Messaging.getPropositionsForSurfaces"

    @Before
    fun setup() {
        TelemetryBridge.setCaptureMode("hybrid")
        flow.setup()
        MobileCore.updateConfiguration(mapOf("telemetry.captureMode" to "hybrid"))
        await { TelemetryBridge.migrationStatus()["captureMode"] == "hybrid" }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        Telemetry.install(app as Application)
        Telemetry.applyConfig(TelemetryConfig(enabled = false))
        Telemetry.applyConfig(TelemetryConfig(enabled = true))
        Telemetry.setEventMappings(
            listOf(
                TelemetryEventMapping(
                    EventType.MESSAGING, EventSource.REQUEST_CONTENT,
                    "com.adobe.messaging", "messaging.refresh.request",
                    eventName = "Refresh in-app messages",
                    retireWhenApisCovered = listOf(refresh)
                ),
                TelemetryEventMapping(
                    EventType.GENERIC_TRACK, EventSource.REQUEST_CONTENT,
                    "legacy.sdk", "legacy.tracking.observed"
                )
            )
        )
    }

    @After
    fun teardown() {
        Telemetry.applyConfig(TelemetryConfig(enabled = false))
        TelemetryBridge.setCaptureMode("hybrid")
        TelemetryBridge.declareApiCapabilities("protocol.poc", emptyList())
        // SetupCoreRule deletes app databases between tests; release the install-once recorder first.
        val recorderField = Telemetry::class.java.getDeclaredField("recorder").apply { isAccessible = true }
        recorderField.get(null)?.let { recorder ->
            val outboxField = recorder.javaClass.getDeclaredField("outbox").apply { isAccessible = true }
            (outboxField.get(recorder) as? SQLiteOpenHelper)?.close()
        }
        recorderField.set(null, null)
        Telemetry::class.java.getDeclaredField("installed").apply { isAccessible = true }.setBoolean(null, false)
        flow.tearDown()
    }

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < end) Thread.sleep(10)
        assertTrue("Condition not reached. Records: ${Telemetry.pendingRecords()}", condition())
    }

    private fun records(name: String) = Telemetry.pendingRecords().filter { it.eventName == name }

    private fun save(name: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "hybrid-telemetry-poc")
        directory.mkdirs()
        File(directory, "$name.json").writeText(
            JSONObject().put("scenario", name).put("syntheticOnly", true)
                .put("status", JSONObject(TelemetryBridge.migrationStatus()))
                .put("records", JSONArray(Telemetry.pendingRecords().map(TelemetryRecord::toJson)))
                .toString(2)
        )
    }

    @Test
    fun realCoalescedCallsHaveTwoEntriesButOneObservedRequest() {
        flow.refresh_overlappingPublicCalls_shareOneRequest()
        await { records(refresh).size == 2 && records("messaging.refresh.request").size == 1 }
        assertTrue(records(refresh).all { it.recordKind == "api_invocation" && it.coverage == "entry_only" })
        assertEquals(2, records(refresh).map { it.operationId }.distinct().size)
        assertEquals("event_observation", records("messaging.refresh.request").single().recordKind)
        save("real-coalescing-two-calls-one-request")
    }

    @Test
    fun customDispatchDoesNotCreateConfirmedRefreshApiEntry() {
        flow.customDispatch_sameRefreshSignature_hasSameCorrelatableLifecycle()
        await { records("messaging.refresh.request").size == 1 }
        assertTrue(records(refresh).isEmpty())
        save("real-custom-dispatch-zero-api-entries")
    }

    @Test
    fun refreshEntryOnlyAndCacheCallbackOutcomesStayDifferent() {
        flow.refresh_emptyResponse_completesWithoutDecisionEvents()
        await { records(get).any { it.phase == "completed" } }
        assertEquals("entry_only", records(refresh).single().coverage)
        assertNull(records(refresh).single().outcome)
        val cache = records(get).single { it.phase == "completed" }
        assertEquals("cache_callback.v1", cache.outcomeContract)
        assertEquals("failure", cache.outcome)
        assertEquals("api_outcome", cache.recordKind)
        save("real-entry-only-refresh-cache-callback-failure")
    }

    @Test
    fun actualLocalRejectionIsRecordedWithoutChangingSilentCallbackContract() {
        val callbackCalled = AtomicBoolean()
        Messaging.getPropositionsForSurfaces(emptyList(), AdobeCallback { callbackCalled.set(true) })
        await { records(get).size == 2 }
        assertEquals("rejected", records(get).single { it.phase == "completed" }.outcome)
        assertFalse(callbackCalled.get())
        assertTrue(records("messaging.refresh.request").isEmpty())
        save("real-local-rejection-no-dispatch")
    }

    @Test
    fun realCoreConfigurationSunsetDoesNotDisableBusinessEventsOrDirectHooks() {
        MobileCore.updateConfiguration(mapOf("telemetry.captureMode" to "direct_only"))
        await { TelemetryBridge.migrationStatus()["captureMode"] == "direct_only" }
        val businessEvent = CountDownLatch(1)
        MobileCore.registerEventListener(EventType.GENERIC_TRACK, EventSource.REQUEST_CONTENT) {
            businessEvent.countDown()
        }
        MobileCore.trackAction("synthetic-business-event", null)
        assertTrue(businessEvent.await(5, TimeUnit.SECONDS))
        flow.refresh_emptyResponse_completesWithoutDecisionEvents()
        await { records(get).any { it.phase == "completed" } }
        assertEquals(1, records(refresh).size)
        assertTrue(records("messaging.refresh.request").isEmpty())
        assertTrue(records("legacy.tracking.observed").isEmpty())
        MobileCore.updateConfiguration(mapOf("telemetry.captureMode" to "hybrid"))
        await { TelemetryBridge.migrationStatus()["captureMode"] == "hybrid" }
        MobileCore.trackAction("synthetic-rollback", null)
        await { records("legacy.tracking.observed").size == 1 }
        save("real-core-policy-sunset-and-rollback")
    }

    @Test
    fun policyTransitionPinsActiveContractButPermissionWithdrawalPurgesIt() {
        TelemetryBridge.declareApiCapabilities(
            "protocol.poc",
            listOf(
                TelemetryApiCapability("call", "1.0", outcomes = setOf("success"), outcomeContract = "protocol.v1")
            )
        )
        val active = requireNotNull(TelemetryBridge.apiStarted("protocol.poc", "call"))
        await { records("call").size == 1 }
        MobileCore.updateConfiguration(mapOf("telemetry.captureMode" to "direct_only"))
        await { TelemetryBridge.migrationStatus()["captureMode"] == "direct_only" }
        Telemetry.applyConfig(TelemetryConfig(enabled = true, flushIntervalMs = 120000))
        TelemetryBridge.apiCompleted(active, "protocol.poc", "call", "success")
        await { records("call").size == 2 }
        assertTrue(records("call").all { it.captureMode == "hybrid" })
        val discarded = requireNotNull(TelemetryBridge.apiStarted("protocol.poc", "call"))
        Telemetry.applyConfig(TelemetryConfig(enabled = false))
        assertEquals(0, Telemetry.pendingCount())
        Telemetry.applyConfig(TelemetryConfig(enabled = true))
        TelemetryBridge.apiCompleted(discarded, "protocol.poc", "call", "success")
        Messaging.getPropositionsForSurfaces(emptyList(), AdobeCallback {})
        await { records(get).size == 2 }
        assertTrue(records("call").isEmpty())
        save("protocol-active-call-policy-and-consent")
    }

    @Test
    fun sharedSelectorRetirementRequiresCompleteGroupNotOneUpgradedApi() {
        fun capability(name: String, complete: Boolean) = TelemetryApiCapability(
            name, "1.0", outcomes = if (complete) setOf("success") else emptySet(),
            outcomeContract = if (complete) "protocol.v1" else null
        )
        Telemetry.setEventMappings(
            listOf(
                TelemetryEventMapping(
                    EventType.GENERIC_TRACK, EventSource.REQUEST_CONTENT, "protocol.poc", "shared.observed",
                    retireWhenApisCovered = listOf("first", "second")
                )
            )
        )
        TelemetryBridge.declareApiCapabilities("protocol.poc", listOf(capability("first", true)))
        MobileCore.trackAction("synthetic-first", null)
        await { records("shared.observed").size == 1 }
        TelemetryBridge.declareApiCapabilities(
            "protocol.poc", listOf(capability("first", true), capability("second", false))
        )
        MobileCore.trackAction("synthetic-second", null)
        await { records("shared.observed").size == 2 }
        TelemetryBridge.declareApiCapabilities(
            "protocol.poc", listOf(capability("first", true), capability("second", true))
        )
        MobileCore.trackAction("synthetic-retired", null)
        val direct = requireNotNull(TelemetryBridge.apiStarted("protocol.poc", "first"))
        TelemetryBridge.apiCompleted(direct, "protocol.poc", "first", "success")
        await { records("first").size == 2 }
        assertEquals(2, records("shared.observed").size)
        save("protocol-partial-shared-selector-retirement")
    }
}
