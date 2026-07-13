@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.chikabell.app.ui.locations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chikabell.app.domain.model.LocationDraft
import com.chikabell.app.domain.model.SavedLocation
import com.chikabell.app.domain.repository.LocationRepository
import com.chikabell.app.domain.repository.HistoryRepository
import com.chikabell.app.domain.repository.NotificationPresetRepository
import com.chikabell.app.domain.repository.RestoreDiagnosticsRepository
import com.chikabell.app.domain.validation.LocationValidationError
import com.chikabell.app.domain.validation.LocationValidator
import com.chikabell.app.geofence.ReconcileGeofencesUseCase
import com.chikabell.app.geofence.ProcessGeofenceEventResult
import com.chikabell.app.geofence.ProcessGeofenceEventUseCase
import com.chikabell.app.geofence.ReconcileResult
import com.chikabell.app.geofence.CheckCurrentLocationUseCase
import com.chikabell.app.geofence.CurrentLocationCheckResult
import com.chikabell.app.domain.model.RegistrationStatus
import com.chikabell.app.permission.PermissionStateReader
import com.chikabell.app.permission.BackgroundRestrictionReader
import com.chikabell.app.share.ParsedSharedPlace
import com.chikabell.app.domain.model.SourceType
import com.chikabell.app.domain.model.TagRules
import com.chikabell.app.domain.model.NotificationPreset
import com.chikabell.app.domain.model.HistoryFilter
import com.chikabell.app.BuildConfig
import com.chikabell.app.importexport.LocationImportPlanner
import com.chikabell.app.importexport.LocationTransferCodec
import com.chikabell.app.importexport.TransferFormat
import com.chikabell.app.importexport.TransferParseResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

class LocationsViewModel(
    private val repository: LocationRepository,
    private val historyRepository: HistoryRepository,
    private val presetRepository: NotificationPresetRepository,
    private val restoreDiagnosticsRepository: RestoreDiagnosticsRepository,
    private val permissionStateReader: PermissionStateReader,
    private val backgroundRestrictionReader: BackgroundRestrictionReader,
    private val reconcileGeofencesUseCase: ReconcileGeofencesUseCase,
    private val processGeofenceEventUseCase: ProcessGeofenceEventUseCase,
    private val checkCurrentLocationUseCase: CheckCurrentLocationUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LocationsUiState())
    private val historyFilter = MutableStateFlow(HistoryFilter())
    val uiState: StateFlow<LocationsUiState> = _uiState.asStateFlow()

    init {
        refreshPermissions()
        viewModelScope.launch {
            repository.observeLocations().collect { locations ->
                _uiState.update { it.copy(locations = locations) }
            }
        }
        viewModelScope.launch {
            historyFilter.flatMapLatest(historyRepository::observeHistory).collect { histories ->
                _uiState.update { it.copy(histories = histories) }
            }
        }
        viewModelScope.launch {
            presetRepository.observePresets().collect { presets ->
                _uiState.update { it.copy(presets = presets) }
            }
        }
        viewModelScope.launch {
            restoreDiagnosticsRepository.observeLatest().collect { attempt ->
                _uiState.update { it.copy(latestRestoreAttempt = attempt) }
            }
        }
    }

    fun updateHistoryFilter(filter: HistoryFilter) {
        historyFilter.value = filter
        _uiState.update { it.copy(historyFilter = filter) }
    }

    fun deleteAllHistory() {
        viewModelScope.launch {
            historyRepository.deleteAllHistory()
        }
    }

    fun updateForm(form: LocationFormState) {
        _uiState.update {
            it.copy(
                form = form,
                validationMessage = null,
            )
        }
    }

    fun applySharedPlace(place: ParsedSharedPlace) {
        _uiState.update {
            it.copy(
                editingLocation = null,
                form = LocationFormState(
                    name = place.nameCandidate,
                    message = place.nameCandidate,
                    latitude = place.latitude?.toString().orEmpty(),
                    longitude = place.longitude?.toString().orEmpty(),
                    sourceType = SourceType.MAP_SHARE,
                    sourceUrl = place.sourceUrl,
                    sourceText = place.rawText,
                    tags = emptyList(),
                ),
                validationMessage = place.warnings.firstOrNull(),
                registrationMessage = if (place.hasCoordinates) "共有内容から座標を入力しました。保存前に確認してください" else null,
            )
        }
    }

    fun buildJsonExport(): String = LocationTransferCodec.exportJson(
        locations = _uiState.value.locations,
        exportedAt = System.currentTimeMillis(),
        appVersion = BuildConfig.VERSION_NAME,
    )

    fun buildCsvExport(): String = LocationTransferCodec.exportCsv(_uiState.value.locations)

    fun reportTransferMessage(message: String) {
        _uiState.update { it.copy(registrationMessage = message) }
    }

    fun previewImport(content: String, format: TransferFormat) {
        val parsed = when (format) {
            TransferFormat.JSON -> LocationTransferCodec.parseJson(content)
            TransferFormat.CSV -> LocationTransferCodec.parseCsv(content)
        }
        when (parsed) {
            is TransferParseResult.Failure -> _uiState.update {
                it.copy(importPreview = null, registrationMessage = "インポートできません: ${parsed.message}")
            }
            is TransferParseResult.Success -> {
                val preview = LocationImportPlanner.preview(parsed.records, _uiState.value.locations, format)
                _uiState.update { it.copy(importPreview = preview, registrationMessage = null) }
            }
        }
    }

    fun cancelImport() {
        _uiState.update { it.copy(importPreview = null) }
    }

    fun confirmImport() {
        val preview = _uiState.value.importPreview ?: return
        if (!preview.canApply) return
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true) }
            runCatching {
                repository.addImportedLocations(preview.candidates)
                reconcileGeofencesUseCase.execute()
            }.onSuccess { reconcileResult ->
                val message = when (reconcileResult) {
                    is ReconcileResult.Registered -> "${preview.candidates.size}件を追加し、${reconcileResult.count}件の監視設定を更新しました"
                    is ReconcileResult.Blocked -> "${preview.candidates.size}件を追加しました。監視登録できません: ${reconcileResult.reason}"
                    is ReconcileResult.Failed -> "${preview.candidates.size}件を追加しました。監視登録に失敗しました: ${reconcileResult.message}"
                }
                _uiState.update {
                    it.copy(
                        importPreview = null,
                        isImporting = false,
                        registrationMessage = message,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isImporting = false, registrationMessage = "インポートに失敗しました: ${error.message ?: "不明なエラー"}")
                }
            }
        }
    }

    fun startEdit(location: SavedLocation) {
        _uiState.update {
            it.copy(
                editingLocation = location,
                form = LocationFormState(
                    name = location.name,
                    message = location.message,
                    latitude = location.latitude.toString(),
                    longitude = location.longitude.toString(),
                    radiusMeters = location.radiusMeters.toString(),
                    loiteringDelaySeconds = ((location.loiteringDelayMs ?: 60_000) / 1_000).toString(),
                    cooldownHours = CooldownHours.format(location.cooldownMinutes),
                    enabled = location.enabled,
                    sourceType = location.sourceType,
                    sourceUrl = location.sourceUrl,
                    sourceText = location.sourceText,
                    tags = location.tags.map { tag -> tag.name },
                ),
                validationMessage = null,
            )
        }
    }

    fun cancelEdit() {
        _uiState.update {
            it.copy(
                editingLocation = null,
                form = LocationFormState(),
                validationMessage = null,
            )
        }
    }

    fun applyPreset(presetId: String) {
        val preset = _uiState.value.presets.firstOrNull { it.id == presetId } ?: return
        _uiState.update {
            it.copy(
                selectedPresetId = presetId,
                form = it.form.copy(
                    radiusMeters = preset.radiusMeters.toString(),
                    loiteringDelaySeconds = preset.loiteringDelaySeconds.toString(),
                    cooldownHours = CooldownHours.format(preset.cooldownMinutes),
                ),
                validationMessage = null,
            )
        }
    }

    fun saveSelectedPreset(name: String) {
        val state = _uiState.value
        val preset = state.presets.firstOrNull { it.id == state.selectedPresetId } ?: return
        val normalizedName = name.trim()
        if (normalizedName.isBlank() || normalizedName.length > 20) {
            _uiState.update { it.copy(validationMessage = "テンプレ名は1〜20文字で入力してください") }
            return
        }
        if (state.presets.any { it.id != preset.id && it.name.equals(normalizedName, ignoreCase = true) }) {
            _uiState.update { it.copy(validationMessage = "同じ名前のテンプレがあります") }
            return
        }
        val radius = state.form.radiusMeters.toIntOrNull()
        val dwell = state.form.loiteringDelaySeconds.toIntOrNull()
        val cooldown = CooldownHours.parse(state.form.cooldownHours)
        if (
            radius == null || radius !in LocationValidator.MIN_RADIUS_METERS..LocationValidator.MAX_RADIUS_METERS ||
            dwell == null || dwell !in LocationValidator.MIN_LOITERING_SECONDS..LocationValidator.MAX_LOITERING_SECONDS ||
            cooldown == null || cooldown !in 0..LocationValidator.MAX_COOLDOWN_MINUTES
        ) {
            _uiState.update { it.copy(validationMessage = "テンプレ値の数値を確認してください") }
            return
        }
        viewModelScope.launch {
            presetRepository.savePreset(
                preset.copy(
                    name = normalizedName,
                    radiusMeters = radius,
                    loiteringDelaySeconds = dwell,
                    cooldownMinutes = cooldown,
                ),
            )
            _uiState.update { it.copy(registrationMessage = "${normalizedName}テンプレを保存しました", validationMessage = null) }
        }
    }

    fun resetSelectedPreset() {
        val state = _uiState.value
        val defaults = defaultPresets[state.selectedPresetId] ?: return
        viewModelScope.launch {
            presetRepository.savePreset(defaults)
            _uiState.update {
                it.copy(
                    form = it.form.copy(
                        radiusMeters = defaults.radiusMeters.toString(),
                        loiteringDelaySeconds = defaults.loiteringDelaySeconds.toString(),
                        cooldownHours = CooldownHours.format(defaults.cooldownMinutes),
                    ),
                    validationMessage = null,
                    registrationMessage = "${defaults.name}テンプレを初期値へ戻しました",
                )
            }
        }
    }

    private val defaultPresets = listOf(
        NotificationPreset("walk", "徒歩", 300, 60, 720, 0),
        NotificationPreset("early_walk", "徒歩早め", 400, 60, 720, 1),
        NotificationPreset("bicycle", "自転車", 500, 45, 720, 2),
        NotificationPreset("car", "車", 1000, 30, 720, 3),
        NotificationPreset("custom", "カスタム", 300, 60, 720, 4),
    ).associateBy(NotificationPreset::id)

    fun save() {
        val state = _uiState.value
        val draft = state.form.toDraftOrNull()
        if (draft == null) {
            _uiState.update { it.copy(validationMessage = "数値の入力を確認してください") }
            return
        }
        if (draft.tags.size > TagRules.MAX_TAGS_PER_LOCATION) {
            _uiState.update { it.copy(validationMessage = "タグは1地点に${TagRules.MAX_TAGS_PER_LOCATION}個までです") }
            return
        }

        val errors = LocationValidator.validate(draft)
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(validationMessage = errors.toMessage()) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            runCatching {
                val editing = state.editingLocation
                if (editing == null) {
                    repository.addLocation(draft)
                    when (val result = reconcileGeofencesUseCase.execute()) {
                        is ReconcileResult.Registered -> "保存し、${result.count}件の監視設定を更新しました"
                        is ReconcileResult.Blocked -> "保存しました。監視登録できません: ${result.reason}"
                        is ReconcileResult.Failed -> "保存しました。監視登録に失敗しました: ${result.message}"
                    }
                } else if (editing.hasSameMonitoringFields(draft)) {
                    repository.updateLocationTags(editing, draft.tags)
                    "タグを保存しました"
                } else {
                    repository.updateLocation(editing, draft)
                    when (val result = reconcileGeofencesUseCase.execute()) {
                        is ReconcileResult.Registered -> "保存し、${result.count}件の監視設定を更新しました"
                        is ReconcileResult.Blocked -> "保存しました。監視登録できません: ${result.reason}"
                        is ReconcileResult.Failed -> "保存しました。監視登録に失敗しました: ${result.message}"
                    }
                }
            }.onSuccess { registrationMessage ->
                _uiState.update {
                    it.copy(
                        form = LocationFormState(),
                        editingLocation = null,
                        validationMessage = null,
                        isSaving = false,
                        registrationMessage = registrationMessage,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        validationMessage = error.message ?: "保存に失敗しました",
                        isSaving = false,
                    )
                }
            }
        }
    }

    fun delete(location: SavedLocation) {
        viewModelScope.launch {
            repository.deleteLocation(location)
            val message = when (val result = reconcileGeofencesUseCase.execute()) {
                is ReconcileResult.Registered -> "削除し、${result.count}件の監視設定を更新しました"
                is ReconcileResult.Blocked -> "削除しました。監視登録できません: ${result.reason}"
                is ReconcileResult.Failed -> "削除しました。監視登録に失敗しました: ${result.message}"
            }
            _uiState.update { it.copy(registrationMessage = message) }
        }
    }

    fun deleteSelected(ids: Set<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.deleteLocations(ids)
            val message = when (val result = reconcileGeofencesUseCase.execute()) {
                is ReconcileResult.Registered -> "${ids.size}件を削除し、${result.count}件の監視設定を更新しました"
                is ReconcileResult.Blocked -> "${ids.size}件を削除しました。監視登録できません: ${result.reason}"
                is ReconcileResult.Failed -> "${ids.size}件を削除しました。監視登録に失敗しました: ${result.message}"
            }
            _uiState.update { it.copy(registrationMessage = message) }
        }
    }

    fun setLocationsEnabled(ids: Set<String>, enabled: Boolean) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.setLocationsEnabled(ids, enabled)
            val message = when (val result = reconcileGeofencesUseCase.execute()) {
                is ReconcileResult.Registered -> "${ids.size}件を${if (enabled) "有効" else "無効"}にし、${result.count}件の監視設定を更新しました"
                is ReconcileResult.Blocked -> "${ids.size}件を更新しました。監視登録できません: ${result.reason}"
                is ReconcileResult.Failed -> "${ids.size}件を更新しました。監視登録に失敗しました: ${result.message}"
            }
            _uiState.update { it.copy(registrationMessage = message) }
        }
    }

    fun refreshPermissions() {
        _uiState.update {
            it.copy(
                permissionSnapshot = permissionStateReader.read(),
                backgroundRestriction = backgroundRestrictionReader.read(),
            )
        }
    }

    fun registerActiveGeofences() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRegisteringGeofences = true,
                    registrationMessage = null,
                    permissionSnapshot = permissionStateReader.read(),
                )
            }

            val message = when (val result = reconcileGeofencesUseCase.execute()) {
                is ReconcileResult.Registered -> "${result.count}件の地点を監視登録しました"
                is ReconcileResult.Blocked -> "監視登録できません: ${result.reason}"
                is ReconcileResult.Failed -> "監視登録に失敗しました: ${result.message}"
            }

            _uiState.update {
                it.copy(
                    isRegisteringGeofences = false,
                    registrationMessage = message,
                    permissionSnapshot = permissionStateReader.read(),
                )
            }
        }
    }

    fun sendTestEnterEvent() {
        val target = _uiState.value.locations.firstOrNull {
            it.enabled && it.registrationStatus == RegistrationStatus.REGISTERED
        }
        if (target == null) {
            _uiState.update { it.copy(registrationMessage = "登録済みの有効地点がありません") }
            return
        }

        viewModelScope.launch {
            val message = when (val result = processGeofenceEventUseCase.execute(
                requestId = target.id,
                transitionType = target.transitionType,
                eventAt = System.currentTimeMillis(),
            )) {
                ProcessGeofenceEventResult.NotificationPosted -> "テスト通知を送信しました"
                is ProcessGeofenceEventResult.HistorySavedWithoutNotification ->
                    "履歴を保存しました: ${result.reason}"
                is ProcessGeofenceEventResult.Ignored -> "テストイベントを無視しました: ${result.reason}"
            }
            _uiState.update { it.copy(registrationMessage = message) }
        }
    }

    fun checkCurrentLocation() {
        viewModelScope.launch {
            _uiState.update { it.copy(registrationMessage = "現在地を確認中です") }

            val message = when (val result = checkCurrentLocationUseCase.execute()) {
                CurrentLocationCheckResult.LocationUnavailable ->
                    "現在地を取得できませんでした"
                is CurrentLocationCheckResult.NoRegisteredLocations ->
                    "登録済みの有効地点がありません"
                is CurrentLocationCheckResult.OutsideRegisteredAreas -> {
                    val distanceText = result.nearestDistanceMeters?.let { "${it.toInt()}m" } ?: "不明"
                    "登録地点の半径外です。最寄りまで約$distanceText (${result.currentLocation.provider})"
                }
                is CurrentLocationCheckResult.InsideRegisteredAreas ->
                    "${result.count}件の登録地点内です。地点ごとの滞在時間後に通知対象です (${result.currentLocation.provider})"
            }

            _uiState.update { it.copy(registrationMessage = message) }
        }
    }
}

private fun LocationFormState.toDraftOrNull(): LocationDraft? {
    return LocationDraft(
        name = name,
        message = message,
        latitude = latitude.toDoubleOrNull() ?: return null,
        longitude = longitude.toDoubleOrNull() ?: return null,
        radiusMeters = radiusMeters.toIntOrNull() ?: return null,
        loiteringDelaySeconds = loiteringDelaySeconds.toIntOrNull() ?: return null,
        cooldownMinutes = CooldownHours.parse(cooldownHours) ?: return null,
        enabled = enabled,
        sourceType = sourceType,
        sourceUrl = sourceUrl,
        sourceText = sourceText,
        tags = TagRules.sanitizeNames(tags),
    )
}

private fun SavedLocation.hasSameMonitoringFields(draft: LocationDraft): Boolean {
    return name.trim() == draft.name.trim() &&
        message.trim() == draft.message.trim() &&
        latitude == draft.latitude &&
        longitude == draft.longitude &&
        radiusMeters == draft.radiusMeters &&
        (loiteringDelayMs ?: 60_000) == draft.loiteringDelaySeconds * 1_000 &&
        cooldownMinutes == draft.cooldownMinutes &&
        enabled == draft.enabled &&
        sourceType == draft.sourceType &&
        sourceUrl == draft.sourceUrl &&
        sourceText == draft.sourceText
}

private fun List<LocationValidationError>.toMessage(): String {
    return joinToString(separator = "\n") { error ->
        when (error) {
            LocationValidationError.Name -> "名前は1文字以上100文字以内で入力してください"
            LocationValidationError.Message -> "メモは500文字以内で入力してください"
            LocationValidationError.Latitude -> "緯度は-90から90で入力してください"
            LocationValidationError.Longitude -> "経度は-180から180で入力してください"
            LocationValidationError.Radius -> "半径は100mから5000mで入力してください"
            LocationValidationError.LoiteringDelay -> "滞在秒数は15秒から3600秒で入力してください"
            LocationValidationError.Cooldown -> "再通知間隔は0時間から720時間まで、0.5時間単位で入力してください"
        }
    }
}
