package com.chikabell.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chikabell.app.ui.locations.LocationFormState
import com.chikabell.app.ui.locations.LocationsScreen
import com.chikabell.app.ui.locations.LocationsUiState
import com.chikabell.app.ui.locations.LocationsViewModel
import com.chikabell.app.ui.locations.LocationsViewModelFactory
import com.chikabell.app.ui.theme.ChikaBellTheme
import com.chikabell.app.domain.model.RestoreTrigger
import com.chikabell.app.geofence.GeofenceRestoreScheduler
import com.chikabell.app.share.ParsedSharedPlace
import com.chikabell.app.share.SharedPlaceParser
import com.chikabell.app.share.GoogleMapsShortLinkResolver
import com.chikabell.app.importexport.LocationTransferCodec
import com.chikabell.app.importexport.TransferFormat
import java.io.OutputStreamWriter
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val sharedPlaceState = mutableStateOf<ParsedSharedPlace?>(null)
    private var sharedPlaceResolutionJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        GeofenceRestoreScheduler.enqueue(this, RestoreTrigger.APP_START)
        setContent {
            ChikaBellApp(sharedPlaceState.value)
        }
        acceptSharedIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptSharedIntent(intent)
    }

    private fun acceptSharedIntent(intent: Intent) {
        sharedPlaceResolutionJob?.cancel()
        val place = intent.toSharedPlace()
        sharedPlaceState.value = place
        if (place != null && !place.hasCoordinates) {
            sharedPlaceResolutionJob = lifecycleScope.launch {
                sharedPlaceState.value = GoogleMapsShortLinkResolver.resolve(place)
            }
        }
    }

    private fun Intent.toSharedPlace(): ParsedSharedPlace? {
        if (action != Intent.ACTION_SEND || type != "text/plain") return null
        return SharedPlaceParser.parse(
            subject = getStringExtra(Intent.EXTRA_SUBJECT),
            text = getStringExtra(Intent.EXTRA_TEXT),
            uri = dataString,
        )
    }
}

@Composable
fun ChikaBellApp(sharedPlace: ParsedSharedPlace? = null) {
    val context = LocalContext.current
    val app = context.applicationContext as ChikaBellApplication
    val viewModel: LocationsViewModel = viewModel(
        factory = LocationsViewModelFactory(
            repository = app.container.locationRepository,
            historyRepository = app.container.historyRepository,
            presetRepository = app.container.notificationPresetRepository,
            restoreDiagnosticsRepository = app.container.restoreDiagnosticsRepository,
            permissionStateReader = app.container.permissionStateReader,
            backgroundRestrictionReader = app.container.backgroundRestrictionReader,
            reconcileGeofencesUseCase = app.container.reconcileGeofencesUseCase,
            processGeofenceEventUseCase = app.container.processGeofenceEventUseCase,
            checkCurrentLocationUseCase = app.container.checkCurrentLocationUseCase,
        ),
    )
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    LaunchedEffect(sharedPlace) {
        sharedPlace?.let(viewModel::applySharedPlace)
    }
    val foregroundPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.refreshPermissions()
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.refreshPermissions()
    }
    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            runCatching { writeTransferText(context, uri, viewModel.buildJsonExport()) }
                .onSuccess { viewModel.reportTransferMessage("JSONバックアップを書き出しました") }
                .onFailure { viewModel.reportTransferMessage("JSON書き出しに失敗しました") }
        }
    }
    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) {
            runCatching { writeTransferText(context, uri, viewModel.buildCsvExport()) }
                .onSuccess { viewModel.reportTransferMessage("CSVを書き出しました") }
                .onFailure { viewModel.reportTransferMessage("CSV書き出しに失敗しました") }
        }
    }
    val importJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching { readTransferText(context, uri) }
                .onSuccess { viewModel.previewImport(it, TransferFormat.JSON) }
                .onFailure { viewModel.reportTransferMessage("JSONを読み込めません: ${it.message ?: "不明なエラー"}") }
        }
    }
    val importCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching { readTransferText(context, uri) }
                .onSuccess { viewModel.previewImport(it, TransferFormat.CSV) }
                .onFailure { viewModel.reportTransferMessage("CSVを読み込めません: ${it.message ?: "不明なエラー"}") }
        }
    }

    ChikaBellTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            LocationsScreen(
                uiState = uiState,
                onFormChange = viewModel::updateForm,
                onApplyPreset = viewModel::applyPreset,
                onSavePreset = viewModel::saveSelectedPreset,
                onResetPreset = viewModel::resetSelectedPreset,
                onSave = viewModel::save,
                onEdit = viewModel::startEdit,
                onDelete = viewModel::delete,
                onDeleteSelected = viewModel::deleteSelected,
                onSetLocationsEnabled = viewModel::setLocationsEnabled,
                onCancelEdit = viewModel::cancelEdit,
                onRefreshPermissions = viewModel::refreshPermissions,
                onRequestForegroundLocation = {
                    foregroundPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                },
                onRequestNotification = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.refreshPermissions()
                    }
                },
                onOpenAppSettings = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                },
                onRegisterGeofences = viewModel::registerActiveGeofences,
                onRestoreGeofences = { GeofenceRestoreScheduler.enqueue(context, RestoreTrigger.MANUAL) },
                onSendTestEnterEvent = viewModel::sendTestEnterEvent,
                onCheckCurrentLocation = viewModel::checkCurrentLocation,
                onExportJson = { exportJsonLauncher.launch("chikabell-backup.json") },
                onExportCsv = { exportCsvLauncher.launch("chikabell-locations.csv") },
                onImportJson = { importJsonLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) },
                onImportCsv = { importCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain")) },
                onConfirmImport = viewModel::confirmImport,
                onCancelImport = viewModel::cancelImport,
                onHistoryFilterChange = viewModel::updateHistoryFilter,
                onDeleteAllHistory = viewModel::deleteAllHistory,
            )
        }
    }
}

private fun writeTransferText(context: Context, uri: Uri, content: String) {
    val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("出力先を開けません")
    OutputStreamWriter(stream, Charsets.UTF_8).use { it.write(content) }
}

private fun readTransferText(context: Context, uri: Uri): String {
    val stream = context.contentResolver.openInputStream(uri) ?: error("ファイルを開けません")
    return stream.bufferedReader(Charsets.UTF_8).use { reader ->
        val result = StringBuilder()
        val buffer = CharArray(8_192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            result.append(buffer, 0, count)
            require(result.length <= LocationTransferCodec.MAX_FILE_CHARS) { "ファイルが2MB相当の上限を超えています" }
        }
        result.toString()
    }
}

@Preview(showBackground = true)
@Composable
private fun ChikaBellPreview() {
    ChikaBellTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            LocationsScreen(
                uiState = LocationsUiState(
                    form = LocationFormState(
                        name = "自宅",
                        latitude = "35.0",
                        longitude = "139.0",
                        radiusMeters = "300",
                    ),
                ),
                onFormChange = {},
                onApplyPreset = {},
                onSavePreset = {},
                onResetPreset = {},
                onSave = {},
                onEdit = {},
                onDelete = {},
                onDeleteSelected = {},
                onSetLocationsEnabled = { _, _ -> },
                onCancelEdit = {},
                onRefreshPermissions = {},
                onRequestForegroundLocation = {},
                onRequestNotification = {},
                onOpenAppSettings = {},
                onRegisterGeofences = {},
                onRestoreGeofences = {},
                onSendTestEnterEvent = {},
                onCheckCurrentLocation = {},
                onExportJson = {},
                onExportCsv = {},
                onImportJson = {},
                onImportCsv = {},
                onConfirmImport = {},
                onCancelImport = {},
                onHistoryFilterChange = {},
                onDeleteAllHistory = {},
            )
        }
    }
}
