package com.example.notionhealthsync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

object PreferencesManager {
    private const val PREF_NAME = "health_sync_prefs"
    private const val KEY_SYNC_HOUR = "sync_hour"
    private const val KEY_SYNC_MINUTE = "sync_minute"
    private const val KEY_NOTION_API_TOKEN = "notion_api_token"
    private const val KEY_NOTION_DATABASE_ID = "notion_database_id"
    private const val KEY_LAST_SUCCESS_DATE = "last_success_date"
    private const val KEY_SYNC_LOGS = "sync_logs"
    private const val MAX_LOG_ENTRIES = 20

    fun getSyncHour(context: Context): Int =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt(KEY_SYNC_HOUR, 7)

    fun getSyncMinute(context: Context): Int =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt(KEY_SYNC_MINUTE, 0)

    fun saveSyncTime(context: Context, hour: Int, minute: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SYNC_HOUR, hour)
            .putInt(KEY_SYNC_MINUTE, minute)
            .apply()
    }

    fun getNotionApiToken(context: Context): String =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_NOTION_API_TOKEN, "") ?: ""

    fun getNotionDatabaseId(context: Context): String =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_NOTION_DATABASE_ID, "") ?: ""

    fun saveNotionConfig(context: Context, apiToken: String, databaseId: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_NOTION_API_TOKEN, apiToken)
            .putString(KEY_NOTION_DATABASE_ID, databaseId)
            .apply()
    }

    fun isNotionConfigured(context: Context): Boolean =
        getNotionApiToken(context).isNotBlank() && getNotionDatabaseId(context).isNotBlank()

    fun getLastSuccessDate(context: Context): LocalDate? =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_SUCCESS_DATE, null)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun saveLastSuccessDate(context: Context, date: LocalDate) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_SUCCESS_DATE, date.toString())
            .apply()
    }

    fun getSyncLogs(context: Context): List<SyncLogEntry> {
        val json = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SYNC_LOGS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                SyncLogEntry(
                    date = obj.getString("date"),
                    executedAt = obj.getString("executedAt"),
                    status = SyncStatus.valueOf(obj.getString("status")),
                    weight = if (obj.isNull("weight")) null else obj.getDouble("weight"),
                    bodyFat = if (obj.isNull("bodyFat")) null else obj.getDouble("bodyFat"),
                    steps = if (obj.isNull("steps")) null else obj.getLong("steps"),
                    message = obj.optString("message", "")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun addSyncLog(context: Context, entry: SyncLogEntry) {
        val logs = getSyncLogs(context).toMutableList().also { it.add(0, entry) }.take(MAX_LOG_ENTRIES)
        val array = JSONArray().apply {
            logs.forEach { e ->
                put(JSONObject().apply {
                    put("date", e.date)
                    put("executedAt", e.executedAt)
                    put("status", e.status.name)
                    put("weight", e.weight ?: JSONObject.NULL)
                    put("bodyFat", e.bodyFat ?: JSONObject.NULL)
                    put("steps", e.steps ?: JSONObject.NULL)
                    put("message", e.message)
                })
            }
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_SYNC_LOGS, array.toString())
            .apply()
    }
}
