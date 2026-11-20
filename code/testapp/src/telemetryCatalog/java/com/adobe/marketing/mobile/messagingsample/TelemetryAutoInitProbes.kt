package com.adobe.marketing.mobile.messagingsample

import android.app.Activity
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import com.adobe.marketing.mobile.telemetry.TelemetryStartupInitializer

/** Enabled only in the isolated verification application, never the prepared POC. */
class TelemetryAutoInitCrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.TELEMETRY_AUTO_INIT_VERIFICATION) {
            finish()
            return
        }
        Thread({
            throw IllegalStateException("synthetic-auto-init-crash-message-not-for-telemetry")
        }, "auto-init-crash-" + "x".repeat(160)).start()
    }
}

class TelemetryAutoInitProbeService : Service() {
    override fun onCreate() {
        super.onCreate()
        TelemetryStartupInitializer().create(this)
        android.util.Log.i("TelemetryAutoInitTest", "Secondary-process initializer returned")
    }

    override fun onBind(intent: Intent) = Binder()
}
