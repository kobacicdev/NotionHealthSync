package com.example.notionhealthsync

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.LocalDate

class HealthSyncWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val isScheduled = inputData.getBoolean(KEY_IS_SCHEDULED, false)
        val dateStr = if (isScheduled) {
            LocalDate.now().toString()
        } else {
            inputData.getString(KEY_DATE) ?: LocalDate.now().toString()
        }
        val date = LocalDate.parse(dateStr)

        return try {
            val healthManager = HealthConnectManager(context)

            if (!healthManager.hasPermissions()) {
                notify("同期失敗", "Health Connectの権限がありません")
                return Result.failure()
            }

            val data = healthManager.readDataForDate(date)
            if (!PreferencesManager.isNotionConfigured(context)) {
                notify("同期失敗", "Notion APIの設定が必要です")
                return Result.failure()
            }
            val notion = NotionApiClient(
                apiToken = PreferencesManager.getNotionApiToken(context),
                databaseId = PreferencesManager.getNotionDatabaseId(context)
            )

            if (notion.upsertRecord(data, Instant.now(), dateStr)) {
                val weight = data.weight?.let { "%.1fkg".format(it) } ?: "-"
                val fat = data.bodyFat?.let { "%.1f%%".format(it) } ?: "-"
                val steps = data.steps?.toString() ?: "-"
                notify("同期完了 ($dateStr)", "体重: $weight / 体脂肪: $fat / 歩数: $steps")
                if (isScheduled) rescheduleNextDay()
                Result.success()
            } else {
                notify("同期失敗", "Notionへの書き込みに失敗しました")
                Result.retry()
            }
        } catch (e: Exception) {
            notify("同期エラー", e.message ?: "不明なエラー")
            Result.failure()
        }
    }

    private fun rescheduleNextDay() {
        val hour = PreferencesManager.getSyncHour(context)
        val minute = PreferencesManager.getSyncMinute(context)
        SyncScheduler.scheduleNextDailySync(context, hour, minute)
    }

    private fun notify(title: String, message: String) {
        try {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {}
    }

    companion object {
        const val CHANNEL_ID = "health_sync_channel"
        const val NOTIFICATION_ID = 1001
        const val KEY_DATE = "date"
        const val KEY_IS_SCHEDULED = "is_scheduled"
    }
}
