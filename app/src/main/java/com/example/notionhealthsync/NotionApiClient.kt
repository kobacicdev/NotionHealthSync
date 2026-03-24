package com.example.notionhealthsync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class NotionApiClient(
    private val apiToken: String,
    private val databaseId: String
) {
    private val httpClient = OkHttpClient()
    private val jsonMediaType = "application/json".toMediaType()

    fun upsertRecord(healthData: HealthData, syncTime: Instant, date: String): Boolean {
        val existingId = findRecordByDate(date)
        return if (existingId != null) {
            updateRecord(existingId, healthData, syncTime, date)
        } else {
            createRecord(healthData, syncTime, date)
        }
    }

    private fun findRecordByDate(date: String): String? {
        val body = JSONObject().put(
            "filter", JSONObject()
                .put("property", "日付")
                .put("title", JSONObject().put("equals", date))
        )
        val request = Request.Builder()
            .url("https://api.notion.com/v1/databases/$databaseId/query")
            .addHeader("Authorization", "Bearer $apiToken")
            .addHeader("Content-Type", "application/json")
            .addHeader("Notion-Version", "2022-06-28")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        val responseStr = httpClient.newCall(request).execute().use { it.body?.string() }
            ?: return null
        val results = JSONObject(responseStr).getJSONArray("results")
        return if (results.length() > 0) results.getJSONObject(0).getString("id") else null
    }

    private fun createRecord(healthData: HealthData, syncTime: Instant, date: String): Boolean {
        val body = JSONObject().apply {
            put("parent", JSONObject().put("database_id", databaseId))
            put("properties", buildProperties(healthData, syncTime, date))
        }
        val request = Request.Builder()
            .url("https://api.notion.com/v1/pages")
            .addHeader("Authorization", "Bearer $apiToken")
            .addHeader("Content-Type", "application/json")
            .addHeader("Notion-Version", "2022-06-28")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        return httpClient.newCall(request).execute().use { it.isSuccessful }
    }

    private fun updateRecord(pageId: String, healthData: HealthData, syncTime: Instant, date: String): Boolean {
        val body = JSONObject().put("properties", buildProperties(healthData, syncTime, date))
        val request = Request.Builder()
            .url("https://api.notion.com/v1/pages/$pageId")
            .addHeader("Authorization", "Bearer $apiToken")
            .addHeader("Content-Type", "application/json")
            .addHeader("Notion-Version", "2022-06-28")
            .patch(body.toString().toRequestBody(jsonMediaType))
            .build()
        return httpClient.newCall(request).execute().use { it.isSuccessful }
    }

    private fun buildProperties(healthData: HealthData, syncTime: Instant, date: String?): JSONObject {
        val today = date ?: LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val measurementDate = healthData.measurementTime
            ?.let { LocalDate.ofInstant(it, ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_DATE) }
            ?: today
        val syncDatetime = DateTimeFormatter.ISO_INSTANT.format(syncTime)

        return JSONObject().apply {
            put("日付", JSONObject().put("title", JSONArray().put(
                JSONObject().put("text", JSONObject().put("content", today))
            )))
            put("計測日", JSONObject().put("date", JSONObject().put("start", measurementDate)))
            put("記録日時", JSONObject().put("date", JSONObject().put("start", syncDatetime)))
            put("同期元", JSONObject().put("select", JSONObject().put("name", "EufyLife")))
            healthData.weight?.let { put("体重", JSONObject().put("number", it)) }
            healthData.bodyFat?.let { put("体脂肪率", JSONObject().put("number", it)) }
            healthData.steps?.let { put("歩数", JSONObject().put("number", it)) }
        }
    }
}
