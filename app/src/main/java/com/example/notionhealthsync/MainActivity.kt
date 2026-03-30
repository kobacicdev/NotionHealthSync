package com.example.notionhealthsync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.ViewModelProvider
import com.example.notionhealthsync.ui.theme.NotionHealthSyncTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : ComponentActivity() {

    private lateinit var mainViewModel: MainViewModel

    private val requestPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            mainViewModel.checkPermissionsAndLoad()
        }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        mainViewModel = ViewModelProvider(this)[MainViewModel::class.java]

        enableEdgeToEdge()
        setContent {
            NotionHealthSyncTheme {
                HealthSyncScreen(
                    viewModel = mainViewModel,
                    onRequestPermissions = {
                        requestPermissions.launch(HealthConnectManager.PERMISSIONS)
                    }
                )
            }
        }

        requestBatteryOptimizationExemption()
        SyncScheduler.scheduleDailySync(this)
        mainViewModel.checkPermissionsAndLoad()
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            HealthSyncWorker.CHANNEL_ID,
            "Health Sync",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Health ConnectとNotionの同期通知"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthSyncScreen(
    viewModel: MainViewModel,
    onRequestPermissions: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    if (uiState.showSettings) {
        NotionSettingsDialog(
            initialToken = PreferencesManager.getNotionApiToken(androidx.compose.ui.platform.LocalContext.current),
            initialDatabaseId = PreferencesManager.getNotionDatabaseId(androidx.compose.ui.platform.LocalContext.current),
            onConfirm = { token, dbId -> viewModel.saveNotionConfig(token, dbId) },
            onDismiss = { viewModel.hideSettings() }
        )
    }

    if (uiState.showDateRangePicker) {
        DateRangePickerDialog(
            onConfirm = { start, end -> viewModel.syncDateRange(start, end) },
            onDismiss = { viewModel.hideDateRangePicker() }
        )
    }

    if (uiState.showTimePicker) {
        SyncTimePickerDialog(
            initialHour = uiState.syncHour,
            initialMinute = uiState.syncMinute,
            onConfirm = { hour, minute -> viewModel.updateSyncTime(hour, minute) },
            onDismiss = { viewModel.hideTimePicker() }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("NotionHealthSync") },
                actions = {
                    IconButton(onClick = { viewModel.showSettings() }) {
                        Icon(Icons.Filled.Settings, contentDescription = "設定")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "NotionHealthSync",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DataRow("体重", uiState.weight)
                    DataRow("体脂肪率", uiState.bodyFat)
                    DataRow("歩数", uiState.steps)
                    HorizontalDivider()
                    DataRow("最終同期", uiState.lastSyncTime)
                }
            }

            if (!uiState.isNotionConfigured) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = "Notion APIが未設定です。右上の設定から入力してください。",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            if (!uiState.hasPermission) {
                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Health Connect 権限を許可")
                }
            } else {
                Button(
                    onClick = { viewModel.syncNow() },
                    enabled = !uiState.isSyncing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (uiState.isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("今すぐ同期")
                    }
                }

                OutlinedButton(
                    onClick = { viewModel.showDateRangePicker() },
                    enabled = !uiState.isSyncing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("期間指定で同期")
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("同期ログ", style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { viewModel.refreshLogs() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "ログを更新")
                }
            }

            if (uiState.syncLogs.isEmpty()) {
                Text(
                    text = "同期履歴なし",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LazyColumn(modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                ) {
                    items(uiState.syncLogs) { log -> SyncLogRow(log) }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("自動同期", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "毎日 %02d:%02d".format(uiState.syncHour, uiState.syncMinute),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { viewModel.showTimePicker() }) {
                    Text("変更")
                }
            }
        }
    }
}

@Composable
fun NotionSettingsDialog(
    initialToken: String,
    initialDatabaseId: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var token by remember { mutableStateOf(initialToken) }
    var dbUrl by remember { mutableStateOf("") }
    var databaseId by remember { mutableStateOf(initialDatabaseId) }

    fun extractDatabaseId(url: String): String? {
        val regex = Regex("[0-9a-f]{32}")
        return regex.find(url.replace("-", ""))?.value
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notion API 設定") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("API Token") },
                    placeholder = { Text("ntn_xxxxxxxxxx...") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dbUrl,
                    onValueChange = { input ->
                        dbUrl = input
                        extractDatabaseId(input)?.let { databaseId = it }
                    },
                    label = { Text("データベースURL") },
                    placeholder = { Text("https://www.notion.so/...") },
                    supportingText = { Text("NotionでDBを開いたURLを貼り付けてください") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (databaseId.isNotBlank()) {
                    Text(
                        text = "Database ID: $databaseId",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(token.trim(), databaseId.trim()) },
                enabled = token.isNotBlank() && databaseId.isNotBlank()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberTimePickerState(initialHour = initialHour, initialMinute = initialMinute)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自動同期時刻") },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) {
                Text("設定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangePickerDialog(
    onConfirm: (LocalDate, LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberDateRangePickerState()

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val start = state.selectedStartDateMillis
                    val end = state.selectedEndDateMillis
                    if (start != null && end != null) {
                        onConfirm(
                            Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate(),
                            Instant.ofEpochMilli(end).atZone(ZoneId.systemDefault()).toLocalDate()
                        )
                    }
                },
                enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null
            ) {
                Text("同期する")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    ) {
        DateRangePicker(state = state, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun SyncLogRow(entry: SyncLogEntry) {
    val (statusIcon, statusColor) = when (entry.status) {
        SyncStatus.SUCCESS -> "✓" to MaterialTheme.colorScheme.primary
        SyncStatus.SKIPPED -> "–" to MaterialTheme.colorScheme.onSurfaceVariant
        SyncStatus.FAILED -> "✗" to MaterialTheme.colorScheme.error
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "$statusIcon ${entry.date}",
                style = MaterialTheme.typography.bodySmall,
                color = statusColor
            )
            Text(
                text = entry.executedAt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        when (entry.status) {
            SyncStatus.SUCCESS -> Text(
                text = "体重: %.1fkg / 体脂肪: %.1f%% / 歩数: %d".format(entry.weight, entry.bodyFat, entry.steps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SyncStatus.SKIPPED, SyncStatus.FAILED -> if (entry.message.isNotBlank()) {
                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun DataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
