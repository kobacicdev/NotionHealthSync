package com.example.notionhealthsync

enum class SyncStatus { SUCCESS, SKIPPED, FAILED }

data class SyncLogEntry(
    val date: String,
    val executedAt: String,
    val status: SyncStatus,
    val weight: Double?,
    val bodyFat: Double?,
    val steps: Long?,
    val message: String = ""
)
