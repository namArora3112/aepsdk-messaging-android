package com.adobe.marketing.mobile.messagingsample

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.adobe.marketing.mobile.services.HttpConnecting
import com.adobe.marketing.mobile.services.HttpMethod
import com.adobe.marketing.mobile.services.NetworkRequest
import com.adobe.marketing.mobile.services.Networking
import com.adobe.marketing.mobile.services.ServiceProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Fail-closed Core Networking fixture, enabled only in the isolated Phase-2 package. */
object TelemetryPhase2Network {
    val requests = CopyOnWriteArrayList<NetworkRequest>()
    @Volatile var onPost: (() -> Unit)? = null
    private lateinit var context: Context
    private val lock = Any()

    fun install(app: Context) {
        context = app.applicationContext
        ServiceProvider.getInstance().networkService = Networking { request, callback ->
            synchronized(lock) {
                requests.add(request)
                val prefs = context.getSharedPreferences("adobe.telemetry.phase2.fixture", Context.MODE_PRIVATE)
                val post = request.method == HttpMethod.POST
                val number = prefs.getInt("posts", 0)
                val failures = prefs.getInt("failures", 0)
                val code = if (post) {
                    prefs.edit().putInt("posts", number + 1).commit()
                    if (number < failures) prefs.getInt("failureCode", 429) else 200
                } else if (prefs.getBoolean("notModified", false)) 304 else 200
                val body = if (post) "" else prefs.getString("config",
                    """{"configVersion":7,"batchSize":2,"flushIntervalMs":5000}""")!!
                val payload = if (post) JSONObject(String(request.body, Charsets.UTF_8)) else null
                val events = payload?.optJSONArray("events")
                val names = JSONArray()
                val timestamps = JSONArray()
                if (events != null) repeat(events.length()) {
                    names.put(events.getJSONObject(it).getString("name"))
                    timestamps.put(events.getJSONObject(it).getLong("timestamp"))
                }
                val record = JSONObject().put("timestamp", System.currentTimeMillis())
                    .put("method", request.method.name).put("code", code)
                    .put("configVersion", payload?.optInt("configVersion", -1))
                    .put("eventNames", names).put("eventTimestamps", timestamps)
                    .put("headerNames", JSONArray(request.headers.keys.toList()))
                    .put("ifNoneMatch", request.headers["If-None-Match"])
                File(context.filesDir, "phase2-network.jsonl").appendText("$record\n")
                android.util.Log.i("AEPTelemetryPhase2Test",
                    "core_network; method=${request.method}; code=$code; events=${names.length()}")
                if (post) onPost?.invoke()
                callback.call(object : HttpConnecting {
                    override fun getInputStream() = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
                    override fun getErrorStream() = null
                    override fun getResponseCode() = code
                    override fun getResponseMessage() = "controlled Phase-2 fixture"
                    override fun getResponsePropertyValue(key: String): String? = when {
                        key.equals("ETag", true) -> "\"phase2-v7\""
                        key.equals("Retry-After", true) -> prefs.getString("retryAfter", "12")
                        else -> null
                    }
                    override fun close() {}
                })
            }
        }
    }

    fun reset() {
        requests.clear()
        onPost = null
        context.getSharedPreferences("adobe.telemetry.phase2.fixture", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "phase2-network.jsonl").writeText("")
    }
}

/** Install the fixture before WorkManager can restore any work from a previous process. */
class TelemetryPhase2NetworkProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        if (BuildConfig.TELEMETRY_PHASE2_VERIFICATION) TelemetryPhase2Network.install(context!!)
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?): Int = 0
}
