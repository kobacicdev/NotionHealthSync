package com.example.notionhealthsync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class HealthData(
    val weight: Double?,
    val bodyFat: Double?,
    val steps: Long?,
    val measurementTime: Instant?
)

class HealthConnectManager(private val context: Context) {
    private val client = HealthConnectClient.getOrCreate(context)

    suspend fun readTodayData(): HealthData = readDataForDate(LocalDate.now())

    suspend fun readDataForDate(date: LocalDate): HealthData {
        val startOfDay = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val endOfDay = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
        val timeRange = TimeRangeFilter.between(startOfDay, endOfDay)

        val weightRecords = client.readRecords(
            ReadRecordsRequest(WeightRecord::class, timeRange)
        ).records

        val bodyFatRecords = client.readRecords(
            ReadRecordsRequest(BodyFatRecord::class, timeRange)
        ).records

        val stepsRecords = client.readRecords(
            ReadRecordsRequest(StepsRecord::class, timeRange)
        ).records

        val minWeightRecord = weightRecords.minByOrNull { it.weight.inKilograms }
        val closestBodyFat = minWeightRecord?.let { target ->
            bodyFatRecords.minByOrNull { Math.abs(it.time.epochSecond - target.time.epochSecond) }
        } ?: bodyFatRecords.minByOrNull { it.time }
        val totalSteps = stepsRecords.sumOf { it.count }

        return HealthData(
            weight = minWeightRecord?.weight?.inKilograms,
            bodyFat = closestBodyFat?.percentage?.value,
            steps = if (stepsRecords.isNotEmpty()) totalSteps else null,
            measurementTime = minWeightRecord?.time ?: closestBodyFat?.time
        )
    }

    suspend fun hasPermissions(): Boolean {
        val granted = client.permissionController.getGrantedPermissions()
        return PERMISSIONS.all { it in granted }
    }

    companion object {
        val PERMISSIONS = setOf(
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
            "android.permission.health.READ_HEALTH_DATA_HISTORY",
        )
    }
}
