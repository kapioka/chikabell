@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.chikabell.app.ui.locations

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.chikabell.app.BuildConfig
import com.chikabell.app.domain.model.DeliveryStatus
import com.chikabell.app.domain.model.NotificationHistory
import com.chikabell.app.domain.model.HistoryFilter
import com.chikabell.app.domain.model.HistoryPeriod
import com.chikabell.app.domain.model.RegistrationStatus
import com.chikabell.app.domain.model.RestoreAttemptResult
import com.chikabell.app.domain.model.RestoreTrigger
import com.chikabell.app.domain.model.SavedLocation
import com.chikabell.app.domain.model.NearbyState
import com.chikabell.app.domain.model.SourceType
import com.chikabell.app.domain.model.TagRules
import com.chikabell.app.geofence.NearbyDistanceFilter
import com.chikabell.app.geofence.NearbySavedLocationCandidate
import com.chikabell.app.geofence.formatApproxStraightLineDistance
import com.chikabell.app.geofence.selectNearbyCandidates
import com.chikabell.app.permission.BackgroundLocationStatus
import com.chikabell.app.permission.ActivityRecognitionStatus
import com.chikabell.app.permission.ForegroundLocationStatus
import com.chikabell.app.permission.GooglePlayServicesStatus
import com.chikabell.app.permission.LocationServicesStatus
import com.chikabell.app.permission.NotificationPermissionStatus
import com.chikabell.app.permission.PermissionSnapshot
import com.chikabell.app.share.SharedPlaceConfidence
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class AppScreen { LOCATIONS, HISTORY, SETTINGS, LOCATION_EDIT, PRESETS, DATA }

internal enum class LocationSortMode(val label: String) {
    REGISTRATION("登録順"),
    RECENTLY_UPDATED("最近更新順"),
    DISTANCE("距離順"),
    ;

    fun next(): LocationSortMode = entries[(ordinal + 1) % entries.size]
}

internal fun shouldUseSingleRowNotificationSettings(
    availableWidthDp: Float,
    fontScale: Float,
): Boolean = availableWidthDp >= 320f && fontScale <= 1.3f

internal fun shouldStackCoordinateFields(
    availableWidthDp: Float,
    fontScale: Float,
): Boolean = availableWidthDp < 600f || fontScale > 1.3f

internal fun sharedPlaceReviewTitle(
    confidence: SharedPlaceConfidence,
    addressCandidateCount: Int,
    isAddressSearching: Boolean,
    candidateConfirmed: Boolean,
    coordinatesManuallyEdited: Boolean,
): String = when {
    isAddressSearching -> "位置候補を検索中"
    candidateConfirmed || coordinatesManuallyEdited -> "位置を確認しました"
    confidence == SharedPlaceConfidence.RESOLVING -> "共有リンクを確認中"
    confidence == SharedPlaceConfidence.HIGH_CONFIDENCE -> "位置を取得しました"
    confidence == SharedPlaceConfidence.NEEDS_CONFIRMATION -> "位置候補の確認が必要です"
    confidence == SharedPlaceConfidence.UNRESOLVED && addressCandidateCount > 0 ->
        "位置候補が${addressCandidateCount}件見つかりました"
    confidence == SharedPlaceConfidence.UNRESOLVED -> "位置を設定できませんでした"
    else -> "共有リンクを確認できませんでした"
}

internal fun locationSaveGuidance(blockReason: LocationSaveBlockReason): String = when (blockReason) {
    LocationSaveBlockReason.MISSING_COORDINATES -> "保存するには位置を設定してください"
    LocationSaveBlockReason.INVALID_COORDINATES -> "緯度・経度の入力を確認してください"
    LocationSaveBlockReason.CANDIDATE_CONFIRMATION_REQUIRED -> "保存するには位置を確定してください"
}

internal fun sortSavedLocations(
    locations: List<SavedLocation>,
    mode: LocationSortMode,
    distancesByLocationId: Map<String, Float> = emptyMap(),
): List<SavedLocation> = when (mode) {
    LocationSortMode.REGISTRATION -> locations.sortedBy(SavedLocation::sortOrder)
    LocationSortMode.RECENTLY_UPDATED -> locations.sortedByDescending(SavedLocation::updatedAt)
    LocationSortMode.DISTANCE -> locations.sortedWith(
        compareBy<SavedLocation> { distancesByLocationId[it.id] ?: Float.POSITIVE_INFINITY }
            .thenBy(SavedLocation::sortOrder),
    )
}

internal fun toggleLocationDetailsExpansion(
    expandedLocationIds: Set<String>,
    locationId: String,
): Set<String> = if (locationId in expandedLocationIds) {
    expandedLocationIds - locationId
} else {
    expandedLocationIds + locationId
}

internal fun retainExistingLocationDetailsExpansion(
    expandedLocationIds: Set<String>,
    existingLocationIds: Set<String>,
): Set<String> = expandedLocationIds.intersect(existingLocationIds)

@Composable
fun LocationsScreen(
    uiState: LocationsUiState,
    onFormChange: (LocationFormState) -> Unit,
    onApplyPreset: (String) -> Unit,
    onSavePreset: (String) -> Unit,
    onResetPreset: () -> Unit,
    onSave: () -> Unit,
    onEdit: (SavedLocation) -> Unit,
    onDelete: (SavedLocation) -> Unit,
    onDeleteSelected: (Set<String>) -> Unit,
    onSetLocationsEnabled: (Set<String>, Boolean) -> Unit,
    onClearSnooze: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onRefreshPermissions: () -> Unit,
    onRequestForegroundLocation: () -> Unit,
    onRequestNotification: () -> Unit,
    onRequestActivityRecognition: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRegisterGeofences: () -> Unit,
    onRestoreGeofences: () -> Unit,
    onSendTestEnterEvent: () -> Unit,
    onFindNearbySavedLocations: () -> Unit,
    onCloseNearbySavedLocations: () -> Unit,
    onExportJson: () -> Unit,
    onExportCsv: () -> Unit,
    onImportJson: () -> Unit,
    onImportCsv: () -> Unit,
    onConfirmImport: () -> Unit,
    onCancelImport: () -> Unit,
    onHistoryFilterChange: (HistoryFilter) -> Unit,
    onDeleteAllHistory: () -> Unit,
    onSelectSharedPlaceCandidate: (Int) -> Unit,
    onConfirmSharedPlaceCandidateAndSave: () -> Unit,
    onOpenSharedPlaceMap: () -> Unit,
    onOpenSharedPlaceQueryMap: () -> Unit,
    onSearchAddressCandidates: () -> Unit,
    onSelectAddressCandidate: (Int) -> Unit,
    onMergePendingSharedPlace: () -> Unit,
    onStartNewFromPendingSharedPlace: () -> Unit,
    onDismissStartNewRegistration: () -> Unit,
    onConfirmStartNewFromPendingSharedPlace: () -> Unit,
    onDismissPendingSharedPlace: () -> Unit,
    onDismissDiscardRegistration: () -> Unit,
    onDiscardRegistration: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var screenName by rememberSaveable { mutableStateOf(AppScreen.LOCATIONS.name) }
    val screen = AppScreen.valueOf(screenName)
    var saveRequestBaseline by rememberSaveable { mutableStateOf<Long?>(null) }
    var showRepairDialog by rememberSaveable { mutableStateOf(false) }
    var continuousEditIds by remember { mutableStateOf(emptyList<String>()) }
    var continuousEditIndex by rememberSaveable { mutableStateOf(0) }

    fun navigate(next: AppScreen) { screenName = next.name }
    fun navigateHome() {
        saveRequestBaseline = null
        onCancelEdit()
        if (uiState.sharedRegistrationSession == null) {
            screenName = AppScreen.LOCATIONS.name
        }
    }

    BackHandler(enabled = screen !in listOf(AppScreen.LOCATIONS, AppScreen.HISTORY, AppScreen.SETTINGS)) {
        when (screen) {
            AppScreen.PRESETS, AppScreen.DATA -> navigate(AppScreen.SETTINGS)
            AppScreen.LOCATION_EDIT -> navigateHome()
            else -> navigateHome()
        }
    }

    LaunchedEffect(
        uiState.form.sourceType,
        uiState.form.sourceUrl,
        uiState.sharedPlace,
        uiState.sharedRegistrationSession,
    ) {
        if (uiState.form.sourceType == SourceType.MAP_SHARE &&
            uiState.sharedPlace != null
        ) {
            navigate(AppScreen.LOCATION_EDIT)
        }
    }

    uiState.sharedRegistrationSession?.pendingIncomingShare
        ?.takeUnless { uiState.showStartNewRegistrationDialog }
        ?.let { pending ->
        AlertDialog(
            onDismissRequest = onDismissPendingSharedPlace,
            title = { Text("登録途中の地点があります") },
            text = {
                Text(
                    "今回共有された「${sharedPlaceDraftLabel(pending.place).ifBlank { "位置情報" }}」を、" +
                        "現在の登録へ追加しますか？",
                )
            },
            confirmButton = {
                Button(onClick = onMergePendingSharedPlace) {
                    Text("現在の登録に追加")
                }
            },
            dismissButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = onStartNewFromPendingSharedPlace) {
                        Text("新しい地点として登録")
                    }
                    TextButton(onClick = onDismissPendingSharedPlace) {
                        Text("キャンセル")
                    }
                }
            },
        )
    }

    if (uiState.showStartNewRegistrationDialog) {
        AlertDialog(
            onDismissRequest = onDismissStartNewRegistration,
            title = { Text("現在の入力を破棄しますか？") },
            text = { Text("現在編集中の名前、メモ、タグ、通知設定、位置候補を破棄し、今回共有された地点を新しく登録します。") },
            confirmButton = {
                Button(onClick = onConfirmStartNewFromPendingSharedPlace) {
                    Text("破棄して新規登録")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissStartNewRegistration) {
                    Text("戻る")
                }
            },
        )
    }

    if (uiState.showDiscardRegistrationDialog) {
        AlertDialog(
            onDismissRequest = onDismissDiscardRegistration,
            title = { Text("登録を中止しますか？") },
            text = { Text("入力した名前、メモ、タグ、通知設定、位置候補は破棄されます。") },
            confirmButton = {
                Button(
                    onClick = {
                        onDiscardRegistration()
                        screenName = AppScreen.LOCATIONS.name
                    },
                ) {
                    Text("破棄する")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDiscardRegistration) {
                    Text("登録を続ける")
                }
            },
        )
    }
    LaunchedEffect(uiState.saveSuccessId) {
        val baseline = saveRequestBaseline
        if (baseline != null && uiState.saveSuccessId > baseline) {
            saveRequestBaseline = null
            if (uiState.form.hasUserAuthoredLocationDraft() || uiState.sharedRegistrationSession != null) {
                return@LaunchedEffect
            }
            val nextIndex = continuousEditIndex + 1
            val next = continuousEditIds.getOrNull(nextIndex)?.let { id -> uiState.locations.firstOrNull { it.id == id } }
            if (next != null) {
                continuousEditIndex = nextIndex
                onEdit(next)
                navigate(AppScreen.LOCATION_EDIT)
            } else {
                continuousEditIds = emptyList()
                continuousEditIndex = 0
                navigate(AppScreen.LOCATIONS)
            }
        }
    }

    if (showRepairDialog) {
        AlertDialog(
            onDismissRequest = { showRepairDialog = false },
            title = { Text("監視を修復しますか？") },
            text = {
                Text("地点や履歴は削除されません。有効地点の監視を登録し直すため、滞在時間の判定は再び最初から始まる場合があります。")
            },
            confirmButton = {
                Button(onClick = {
                    showRepairDialog = false
                    onRestoreGeofences()
                }) { Text("修復する") }
            },
            dismissButton = { TextButton(onClick = { showRepairDialog = false }) { Text("キャンセル") } },
        )
    }

    when (screen) {
        AppScreen.LOCATIONS -> LocationsHomeScreen(
            uiState = uiState,
            onNavigate = ::navigate,
            onEdit = {
                onEdit(it)
                navigate(AppScreen.LOCATION_EDIT)
            },
            onDelete = onDelete,
            onDeleteSelected = onDeleteSelected,
            onSetLocationsEnabled = onSetLocationsEnabled,
            onClearSnooze = onClearSnooze,
            onStartContinuousEdit = { locations ->
                if (locations.isNotEmpty()) {
                    continuousEditIds = locations.map(SavedLocation::id)
                    continuousEditIndex = 0
                    onEdit(locations.first())
                    navigate(AppScreen.LOCATION_EDIT)
                }
            },
            onFormChange = onFormChange,
            onFindNearbySavedLocations = onFindNearbySavedLocations,
            onCloseNearbySavedLocations = onCloseNearbySavedLocations,
            modifier = modifier,
        )
        AppScreen.HISTORY -> MainScaffold(
            selected = AppScreen.HISTORY,
            onNavigate = ::navigate,
            modifier = modifier,
        ) { padding ->
            HistoryScreen(
                histories = uiState.histories,
                filter = uiState.historyFilter,
                onFilterChange = onHistoryFilterChange,
                onDeleteAllHistory = onDeleteAllHistory,
                modifier = Modifier.padding(padding),
            )
        }
        AppScreen.SETTINGS -> MainScaffold(
            selected = AppScreen.SETTINGS,
            onNavigate = ::navigate,
            modifier = modifier,
        ) { padding ->
            SettingsScreen(
                uiState = uiState,
                onRefreshPermissions = onRefreshPermissions,
                onRequestForegroundLocation = onRequestForegroundLocation,
                onRequestNotification = onRequestNotification,
                onRequestActivityRecognition = onRequestActivityRecognition,
                onOpenAppSettings = onOpenAppSettings,
                onRegisterGeofences = onRegisterGeofences,
                onRepair = { showRepairDialog = true },
                onSendTestEnterEvent = onSendTestEnterEvent,
                onNavigate = ::navigate,
                modifier = Modifier.padding(padding),
            )
        }
        AppScreen.LOCATION_EDIT -> LocationEditScreen(
            uiState = uiState,
            onBack = ::navigateHome,
            onFormChange = onFormChange,
            onApplyPreset = onApplyPreset,
            onSelectSharedPlaceCandidate = onSelectSharedPlaceCandidate,
            onConfirmSharedPlaceCandidateAndSave = {
                saveRequestBaseline = uiState.saveSuccessId
                onConfirmSharedPlaceCandidateAndSave()
            },
            onOpenSharedPlaceMap = onOpenSharedPlaceMap,
            onOpenSharedPlaceQueryMap = onOpenSharedPlaceQueryMap,
            onSearchAddressCandidates = onSearchAddressCandidates,
            onSelectAddressCandidate = onSelectAddressCandidate,
            onSave = {
                saveRequestBaseline = uiState.saveSuccessId
                onSave()
            },
            scrollToSharedPlaceOnError = saveRequestBaseline != null,
            continuousProgress = continuousEditIds.takeIf { it.isNotEmpty() }?.let { continuousEditIndex + 1 to it.size },
            modifier = modifier,
        )
        AppScreen.PRESETS -> PresetSettingsScreen(
            uiState = uiState,
            onBack = { navigate(AppScreen.SETTINGS) },
            onFormChange = onFormChange,
            onApplyPreset = onApplyPreset,
            onSavePreset = onSavePreset,
            onResetPreset = onResetPreset,
            modifier = modifier,
        )
        AppScreen.DATA -> DataManagementScreen(
            uiState = uiState,
            onBack = { navigate(AppScreen.SETTINGS) },
            onExportJson = onExportJson,
            onExportCsv = onExportCsv,
            onImportJson = onImportJson,
            onImportCsv = onImportCsv,
            onConfirmImport = onConfirmImport,
            onCancelImport = onCancelImport,
            modifier = modifier,
        )
    }
}

@Composable
private fun MainScaffold(
    selected: AppScreen,
    onNavigate: (AppScreen) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        bottomBar = { BottomNavigation(selected, onNavigate) },
        content = content,
    )
}

@Composable
private fun BottomNavigation(selected: AppScreen, onNavigate: (AppScreen) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        listOf(
            Triple(AppScreen.LOCATIONS, "地点", Icons.Default.LocationOn),
            Triple(AppScreen.HISTORY, "履歴", Icons.Default.DateRange),
            Triple(AppScreen.SETTINGS, "設定", Icons.Default.Settings),
        ).forEach { (screen, label, icon) ->
            NavigationBarItem(
                selected = selected == screen,
                onClick = { onNavigate(screen) },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun LocationsHomeScreen(
    uiState: LocationsUiState,
    onNavigate: (AppScreen) -> Unit,
    onEdit: (SavedLocation) -> Unit,
    onDelete: (SavedLocation) -> Unit,
    onDeleteSelected: (Set<String>) -> Unit,
    onSetLocationsEnabled: (Set<String>, Boolean) -> Unit,
    onClearSnooze: (String) -> Unit,
    onStartContinuousEdit: (List<SavedLocation>) -> Unit,
    onFormChange: (LocationFormState) -> Unit,
    onFindNearbySavedLocations: () -> Unit,
    onCloseNearbySavedLocations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var sortModeName by rememberSaveable { mutableStateOf(LocationSortMode.REGISTRATION.name) }
    val sortMode = LocationSortMode.valueOf(sortModeName)
    var pendingDelete by remember { mutableStateOf<SavedLocation?>(null) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var expandedLocationIds by remember { mutableStateOf(setOf<String>()) }
    var showBatchDeleteDialog by rememberSaveable { mutableStateOf(false) }
    val distancesByLocationId = remember(uiState.nearbySearch) {
        (uiState.nearbySearch as? NearbySearchUiState.Results)
            ?.result
            ?.candidates
            ?.associate { it.location.id to it.distanceMeters }
            .orEmpty()
    }
    val filtered = remember(uiState.locations, query, sortMode, distancesByLocationId) {
        val normalizedQuery = TagRules.normalize(query)
        val tagOnly = query.trim().startsWith("#")
        val matchingLocations = uiState.locations
            .filter { location ->
                query.isBlank() ||
                    if (tagOnly) {
                        location.tags.any { tag -> tag.normalizedName.contains(normalizedQuery) }
                    } else {
                        location.name.contains(query, true) ||
                            location.message.contains(query, true) ||
                            location.tags.any { tag -> tag.normalizedName.contains(normalizedQuery) || tag.name.contains(query, true) }
                    }
            }
        sortSavedLocations(matchingLocations, sortMode, distancesByLocationId)
    }
    val tagSuggestions = remember(uiState.tags, query) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            emptyList()
        } else {
            val normalizedQuery = TagRules.normalize(trimmed)
            uiState.tags
                .filter { tag -> trimmed == "#" || tag.normalizedName.contains(normalizedQuery) || tag.name.contains(trimmed.removePrefix("#"), true) }
                .take(8)
        }
    }
    val existingLocationIds = remember(uiState.locations) { uiState.locations.mapTo(mutableSetOf(), SavedLocation::id) }
    LaunchedEffect(existingLocationIds) {
        expandedLocationIds = retainExistingLocationDetailsExpansion(
            expandedLocationIds = expandedLocationIds,
            existingLocationIds = existingLocationIds,
        )
    }

    pendingDelete?.let { location ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("「${location.name}」を削除しますか？") },
            text = { Text("地点は一覧と監視対象から削除されます。保存済みの通知履歴は残ります。") },
            confirmButton = {
                Button(onClick = {
                    pendingDelete = null
                    onDelete(location)
                }) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("キャンセル") } },
        )
    }
    if (showBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = { Text("${selectedIds.size}件を削除しますか？") },
            text = { Text("選択した地点は一覧と監視対象から削除されます。通知履歴は残ります。") },
            confirmButton = {
                Button(onClick = {
                    showBatchDeleteDialog = false
                    onDeleteSelected(selectedIds)
                    selectedIds = emptySet()
                }) { Text("まとめて削除") }
            },
            dismissButton = { TextButton(onClick = { showBatchDeleteDialog = false }) { Text("キャンセル") } },
        )
    }
    Scaffold(
        modifier = modifier,
        bottomBar = { BottomNavigation(AppScreen.LOCATIONS, onNavigate) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                modifier = Modifier.semantics { contentDescription = "地点を追加" },
                onClick = {
                    onFormChange(LocationFormState())
                    onNavigate(AppScreen.LOCATION_EDIT)
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("地点を追加") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(start = 20.dp, end = 20.dp, top = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (selectedIds.isEmpty()) "登録地点" else "${selectedIds.size}件を選択中",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (selectedIds.isEmpty()) {
                    IconButton(onClick = { onNavigate(AppScreen.SETTINGS) }) {
                        Icon(Icons.Default.Settings, contentDescription = "設定")
                    }
                } else {
                    TextButton(onClick = { selectedIds = emptySet() }) { Text("解除") }
                }
            }
            if (selectedIds.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { onSetLocationsEnabled(selectedIds, true); selectedIds = emptySet() }) { Text("有効にする") }
                    OutlinedButton(onClick = { onSetLocationsEnabled(selectedIds, false); selectedIds = emptySet() }) { Text("無効にする") }
                    OutlinedButton(onClick = {
                        onStartContinuousEdit(uiState.locations.filter { it.id in selectedIds })
                        selectedIds = emptySet()
                    }) { Text("連続編集") }
                    OutlinedButton(onClick = { showBatchDeleteDialog = true }) { Text("削除") }
                }
            }
            MonitoringSummary(uiState = uiState, onOpenSettings = { onNavigate(AppScreen.SETTINGS) })
            uiState.registrationMessage?.let { message -> FeedbackText(message) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = query,
                        onValueChange = { query = it },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        label = { Text("地点名・メモ・タグで検索") },
                        singleLine = true,
                    )
                    OutlinedButton(
                        onClick = {
                            val nextMode = sortMode.next()
                            sortModeName = nextMode.name
                            if (nextMode == LocationSortMode.DISTANCE) {
                                onFindNearbySavedLocations()
                            } else if (sortMode == LocationSortMode.DISTANCE) {
                                onCloseNearbySavedLocations()
                            }
                        },
                    ) {
                        Text(
                            if (sortMode == LocationSortMode.DISTANCE && uiState.nearbySearch is NearbySearchUiState.Loading) {
                                "距離取得中"
                            } else {
                                sortMode.label
                            },
                        )
                    }
                }
                if (tagSuggestions.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        tagSuggestions.forEach { tag ->
                            AssistChip(
                                onClick = { query = "#${tag.name}" },
                                label = { Text("#${tag.name} ${tag.usageCount}件") },
                            )
                        }
                    }
                }
            }
            if (sortMode == LocationSortMode.DISTANCE) {
                when (val nearbySearch = uiState.nearbySearch) {
                    NearbySearchUiState.Hidden,
                    NearbySearchUiState.Loading,
                    -> Unit
                    NearbySearchUiState.PermissionRequired -> FeedbackText("距離順には位置情報の権限が必要です")
                    NearbySearchUiState.LocationServicesDisabled -> FeedbackText("距離順には端末の位置情報をONにしてください")
                    NearbySearchUiState.LocationUnavailable -> FeedbackText("現在地を取得できませんでした。再度、距離順を選び直してください")
                    NearbySearchUiState.NoSavedLocations -> Unit
                    is NearbySearchUiState.Results -> {
                        Text(
                            if (nearbySearch.result.lowAccuracy) {
                                "現在地の精度が低いため、直線距離は大まかな目安です"
                            } else {
                                "現在地からの概算直線距離で並べています"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            if (uiState.locations.isEmpty()) "地点はまだ登録されていません" else "検索に一致する地点がありません",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(vertical = 32.dp),
                        )
                    }
                } else {
                    items(filtered, key = SavedLocation::id) { location ->
                        LocationListRow(
                            location = location,
                            distanceMeters = if (sortMode == LocationSortMode.DISTANCE) distancesByLocationId[location.id] else null,
                            selected = location.id in selectedIds,
                            detailsExpanded = location.id in expandedLocationIds,
                            onSelectedChange = { selected ->
                                selectedIds = if (selected) selectedIds + location.id else selectedIds - location.id
                            },
                            onToggleDetails = {
                                expandedLocationIds = toggleLocationDetailsExpansion(expandedLocationIds, location.id)
                            },
                            onEnabledChange = { enabled -> onSetLocationsEnabled(setOf(location.id), enabled) },
                            onClearSnooze = { onClearSnooze(location.id) },
                            onEdit = { onEdit(location) },
                            onDelete = { pendingDelete = location },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NearbySavedLocationsSheet(
    searchState: NearbySearchUiState,
    selectedFilter: NearbyDistanceFilter,
    onFilterChange: (NearbyDistanceFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("近くの登録地点", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("現在地からの概算直線距離です。経路距離や移動時間ではありません。")
            when (searchState) {
                NearbySearchUiState.Hidden -> Unit
                NearbySearchUiState.Loading -> Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("現在地を取得中です")
                }
                NearbySearchUiState.PermissionRequired -> NearbySearchMessage(
                    "正確な位置情報の権限が必要です。設定画面から許可してください。",
                )
                NearbySearchUiState.LocationServicesDisabled -> NearbySearchMessage(
                    "端末の位置情報サービスがOFFです。ONにしてからもう一度お試しください。",
                )
                NearbySearchUiState.LocationUnavailable -> NearbySearchMessage(
                    "有効な現在地を取得できませんでした。屋外などで再度お試しください。",
                )
                NearbySearchUiState.NoSavedLocations -> NearbySearchMessage("登録地点がありません。")
                is NearbySearchUiState.Results -> {
                    if (searchState.result.lowAccuracy) {
                        Text(
                            "位置精度が低いため、距離は大まかな目安です。",
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            NearbyDistanceFilter.WITHIN_1_KM to "1km以内",
                            NearbyDistanceFilter.WITHIN_5_KM to "5km以内",
                            NearbyDistanceFilter.ALL to "すべて",
                        ).forEach { (filter, label) ->
                            FilterChip(
                                selected = selectedFilter == filter,
                                onClick = { onFilterChange(filter) },
                                label = { Text(label) },
                            )
                        }
                    }
                    val selection = selectNearbyCandidates(searchState.result.candidates, selectedFilter)
                    if (selection.showingFarFallback) {
                        Text("5km以内に候補がないため、5kmより遠い最寄り地点を表示します。")
                    } else if (selection.candidates.isEmpty()) {
                        NearbySearchMessage("指定した距離内に登録地点がありません。")
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 520.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(selection.candidates, key = { it.location.id }) { candidate ->
                            NearbySavedLocationRow(candidate)
                        }
                    }
                }
            }
            TextButton(modifier = Modifier.align(Alignment.End), onClick = onDismiss) { Text("閉じる") }
        }
    }
}

@Composable
private fun NearbySearchMessage(message: String) {
    Text(message, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
}

@Composable
private fun NearbySavedLocationRow(candidate: NearbySavedLocationCandidate) {
    val location = candidate.location
    val status = when {
        !location.enabled -> "通知オフ"
        location.registrationStatus == RegistrationStatus.ERROR -> "監視登録エラー"
        location.registrationStatus == RegistrationStatus.REGISTERED -> "通知有効"
        else -> "監視準備中"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    location.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(12.dp))
                Text(formatApproxStraightLineDistance(candidate.distanceMeters), fontWeight = FontWeight.Bold)
            }
            if (location.message.isNotBlank()) {
                Text(location.message, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (location.tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    location.tags.forEach { tag -> Text("#${tag.name}", style = MaterialTheme.typography.labelMedium) }
                }
            }
            Text("$status ・ 通知半径 ${location.radiusMeters}m ・ 直線距離", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MonitoringSummary(uiState: LocationsUiState, onOpenSettings: () -> Unit) {
    val enabled = uiState.locations.count(SavedLocation::enabled)
    val registered = uiState.locations.count { it.enabled && it.registrationStatus == RegistrationStatus.REGISTERED }
    val hasError = uiState.locations.any { it.enabled && it.registrationStatus == RegistrationStatus.ERROR }
    val background = if (hasError) MaterialTheme.colorScheme.errorContainer else Color(0xFFEAF3EF)
    val foreground = if (hasError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSettings),
        colors = CardDefaults.cardColors(containerColor = background),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    if (hasError) "監視に問題があります" else "監視中 $registered/$enabled 件",
                    color = foreground,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (hasError) "設定画面で状態を確認してください" else "有効地点の登録状態",
                    style = MaterialTheme.typography.bodySmall,
                    color = foreground,
                )
            }
            Text("状態を確認", color = foreground, style = MaterialTheme.typography.labelLarge)
        }
    }
}
@Composable
private fun LocationListRow(
    location: SavedLocation,
    distanceMeters: Float?,
    selected: Boolean,
    detailsExpanded: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onToggleDetails: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onClearSnooze: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by rememberSaveable(location.id) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = onSelectedChange,
                modifier = Modifier.padding(end = 4.dp).semantics { contentDescription = "${location.name}を選択" },
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable(
                            onClickLabel = if (detailsExpanded) "詳細を非表示" else "詳細を表示",
                            onClick = onToggleDetails,
                        )
                        .semantics(mergeDescendants = true) {
                            contentDescription = if (detailsExpanded) {
                                "${location.name}の詳細を非表示"
                            } else {
                                "${location.name}の詳細を表示"
                            }
                            stateDescription = if (detailsExpanded) "展開中" else "折りたたみ中"
                        }
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            location.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Icon(
                            imageVector = if (detailsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                        )
                    }
                    if (location.message.isNotBlank()) {
                        Text(
                            location.message,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (detailsExpanded) {
                    LocationNotificationSummary(location = location)
                }
                if (location.nearbyState == NearbyState.SNOOZED &&
                    (location.snoozedUntil ?: 0L) > System.currentTimeMillis()
                ) {
                    Text(
                        "12時間休止中",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (location.tags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        location.tags.forEach { tag ->
                            CompactLocationTag(name = tag.name)
                        }
                    }
                }
                distanceMeters?.let {
                    Text(
                        "直線 ${formatApproxStraightLineDistance(it)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Switch(
                    checked = location.enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.semantics { contentDescription = "${location.name}を${if (location.enabled) "無効" else "有効"}にする" },
                )
                Text(
                    location.registrationLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (location.registrationStatus == RegistrationStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "地点メニュー") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (location.nearbyState == NearbyState.SNOOZED) {
                        DropdownMenuItem(
                            text = { Text("12時間休止を解除") },
                            onClick = { menuOpen = false; onClearSnooze() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("編集") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text("削除") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun CompactLocationTag(name: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = "#$name",
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LocationNotificationSummary(location: SavedLocation) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "通知距離: ${location.radiusMeters}m　再通知: 十分に退出後",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            location.lastVerificationReason?.let { reason ->
                Text(
                    text = "直近判定: $reason",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            location.lastVerificationAt?.let {
                Text("最終検証状態更新: ${formatEventTime(it)}", style = MaterialTheme.typography.labelSmall)
            }
            location.lastValidLocationAt?.let {
                Text("最終有効測位: ${formatEventTime(it)}", style = MaterialTheme.typography.labelSmall)
            }
            location.lastEventAt?.let {
                Text("最終ジオフェンスイベント: ${formatEventTime(it)}", style = MaterialTheme.typography.labelSmall)
            }
            location.lastSuppressionReason?.let {
                Text("直近抑制・破棄: $it", style = MaterialTheme.typography.labelSmall, maxLines = 2)
            }
            if (location.lastAccuracyMeters != null || location.lastSpeedMetersPerSecond != null) {
                val accuracy = location.lastAccuracyMeters?.let { "accuracy ${it.toInt()}m" } ?: "accuracy不明"
                val speed = location.lastSpeedMetersPerSecond?.let { "速度 ${"%.1f".format(it)}m/s" } ?: "速度不明"
                Text("$accuracy / $speed", style = MaterialTheme.typography.labelSmall)
            }
            location.snoozedUntil?.takeIf { it > System.currentTimeMillis() }?.let {
                Text("休止期限: ${formatEventTime(it)}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun LocationEditScreen(
    uiState: LocationsUiState,
    onBack: () -> Unit,
    onFormChange: (LocationFormState) -> Unit,
    onApplyPreset: (String) -> Unit,
    onSelectSharedPlaceCandidate: (Int) -> Unit,
    onConfirmSharedPlaceCandidateAndSave: () -> Unit,
    onOpenSharedPlaceMap: () -> Unit,
    onOpenSharedPlaceQueryMap: () -> Unit,
    onSearchAddressCandidates: () -> Unit,
    onSelectAddressCandidate: (Int) -> Unit,
    onSave: () -> Unit,
    scrollToSharedPlaceOnError: Boolean,
    continuousProgress: Pair<Int, Int>?,
    modifier: Modifier = Modifier,
) {
    val form = uiState.form
    val presentation = locationRegistrationPresentation(uiState)
    val blockReason = locationSaveBlockReason(
        form = form,
        place = uiState.sharedPlace,
        candidateConfirmed = uiState.sharedPlaceCandidateConfirmed,
        coordinatesManuallyEdited = uiState.sharedPlaceCoordinatesManuallyEdited,
    )
    val disclosureKey = uiState.sharedRegistrationSession?.sessionId
        ?: uiState.editingLocation?.id
        ?: "new-location"
    var detailsExpanded by rememberSaveable(disclosureKey) { mutableStateOf(form.name.isBlank()) }
    var notificationExpanded by rememberSaveable(disclosureKey) { mutableStateOf(false) }
    var coordinatesExpanded by rememberSaveable(disclosureKey) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val firstItemIndex = if (continuousProgress == null) 0 else 1
    val validationSections = locationFormSectionsForValidation(
        validationMessage = uiState.validationMessage,
        blockReason = if (uiState.validationMessage == null) null else blockReason,
    )
    LaunchedEffect(
        scrollToSharedPlaceOnError,
        uiState.validationMessage,
        uiState.sharedPlace?.failureReason,
        disclosureKey,
    ) {
        if (uiState.validationMessage != null) {
            if (LocationFormSection.DETAILS in validationSections) detailsExpanded = true
            if (LocationFormSection.NOTIFICATION in validationSections) notificationExpanded = true
            if (LocationFormSection.COORDINATES in validationSections) coordinatesExpanded = true
            val targetIndex = when {
                LocationFormSection.DETAILS in validationSections -> firstItemIndex + 1
                LocationFormSection.NOTIFICATION in validationSections -> firstItemIndex + 2
                LocationFormSection.COORDINATES in validationSections -> firstItemIndex + 3
                else -> firstItemIndex
            }
            listState.animateScrollToItem(targetIndex)
        } else if (scrollToSharedPlaceOnError && uiState.sharedPlace?.failureReason != null) {
            listState.animateScrollToItem(firstItemIndex)
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.editingLocation == null) "地点を追加" else "地点を編集") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") } },
                actions = {
                    Button(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 0.dp, minHeight = 48.dp)
                            .heightIn(min = 48.dp)
                            .semantics { stateDescription = presentation.topSaveStateDescription },
                        enabled = presentation.topSaveEnabled,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        onClick = onSave,
                    ) {
                        Text(if (uiState.isSaving) "保存中" else "保存")
                    }
                },
            )
        },
    ) { padding ->
        val tagQuery = form.tagInput.trim()
        val tagSuggestions = remember(uiState.tags, form.tags, tagQuery) {
            val normalizedQuery = TagRules.normalize(tagQuery)
            uiState.tags
                .filter { tag ->
                    form.tags.none { selected -> TagRules.normalize(selected) == tag.normalizedName } &&
                        (tagQuery == "#" || tagQuery.isBlank() || tag.normalizedName.contains(normalizedQuery) || tag.name.contains(tagQuery.removePrefix("#"), true))
                }
                .take(8)
        }
        fun addTag(raw: String) {
            val tag = TagRules.cleanDisplayName(raw)
            if (tag.isBlank()) return
            if (form.tags.size >= TagRules.MAX_TAGS_PER_LOCATION) return
            val next = TagRules.sanitizeNames(form.tags + tag)
            onFormChange(form.copy(tags = next, tagInput = ""))
        }
        fun removeTag(name: String) {
            onFormChange(form.copy(tags = form.tags.filterNot { TagRules.normalize(it) == TagRules.normalize(name) }))
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            state = listState,
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            continuousProgress?.let { (current, total) ->
                item { Text("連続編集 $current/$total 件目", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
            }
            item {
                LocationPositionSection(
                    uiState = uiState,
                    presentation = presentation,
                    validationMessage = uiState.validationMessage.takeIf { validationSections.isEmpty() },
                    onFormChange = onFormChange,
                    onSelectCandidate = onSelectSharedPlaceCandidate,
                    onConfirmAndSave = onConfirmSharedPlaceCandidateAndSave,
                    onOpenMap = onOpenSharedPlaceMap,
                    onOpenQueryMap = onOpenSharedPlaceQueryMap,
                    onSearchAddressCandidates = onSearchAddressCandidates,
                    onSelectAddressCandidate = onSelectAddressCandidate,
                )
            }
            item {
                RegistrationDisclosure(
                    title = "名前・メモを編集",
                    icon = Icons.Default.Edit,
                    expanded = detailsExpanded,
                    enabled = !uiState.isSaving,
                    onToggle = { detailsExpanded = !detailsExpanded },
                ) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = form.name,
                        onValueChange = { onFormChange(form.copy(name = it)) },
                        label = { Text("名前") },
                        singleLine = true,
                        enabled = !uiState.isSaving,
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = form.message,
                        onValueChange = { onFormChange(form.copy(message = it)) },
                        label = { Text("メモ（任意）") },
                        minLines = 2,
                        enabled = !uiState.isSaving,
                    )
                    Text("タグ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (form.tags.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            form.tags.forEach { tag ->
                                AssistChip(
                                    onClick = { removeTag(tag) },
                                    label = { Text("#$tag ×") },
                                    enabled = !uiState.isSaving,
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = form.tagInput,
                        onValueChange = { onFormChange(form.copy(tagInput = it.take(TagRules.MAX_TAG_NAME_LENGTH + 1))) },
                        label = { Text("タグを追加（例: #会社）") },
                        singleLine = true,
                        supportingText = { Text("${form.tags.size}/${TagRules.MAX_TAGS_PER_LOCATION}個・全体${TagRules.MAX_TAGS_TOTAL}個まで") },
                        enabled = !uiState.isSaving,
                    )
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = !uiState.isSaving &&
                            form.tagInput.isNotBlank() &&
                            form.tags.size < TagRules.MAX_TAGS_PER_LOCATION,
                        onClick = { addTag(form.tagInput) },
                    ) { Text("追加") }
                    if (tagSuggestions.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            tagSuggestions.forEach { tag ->
                                AssistChip(
                                    onClick = { addTag(tag.name) },
                                    label = { Text("#${tag.name} ${tag.usageCount}件") },
                                    enabled = !uiState.isSaving,
                                )
                            }
                        }
                    }
                    if (LocationFormSection.DETAILS in validationSections) {
                        InlineValidationMessage(uiState.validationMessage.orEmpty())
                    }
                }
            }
            item {
                RegistrationDisclosure(
                    title = "通知条件を編集",
                    icon = Icons.Default.Settings,
                    expanded = notificationExpanded,
                    enabled = !uiState.isSaving,
                    onToggle = { notificationExpanded = !notificationExpanded },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("この地点を有効にする")
                            Text("無効にすると監視対象から外れます", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = form.enabled,
                            onCheckedChange = { onFormChange(form.copy(enabled = it)) },
                            enabled = !uiState.isSaving,
                        )
                    }
                    Text("移動手段（テンプレ）", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        uiState.presets.forEach { preset ->
                            FilterChip(
                                selected = uiState.selectedPresetId == preset.id,
                                onClick = { onApplyPreset(preset.id) },
                                label = { Text(preset.name) },
                                enabled = !uiState.isSaving,
                            )
                        }
                    }
                    Text("再通知は0.5時間単位で指定できます", style = MaterialTheme.typography.bodySmall)
                    NotificationSettingsFields(
                        form = form,
                        onFormChange = onFormChange,
                        enabled = !uiState.isSaving,
                    )
                    if (LocationFormSection.NOTIFICATION in validationSections) {
                        InlineValidationMessage(uiState.validationMessage.orEmpty())
                    }
                }
            }
            item {
                RegistrationDisclosure(
                    title = "緯度・経度を確認",
                    icon = Icons.Default.LocationOn,
                    expanded = coordinatesExpanded,
                    enabled = !uiState.isSaving,
                    onToggle = { coordinatesExpanded = !coordinatesExpanded },
                ) {
                    val fontScale = LocalDensity.current.fontScale
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val latitudeIsError = form.latitude.isNotBlank() &&
                            (form.latitude.toDoubleOrNull()?.takeIf { it.isFinite() && it in -90.0..90.0 } == null)
                        val longitudeIsError = form.longitude.isNotBlank() &&
                            (form.longitude.toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 } == null)
                        if (shouldStackCoordinateFields(maxWidth.value, fontScale)) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                NumberField(
                                    form.latitude,
                                    { onFormChange(form.copy(latitude = it)) },
                                    "緯度",
                                    Modifier.fillMaxWidth(),
                                    isError = latitudeIsError,
                                    supportingMessage = "入力範囲: -90〜90",
                                    enabled = !uiState.isSaving,
                                )
                                NumberField(
                                    form.longitude,
                                    { onFormChange(form.copy(longitude = it)) },
                                    "経度",
                                    Modifier.fillMaxWidth(),
                                    isError = longitudeIsError,
                                    supportingMessage = "入力範囲: -180〜180",
                                    enabled = !uiState.isSaving,
                                )
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                NumberField(
                                    form.latitude,
                                    { onFormChange(form.copy(latitude = it)) },
                                    "緯度",
                                    Modifier.weight(1f),
                                    isError = latitudeIsError,
                                    supportingMessage = "入力範囲: -90〜90",
                                    enabled = !uiState.isSaving,
                                )
                                NumberField(
                                    form.longitude,
                                    { onFormChange(form.copy(longitude = it)) },
                                    "経度",
                                    Modifier.weight(1f),
                                    isError = longitudeIsError,
                                    supportingMessage = "入力範囲: -180〜180",
                                    enabled = !uiState.isSaving,
                                )
                            }
                        }
                    }
                    if (LocationFormSection.COORDINATES in validationSections) {
                        InlineValidationMessage(uiState.validationMessage.orEmpty())
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationSettingsFields(
    form: LocationFormState,
    onFormChange: (LocationFormState) -> Unit,
    enabled: Boolean = true,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (shouldUseSingleRowNotificationSettings(maxWidth.value, fontScale)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(form.radiusMeters, { onFormChange(form.copy(radiusMeters = it)) }, "半径m", Modifier.weight(1f), enabled = enabled)
                NumberField(form.loiteringDelaySeconds, { onFormChange(form.copy(loiteringDelaySeconds = it)) }, "滞在秒", Modifier.weight(1f), enabled = enabled)
                NumberField(form.cooldownHours, { onFormChange(form.copy(cooldownHours = it)) }, "再通知h", Modifier.weight(1f), enabled = enabled)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(form.radiusMeters, { onFormChange(form.copy(radiusMeters = it)) }, "半径m", Modifier.weight(1f), enabled = enabled)
                    NumberField(form.loiteringDelaySeconds, { onFormChange(form.copy(loiteringDelaySeconds = it)) }, "滞在秒", Modifier.weight(1f), enabled = enabled)
                }
                NumberField(
                    form.cooldownHours,
                    { onFormChange(form.copy(cooldownHours = it)) },
                    "再通知h",
                    Modifier.fillMaxWidth(),
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun LocationPositionSection(
    uiState: LocationsUiState,
    presentation: LocationRegistrationPresentation,
    validationMessage: String?,
    onFormChange: (LocationFormState) -> Unit,
    onSelectCandidate: (Int) -> Unit,
    onConfirmAndSave: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenQueryMap: () -> Unit,
    onSearchAddressCandidates: () -> Unit,
    onSelectAddressCandidate: (Int) -> Unit,
) {
    val place = uiState.sharedPlace
    val candidateChoices = place?.candidates
        .orEmpty()
        .mapIndexed { index, candidate -> index to candidate }
        .distinctBy { (_, candidate) ->
            "${"%.5f".format(Locale.US, candidate.latitude)}|${"%.5f".format(Locale.US, candidate.longitude)}"
        }
    val hasAlternatives = candidateChoices.size > 1 || uiState.addressCandidates.size > 1
    var showAlternatives by rememberSaveable(place?.sourceUrl, place?.selectedCandidateIndex) {
        mutableStateOf(false)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (presentation.mode) {
                LocationRegistrationMode.RESOLVING -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("位置を確認しています", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text(presentation.guidance, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                LocationRegistrationMode.NEEDS_CONFIRMATION -> {
                    Text("この場所で合っていますか？", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    CandidateSummary(
                        title = presentation.candidateTitle,
                        address = presentation.candidateAddress,
                    )
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = !uiState.isSaving,
                        onClick = onOpenMap,
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(presentation.mapActionLabel)
                    }
                    Text(
                        presentation.guidance,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        enabled = !uiState.isSaving,
                        onClick = onConfirmAndSave,
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (uiState.isSaving) "保存中" else "この位置で登録する")
                    }
                    Text(
                        "この位置を確定すると保存されます",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (hasAlternatives) {
                        TextButton(
                            modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp),
                            enabled = !uiState.isSaving,
                            onClick = { showAlternatives = !showAlternatives },
                        ) {
                            Text(if (showAlternatives) "候補を閉じる" else "候補を選び直す")
                        }
                    }
                    if (showAlternatives) {
                        candidateChoices.forEachIndexed { displayIndex, (sourceIndex, _) ->
                            if (sourceIndex != place?.selectedCandidateIndex) {
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    enabled = !uiState.isSaving,
                                    onClick = {
                                        showAlternatives = false
                                        onSelectCandidate(sourceIndex)
                                    },
                                ) { Text("候補${displayIndex + 1}を使用") }
                            }
                        }
                        uiState.addressCandidates.forEachIndexed { index, candidate ->
                            if (candidate.label != uiState.form.address) {
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    enabled = !uiState.isSaving,
                                    onClick = {
                                        showAlternatives = false
                                        onSelectAddressCandidate(index)
                                    },
                                ) {
                                    Text(candidate.label, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }

                LocationRegistrationMode.NEEDS_LOCATION -> {
                    Text("場所を設定してください", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(presentation.guidance, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    place?.warnings.orEmpty().forEach { warning ->
                        Text(warning, style = MaterialTheme.typography.bodySmall)
                    }
                    place?.failureReason?.let { reason ->
                        InlineValidationMessage(reason)
                    }
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = uiState.form.address,
                        onValueChange = { onFormChange(uiState.form.copy(address = it.take(256))) },
                        label = { Text("住所・施設名") },
                        singleLine = true,
                        enabled = !uiState.isSaving,
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = !uiState.isSaving &&
                            !uiState.isAddressSearching &&
                            uiState.form.address.trim().length >= 3,
                        onClick = onSearchAddressCandidates,
                    ) {
                        if (uiState.isAddressSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (uiState.isAddressSearching) "検索中" else "住所・施設名で検索")
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = !uiState.isSaving &&
                            (uiState.form.address.isNotBlank() || uiState.form.name.isNotBlank()),
                        onClick = onOpenQueryMap,
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Googleマップで確認")
                    }
                    uiState.addressSearchMessage?.let { message ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(message, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    uiState.addressCandidates.forEachIndexed { index, candidate ->
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            enabled = !uiState.isSaving,
                            onClick = { onSelectAddressCandidate(index) },
                        ) {
                            Text(candidate.label, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(
                        "Googleマップから座標を共有し直しても、名前やメモなどの入力内容は保持されます",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                LocationRegistrationMode.READY,
                LocationRegistrationMode.SAVING,
                -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("位置を設定しました", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    CandidateSummary(
                        title = presentation.candidateTitle,
                        address = presentation.candidateAddress,
                    )
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = !uiState.isSaving,
                        onClick = onOpenMap,
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(presentation.mapActionLabel)
                    }
                    Text(
                        if (uiState.isSaving) "保存しています" else "右上の「保存」で登録できます",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            validationMessage?.takeIf(String::isNotBlank)?.let { message ->
                InlineValidationMessage(message)
            }
        }
    }
}

@Composable
private fun CandidateSummary(
    title: String,
    address: String?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                address?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RegistrationDisclosure(
    title: String,
    icon: ImageVector,
    expanded: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 1.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .semantics { stateDescription = if (expanded) "展開中" else "折りたたみ中" }
                    .clickable(
                        enabled = enabled,
                        onClickLabel = if (expanded) "${title}を閉じる" else "${title}を開く",
                        onClick = onToggle,
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                )
            }
            if (expanded) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun InlineValidationMessage(message: String) {
    if (message.isBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PresetSettingsScreen(
    uiState: LocationsUiState,
    onBack: () -> Unit,
    onFormChange: (LocationFormState) -> Unit,
    onApplyPreset: (String) -> Unit,
    onSavePreset: (String) -> Unit,
    onResetPreset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedPreset = uiState.presets.firstOrNull { it.id == uiState.selectedPresetId }
    var presetName by rememberSaveable(uiState.selectedPresetId, selectedPreset?.name) {
        mutableStateOf(selectedPreset?.name.orEmpty())
    }
    var showResetDialog by rememberSaveable { mutableStateOf(false) }
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("初期値へ戻しますか？") },
            text = { Text("選択中のテンプレ名と数値を初期値へ戻します。登録済み地点は変更されません。") },
            confirmButton = {
                Button(onClick = { showResetDialog = false; onResetPreset() }) { Text("初期値へ戻す") }
            },
            dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text("キャンセル") } },
        )
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("通知テンプレ") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") } },
            )
        },
    ) { padding ->
        val form = uiState.form
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.presets.forEach { preset ->
                        FilterChip(
                            selected = uiState.selectedPresetId == preset.id,
                            onClick = { onApplyPreset(preset.id) },
                            label = { Text(preset.name) },
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(), value = presetName, onValueChange = { presetName = it.take(20) },
                    label = { Text("テンプレ名") },
                    supportingText = { Text("1〜20文字・ほかのテンプレと異なる名前") },
                )
            }
            item { NumberField(form.radiusMeters, { onFormChange(form.copy(radiusMeters = it)) }, "通知範囲の半径", Modifier.fillMaxWidth()) }
            item { NumberField(form.loiteringDelaySeconds, { onFormChange(form.copy(loiteringDelaySeconds = it)) }, "滞在時間（秒）", Modifier.fillMaxWidth()) }
            item { NumberField(form.cooldownHours, { onFormChange(form.copy(cooldownHours = it)) }, "繰り返し通知の間隔（時間）", Modifier.fillMaxWidth()) }
            item {
                Text(
                    "変更は今後テンプレを適用する地点に反映されます。作成済み地点は自動変更されません。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            uiState.validationMessage?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            item { Button(modifier = Modifier.fillMaxWidth(), onClick = { onSavePreset(presetName) }) { Text("保存") } }
            item { TextButton(modifier = Modifier.fillMaxWidth(), onClick = { showResetDialog = true }) { Text("初期値へ戻す") } }
        }
    }
}

@Composable
private fun HistoryScreen(
    histories: List<NotificationHistory>,
    filter: HistoryFilter,
    onFilterChange: (HistoryFilter) -> Unit,
    onDeleteAllHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("通知履歴を削除しますか？") },
            text = { Text("フィルター条件にかかわらず、すべての通知履歴を削除します。この操作は元に戻せません。") },
            confirmButton = {
                Button(onClick = {
                    showDeleteDialog = false
                    onDeleteAllHistory()
                }) { Text("すべて削除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("キャンセル") } },
        )
    }
    Column(
        modifier = modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("通知履歴", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(), value = filter.locationQuery,
            onValueChange = { onFilterChange(filter.copy(locationQuery = it)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }, label = { Text("地点名で検索") }, singleLine = true,
        )
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("すべて", "表示済み", "抑制", "失敗", "検証記録").forEach { value ->
                val status = value.toDeliveryStatusOrNull()
                FilterChip(
                    selected = filter.deliveryStatus == status,
                    onClick = { onFilterChange(filter.copy(deliveryStatus = status)) },
                    label = { Text(value) },
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    HistoryPeriod.ALL to "すべて",
                    HistoryPeriod.TODAY to "今日",
                    HistoryPeriod.SEVEN_DAYS to "7日",
                    HistoryPeriod.THIRTY_DAYS to "30日",
                ).forEach { (period, label) ->
                    FilterChip(
                        selected = filter.period == period,
                        onClick = { onFilterChange(filter.copy(period = period)) },
                        label = { Text(label) },
                    )
                }
            }
            IconButton(
                onClick = { showDeleteDialog = true },
            ) { Icon(Icons.Default.Delete, contentDescription = "通知履歴をすべて削除") }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (histories.isEmpty()) {
                item {
                    Text(
                        if (filter == HistoryFilter()) "履歴はまだありません" else "条件に一致する履歴がありません",
                        modifier = Modifier.padding(vertical = 32.dp),
                    )
                }
            } else {
                items(histories, key = NotificationHistory::id) { HistoryRow(it) }
            }
        }
    }
}

@Composable
private fun HistoryRow(history: NotificationHistory) {
    var expanded by rememberSaveable(history.id) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(history.locationNameSnapshot, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${history.transitionType.label()} ${formatEventTime(history.eventAt)}", style = MaterialTheme.typography.bodySmall)
            }
            Text(history.deliveryStatus.label(), color = history.deliveryStatus.statusColor(), style = MaterialTheme.typography.labelLarge)
        }
        if (expanded) {
            Text(history.messageSnapshot.ifBlank { "接近イベント" }, style = MaterialTheme.typography.bodyMedium)
            if (history.deviceLatitude != null && history.deviceLongitude != null) {
                val distance = com.chikabell.app.geofence.DistanceCalculator.distanceMeters(
                    history.latitudeSnapshot, history.longitudeSnapshot, history.deviceLatitude, history.deviceLongitude,
                )
                val accuracy = history.deviceAccuracyMeters?.let { " / 精度±${it.toInt()}m" }.orEmpty()
                Text("通知時端末: 登録地点から${distance.toInt()}m$accuracy", style = MaterialTheme.typography.bodySmall)
                Text(
                    "${history.deviceLatitude}, ${history.deviceLongitude} / ${history.deviceLocationAt?.let(::formatEventTime) ?: "時刻不明"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (history.deliveryStatus == DeliveryStatus.POSTED) {
                Text("通知時の端末位置を取得できませんでした", style = MaterialTheme.typography.bodySmall)
            }
            history.deliveryReason?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        HorizontalDivider()
    }
}

@Composable
private fun SettingsScreen(
    uiState: LocationsUiState,
    onRefreshPermissions: () -> Unit,
    onRequestForegroundLocation: () -> Unit,
    onRequestNotification: () -> Unit,
    onRequestActivityRecognition: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRegisterGeofences: () -> Unit,
    onRepair: () -> Unit,
    onSendTestEnterEvent: () -> Unit,
    onNavigate: (AppScreen) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { Text("設定", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item {
            SettingsSection("権限と端末設定") {
                uiState.permissionSnapshot?.let { PermissionRows(it) } ?: Text("権限状態を確認中")
                uiState.backgroundRestriction?.let { restriction ->
                    val state = when {
                        restriction.backgroundRestricted -> "制限あり"
                        restriction.ignoringBatteryOptimizations -> "最適化対象外"
                        else -> "制限なし"
                    }
                    StatusText("バッテリー", state)
                }
                uiState.activitySnapshot?.let { snapshot ->
                    StatusText("現在の活動状態", snapshot.state.label())
                    snapshot.updatedAt?.let { Text("最終更新: ${formatEventTime(it)}", style = MaterialTheme.typography.bodySmall) }
                }
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRequestForegroundLocation) { Text("位置許可") }
                    TextButton(onClick = onRequestNotification) { Text("通知許可") }
                    TextButton(onClick = onRequestActivityRecognition) { Text("活動認識許可") }
                    TextButton(onClick = onOpenAppSettings) { Text("Android設定") }
                }
            }
        }
        item {
            SettingsSection("監視状態") {
                val enabled = uiState.locations.count(SavedLocation::enabled)
                val registered = uiState.locations.count { it.enabled && it.registrationStatus == RegistrationStatus.REGISTERED }
                StatusText("有効地点", "${enabled}件")
                StatusText("登録済み", "${registered}件")
                uiState.latestRestoreAttempt?.let { attempt ->
                    StatusText("復元診断", attempt.result.label())
                    Text(
                        "${attempt.trigger.label()} / ${formatEventTime(attempt.finishedAt ?: attempt.startedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRefreshPermissions) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("状態を再確認")
                    }
                    OutlinedButton(onClick = onRepair) { Text("監視を修復") }
                }
                TextButton(enabled = !uiState.isRegisteringGeofences, onClick = onRegisterGeofences) {
                    Text(if (uiState.isRegisteringGeofences) "登録中" else "有効地点を登録")
                }
            }
        }
        uiState.registrationMessage?.let { item { FeedbackText(it) } }
        item { SettingsAction("テスト通知", onSendTestEnterEvent) }
        item { SettingsAction("通知テンプレ", { onNavigate(AppScreen.PRESETS) }) }
        item { SettingsAction("データ管理", { onNavigate(AppScreen.DATA) }) }
        item {
            SettingsSection("アプリ情報") {
                Text("地点・履歴は端末内に保存されます。Android自動バックアップは無効です。", style = MaterialTheme.typography.bodySmall)
                Text("バージョン ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
}

@Composable
private fun SettingsAction(label: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun DataManagementScreen(
    uiState: LocationsUiState,
    onBack: () -> Unit,
    onExportJson: () -> Unit,
    onExportCsv: () -> Unit,
    onImportJson: () -> Unit,
    onImportCsv: () -> Unit,
    onConfirmImport: () -> Unit,
    onCancelImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("データ管理") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                DataSection(
                    title = "バックアップ・復元",
                    description = "JSONは機種移行・復元用です。",
                    exportLabel = "JSONを書き出す",
                    importLabel = "JSONを読み込む",
                    onExport = onExportJson,
                    onImport = onImportJson,
                )
            }
            item {
                DataSection(
                    title = "CSV一括入力",
                    description = "PCで編集した地点を追加します。",
                    exportLabel = "CSVを書き出す",
                    importLabel = "CSVを読み込む",
                    onExport = onExportCsv,
                    onImport = onImportCsv,
                )
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF3EF))) {
                    Text(
                        "ファイルには緯度・経度やメモなど、位置情報や個人の記録が含まれます。安全に管理してください。",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            uiState.registrationMessage?.let { item { FeedbackText(it) } }
            uiState.importPreview?.let { preview ->
                item {
                    SettingsSection("インポートプレビュー") {
                        Text("読込 ${preview.totalCount}件 / 新規 ${preview.candidates.size}件 / 重複 ${preview.duplicateCount}件")
                        preview.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        Text("既存地点の削除・上書きは行いません。", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = preview.canApply && !uiState.isImporting, onClick = onConfirmImport) {
                                Text(if (uiState.isImporting) "追加中" else "${preview.candidates.size}件を追加")
                            }
                            OutlinedButton(onClick = onCancelImport) { Text("キャンセル") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DataSection(
    title: String,
    description: String,
    exportLabel: String,
    importLabel: String,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onExport) { Text(exportLabel) }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onImport) { Text(importLabel) }
    }
}

@Composable
private fun PermissionRows(snapshot: PermissionSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusText("通知", snapshot.notificationPermission.label())
        StatusText("位置", snapshot.foregroundLocation.label())
        StatusText("バックグラウンド位置", snapshot.backgroundLocation.label())
        StatusText("位置情報サービス", snapshot.locationServices.label())
        StatusText("Google Play services", snapshot.googlePlayServices.label())
        StatusText("活動認識", snapshot.activityRecognition.label())
    }
}

@Composable
private fun StatusText(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FeedbackText(message: String) {
    Text(
        message,
        color = if (message.contains("失敗") || message.contains("できません") || message.contains("読み込めません")) {
            MaterialTheme.colorScheme.error
        } else MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingMessage: String? = null,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        modifier = modifier,
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        supportingText = supportingMessage?.let { message ->
            { Text(message) }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

private fun NotificationPermissionStatus.label() = when (this) {
    NotificationPermissionStatus.Granted -> "許可"
    NotificationPermissionStatus.Denied -> "未許可"
    NotificationPermissionStatus.NotRequired -> "不要"
}

private fun ActivityRecognitionStatus.label() = when (this) {
    ActivityRecognitionStatus.Granted -> "許可"
    ActivityRecognitionStatus.Denied -> "未許可（速度で代替）"
    ActivityRecognitionStatus.NotRequired -> "不要"
}

private fun com.chikabell.app.geofence.DetectedMotion.label() = when (this) {
    com.chikabell.app.geofence.DetectedMotion.STILL -> "静止"
    com.chikabell.app.geofence.DetectedMotion.WALKING -> "徒歩"
    com.chikabell.app.geofence.DetectedMotion.RUNNING -> "走行"
    com.chikabell.app.geofence.DetectedMotion.ON_BICYCLE -> "自転車"
    com.chikabell.app.geofence.DetectedMotion.IN_VEHICLE -> "車両"
    com.chikabell.app.geofence.DetectedMotion.UNKNOWN -> "不明"
}

private fun ForegroundLocationStatus.label() = when (this) {
    ForegroundLocationStatus.Precise -> "正確な位置 許可"
    ForegroundLocationStatus.ApproximateOnly -> "おおよその位置のみ"
    ForegroundLocationStatus.Denied -> "未許可"
}

private fun BackgroundLocationStatus.label() = when (this) {
    BackgroundLocationStatus.Granted -> "許可"
    BackgroundLocationStatus.Denied -> "未許可"
    BackgroundLocationStatus.NotRequired -> "不要"
}

private fun LocationServicesStatus.label() = when (this) {
    LocationServicesStatus.Enabled -> "ON"
    LocationServicesStatus.Disabled -> "OFF"
}

private fun GooglePlayServicesStatus.label() = when (this) {
    GooglePlayServicesStatus.Available -> "利用可能"
    GooglePlayServicesStatus.UserResolvableError -> "更新または対応が必要"
    GooglePlayServicesStatus.Unavailable -> "利用不可"
}

private fun DeliveryStatus.label() = when (this) {
    DeliveryStatus.TRACKED -> "検証記録"
    DeliveryStatus.POSTED -> "表示済み"
    DeliveryStatus.SUPPRESSED -> "抑制"
    DeliveryStatus.FAILED -> "失敗"
}

private fun String.toDeliveryStatusOrNull(): DeliveryStatus? = when (this) {
    "表示済み" -> DeliveryStatus.POSTED
    "抑制" -> DeliveryStatus.SUPPRESSED
    "失敗" -> DeliveryStatus.FAILED
    "検証記録" -> DeliveryStatus.TRACKED
    else -> null
}

@Composable
private fun DeliveryStatus.statusColor() = when (this) {
    DeliveryStatus.FAILED -> MaterialTheme.colorScheme.error
    DeliveryStatus.TRACKED -> Color(0xFF9A6700)
    else -> MaterialTheme.colorScheme.primary
}

private fun com.chikabell.app.domain.model.TransitionType.label() = when (this) {
    com.chikabell.app.domain.model.TransitionType.ENTER -> "範囲内"
    com.chikabell.app.domain.model.TransitionType.DWELL -> "滞在通知"
    com.chikabell.app.domain.model.TransitionType.EXIT -> "範囲外"
}

private fun formatEventTime(eventAt: Long): String =
    SimpleDateFormat("MM/dd HH:mm:ss", Locale.JAPAN).format(Date(eventAt))

private fun SavedLocation.registrationLabel() = when (registrationStatus) {
    RegistrationStatus.INACTIVE -> "未登録"
    RegistrationStatus.PENDING -> "登録中"
    RegistrationStatus.REGISTERED -> "登録済み"
    RegistrationStatus.ERROR -> registrationErrorMessage ?: "エラー"
}

private fun RestoreAttemptResult.label() = when (this) {
    RestoreAttemptResult.RUNNING -> "復元中"
    RestoreAttemptResult.SUCCESS -> "正常"
    RestoreAttemptResult.SKIPPED -> "再登録不要"
    RestoreAttemptResult.RETRY -> "再試行待ち"
    RestoreAttemptResult.BLOCKED -> "設定が必要"
    RestoreAttemptResult.FAILED -> "失敗"
}

private fun RestoreTrigger.label() = when (this) {
    RestoreTrigger.BOOT -> "端末再起動"
    RestoreTrigger.PACKAGE_REPLACED -> "アプリ更新"
    RestoreTrigger.APP_START -> "起動時確認"
    RestoreTrigger.MANUAL -> "手動"
    RestoreTrigger.HEALTH_CHECK -> "定期確認"
}
