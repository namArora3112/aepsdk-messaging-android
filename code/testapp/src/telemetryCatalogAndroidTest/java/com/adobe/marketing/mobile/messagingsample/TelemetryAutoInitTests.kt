package com.adobe.marketing.mobile.messagingsample

import android.database.sqlite.SQLiteDatabase
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adobe.marketing.mobile.telemetry.Telemetry
import com.adobe.marketing.mobile.telemetry.TelemetryStartupInitializer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TelemetryAutoInitTests {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext
    private val processStartedAt
        get() = System.currentTimeMillis() - SystemClock.elapsedRealtime() +
            Process.getStartElapsedRealtime()

    @Before fun verificationBuildOnly() {
        assumeTrue(BuildConfig.TELEMETRY_AUTO_INIT_VERIFICATION)
    }

    private fun events(): List<JSONObject> {
        val file = app.getDatabasePath("adobe.telemetry.autoqueue")
        if (!file.exists()) return emptyList()
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT body FROM events ORDER BY id", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(JSONObject(cursor.getString(0))) }
            }
        }
    }

    private fun startup(): JSONObject {
        assertTrue("Use -PtelemetryAutoInitVerification=true", BuildConfig.TELEMETRY_AUTO_INIT_VERIFICATION)
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            events().lastOrNull {
                it.getString("name") == "app_start" && it.getLong("timestamp") >= processStartedAt - 100
            }?.let { return it }
            Thread.sleep(25)
        }
        fail("No automatic app_start persisted for this process")
        throw AssertionError()
    }

    @Test fun startupDoesNotNeedManualTelemetryInstallOrInit() {
        val start = startup()
        assertFalse("Verification host must not manually install the POC", Telemetry.installed)
        assertTrue(start.getLong("timestamp") >= processStartedAt - 100)
        assertTrue(start.getString("sessionId").isNotBlank())
    }

    @Test fun coldStartIsADurationNotBootUptime() {
        if (Build.VERSION.SDK_INT < 24) return
        val start = startup()
        val duration = start.getJSONObject("properties").getLong("cold_start_ms")
        val elapsedSinceStart = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        assertTrue("cold_start_ms=$duration must be elapsed process duration <= $elapsedSinceStart",
            duration in 0..elapsedSinceStart)
    }

    @Test fun repeatedInitializerCallsDoNotDuplicateProcessStart() {
        startup()
        val before = events().count { it.getString("name") == "app_start" }
        instrumentation.runOnMainSync {
            repeat(3) { TelemetryStartupInitializer().create(app) }
        }
        assertEquals(before, events().count { it.getString("name") == "app_start" })
    }

    @Test fun secondaryProcessDoesNotCollectAnotherAppStart() {
        startup()
        val before = events().count { it.getString("name") == "app_start" }
        val connected = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        assertTrue(app.bindService(Intent(app, TelemetryAutoInitProbeService::class.java),
            connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("Secondary process must execute initializer", connected.await(10, TimeUnit.SECONDS))
            assertEquals(before, events().count { it.getString("name") == "app_start" })
        } finally {
            app.unbindService(connection)
        }
    }
}
