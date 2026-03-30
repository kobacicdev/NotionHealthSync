package com.example.notionhealthsync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class UiState(
    val weight: String = "-",
    val bodyFat: String = "-",
    val steps: String = "-",
    val lastSyncTime: String = "未同期",
    val isSyncing: Boolean = false,
    val hasPermission: Boolean = false,
    val showDateRangePicker: Boolean = false,
    val showTimePicker: Boolean = false,
    val syncHour: Int = 7,
    val syncMinute: Int = 0,
    val showSettings: Boolean = false,
    val isNotionConfigured: Boolean = false,
    val syncLogs: List<SyncLogEntry> = emptyList()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val healthManager = HealthConnectManager(application)
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage: SharedFlow<String> = _snackbarMessage

    init {
        val hour = PreferencesManager.getSyncHour(application)
        val minute = PreferencesManager.getSyncMinute(application)
        val configured = PreferencesManager.isNotionConfigured(application)
        _uiState.value = _uiState.value.copy(
            syncHour = hour,
            syncMinute = minute,
            isNotionConfigured = configured
        )
    }

    fun checkPermissionsAndLoad() {
        viewModelScope.launch {
            val hasPermission = healthManager.hasPermissions()
            _uiState.value = _uiState.value.copy(hasPermission = hasPermission)
            if (hasPermission) loadHealthData()
        }
    }

    private suspend fun loadHealthData() {
        try {
            val data = healthManager.readTodayData()
            _uiState.value = _uiState.value.copy(
                weight = data.weight?.let { "%.1f kg".format(it) } ?: "-",
                bodyFat = data.bodyFat?.let { "%.1f %%".format(it) } ?: "-",
                steps = data.steps?.toString() ?: "-",
                syncLogs = PreferencesManager.getSyncLogs(getApplication())
            )
        } catch (_: Exception) {}
    }

    fun syncNow() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true)
            val workId = SyncScheduler.syncNow(getApplication())
            observeWork(workId, "今日のデータを同期完了")
        }
    }

    fun syncDateRange(startDate: LocalDate, endDate: LocalDate) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true, showDateRangePicker = false)
            val days = startDate.datesUntil(endDate.plusDays(1)).count()
            _snackbarMessage.emit("${days}日分の同期を開始しました")
            val ids = SyncScheduler.syncDateRange(getApplication(), startDate, endDate)
            observeWork(ids.last(), "${days}日分の同期完了")
        }
    }

    fun updateSyncTime(hour: Int, minute: Int) {
        PreferencesManager.saveSyncTime(getApplication(), hour, minute)
        SyncScheduler.scheduleNextDailySync(getApplication(), hour, minute)
        _uiState.value = _uiState.value.copy(
            syncHour = hour,
            syncMinute = minute,
            showTimePicker = false
        )
        viewModelScope.launch {
            _snackbarMessage.emit("自動同期を %02d:%02d に設定しました".format(hour, minute))
        }
    }

    private fun observeWork(workId: java.util.UUID, completionMessage: String) {
        viewModelScope.launch {
            WorkManager.getInstance(getApplication())
                .getWorkInfoByIdFlow(workId)
                .collect { info ->
                    if (info?.state?.isFinished == true) {
                        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))
                        _uiState.value = _uiState.value.copy(
                            isSyncing = false,
                            lastSyncTime = now,
                            syncLogs = PreferencesManager.getSyncLogs(getApplication())
                        )
                        when (info.state) {
                            WorkInfo.State.SUCCEEDED -> _snackbarMessage.emit(completionMessage)
                            WorkInfo.State.FAILED -> _snackbarMessage.emit("同期失敗。通知を確認してください")
                            else -> {}
                        }
                    }
                }
        }
    }

    fun saveNotionConfig(apiToken: String, databaseId: String) {
        PreferencesManager.saveNotionConfig(getApplication(), apiToken, databaseId)
        _uiState.value = _uiState.value.copy(
            isNotionConfigured = true,
            showSettings = false
        )
        viewModelScope.launch {
            _snackbarMessage.emit("Notion設定を保存しました")
        }
    }

    fun refreshLogs() {
        _uiState.value = _uiState.value.copy(
            syncLogs = PreferencesManager.getSyncLogs(getApplication())
        )
    }

    fun showDateRangePicker() { _uiState.value = _uiState.value.copy(showDateRangePicker = true) }
    fun hideDateRangePicker() { _uiState.value = _uiState.value.copy(showDateRangePicker = false) }
    fun showTimePicker() { _uiState.value = _uiState.value.copy(showTimePicker = true) }
    fun hideTimePicker() { _uiState.value = _uiState.value.copy(showTimePicker = false) }
    fun showSettings() { _uiState.value = _uiState.value.copy(showSettings = true) }
    fun hideSettings() { _uiState.value = _uiState.value.copy(showSettings = false) }
}
