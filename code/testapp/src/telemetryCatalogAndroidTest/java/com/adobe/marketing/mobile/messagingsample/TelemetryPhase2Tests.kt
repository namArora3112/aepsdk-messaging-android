package com.adobe.marketing.mobile.messagingsample

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.adobe.marketing.mobile.services.HttpMethod
import com.adobe.marketing.mobile.telemetry.ConsentStatus
import com.adobe.marketing.mobile.telemetry.SyncSettings
import com.adobe.marketing.mobile.telemetry.Telemetry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.RunWith
import org.junit.runner.Description
import org.junit.runners.MethodSorters
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TelemetryPhase2Tests {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext
    private val fixture get() = app.getSharedPreferences("adobe.telemetry.phase2.fixture", Context.MODE_PRIVATE)
    private var preserve = false
    private var startedAt = 0L
    private val workName = "aep-telemetry-sync-retry"
    @get:Rule val evidence = object : TestWatcher() {
        override fun starting(description: Description) {
            if (!BuildConfig.TELEMETRY_PHASE2_VERIFICATION) return
            startedAt = SystemClock.elapsedRealtime()
            if (description.methodName.startsWith("aUnknown")) File(app.filesDir, "phase2-results.jsonl").writeText("")
            android.util.Log.i("AEPTelemetryPhase2Test", "scenario_start; name=${description.methodName}")
        }
        override fun succeeded(description: Description) = record(description, "pass")
        override fun failed(error: Throwable, description: Description) = record(description, "fail")
        private fun record(description: Description, status: String) {
            if (!BuildConfig.TELEMETRY_PHASE2_VERIFICATION) return
            val network = File(app.filesDir, "phase2-network.jsonl").takeIf { it.exists() }
                ?.readLines()?.map { JSONObject(it) } ?: emptyList()
            val row = JSONObject().put("name", description.methodName).put("status", status)
                .put("durationMs", SystemClock.elapsedRealtime() - startedAt)
                .put("remaining", size()).put("network", JSONArray(network))
            File(app.filesDir, "phase2-results.jsonl").appendText("$row\n")
            android.util.Log.i("AEPTelemetryPhase2Test", "scenario_$status; name=${description.methodName}")
        }
    }
    private fun settings(batch: Int = 2, interval: Long = 5000) =
        SyncSettings(SyncSettings.DEFAULT_ENDPOINT, "phase2-test-key", batchSize = batch, flushIntervalMs = interval)
    private fun posts() = TelemetryPhase2Network.requests.filter { it.method == HttpMethod.POST }
    private fun gets() = TelemetryPhase2Network.requests.filter { it.method == HttpMethod.GET }

    private fun sync(method: String, vararg args: Any) {
        val type = Class.forName("com.adobe.marketing.mobile.telemetry.internal.Sync")
        val target = type.getField("INSTANCE").get(null)
        type.declaredMethods.first {
            java.lang.reflect.Modifier.isPublic(it.modifiers) &&
                it.name.substringBefore('$') == method && it.parameterTypes.size == args.size
        }.invoke(target, *args)
    }

    private fun await(reason: String, timeoutMs: Long = 10_000, check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (check()) return
            Thread.sleep(25)
        }
        fail("Timed out: $reason")
    }

    private fun <T> database(block: (SQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(app.getDatabasePath("adobe.telemetry.autoqueue").path, null,
            SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun size(): Int = database { db ->
        db.rawQuery("SELECT COUNT(*) FROM events", null).use { it.moveToFirst(); it.getInt(0) }
    }

    private fun seed(count: Int, notify: Boolean = true): List<Long> {
        val times = (0 until count).map { 1_795_000_000_000L + it }
        database { db ->
            times.forEach { timestamp ->
                val json = JSONObject().put("name", "screen_view").put("timestamp", timestamp)
                    .put("sessionId", "phase2-test-session").put("properties", JSONObject())
                db.insertOrThrow("events", null, ContentValues().apply {
                    put("timestamp", timestamp); put("body", json.toString())
                })
            }
        }
        if (notify) sync("onEventEnqueued", size())
        return times
    }

    @Before fun setup() {
        assumeTrue(BuildConfig.TELEMETRY_PHASE2_VERIFICATION)
        await("automatic startup database") { app.getDatabasePath("adobe.telemetry.autoqueue").exists() }
        sync("resetForTests")
        WorkManager.getInstance(app).cancelUniqueWork(workName).result.get(10, TimeUnit.SECONDS)
        app.getSharedPreferences("adobe.telemetry.sync", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("adobe.telemetry.remoteconfig", Context.MODE_PRIVATE).edit().clear().commit()
        TelemetryPhase2Network.reset()
        database { it.delete("events", null, null) }
        Telemetry.notifyConsentChanged(ConsentStatus.UNKNOWN)
        sync("awaitIdleForTests")
    }

    @After fun cleanup() {
        if (!BuildConfig.TELEMETRY_PHASE2_VERIFICATION || preserve) return
        TelemetryPhase2Network.onPost = null
        sync("resetForTests")
        WorkManager.getInstance(app).cancelUniqueWork(workName).result.get(10, TimeUnit.SECONDS)
    }

    @Test fun aUnknownConsentBlocksConfigAndUploadsThenDrainsOriginalTimestamps() {
        val originalTimes = seed(5)
        Telemetry.init(app, settings().copy(configEndpoint = "https://edge-int.adobedc.net/phase2-config"))
        sync("awaitIdleForTests")
        assertEquals(5, size())
        assertTrue(TelemetryPhase2Network.requests.isEmpty())
        Telemetry.notifyConsentChanged(ConsentStatus.OPTED_IN)
        await("consented drain") { size() == 0 }
        assertEquals(1, gets().size)
        assertEquals(listOf(2, 2, 1), posts().map { JSONObject(String(it.body)).getJSONArray("events").length() })
        val sent = posts().flatMap {
            val payload = JSONObject(String(it.body))
            assertEquals(7, payload.getInt("configVersion"))
            assertEquals("7", it.headers["x-aep-telemetry-config-version"])
            val events = payload.getJSONArray("events")
            (0 until events.length()).map { i -> events.getJSONObject(i).getLong("timestamp") }
        }
        assertEquals(originalTimes, sent)
    }

    @Test fun bInvalidFirstConfigWinsAndBuffersWithoutNetwork() {
        seed(3)
        Telemetry.notifyConsentChanged(ConsentStatus.OPTED_IN)
        Telemetry.init(app, settings().copy(endpoint = "http://invalid.local", apiKey = ""))
        Telemetry.init(app, settings())
        sync("awaitIdleForTests")
        assertEquals(3, size())
        assertTrue(TelemetryPhase2Network.requests.isEmpty())
    }

    @Test fun cValidFirstConfigWinsAndInitDoesNotBlockMainThread() {
        seed(1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        TelemetryPhase2Network.onPost = { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
        Telemetry.notifyConsentChanged(ConsentStatus.OPTED_IN)
        var duration = Long.MAX_VALUE
        instrumentation.runOnMainSync {
            val before = SystemClock.elapsedRealtime()
            Telemetry.init(app, settings().copy(apiKey = "first-test-key"))
            Telemetry.init(app, settings().copy(apiKey = "second-test-key"))
            duration = SystemClock.elapsedRealtime() - before
        }
        try {
            assertTrue("init took $duration ms", duration < 500)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
        } finally { release.countDown() }
        await("initial upload") { size() == 0 }
        assertEquals("first-test-key", posts().single().headers["x-api-key"])
    }

    @Test fun dOptOutBeforeInitPurgesAndPausesActualLifecycleCollection() {
        seed(3)
        Telemetry.optOut()
        Telemetry.init(app, settings().copy(apiKey = ""))
        sync("awaitIdleForTests")
        assertEquals(0, size())
        val activity = openActivity()
        instrumentation.runOnMainSync { activity.finish() }
        await("actual process background while opted out") {
            var stopped = false
            instrumentation.runOnMainSync {
                val owner = Class.forName("androidx.lifecycle.ProcessLifecycleOwner").getMethod("get")
                    .invoke(null) as androidx.lifecycle.LifecycleOwner
                stopped = owner.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED
            }
            stopped
        }
        sync("awaitIdleForTests")
        assertEquals(0, size())
        assertTrue(TelemetryPhase2Network.requests.isEmpty())
    }

    @Test fun eOptOutDuringPostPurgesAndStopsNextChunk() {
        seed(5)
        TelemetryPhase2Network.onPost = { Telemetry.optOut() }
        Telemetry.notifyConsentChanged(ConsentStatus.OPTED_IN)
        Telemetry.init(app, settings())
        await("opt-out purge") { size() == 0 }
        sync("awaitIdleForTests")
        assertEquals(1, posts().size)
        assertTrue(WorkManager.getInstance(app).getWorkInfosForUniqueWork(workName)
            .get(5, TimeUnit.SECONDS).none { !it.state.isFinished })
    }

    @Test fun fSubThresholdEventsFlushOnTimer() {
        Telemetry.notifyConsentChanged(ConsentStatus.OPTED_IN)
        val beforeInit = SystemClock.elapsedRealtime()
        Telemetry.init(app, settings(batch = 100))
        sync("awaitIdleForTests")
        seed(1)
        sync("awaitIdleForTests")
        assertEquals(1, size())
        assertTrue(posts().isEmpty())
        await("five-second periodic flush", 8000) { size() == 0 }
        assertTrue("Must be the timer, not a leftover lifecycle flush",
            SystemClock.elapsedRealtime() - beforeInit >= 4800)
        assertEquals(1, posts().size)
    }

    @Test fun gBackgroundFlushIncludesTheBackgroundEvent() {
        Telemetry.notifyConsentChanged(ConsentStatus.OPTED_IN)
        Telemetry.init(app, settings(batch = 100, interval = 900_000))
        sync("awaitIdleForTests")
        val activity = openActivity()
        instrumentation.runOnMainSync { activity.finish() }
        await("process background flush") {
            posts().any { request ->
                val events = JSONObject(String(request.body)).getJSONArray("events")
                (0 until events.length()).any { events.getJSONObject(it).getString("name") == "app_background" }
            }
        }
    }

    @Test fun hRetryAfterRetainsChunkAndRealWorkerRetriesWithoutEarlyTimerSend() {
        fixture.edit().putInt("failures", 1).putString("retryAfter", "12").commit()
        seed(3)
        Telemetry.notifyConsentChanged(ConsentStatus.OPTED_IN)
        val failedAt = System.currentTimeMillis()
        Telemetry.init(app, settings())
        await("retry work enqueued") { pendingWork().isNotEmpty() }
        assertEquals(3, size())
        assertEquals(1, posts().size)
        val notBefore = app.getSharedPreferences("adobe.telemetry.sync", Context.MODE_PRIVATE).getLong("retryAtMs", 0)
        assertTrue(notBefore >= failedAt + 12_000)
        while (System.currentTimeMillis() < notBefore - 100) {
            assertEquals("No timer/batch retry before Retry-After", 1, posts().size)
            Thread.sleep(50)
        }
        await("real WorkManager retry", 12_000) { size() == 0 }
        assertEquals(3, posts().size) // one failed chunk, then accepted chunks of 2 + 1
    }

    @Test fun iClientRejectionKeepsRowsAndDoesNotHotLoop() {
        fixture.edit().putInt("failures", 100).putInt("failureCode", 400).commit()
        seed(3)
        Telemetry.notifyConsentChanged(ConsentStatus.OPTED_IN)
        Telemetry.init(app, settings())
        await("client rejection") { posts().size == 1 }
        sync("awaitIdleForTests")
        repeat(3) { sync("onEventEnqueued", 100) }
        sync("awaitIdleForTests")
        assertEquals(3, size())
        assertEquals(1, posts().size)
        assertTrue(pendingWork().isEmpty())
    }

    @Test fun jEtagMinimumIntervalAnd304KeepLastKnownGood() {
        consentedRemoteInit()
        repeat(3) { sync("onAppBackgrounded") }
        sync("awaitIdleForTests")
        assertEquals(1, gets().size)
        fixture.edit().putBoolean("notModified", true).commit()
        forceConfigRefresh()
        assertEquals(2, gets().size)
        assertEquals("\"phase2-v7\"", gets().last().headers["If-None-Match"])
        seed(2)
        await("cached version upload") { size() == 0 }
        assertEquals(7, JSONObject(String(posts().last().body)).getInt("configVersion"))
    }

    @Test fun kMalformedAndOversizedRemoteConfigRollBackToPreviousEndpointAndVersion() {
        fixture.edit().putString("config",
            """{"endpoint":"https://edge.adobedc.net/phase2-upload","configVersion":7}""").commit()
        consentedRemoteInit()
        for (invalid in listOf(
            """{"endpoint":"https://not-allowed.invalid/x","configVersion":99}""",
            """{"configVersion":999,"padding":"${"x".repeat(70_000)}"}"""
        )) {
            fixture.edit().putString("config", invalid).commit()
            forceConfigRefresh()
            seed(2)
            await("last-known-good upload") { size() == 0 }
            assertEquals("https://edge.adobedc.net/phase2-upload", posts().last().url)
            assertEquals(7, JSONObject(String(posts().last().body)).getInt("configVersion"))
        }
    }

    @Test fun lUnknownConsentDuringPostBuffersNextChunkUntilOptIn() {
        seed(5)
        TelemetryPhase2Network.onPost = { Telemetry.notifyConsentChanged(ConsentStatus.UNKNOWN) }
        Telemetry.notifyConsentChanged(ConsentStatus.OPTED_IN)
        Telemetry.init(app, settings())
        await("first accepted chunk") { size() == 3 }
        sync("awaitIdleForTests")
        assertEquals(1, posts().size)
        TelemetryPhase2Network.onPost = null
        Telemetry.notifyConsentChanged(ConsentStatus.OPTED_IN)
        await("remaining chunks after opt-in") { size() == 0 }
        assertEquals(3, posts().size)
    }

    @Test fun mOptInResumesRealLifecycleCollectionAndBatchThreshold() {
        Telemetry.optOut()
        Telemetry.init(app, settings(batch = 1, interval = 900_000))
        sync("awaitIdleForTests")
        Telemetry.notifyConsentChanged(ConsentStatus.OPTED_IN)
        sync("awaitIdleForTests")
        val activity = openActivity()
        await("real screen callback reaches batch threshold") {
            posts().any { request -> String(request.body).contains("\"screen_view\"") }
        }
        instrumentation.runOnMainSync { activity.finish() }
        await("resumed collection includes background") {
            posts().any { request -> String(request.body).contains("\"app_background\"") }
        }
    }

    @Test fun nRepeated503UsesExponentialDelayAndAppendsAUniqueWorker() {
        fixture.edit().putInt("failures", 2).putInt("failureCode", 503).putString("retryAfter", "0").commit()
        seed(3)
        Telemetry.notifyConsentChanged(ConsentStatus.OPTED_IN)
        Telemetry.init(app, settings())
        val prefs = app.getSharedPreferences("adobe.telemetry.sync", Context.MODE_PRIVATE)
        await("second 503 creates exponential retry", 18_000) { prefs.getInt("retryAttempt", 0) == 2 }
        sync("awaitIdleForTests")
        assertEquals(2, posts().size)
        assertEquals(3, size())
        val lastFailure = JSONObject(File(app.filesDir, "phase2-network.jsonl").readLines().last())
        assertTrue(prefs.getLong("retryAtMs", 0) >= lastFailure.getLong("timestamp") + 20_000)
        await("appended follow-up Worker") { pendingWork().any { it.state == WorkInfo.State.ENQUEUED } }
    }

    @Test fun oRemoteBoundsAndForbiddenHeadersApplyAtNetworkBoundary() {
        fixture.edit().putString("config", """{
            "configVersion":8,"minRemoteConfigFetchMs":1,"batchSize":9999,"flushIntervalMs":1,
            "headers":{"Authorization":"forbidden","Host":"other","Cookie":"x","Content-Length":"1",
                "x-api-key":"not-the-key","x-safe":"allowed"}
        }""").commit()
        val before = System.currentTimeMillis()
        consentedRemoteInit()
        val next = app.getSharedPreferences("adobe.telemetry.remoteconfig", Context.MODE_PRIVATE)
            .getLong("nextFetchMs", 0)
        assertTrue(next >= before + SyncSettings.MIN_CONFIG_FETCH_MS)
        assertTrue("Must honor the remotely configured, clamped minimum",
            next <= System.currentTimeMillis() + SyncSettings.MIN_CONFIG_FETCH_MS * 5 / 4)
        seed(105)
        await("clamped batch upload") { size() == 0 }
        assertEquals(listOf(100, 5), posts().map { JSONObject(String(it.body)).getJSONArray("events").length() })
        posts().forEach { request ->
            assertEquals("phase2-test-key", request.headers["x-api-key"])
            assertEquals("allowed", request.headers["x-safe"])
            assertTrue(request.headers.keys.none {
                it.lowercase() in setOf("authorization", "host", "cookie", "content-length")
            })
        }
    }

    @Test fun pUnknownDuringFailedUploadPreservesRetryAfterAcrossOptIn() {
        fixture.edit().putInt("failures", 1).putString("retryAfter", "12").commit()
        seed(3)
        TelemetryPhase2Network.onPost = { Telemetry.notifyConsentChanged(ConsentStatus.UNKNOWN) }
        Telemetry.notifyConsentChanged(ConsentStatus.OPTED_IN)
        val failedAt = System.currentTimeMillis()
        Telemetry.init(app, settings())
        await("failed upload while consent becomes UNKNOWN") { posts().size == 1 }
        sync("awaitIdleForTests")
        val notBefore = app.getSharedPreferences("adobe.telemetry.sync", Context.MODE_PRIVATE)
            .getLong("retryAtMs", 0)
        assertTrue("Consent changes must not discard Retry-After", notBefore >= failedAt + 12_000)
        TelemetryPhase2Network.onPost = null
        Telemetry.notifyConsentChanged(ConsentStatus.OPTED_IN)
        sync("awaitIdleForTests")
        while (System.currentTimeMillis() < notBefore - 100) {
            assertEquals(1, posts().size)
            assertEquals(3, size())
            Thread.sleep(50)
        }
        await("retry after consent and backoff gates", 12_000) { size() == 0 }
        assertEquals(3, posts().size)
    }

    @Test fun zPrepareWorkerRecoveryWithoutAnotherInitCall() {
        fixture.edit().putInt("failures", 1).putString("retryAfter", "20").commit()
        seed(3)
        Telemetry.notifyConsentChanged(ConsentStatus.OPTED_IN)
        Telemetry.init(app, settings().copy(configEndpoint = "https://edge-int.adobedc.net/phase2-config"))
        await("durable retry scheduled") { pendingWork().isNotEmpty() }
        assertEquals(3, size())
        assertEquals(1, posts().size)
        preserve = true
        android.util.Log.i("AEPTelemetryPhase2Test", "worker_recovery_prepared; queued=3; init_must_not_be_called_after_restart")
    }

    private fun openActivity(): Activity = instrumentation.startActivitySync(
        Intent(app, TelemetryCatalogActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("run_poc", false)
    )

    private fun pendingWork(): List<WorkInfo> = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWork(workName).get(5, TimeUnit.SECONDS).filter { !it.state.isFinished }

    private fun consentedRemoteInit() {
        Telemetry.notifyConsentChanged(ConsentStatus.OPTED_IN)
        Telemetry.init(app, settings().copy(configEndpoint = "https://edge-int.adobedc.net/phase2-config"))
        sync("awaitIdleForTests")
        assertEquals(1, gets().size)
    }

    private fun forceConfigRefresh() {
        app.getSharedPreferences("adobe.telemetry.remoteconfig", Context.MODE_PRIVATE)
            .edit().putLong("nextFetchMs", 0).commit()
        sync("onAppBackgrounded")
        sync("awaitIdleForTests")
    }
}
