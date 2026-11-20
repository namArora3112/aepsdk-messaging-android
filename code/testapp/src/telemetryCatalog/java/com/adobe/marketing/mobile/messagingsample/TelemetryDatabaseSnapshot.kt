package com.adobe.marketing.mobile.messagingsample

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

internal object TelemetryDatabaseSnapshot {
    private const val LIMIT = 100
    private val autoProperties = setOf(
        "cold_start_ms", "version_code", "previous_version_code", "screen", "exception_class"
    )

    fun read(context: Context, automatic: Boolean? = null): String {
        val queues = mutableListOf<String>()
        if (automatic != false) queues.add(readQueue(context, "adobe.telemetry.autoqueue", "events", "id", automatic = true))
        if (automatic != true) queues.add(readQueue(context, "adobe.telemetry.outbox", "records", "seq", automatic = false))
        return queues.joinToString("\n\n")
    }

    private fun readQueue(context: Context, name: String, table: String, id: String, automatic: Boolean): String {
        val file = context.getDatabasePath(name)
        if (!file.exists()) return "$name / $table\nNot created yet."
        return runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val count = db.rawQuery("SELECT COUNT(*) FROM $table", null).use {
                    if (it.moveToFirst()) it.getInt(0) else 0
                }
                val rows = db.rawQuery("SELECT $id, body FROM $table ORDER BY $id DESC LIMIT $LIMIT", null).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val rowId = cursor.getLong(0)
                            add(runCatching {
                                val body = JSONObject(cursor.getString(1))
                                val view = JSONObject().put("rowId", rowId)
                                    .put("timestamp", body.optLong("timestamp"))
                                if (automatic) {
                                    view.put("name", body.optString("name"))
                                    val properties = body.optJSONObject("properties") ?: JSONObject()
                                    view.put("properties", JSONObject().apply {
                                        autoProperties.forEach { key ->
                                            if (properties.has(key)) put(key, properties.get(key))
                                        }
                                    })
                                } else {
                                    val api = body.optString("eventName")
                                    view.put("apiName", if (api == "unmapped") JSONObject.NULL else api)
                                    listOf("producer", "recordKind", "phase", "apiEventType", "mappingStatus").forEach { key ->
                                        if (body.has(key)) view.put(key, body.get(key))
                                    }
                                    body.optJSONArray("apiCandidates")?.let { view.put("apiCandidates", it) }
                                    body.optJSONObject("hubEvent")?.let { event ->
                                        view.put("hubEvent", JSONObject().apply {
                                            listOf("name", "type", "source", "eventId").forEach { key ->
                                                if (event.has(key)) put(key, event.get(key))
                                            }
                                        })
                                    }
                                }
                                view.toString(2)
                            }.getOrElse { "rowId=$rowId; unreadable event" })
                        }
                    }
                }
                "$name / $table\nTotal rows: $count; showing latest ${rows.size} (limit $LIMIT), newest first.\n" +
                    "Read-only. Session IDs, device context and raw event payloads omitted.\n\n" +
                    if (rows.isEmpty()) "(empty)" else rows.joinToString("\n\n")
            }
        }.getOrElse { "$name / $table\nUnable to read database (${it.javaClass.simpleName})." }
    }
}
