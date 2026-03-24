package com.example.notionhealthsync

import android.content.Context
import androidx.work.*
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private const val WORK_NAME = "daily_health_sync"

    fun scheduleDailySync(context: Context) {
        val hour = PreferencesManager.getSyncHour(context)
        val minute = PreferencesManager.getSyncMinute(context)
        scheduleNextDailySync(context, hour, minute)
    }

    fun scheduleNextDailySync(context: Context, hour: Int, minute: Int) {
        val now = LocalDateTime.now()
        var next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)

        val delay = Duration.between(now, next).toMillis()

        val request = OneTimeWorkRequestBuilder<HealthSyncWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(
                HealthSyncWorker.KEY_DATE to LocalDate.now().toString(),
                HealthSyncWorker.KEY_IS_SCHEDULED to true
            ))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun syncNow(context: Context, date: LocalDate = LocalDate.now()): UUID {
        val request = OneTimeWorkRequestBuilder<HealthSyncWorker>()
            .setInputData(workDataOf(
                HealthSyncWorker.KEY_DATE to date.toString(),
                HealthSyncWorker.KEY_IS_SCHEDULED to false
            ))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueue(request)
        return request.id
    }

    fun syncDateRange(context: Context, startDate: LocalDate, endDate: LocalDate): List<UUID> {
        val ids = mutableListOf<UUID>()
        var current = startDate
        while (!current.isAfter(endDate)) {
            ids.add(syncNow(context, current))
            current = current.plusDays(1)
        }
        return ids
    }
}
