@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.chikabell.app.ui.locations

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.chikabell.app.BuildConfig
import com.chikabell.app.domain.model.DeliveryStatus
import com.chikabell.app.domain.model.NotificationHistory
import com.chikabell.app.domain.model.HistoryFilter
import com.chikabell.app.domain.model.HistoryPeriod
import com.chikabell.app.domain.model.RegistrationStatus
import com.chikabell.app.domain.model.RestoreAttemptResult
import com.chikabell.app.domain.model.RestoreTrigger
import com.chikabell.app.domain.model.SavedLocation
import com.chikabell.app.domain.model.SourceType
import com.chikabell.app.domain.model.TagRules
import com.chikabell.app.permission.BackgroundLocationStatus
import com.chikabell.app.permission.ForegroundLocationStatus
import com.chikabell.app.permission.GooglePlayServicesStatus
import com.chikabell.app.permission.LocationServicesStatus
import com.chikabell.app.permission.NotificationPermissionStatus
import com.chikabell.app.permission.PermissionSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class AppScreen { LOCATIONS, HISTORY, SETTINGS, LOCATION_EDIT, PRESETS, DATA }

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
    onCancelEdit: () -> Unit,
    onRefreshPermissions: () -> Unit,
    onRequestForegroundLocation: () -> Unit,
    onRequestNotification: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRegisterGeofences: () -> Unit,
    onRestoreGeofences: () -> Unit,
    onSendTestEnterEvent: () -> Unit,
    onCheckCurrentLocation: () -> Unit,
    onExportJson: () -> Unit,
    onExportCsv: () -> Unit,
    onImportJson: () -> Unit,
    onImportCsv: () -> Unit,
    onConfirmImport: () -> Unit,
    onCancelImport: () -> Unit,
    onHistoryFilterChange: (HistoryFilter) -> Unit,
    onDeleteAllHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var screenName by rememberSaveable { mutableStateOf(AppScreen.LOCATIONS.name) }
    val screen = AppScreen.valueOf(screenName)
    var saveRequested by rememberSaveable { mutableStateOf(false) }
    var showRepairDialog by rememberSaveable { mutableStateOf(false) }
    var continuousEditIds by remember { mutableStateOf(emptyList<String>()) }
    var continuousEditIndex by rememberSaveable { mutableStateOf(0) }

    fun navigate(next: AppScreen) { screenName = next.name }
    fun navigateHome() {
        onCancelEdit()
        screenName = AppScreen.LOCATIONS.name
    }

    BackHandler(enabled = screen !in listOf(AppScreen.LOCATIONS, AppScreen.HISTORY, AppScreen.SETTINGS)) {
        when (screen) {
            AppScreen.PRESETS, AppScreen.DATA -> navigate(AppScreen.SETTINGS)
            AppScreen.LOCATION_EDIT -> navigateHome()
            else -> navigateHome()
        }
    }

    LaunchedEffect(uiState.form.sourceType, uiState.form.sourceUrl) {
        if (uiState.form.sourceType == SourceType.MAP_SHARE &&
            (uiState.form.name.isNotBlank() || uiState.form.sourceUrl != null)
        ) {
            navigate(AppScreen.LOCATION_EDIT)
        }
    }
    LaunchedEffect(uiState.isSaving, uiState.editingLocation, uiState.form.name) {
        if (saveRequested && !uiState.isSaving && uiState.editingLocation == null && uiState.form.name.isBlank()) {
            saveRequested = false
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
            onStartContinuousEdit = { locations ->
                if (locations.isNotEmpty()) {
                    continuousEditIds = locations.map(SavedLocation::id)
                    continuousEditIndex = 0
                    onEdit(locations.first())
                    navigate(AppScreen.LOCATION_EDIT)
                }
            },
            onFormChange = onFormChange,
            onCheckCurrentLocation = onCheckCurrentLocation,
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
            onSave = {
                saveRequested = true
                onSave()
            },
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
    onStartContinuousEdit: (List<SavedLocation>) -> Unit,
    onFormChange: (LocationFormState) -> Unit,
    onCheckCurrentLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var descending by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SavedLocation?>(null) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showBatchDeleteDialog by rememberSaveable { mutableStateOf(false) }
    val filtered = remember(uiState.locations, query, descending) {
        val normalizedQuery = TagRules.normalize(query)
        val tagOnly = query.trim().startsWith("#")
        uiState.locations
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
            .let { if (descending) it.sortedByDescending(SavedLocation::updatedAt) else it.sortedBy(SavedLocation::sortOrder) }
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
                    OutlinedButton(onClick = { descending = !descending }) {
                        Text(if (descending) "更新順" else "登録順")
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
            TextButton(onClick = onCheckCurrentLocation) { Text("現在地をチェック") }
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
                            selected = location.id in selectedIds,
                            onSelectedChange = { selected ->
                                selectedIds = if (selected) selectedIds + location.id else selectedIds - location.id
                            },
                            onEnabledChange = { enabled -> onSetLocationsEnabled(setOf(location.id), enabled) },
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
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
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
                Text(location.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (location.message.isNotBlank()) {
                    Text(
                        location.message,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LocationNotificationSummary(location = location, onEdit = onEdit)
                if (location.tags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        location.tags.forEach { tag ->
                            AssistChip(onClick = onEdit, label = { Text("#${tag.name}") })
                        }
                    }
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
private fun LocationNotificationSummary(location: SavedLocation, onEdit: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(
            text = "半径: ${location.radiusMeters}m　滞在: ${(location.loiteringDelayMs ?: 60_000) / 1_000}秒　再通知: ${CooldownHours.format(location.cooldownMinutes)}時間",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LocationEditScreen(
    uiState: LocationsUiState,
    onBack: () -> Unit,
    onFormChange: (LocationFormState) -> Unit,
    onApplyPreset: (String) -> Unit,
    onSave: () -> Unit,
    continuousProgress: Pair<Int, Int>?,
    modifier: Modifier = Modifier,
) {
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.editingLocation == null) "地点を追加" else "地点を編集") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") } },
            )
        },
        bottomBar = {
            Button(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                enabled = !uiState.isSaving,
                onClick = onSave,
            ) { Text(if (uiState.isSaving) "保存中" else "保存") }
        },
    ) { padding ->
        val form = uiState.form
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
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            continuousProgress?.let { (current, total) ->
                item { Text("連続編集 $current/$total 件目", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
            }
            item {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(), value = form.name,
                    onValueChange = { onFormChange(form.copy(name = it)) }, label = { Text("名前") }, singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(), value = form.message,
                    onValueChange = { onFormChange(form.copy(message = it)) }, label = { Text("メモ（任意）") }, minLines = 2,
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("タグ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (form.tags.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            form.tags.forEach { tag ->
                                AssistChip(onClick = { removeTag(tag) }, label = { Text("#$tag ×") })
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            modifier = Modifier.weight(1f),
                            value = form.tagInput,
                            onValueChange = { onFormChange(form.copy(tagInput = it.take(TagRules.MAX_TAG_NAME_LENGTH + 1))) },
                            label = { Text("タグを追加（例: #会社）") },
                            singleLine = true,
                            supportingText = { Text("${form.tags.size}/${TagRules.MAX_TAGS_PER_LOCATION}個・全体${TagRules.MAX_TAGS_TOTAL}個まで") },
                        )
                        OutlinedButton(
                            enabled = form.tagInput.isNotBlank() && form.tags.size < TagRules.MAX_TAGS_PER_LOCATION,
                            onClick = { addTag(form.tagInput) },
                        ) { Text("追加") }
                    }
                    if (tagSuggestions.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            tagSuggestions.forEach { tag ->
                                AssistChip(onClick = { addTag(tag.name) }, label = { Text("#${tag.name} ${tag.usageCount}件") })
                            }
                        }
                    }
                }
            }
            item {
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
                        )
                    }
                }
            }
            item { Text("通知の設定", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField(form.radiusMeters, { onFormChange(form.copy(radiusMeters = it)) }, "半径m", Modifier.weight(1f))
                    NumberField(form.loiteringDelaySeconds, { onFormChange(form.copy(loiteringDelaySeconds = it)) }, "滞在秒", Modifier.weight(1f))
                }
            }
            item { NumberField(form.cooldownHours, { onFormChange(form.copy(cooldownHours = it)) }, "再通知時間（0.5時間単位）", Modifier.fillMaxWidth()) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("この地点を有効にする")
                        Text("無効にすると監視対象から外れます", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = form.enabled, onCheckedChange = { onFormChange(form.copy(enabled = it)) })
                }
            }
            item {
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { detailsExpanded = !detailsExpanded }) {
                    Text(if (detailsExpanded) "位置の詳細を閉じる" else "位置の詳細（緯度・経度）")
                }
            }
            if (detailsExpanded) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumberField(form.latitude, { onFormChange(form.copy(latitude = it)) }, "緯度", Modifier.weight(1f))
                        NumberField(form.longitude, { onFormChange(form.copy(longitude = it)) }, "経度", Modifier.weight(1f))
                    }
                }
            }
            uiState.validationMessage?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        }
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
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRequestForegroundLocation) { Text("位置許可") }
                    TextButton(onClick = onRequestNotification) { Text("通知許可") }
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
) {
    OutlinedTextField(
        modifier = modifier,
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

private fun NotificationPermissionStatus.label() = when (this) {
    NotificationPermissionStatus.Granted -> "許可"
    NotificationPermissionStatus.Denied -> "未許可"
    NotificationPermissionStatus.NotRequired -> "不要"
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
