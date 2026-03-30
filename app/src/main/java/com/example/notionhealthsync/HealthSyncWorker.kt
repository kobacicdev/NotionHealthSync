package com.example.notionhealthsync

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class HealthSyncWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val isScheduled = inputData.getBoolean(KEY_IS_SCHEDULED, false)
        val executedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))

        val healthManager = HealthConnectManager(context)
        if (!healthManager.hasPermissions()) {
            notify("同期失敗", "Health Connectの権限がありません")
            return Result.failure()
        }
        if (!PreferencesManager.isNotionConfigured(context)) {
            notify("同期失敗", "Notion APIの設定が必要です")
            return Result.failure()
        }

        val notion = NotionApiClient(
            apiToken = PreferencesManager.getNotionApiToken(context),
            databaseId = PreferencesManager.getNotionDatabaseId(context)
        )

        return if (isScheduled) {
            syncScheduled(healthManager, notion, executedAt)
        } else {
            val dateStr = inputData.getString(KEY_DATE) ?: LocalDate.now().toString()
            val result = syncSingleDate(healthManager, notion, LocalDate.parse(dateStr), executedAt, sendNotification = true)
            if (result == SyncStatus.FAILED) Result.retry() else Result.success()
        }
    }

    private suspend fun syncScheduled(
        healthManager: HealthConnectManager,
        notion: NotionApiClient,
        executedAt: String
    ): Result {
        val yesterday = LocalDate.now().minusDays(1)
        val lastSuccess = PreferencesManager.getLastSuccessDate(context)
        val startDate = lastSuccess?.plusDays(1) ?: yesterday

        if (startDate.isAfter(yesterday)) {
            rescheduleNextDay()
            return Result.success()
        }

        val dates = generateSequence(startDate) { it.plusDays(1) }
            .takeWhile { !it.isAfter(yesterday) }
            .toList()

        val results = dates.map { date ->
            date to syncSingleDate(healthManager, notion, date, executedAt, sendNotification = false)
        }

        val failCount = results.count { (_, s) -> s == SyncStatus.FAILED }
        val lastDate = dates.last().toString()

        val summaryBody = if (dates.size == 1) {
            val (_, status) = results.first()
            val log = PreferencesManager.getSyncLogs(context).firstOrNull()
            when (status) {
                SyncStatus.SUCCESS -> "体重: %.1fkg / 体脂肪: %.1f%% / 歩数: %d".format(log?.weight, log?.bodyFat, log?.steps)
                SyncStatus.SKIPPED -> log?.message ?: "データ不足のためスキップ"
                SyncStatus.FAILED -> log?.message ?: "同期失敗"
            }
        } else {
            val successCount = results.count { (_, s) -> s == SyncStatus.SUCCESS }
            val skipCount = results.count { (_, s) -> s == SyncStatus.SKIPPED }
            "${dates.size}日分: 成功${successCount} / スキップ${skipCount} / 失敗${failCount}"
        }

        notify(
            if (failCount == 0) "同期完了 ($lastDate)" else "同期一部失敗 ($lastDate)",
            summaryBody
        )

        rescheduleNextDay()
        return if (failCount > 0) Result.failure() else Result.success()
    }

    private suspend fun syncSingleDate(
        healthManager: HealthConnectManager,
        notion: NotionApiClient,
        date: LocalDate,
        executedAt: String,
        sendNotification: Boolean
    ): SyncStatus {
        val dateStr = date.toString()

        val data = try {
            healthManager.readDataForDate(date)
        } catch (e: Exception) {
            val msg = "Health Connect取得エラー: ${e.message}"
            PreferencesManager.addSyncLog(context, SyncLogEntry(
                date = dateStr, executedAt = executedAt, status = SyncStatus.FAILED,
                weight = null, bodyFat = null, steps = null, message = msg
            ))
            if (sendNotification) notify("同期失敗 ($dateStr)", msg)
            return SyncStatus.FAILED
        }

        if (data.weight == null || data.bodyFat == null || data.steps == null) {
            val missing = listOfNotNull(
                if (data.weight == null) "体重" else null,
                if (data.bodyFat == null) "体脂肪率" else null,
                if (data.steps == null) "歩数" else null
            ).joinToString(", ")
            val msg = "データ不足: $missing"
            PreferencesManager.addSyncLog(context, SyncLogEntry(
                date = dateStr, executedAt = executedAt, status = SyncStatus.SKIPPED,
                weight = data.weight, bodyFat = data.bodyFat, steps = data.steps, message = msg
            ))
            if (sendNotification) notify("同期スキップ ($dateStr)", msg)
            PreferencesManager.saveLastSuccessDate(context, date)
            return SyncStatus.SKIPPED
        }

        return try {
            if (notion.upsertRecord(data, Instant.now(), dateStr)) {
                PreferencesManager.addSyncLog(context, SyncLogEntry(
                    date = dateStr, executedAt = executedAt, status = SyncStatus.SUCCESS,
                    weight = data.weight, bodyFat = data.bodyFat, steps = data.steps
                ))
                if (sendNotification) {
                    notify(
                        "同期完了 ($dateStr)",
                        "体重: %.1fkg / 体脂肪: %.1f%% / 歩数: %d".format(data.weight, data.bodyFat, data.steps)
                    )
                }
                PreferencesManager.saveLastSuccessDate(context, date)
                SyncStatus.SUCCESS
            } else {
                val msg = "Notion書き込み失敗"
                PreferencesManager.addSyncLog(context, SyncLogEntry(
                    date = dateStr, executedAt = executedAt, status = SyncStatus.FAILED,
                    weight = data.weight, bodyFat = data.bodyFat, steps = data.steps, message = msg
                ))
                if (sendNotification) notify("同期失敗 ($dateStr)", msg)
                SyncStatus.FAILED
            }
        } catch (e: Exception) {
            val msg = "Notionエラー: ${e.message}"
            PreferencesManager.addSyncLog(context, SyncLogEntry(
                date = dateStr, executedAt = executedAt, status = SyncStatus.FAILED,
                weight = data.weight, bodyFat = data.bodyFat, steps = data.steps, message = msg
            ))
            if (sendNotification) notify("同期エラー ($dateStr)", msg)
            SyncStatus.FAILED
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
