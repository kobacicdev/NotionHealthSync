package com.example.notionhealthsync

import android.content.Context

object PreferencesManager {
    private const val PREF_NAME = "health_sync_prefs"
    private const val KEY_SYNC_HOUR = "sync_hour"
    private const val KEY_SYNC_MINUTE = "sync_minute"
    private const val KEY_NOTION_API_TOKEN = "notion_api_token"
    private const val KEY_NOTION_DATABASE_ID = "notion_database_id"

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
}
