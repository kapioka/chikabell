package com.chikabell.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.BadParcelableException
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chikabell.app.ui.locations.LocationFormState
import com.chikabell.app.ui.locations.LocationsScreen
import com.chikabell.app.ui.locations.LocationsUiState
import com.chikabell.app.ui.locations.LocationsViewModel
import com.chikabell.app.ui.locations.LocationsViewModelFactory
import com.chikabell.app.ui.locations.PermissionSettingsAction
import com.chikabell.app.ui.theme.ChikaBellTheme
import com.chikabell.app.domain.model.RestoreTrigger
import com.chikabell.app.geofence.GeofenceRestoreScheduler
import com.chikabell.app.share.GoogleMapsShortLinkResolver
import com.chikabell.app.share.SharedIntentNormalizer
import com.chikabell.app.share.SharedPlaceConfidence
import com.chikabell.app.share.SharedPlaceEvent
import com.chikabell.app.share.SystemAddressCandidateProvider
import com.chikabell.app.importexport.LocationTransferCodec
import com.chikabell.app.importexport.TransferFormat
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.channels.Channel

internal data class SharedPlaceDelivery(
    val event: SharedPlaceEvent,
    val applied: CompletableDeferred<Unit> = CompletableDeferred(),
)

private data class SharedIntentEnvelope(
    val processingIntent: Intent,
    val sourceIntent: Intent,
    val fingerprint: String,
)

class MainActivity : ComponentActivity() {
    private val sharedIntentEnvelopes = Channel<SharedIntentEnvelope>(capacity = 64)
    private val sharedPlaceDeliveries = Channel<SharedPlaceDelivery>(capacity = 64)
    private val queuedFingerprints = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var lastHandledLaunchFingerprint: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastHandledLaunchFingerprint = savedInstanceState?.getString(STATE_HANDLED_LAUNCH_FINGERPRINT)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        GeofenceRestoreScheduler.enqueue(this, RestoreTrigger.APP_START)
        setContent {
            ChikaBellApp(sharedPlaceDeliveries.receiveAsFlow())
        }
        startSharedIntentActor()
        acceptSharedIntent(intent, acceptRepeatedDelivery = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptSharedIntent(intent, acceptRepeatedDelivery = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        lastHandledLaunchFingerprint?.let {
            outState.putString(STATE_HANDLED_LAUNCH_FINGERPRINT, it)
        }
        super.onSaveInstanceState(outState)
    }

    private fun acceptSharedIntent(intent: Intent, acceptRepeatedDelivery: Boolean) {
        val fingerprint = try {
            sharedIntentFingerprint(intent)
        } catch (_: BadParcelableException) {
            return
        } catch (_: ClassCastException) {
            return
        }
        if (!acceptRepeatedDelivery &&
            (intent.getStringExtra(EXTRA_HANDLED_SHARE_EVENT_ID) != null ||
                fingerprint == lastHandledLaunchFingerprint ||
                fingerprint in queuedFingerprints)
        ) {
            return
        }
        queuedFingerprints += fingerprint
        val accepted = sharedIntentEnvelopes.trySend(
            SharedIntentEnvelope(
                processingIntent = Intent(intent),
                sourceIntent = intent,
                fingerprint = fingerprint,
            ),
        ).isSuccess
        if (!accepted) {
            queuedFingerprints -= fingerprint
        }
    }

    private fun startSharedIntentActor() {
        lifecycleScope.launch(Dispatchers.IO) {
            for (envelope in sharedIntentEnvelopes) {
                try {
                    val event = SharedIntentNormalizer.normalize(
                        this@MainActivity,
                        envelope.processingIntent,
                    )
                    if (event != null) {
                        deliverSharedEvent(initialSharedEvent(event))
                        envelope.sourceIntent.putExtra(EXTRA_HANDLED_SHARE_EVENT_ID, event.eventId)
                    } else {
                        envelope.sourceIntent.putExtra(EXTRA_HANDLED_SHARE_EVENT_ID, "ignored")
                    }
                    lastHandledLaunchFingerprint = envelope.fingerprint
                    if (event?.place?.shortUrl != null) {
                        launch {
                            val resolved = GoogleMapsShortLinkResolver.resolve(event.place)
                            deliverSharedEvent(event.copy(place = resolved))
                        }
                    }
                } catch (_: BadParcelableException) {
                    envelope.sourceIntent.putExtra(EXTRA_HANDLED_SHARE_EVENT_ID, "ignored")
                } catch (_: ClassCastException) {
                    envelope.sourceIntent.putExtra(EXTRA_HANDLED_SHARE_EVENT_ID, "ignored")
                } finally {
                    queuedFingerprints -= envelope.fingerprint
                }
            }
        }
    }

    private fun initialSharedEvent(event: SharedPlaceEvent): SharedPlaceEvent {
        val shouldResolveShortUrl = event.place.shortUrl != null
        return if (shouldResolveShortUrl) {
            event.copy(
                place = event.place.copy(
                    latitude = null,
                    longitude = null,
                    confidence = SharedPlaceConfidence.RESOLVING,
                    warnings = emptyList(),
                    selectedCandidateIndex = null,
                ),
            )
        } else {
            event
        }
    }

    private suspend fun deliverSharedEvent(event: SharedPlaceEvent) {
        val delivery = SharedPlaceDelivery(event)
        sharedPlaceDeliveries.send(delivery)
        delivery.applied.await()
    }

    companion object {
        private const val EXTRA_HANDLED_SHARE_EVENT_ID = "com.chikabell.app.extra.HANDLED_SHARE_EVENT_ID"
        private const val STATE_HANDLED_LAUNCH_FINGERPRINT = "handled_share_launch_fingerprint"
    }
}

private fun sharedIntentFingerprint(intent: Intent): String {
    val source = buildString {
        append(intent.action).append('\u0000')
        append(intent.type).append('\u0000')
        append(intent.dataString).append('\u0000')
        append(intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)).append('\u0000')
        append(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)).append('\u0000')
        append(intent.getStringExtra(Intent.EXTRA_HTML_TEXT)).append('\u0000')
        intent.clipData?.let { clip ->
            repeat(clip.itemCount.coerceAtMost(8)) { index ->
                val item = clip.getItemAt(index)
                append(item.uri).append('\u0000')
                append(item.text).append('\u0000')
                append(item.htmlText).append('\u0000')
            }
        }
    }.take(65_536)
    return MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it) }
}

@Composable
internal fun ChikaBellApp(sharedPlaceEvents: Flow<SharedPlaceDelivery> = emptyFlow()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
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
            sendTestNotificationUseCase = app.container.sendTestNotificationUseCase,
            checkCurrentLocationUseCase = app.container.checkCurrentLocationUseCase,
            findNearbySavedLocationsUseCase = app.container.findNearbySavedLocationsUseCase,
            activityStateSource = app.container.activityStateStore,
            addressCandidateProvider = SystemAddressCandidateProvider(context),
        ),
    )
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(sharedPlaceEvents, viewModel) {
        sharedPlaceEvents.collect { delivery ->
            try {
                viewModel.applySharedPlace(delivery.event)
            } finally {
                delivery.applied.complete(Unit)
            }
        }
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
    val activityRecognitionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        app.applicationScope.launch(Dispatchers.IO) {
            app.container.activityRecognitionRegistrar.registerIfAllowed()
        }
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
                onClearSnooze = viewModel::clearSnooze,
                onCancelEdit = viewModel::requestCancelEdit,
                onRefreshPermissions = viewModel::refreshPermissions,
                onPermissionSettingsAction = { action ->
                    when (action) {
                        PermissionSettingsAction.REQUEST_FOREGROUND_LOCATION -> {
                            foregroundPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        }
                        PermissionSettingsAction.REQUEST_NOTIFICATION -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.refreshPermissions()
                            }
                        }
                        PermissionSettingsAction.REQUEST_ACTIVITY_RECOGNITION -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                activityRecognitionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                            } else {
                                viewModel.refreshPermissions()
                            }
                        }
                        PermissionSettingsAction.OPEN_APP_SETTINGS -> {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ),
                            )
                        }
                        PermissionSettingsAction.OPEN_LOCATION_SETTINGS -> {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        }
                        PermissionSettingsAction.OPEN_GOOGLE_PLAY_SERVICES_SETTINGS -> {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", "com.google.android.gms", null),
                                ),
                            )
                        }
                    }
                },
                onRegisterGeofences = viewModel::registerActiveGeofences,
                onRestoreGeofences = { GeofenceRestoreScheduler.enqueue(context, RestoreTrigger.MANUAL) },
                onSendTestEnterEvent = viewModel::sendTestEnterEvent,
                onFindNearbySavedLocations = viewModel::findNearbySavedLocations,
                onCloseNearbySavedLocations = viewModel::closeNearbySavedLocations,
                onExportJson = { exportJsonLauncher.launch("chikabell-backup.json") },
                onExportCsv = { exportCsvLauncher.launch("chikabell-locations.csv") },
                onImportJson = { importJsonLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) },
                onImportCsv = { importCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain")) },
                onConfirmImport = viewModel::confirmImport,
                onCancelImport = viewModel::cancelImport,
                onHistoryFilterChange = viewModel::updateHistoryFilter,
                onDeleteAllHistory = viewModel::deleteAllHistory,
                onSelectSharedPlaceCandidate = viewModel::selectSharedPlaceCandidate,
                onConfirmSharedPlaceCandidateAndSave = viewModel::confirmSharedPlaceCandidateAndSave,
                onOpenSharedPlaceMap = {
                    if (openSharedPlaceInMap(context, uiState.form.latitude, uiState.form.longitude)) {
                        viewModel.markExternalMapOpened()
                    }
                },
                onOpenSharedPlaceQueryMap = {
                    val query = uiState.form.address.ifBlank { uiState.form.name }
                    if (openSharedPlaceQueryInMap(context, query)) viewModel.markExternalMapOpened()
                },
                onSearchAddressCandidates = viewModel::searchAddressCandidates,
                onSelectAddressCandidate = viewModel::selectAddressCandidate,
                onMergePendingSharedPlace = viewModel::mergePendingSharedPlace,
                onStartNewFromPendingSharedPlace = viewModel::startNewFromPendingSharedPlace,
                onDismissStartNewRegistration = viewModel::dismissStartNewRegistration,
                onConfirmStartNewFromPendingSharedPlace = viewModel::confirmStartNewFromPendingSharedPlace,
                onDismissPendingSharedPlace = viewModel::dismissPendingSharedPlace,
                onDismissDiscardRegistration = viewModel::dismissDiscardRegistration,
                onDiscardRegistration = viewModel::discardRegistration,
            )
        }
    }
}

private fun openSharedPlaceInMap(context: Context, latitudeText: String, longitudeText: String): Boolean {
    val latitude = latitudeText.toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return false
    val longitude = longitudeText.toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return false
    val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$latitude,$longitude")
    return openMapUri(context, uri)
}

private fun openSharedPlaceQueryInMap(context: Context, query: String): Boolean {
    val safeQuery = query.trim().take(256)
    if (safeQuery.isBlank()) return false
    val uri = Uri.parse("https://www.google.com/maps/search/")
        .buildUpon()
        .appendQueryParameter("api", "1")
        .appendQueryParameter("query", safeQuery)
        .build()
    return openMapUri(context, uri)
}

private fun openMapUri(context: Context, uri: Uri): Boolean {
    val googleMapsIntent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
    val intent = if (googleMapsIntent.resolveActivity(context.packageManager) != null) {
        googleMapsIntent
    } else {
        Intent(Intent.ACTION_VIEW, uri)
    }
    return runCatching { context.startActivity(intent) }.isSuccess
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
                onClearSnooze = {},
                onCancelEdit = {},
                onRefreshPermissions = {},
                onPermissionSettingsAction = {},
                onRegisterGeofences = {},
                onRestoreGeofences = {},
                onSendTestEnterEvent = {},
                onFindNearbySavedLocations = {},
                onCloseNearbySavedLocations = {},
                onExportJson = {},
                onExportCsv = {},
                onImportJson = {},
                onImportCsv = {},
                onConfirmImport = {},
                onCancelImport = {},
                onHistoryFilterChange = {},
                onDeleteAllHistory = {},
                onSelectSharedPlaceCandidate = {},
                onConfirmSharedPlaceCandidateAndSave = {},
                onOpenSharedPlaceMap = {},
                onOpenSharedPlaceQueryMap = {},
                onSearchAddressCandidates = {},
                onSelectAddressCandidate = {},
                onMergePendingSharedPlace = {},
                onStartNewFromPendingSharedPlace = {},
                onDismissStartNewRegistration = {},
                onConfirmStartNewFromPendingSharedPlace = {},
                onDismissPendingSharedPlace = {},
                onDismissDiscardRegistration = {},
                onDiscardRegistration = {},
            )
        }
    }
}
