package com.adobe.marketing.mobile.messagingsample

import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adobe.marketing.mobile.telemetry.ConsentStatus
import com.adobe.marketing.mobile.telemetry.SyncSettings
import com.adobe.marketing.mobile.telemetry.Telemetry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TelemetryInitFallbackTests {
    @Test fun backgroundInitStartsCollectionWithoutTheStartupInitializer() {
        assumeTrue(BuildConfig.TELEMETRY_PHASE2_VERIFICATION)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val provider = app.packageManager.resolveContentProvider(
            "${app.packageName}.androidx-startup", PackageManager.GET_META_DATA)
        assertFalse("Build with -PtelemetryInitializerNode=remove", provider?.metaData?.containsKey(
            "com.adobe.marketing.mobile.telemetry.TelemetryStartupInitializer") == true)
        assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
        val calledAt = System.currentTimeMillis()
        Telemetry.notifyConsentChanged(ConsentStatus.UNKNOWN)
        Telemetry.init(app, SyncSettings("http://intentionally-invalid.local", ""))
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var found = false
        while (!found && SystemClock.elapsedRealtime() < deadline) {
            val file = app.getDatabasePath("adobe.telemetry.autoqueue")
            if (file.exists()) {
                found = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT body FROM events", null).use { rows ->
                        var matched = false
                        while (rows.moveToNext()) {
                            val event = JSONObject(rows.getString(0))
                            if (event.getString("name") == "app_start" && event.getLong("timestamp") >= calledAt) matched = true
                        }
                        matched
                    }
                }
            }
            if (!found) Thread.sleep(25)
        }
        assertTrue("Background init must register the lifecycle fallback on the main thread", found)
        assertFalse(Telemetry.installed)
        assertTrue(TelemetryPhase2Network.requests.isEmpty())
    }
}
