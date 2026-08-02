@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.chikabell.app.ui.locations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
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
import com.chikabell.app.geofence.ProcessGeofenceEventUseCase
import com.chikabell.app.geofence.ReconcileResult
import com.chikabell.app.geofence.CheckCurrentLocationUseCase
import com.chikabell.app.geofence.CurrentLocationCheckResult
import com.chikabell.app.geofence.FindNearbySavedLocationsUseCase
import com.chikabell.app.geofence.NearbyDistanceFilter
import com.chikabell.app.geofence.NearbySavedLocationsResult
import com.chikabell.app.notification.SendTestNotificationUseCase
import com.chikabell.app.notification.TestNotificationResult
import com.chikabell.app.permission.PermissionStateReader
import com.chikabell.app.permission.BackgroundRestrictionReader
import com.chikabell.app.share.ParsedSharedPlace
import com.chikabell.app.share.SharedPlaceEvent
import com.chikabell.app.share.SharedPlaceConfidence
import com.chikabell.app.share.SharedPlaceParser
import com.chikabell.app.share.SharedPlaceParseMethod
import com.chikabell.app.share.CoordinateCandidate
import com.chikabell.app.share.CoordinateEvidenceFamily
import com.chikabell.app.share.CoordinateSemanticRole
import com.chikabell.app.share.CandidateReliability
import com.chikabell.app.share.AddressCandidateProvider
import com.chikabell.app.share.UnavailableAddressCandidateProvider
import com.chikabell.app.domain.model.SourceType
import com.chikabell.app.domain.model.TagRules
import com.chikabell.app.domain.model.NotificationPreset
import com.chikabell.app.domain.model.NearbyState
import com.chikabell.app.geofence.NearbyVerificationPolicy
import com.chikabell.app.geofence.ActivityStateSource
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID
import com.chikabell.app.permission.ForegroundLocationStatus
import com.chikabell.app.permission.LocationServicesStatus

internal fun sharedCoordinatesManuallyEdited(
    place: ParsedSharedPlace?,
    form: LocationFormState,
): Boolean {
    val latitude = form.latitude.toDoubleOrNull() ?: return false
    val longitude = form.longitude.toDoubleOrNull() ?: return false
    val selectedCandidate = place?.selectedCandidateIndex?.let { place.candidates.getOrNull(it) }
    return selectedCandidate == null ||
        latitude != selectedCandidate.latitude ||
        longitude != selectedCandidate.longitude
}

internal fun sharedPlaceRequiresConfirmation(
    place: ParsedSharedPlace?,
    candidateConfirmed: Boolean,
    coordinatesManuallyEdited: Boolean,
): Boolean = place?.requiresUserConfirmation == true &&
    !candidateConfirmed &&
    !coordinatesManuallyEdited

internal enum class LocationSaveBlockReason {
    MISSING_COORDINATES,
    INVALID_COORDINATES,
    CANDIDATE_CONFIRMATION_REQUIRED,
}

internal fun locationSaveBlockReason(
    form: LocationFormState,
    place: ParsedSharedPlace?,
    candidateConfirmed: Boolean,
    coordinatesManuallyEdited: Boolean,
): LocationSaveBlockReason? {
    if (form.latitude.isBlank() || form.longitude.isBlank()) {
        return LocationSaveBlockReason.MISSING_COORDINATES
    }
    val latitude = form.latitude.toDoubleOrNull()
    val longitude = form.longitude.toDoubleOrNull()
    if (
        latitude == null ||
        longitude == null ||
        !latitude.isFinite() ||
        !longitude.isFinite() ||
        latitude !in -90.0..90.0 ||
        longitude !in -180.0..180.0
    ) {
        return LocationSaveBlockReason.INVALID_COORDINATES
    }
    if (
        sharedPlaceRequiresConfirmation(
            place = place,
            candidateConfirmed = candidateConfirmed,
            coordinatesManuallyEdited = coordinatesManuallyEdited,
        )
    ) {
        return LocationSaveBlockReason.CANDIDATE_CONFIRMATION_REQUIRED
    }
    return null
}

internal fun mergeResolvedSharedPlaceForm(
    existingForm: LocationFormState?,
    place: ParsedSharedPlace,
    preserveManualCoordinates: Boolean,
    preserveSearchInput: Boolean = false,
): LocationFormState {
    val sharedLabel = sharedPlaceDraftLabel(place)
    return (existingForm ?: LocationFormState()).copy(
        name = existingForm?.name?.takeIf(String::isNotBlank) ?: sharedLabel,
        message = existingForm?.message.orEmpty(),
        address = sharedPlaceSearchInput(
            existingForm = existingForm,
            place = place,
            preserveSearchInput = preserveSearchInput,
        ),
        latitude = if (preserveManualCoordinates) existingForm?.latitude.orEmpty() else place.latitude?.toString().orEmpty(),
        longitude = if (preserveManualCoordinates) existingForm?.longitude.orEmpty() else place.longitude?.toString().orEmpty(),
        sourceType = SourceType.MAP_SHARE,
        sourceUrl = place.sourceUrl,
        sourceText = place.rawText,
    )
}

internal fun sharedPlaceDraftLabel(place: ParsedSharedPlace): String =
    place.nameCandidate
        .trim()
        .takeUnless(SharedPlaceParser::isCoordinateOnlyLabel)
        .orEmpty()

internal fun LocationFormState.hasUserAuthoredLocationDraft(): Boolean {
    val defaults = LocationFormState()
    return name.isNotBlank() ||
        message.isNotBlank() ||
        address.isNotBlank() ||
        latitude.isNotBlank() ||
        longitude.isNotBlank() ||
        tags.isNotEmpty() ||
        tagInput.isNotBlank() ||
        radiusMeters != defaults.radiusMeters ||
        loiteringDelaySeconds != defaults.loiteringDelaySeconds ||
        cooldownHours != defaults.cooldownHours ||
        enabled != defaults.enabled
}

private fun sharedPlaceSearchInput(
    existingForm: LocationFormState?,
    place: ParsedSharedPlace,
    preserveSearchInput: Boolean,
): String {
    val current = existingForm?.address.orEmpty()
    if (current.isNotBlank() || preserveSearchInput) return current
    if (place.hasCoordinates || place.confidence == SharedPlaceConfidence.RESOLVING) return current
    return sequenceOf(existingForm?.message, place.nameCandidate, existingForm?.name)
        .mapNotNull { it?.trim()?.replace(Regex("\\s+"), " ") }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
        .take(256)
}

internal fun automaticAddressSearchQuery(
    previous: LocationsUiState,
    current: LocationsUiState,
    event: SharedPlaceEvent,
): String? {
    if (event.place.hasCoordinates || event.place.confidence == SharedPlaceConfidence.RESOLVING) return null
    if (event.eventId in previous.sharedRegistrationSession?.handledTerminalEventIds.orEmpty()) return null
    val session = current.sharedRegistrationSession ?: return null
    if (session.activeEventId != event.eventId || session.pendingIncomingShare != null) return null
    if (RegistrationField.ADDRESS in session.userEditedFields) return null
    if (current.sharedPlace?.hasCoordinates != false) return null
    return current.form.address.trim().takeIf { it.length >= 3 }
}

class LocationsViewModel(
    private val repository: LocationRepository,
    private val historyRepository: HistoryRepository,
    private val presetRepository: NotificationPresetRepository,
    private val restoreDiagnosticsRepository: RestoreDiagnosticsRepository,
    private val permissionStateReader: PermissionStateReader,
    private val backgroundRestrictionReader: BackgroundRestrictionReader,
    private val reconcileGeofencesUseCase: ReconcileGeofencesUseCase,
    private val processGeofenceEventUseCase: ProcessGeofenceEventUseCase,
    private val sendTestNotificationUseCase: SendTestNotificationUseCase,
    private val checkCurrentLocationUseCase: CheckCurrentLocationUseCase,
    private val findNearbySavedLocationsUseCase: FindNearbySavedLocationsUseCase,
    private val activityStateSource: ActivityStateSource,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    private val addressCandidateProvider: AddressCandidateProvider = UnavailableAddressCandidateProvider,
) : ViewModel() {
    private val restoredRegistration =
        SharedRegistrationSessionCodec.decode(savedStateHandle[SHARED_REGISTRATION_SNAPSHOT_KEY])
    private val _uiState = MutableStateFlow(
        restoredRegistration?.let { restored ->
            LocationsUiState(
                selectedPresetId = restored.selectedPresetId,
                form = restored.form,
                sharedPlace = restored.place,
                sharedPlaceCandidateConfirmed = restored.candidateConfirmed,
                sharedPlaceCoordinatesManuallyEdited = restored.coordinatesManuallyEdited,
                sharedRegistrationSession = restored.session,
            )
        } ?: LocationsUiState(),
    )
    private val historyFilter = MutableStateFlow(HistoryFilter())
    val uiState: StateFlow<LocationsUiState> = _uiState.asStateFlow()
    private var nearbySearchJob: Job? = null
    private var addressSearchJob: Job? = null
    private var addressSearchGeneration: String? = null

    init {
        val repairedRegistrationState = repairInterruptedSharedResolution(_uiState.value)
        if (repairedRegistrationState != _uiState.value) {
            mutateRegistrationState { repairedRegistrationState }
        }
        refreshPermissions()
        viewModelScope.launch {
            repository.observeLocations().collect { locations ->
                _uiState.update { it.copy(locations = locations) }
                if (locations.any { it.snoozedUntil != null && it.snoozedUntil <= System.currentTimeMillis() }) {
                    repository.refreshExpiredSnoozes()
                }
                if (locations.any {
                        it.nearbyState == NearbyState.VERIFYING &&
                            (it.lastVerificationAt == null ||
                                it.lastVerificationAt <= System.currentTimeMillis() - NearbyVerificationPolicy.MAX_VERIFICATION_SESSION_MILLIS)
                    }
                ) {
                    repository.recoverStaleVerifications()
                }
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

    fun clearSnooze(locationId: String) {
        viewModelScope.launch {
            repository.clearSnooze(locationId)
            _uiState.update { it.copy(registrationMessage = "12時間休止を解除しました") }
        }
    }

    fun updateForm(form: LocationFormState) {
        val addressChanged = form.address != _uiState.value.form.address
        if (addressChanged) {
            addressSearchGeneration = null
            addressSearchJob?.cancel()
        }
        mutateRegistrationState { state ->
            val coordinatesChanged = form.latitude != state.form.latitude || form.longitude != state.form.longitude
            val manuallyEdited = if (!coordinatesChanged) {
                state.sharedPlaceCoordinatesManuallyEdited
            } else {
                sharedCoordinatesManuallyEdited(state.sharedPlace, form)
            }
            val session = state.sharedRegistrationSession?.copy(
                userEditedFields = state.sharedRegistrationSession.userEditedFields +
                    changedRegistrationFields(state.form, form),
            )
            state.copy(
                form = form,
                validationMessage = null,
                sharedPlaceCandidateConfirmed = if (coordinatesChanged) false else state.sharedPlaceCandidateConfirmed,
                sharedPlaceCoordinatesManuallyEdited = manuallyEdited,
                sharedRegistrationSession = session,
                isAddressSearching = if (addressChanged) false else state.isAddressSearching,
                addressCandidates = if (addressChanged) emptyList() else state.addressCandidates,
                addressSearchMessage = if (addressChanged) null else state.addressSearchMessage,
            )
        }
    }

    fun applySharedPlace(event: SharedPlaceEvent) {
        addressSearchGeneration = null
        addressSearchJob?.cancel()
        val previous = _uiState.value
        mutateRegistrationState { state -> reduceSharedPlaceEvent(state, event) }
        if (automaticAddressSearchQuery(previous, _uiState.value, event) != null) {
            searchAddressCandidates(automatic = true)
        }
    }

    fun mergePendingSharedPlace() {
        mutateRegistrationState(::mergePendingSharedPlaceState)
    }

    fun startNewFromPendingSharedPlace() {
        mutateRegistrationState { it.copy(showStartNewRegistrationDialog = true) }
    }

    fun dismissStartNewRegistration() {
        mutateRegistrationState { it.copy(showStartNewRegistrationDialog = false) }
    }

    fun confirmStartNewFromPendingSharedPlace() {
        val pending = _uiState.value.sharedRegistrationSession?.pendingIncomingShare ?: return
        clearRegistrationSnapshot()
        _uiState.update {
            it.copy(
                form = LocationFormState(),
                sharedPlace = null,
                sharedPlaceCandidateConfirmed = false,
                sharedPlaceCoordinatesManuallyEdited = false,
                sharedRegistrationSession = null,
                validationMessage = null,
                registrationMessage = null,
                showStartNewRegistrationDialog = false,
            )
        }
        applySharedPlace(SharedPlaceEvent(pending.eventId, pending.place))
    }

    fun dismissPendingSharedPlace() {
        mutateRegistrationState { state ->
            val session = state.sharedRegistrationSession ?: return@mutateRegistrationState state
            val dismissedEventId = session.pendingIncomingShare?.eventId
            state.copy(
                sharedRegistrationSession = session.copy(
                    phase = phaseFor(state.sharedPlace),
                    pendingIncomingShare = null,
                    handledTerminalEventIds = if (dismissedEventId != null) {
                        (session.handledTerminalEventIds + dismissedEventId).toList().takeLast(16).toSet()
                    } else {
                        session.handledTerminalEventIds
                    },
                ),
                registrationMessage = null,
            )
        }
    }

    fun markExternalMapOpened() {
        mutateRegistrationState { state ->
            val session = state.sharedRegistrationSession ?: return@mutateRegistrationState state
            state.copy(
                sharedRegistrationSession = session.copy(
                    phase = if (state.sharedPlace?.hasCoordinates == true) {
                        RegistrationSessionPhase.AWAITING_MAP_CONFIRMATION
                    } else {
                        RegistrationSessionPhase.AWAITING_RECOVERY_SHARE
                    },
                ),
            )
        }
    }

    fun searchAddressCandidates() {
        searchAddressCandidates(automatic = false)
    }

    private fun searchAddressCandidates(automatic: Boolean) {
        val query = _uiState.value.form.address.trim()
        if (query.length < 3) {
            mutateRegistrationState {
                it.copy(addressSearchMessage = "住所または施設名を3文字以上入力してください")
            }
            return
        }
        addressSearchJob?.cancel()
        val generation = UUID.randomUUID().toString()
        val sessionId = _uiState.value.sharedRegistrationSession?.sessionId
        val activeEventId = _uiState.value.sharedRegistrationSession?.activeEventId
        addressSearchGeneration = generation
        mutateRegistrationState { state ->
            state.copy(
                isAddressSearching = true,
                addressCandidates = emptyList(),
                addressSearchMessage = if (automatic) {
                    "メモの場所名から候補を自動検索しています"
                } else {
                    null
                },
                sharedRegistrationSession = state.sharedRegistrationSession?.copy(
                    phase = RegistrationSessionPhase.GEOCODING,
                ),
            )
        }
        addressSearchJob = viewModelScope.launch {
            val result = runCatching { withTimeout(10_000L) { addressCandidateProvider.find(query) } }
            mutateRegistrationState { state ->
                if (addressSearchGeneration != generation ||
                    state.sharedRegistrationSession?.sessionId != sessionId ||
                    state.sharedRegistrationSession?.activeEventId != activeEventId
                ) {
                    return@mutateRegistrationState state
                }
                val candidates = result.getOrDefault(emptyList())
                addressSearchGeneration = null
                state.copy(
                    isAddressSearching = false,
                    addressCandidates = candidates,
                    addressSearchMessage = when {
                        result.isFailure -> "端末の住所検索を利用できませんでした。通信状態または位置サービスを確認してください"
                        candidates.isEmpty() -> "住所から候補を見つけられませんでした。表記を変えて再検索してください"
                        automatic -> "メモの場所名から${candidates.size}件の候補が見つかりました。地図で確認する候補を選んでください"
                        else -> "${candidates.size}件の候補が見つかりました。地図で確認する候補を選んでください"
                    },
                    sharedRegistrationSession = state.sharedRegistrationSession?.copy(
                        phase = if (candidates.isEmpty()) {
                            RegistrationSessionPhase.AWAITING_RECOVERY_SHARE
                        } else {
                            RegistrationSessionPhase.CANDIDATE_REVIEW
                        },
                    ),
                )
            }
        }
    }

    fun selectAddressCandidate(index: Int) {
        mutateRegistrationState { state ->
            val selected = state.addressCandidates.getOrNull(index) ?: return@mutateRegistrationState state
            val candidate = CoordinateCandidate(
                latitude = selected.latitude,
                longitude = selected.longitude,
                semanticRole = CoordinateSemanticRole.GEOCODE_RESULT,
                parseMethod = SharedPlaceParseMethod.SYSTEM_GEOCODER,
                evidenceFamily = CoordinateEvidenceFamily.SYSTEM_GEOCODER,
                evidenceId = "system-geocoder:$index",
                lineageId = "system-geocoder:${state.form.address.trim().hashCode()}",
                reliability = CandidateReliability.WEAK,
                uncertaintyMeters = 100.0,
            )
            val base = state.sharedPlace ?: ParsedSharedPlace(
                nameCandidate = state.form.name,
                latitude = null,
                longitude = null,
                sourceUrl = state.form.sourceUrl,
                rawText = null,
                parseMethod = SharedPlaceParseMethod.MANUAL_REQUIRED,
            )
            val candidates = (base.candidates + candidate).distinctBy {
                "${it.latitude}|${it.longitude}|${it.evidenceId}|${it.lineageId}"
            }
            val selectedIndex = candidates.indexOf(candidate)
            val place = base.copy(
                latitude = selected.latitude,
                longitude = selected.longitude,
                parseMethod = SharedPlaceParseMethod.SYSTEM_GEOCODER,
                confidence = SharedPlaceConfidence.NEEDS_CONFIRMATION,
                candidates = candidates,
                selectedCandidateIndex = selectedIndex,
                warnings = listOf("住所検索の候補です。Googleマップで位置を確認してください"),
                failureReason = null,
            )
            state.copy(
                form = state.form.copy(
                    address = selected.label,
                    latitude = selected.latitude.toString(),
                    longitude = selected.longitude.toString(),
                ),
                sharedPlace = place,
                sharedPlaceCandidateConfirmed = false,
                sharedPlaceCoordinatesManuallyEdited = false,
                addressSearchMessage = "候補を選択しました。Googleマップで位置を確認してください",
                sharedRegistrationSession = state.sharedRegistrationSession?.copy(
                    phase = RegistrationSessionPhase.CANDIDATE_REVIEW,
                ),
            )
        }
    }

    fun selectSharedPlaceCandidate(index: Int) {
        mutateRegistrationState { state ->
            val place = state.sharedPlace ?: return@mutateRegistrationState state
            val candidate = place.candidates.getOrNull(index) ?: return@mutateRegistrationState state
            state.copy(
                form = state.form.copy(
                    latitude = candidate.latitude.toString(),
                    longitude = candidate.longitude.toString(),
                ),
                sharedPlace = place.copy(
                    latitude = candidate.latitude,
                    longitude = candidate.longitude,
                    parseMethod = candidate.parseMethod,
                    confidence = SharedPlaceConfidence.NEEDS_CONFIRMATION,
                    selectedCandidateIndex = index,
                    warnings = listOf("選択した候補位置を地図で確認してください"),
                    failureReason = null,
                ),
                sharedPlaceCandidateConfirmed = false,
                sharedPlaceCoordinatesManuallyEdited = false,
                sharedRegistrationSession = state.sharedRegistrationSession?.copy(
                    phase = RegistrationSessionPhase.CANDIDATE_REVIEW,
                ),
                validationMessage = null,
                registrationMessage = "候補位置を選択しました。地図で確認してください",
            )
        }
    }

    fun confirmSharedPlaceCandidate() {
        mutateRegistrationState { state ->
            if (state.sharedPlace?.hasCoordinates != true) return@mutateRegistrationState state
            state.copy(
                sharedPlaceCandidateConfirmed = true,
                sharedPlaceCoordinatesManuallyEdited = false,
                sharedRegistrationSession = state.sharedRegistrationSession?.copy(
                    phase = RegistrationSessionPhase.READY_TO_SAVE,
                ),
                validationMessage = null,
                registrationMessage = "候補位置を確認済みにしました",
            )
        }
    }

    fun confirmSharedPlaceCandidateAndSave() {
        val state = _uiState.value
        if (state.isSaving || state.sharedPlace?.hasCoordinates != true) return
        val expectedCoordinates = state.form.latitude to state.form.longitude
        confirmSharedPlaceCandidate()
        saveInternal(expectedCoordinates)
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
        clearRegistrationSnapshot()
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
                sharedPlace = null,
                sharedPlaceCandidateConfirmed = false,
                sharedPlaceCoordinatesManuallyEdited = false,
                sharedRegistrationSession = null,
                showDiscardRegistrationDialog = false,
            )
        }
    }

    fun requestCancelEdit() {
        if (_uiState.value.sharedRegistrationSession != null) {
            _uiState.update { it.copy(showDiscardRegistrationDialog = true) }
        } else {
            cancelEdit()
        }
    }

    fun dismissDiscardRegistration() {
        _uiState.update { it.copy(showDiscardRegistrationDialog = false) }
    }

    fun discardRegistration() {
        cancelEdit()
    }

    fun cancelEdit() {
        clearRegistrationSnapshot()
        _uiState.update {
            it.copy(
                editingLocation = null,
                form = LocationFormState(),
                validationMessage = null,
                sharedPlace = null,
                sharedPlaceCandidateConfirmed = false,
                sharedPlaceCoordinatesManuallyEdited = false,
                sharedRegistrationSession = null,
                showDiscardRegistrationDialog = false,
            )
        }
    }

    fun applyPreset(presetId: String) {
        val preset = _uiState.value.presets.firstOrNull { it.id == presetId } ?: return
        mutateRegistrationState {
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
        saveInternal(expectedCoordinates = null)
    }

    private fun saveInternal(expectedCoordinates: Pair<String, String>?) {
        val state = _uiState.value
        if (state.isSaving) return
        if (
            expectedCoordinates != null &&
            (state.form.latitude != expectedCoordinates.first || state.form.longitude != expectedCoordinates.second)
        ) {
            mutateRegistrationState {
                it.copy(
                    sharedPlaceCandidateConfirmed = false,
                    validationMessage = "位置が更新されたため、もう一度確認してください",
                )
            }
            return
        }
        when (
            locationSaveBlockReason(
                form = state.form,
                place = state.sharedPlace,
                candidateConfirmed = state.sharedPlaceCandidateConfirmed,
                coordinatesManuallyEdited = state.sharedPlaceCoordinatesManuallyEdited,
            )
        ) {
            LocationSaveBlockReason.MISSING_COORDINATES -> {
                _uiState.update { it.copy(validationMessage = "位置を設定し、緯度・経度を確認してください") }
                return
            }
            LocationSaveBlockReason.INVALID_COORDINATES -> {
                _uiState.update { it.copy(validationMessage = "緯度は-90から90、経度は-180から180で入力してください") }
                return
            }
            LocationSaveBlockReason.CANDIDATE_CONFIRMATION_REQUIRED -> {
                _uiState.update { it.copy(validationMessage = "候補位置を地図で確認し、「この位置を確認しました」を押してください") }
                return
            }
            null -> Unit
        }
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

        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
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
                var clearSavedRegistrationSnapshot = false
                _uiState.update {
                    val registrationChangedWhileSaving =
                        it.form != state.form ||
                            it.sharedPlace != state.sharedPlace ||
                            it.sharedRegistrationSession != state.sharedRegistrationSession
                    if (registrationChangedWhileSaving) {
                        it.copy(
                            validationMessage = null,
                            isSaving = false,
                            saveSuccessId = it.saveSuccessId + 1,
                            registrationMessage = "$registrationMessage。新しく共有・編集された内容は下書きに保持しています",
                        )
                    } else {
                        clearSavedRegistrationSnapshot = true
                        it.copy(
                            form = LocationFormState(),
                            editingLocation = null,
                            validationMessage = null,
                            isSaving = false,
                            saveSuccessId = it.saveSuccessId + 1,
                            registrationMessage = registrationMessage,
                            sharedPlace = null,
                            sharedPlaceCandidateConfirmed = false,
                            sharedPlaceCoordinatesManuallyEdited = false,
                            sharedRegistrationSession = null,
                            showDiscardRegistrationDialog = false,
                        )
                    }
                }
                if (clearSavedRegistrationSnapshot) clearRegistrationSnapshot()
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
                activitySnapshot = activityStateSource.read(),
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
        val message = when (sendTestNotificationUseCase.execute()) {
            TestNotificationResult.POSTED -> "テスト通知を送信しました"
            TestNotificationResult.PERMISSION_DENIED -> "通知権限がありません"
            TestNotificationResult.CHANNEL_DISABLED -> "通知チャンネルが無効です"
            TestNotificationResult.POST_FAILED -> "テスト通知を送信できませんでした"
        }
        _uiState.update { it.copy(registrationMessage = message) }
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

    fun findNearbySavedLocations() {
        nearbySearchJob?.cancel()
        val locations = _uiState.value.locations
        if (locations.isEmpty()) {
            _uiState.update {
                it.copy(
                    nearbySearch = NearbySearchUiState.NoSavedLocations,
                    nearbyDistanceFilter = NearbyDistanceFilter.WITHIN_5_KM,
                )
            }
            return
        }
        val permissionSnapshot = permissionStateReader.read()
        _uiState.update { it.copy(permissionSnapshot = permissionSnapshot) }
        if (permissionSnapshot.foregroundLocation == ForegroundLocationStatus.Denied) {
            _uiState.update { it.copy(nearbySearch = NearbySearchUiState.PermissionRequired) }
            return
        }
        if (permissionSnapshot.locationServices == LocationServicesStatus.Disabled) {
            _uiState.update { it.copy(nearbySearch = NearbySearchUiState.LocationServicesDisabled) }
            return
        }

        _uiState.update {
            it.copy(
                nearbySearch = NearbySearchUiState.Loading,
                nearbyDistanceFilter = NearbyDistanceFilter.WITHIN_5_KM,
            )
        }
        nearbySearchJob = viewModelScope.launch {
            val uiResult = when (val result = findNearbySavedLocationsUseCase.execute(locations)) {
                NearbySavedLocationsResult.LocationUnavailable -> NearbySearchUiState.LocationUnavailable
                NearbySavedLocationsResult.NoSavedLocations -> NearbySearchUiState.NoSavedLocations
                is NearbySavedLocationsResult.Success -> NearbySearchUiState.Results(result)
            }
            _uiState.update { it.copy(nearbySearch = uiResult) }
        }
    }

    fun updateNearbyDistanceFilter(filter: NearbyDistanceFilter) {
        _uiState.update { it.copy(nearbyDistanceFilter = filter) }
    }

    fun closeNearbySavedLocations() {
        nearbySearchJob?.cancel()
        nearbySearchJob = null
        _uiState.update { it.copy(nearbySearch = NearbySearchUiState.Hidden) }
    }

    private fun mutateRegistrationState(transform: (LocationsUiState) -> LocationsUiState) {
        _uiState.update(transform)
        persistRegistrationState(_uiState.value)
    }

    private fun persistRegistrationState(state: LocationsUiState) {
        val session = state.sharedRegistrationSession
        val place = state.sharedPlace
        if (session == null || place == null) {
            clearRegistrationSnapshot()
            return
        }
        val snapshot = RestoredSharedRegistration(
            session = session,
            form = state.form,
            selectedPresetId = state.selectedPresetId,
            place = place,
            candidateConfirmed = state.sharedPlaceCandidateConfirmed,
            coordinatesManuallyEdited = state.sharedPlaceCoordinatesManuallyEdited,
        )
        val encoded = SharedRegistrationSessionCodec.encode(snapshot)
        if (encoded == null) {
            clearRegistrationSnapshot()
        } else {
            savedStateHandle[SHARED_REGISTRATION_SNAPSHOT_KEY] = encoded
        }
    }

    private fun clearRegistrationSnapshot() {
        savedStateHandle.remove<String>(SHARED_REGISTRATION_SNAPSHOT_KEY)
    }

    companion object {
        private const val SHARED_REGISTRATION_SNAPSHOT_KEY = "shared_registration_snapshot_v1"
    }
}

internal fun repairInterruptedSharedResolution(state: LocationsUiState): LocationsUiState {
    val session = state.sharedRegistrationSession ?: return state
    val activeWasResolving = state.sharedPlace?.confidence == SharedPlaceConfidence.RESOLVING
    val pendingWasResolving =
        session.pendingIncomingShare?.place?.confidence == SharedPlaceConfidence.RESOLVING
    if (!activeWasResolving && !pendingWasResolving) return state

    fun ParsedSharedPlace.asInterruptedResolution(): ParsedSharedPlace = copy(
        confidence = SharedPlaceConfidence.NETWORK_FAILURE,
        warnings = listOf(
            "リンク確認中に画面が再開されました。Googleマップから再共有するか住所を検索してください",
        ),
        failureReason = "resolution_interrupted",
    )

    val repairedPending = session.pendingIncomingShare?.let { pending ->
        if (pending.place.confidence == SharedPlaceConfidence.RESOLVING) {
            pending.copy(place = pending.place.asInterruptedResolution())
        } else {
            pending
        }
    }
    return state.copy(
        sharedPlace = state.sharedPlace?.let { place ->
            if (place.confidence == SharedPlaceConfidence.RESOLVING) {
                place.asInterruptedResolution()
            } else {
                place
            }
        },
        sharedRegistrationSession = session.copy(
            phase = if (repairedPending != null) {
                RegistrationSessionPhase.PENDING_SHARE_DECISION
            } else {
                RegistrationSessionPhase.AWAITING_RECOVERY_SHARE
            },
            pendingIncomingShare = repairedPending,
        ),
        validationMessage = "リンク確認を再開できませんでした。再共有または住所検索を利用してください",
    )
}

internal fun reduceSharedPlaceEvent(
    state: LocationsUiState,
    event: SharedPlaceEvent,
): LocationsUiState {
    val session = state.sharedRegistrationSession
    if (session != null && event.eventId != session.activeEventId) {
        if (event.eventId in session.handledTerminalEventIds) return state
        if (session.pendingIncomingShare?.eventId == event.eventId) {
            return state.copy(
                sharedRegistrationSession = session.copy(
                    pendingIncomingShare = PendingSharedPlace(event.eventId, event.place),
                ),
            )
        }
        return state.copy(
            sharedRegistrationSession = session.copy(
                phase = RegistrationSessionPhase.PENDING_SHARE_DECISION,
                pendingIncomingShare = PendingSharedPlace(event.eventId, event.place),
            ),
            validationMessage = null,
            registrationMessage = "登録途中の地点があります。今回の共有を追加するか選んでください",
        )
    }
    if (session != null &&
        event.eventId == session.activeEventId &&
        event.eventId in session.handledTerminalEventIds &&
        event.place.confidence != SharedPlaceConfidence.RESOLVING
    ) {
        return state
    }

    val preserveExistingDraft = session != null ||
        state.editingLocation != null ||
        state.form.hasUserAuthoredLocationDraft()
    val existingForm = state.form.takeIf { preserveExistingDraft }
    val preserveManualCoordinates = existingForm != null && state.sharedPlaceCoordinatesManuallyEdited
    val preserveSearchInput = RegistrationField.ADDRESS in session?.userEditedFields.orEmpty()
    val completingMergedEvent = session != null &&
        event.eventId == session.activeEventId &&
        state.sharedPlace != null &&
        state.sharedPlace.confidence != SharedPlaceConfidence.RESOLVING &&
        event.place.confidence != SharedPlaceConfidence.RESOLVING
    val effectivePlace = if (completingMergedEvent) {
        SharedPlaceParser.mergePlaces(state.sharedPlace, event.place)
    } else {
        event.place
    }
    val form = mergeResolvedSharedPlaceForm(
        existingForm = existingForm,
        place = effectivePlace,
        preserveManualCoordinates = preserveManualCoordinates,
        preserveSearchInput = preserveSearchInput,
    )
    val isTerminal = effectivePlace.confidence != SharedPlaceConfidence.RESOLVING
    val updatedSession = (session ?: SharedRegistrationSession(
        sessionId = UUID.randomUUID().toString(),
        phase = phaseFor(effectivePlace),
        activeEventId = event.eventId,
    )).copy(
        phase = if (session?.pendingIncomingShare != null) {
            RegistrationSessionPhase.PENDING_SHARE_DECISION
        } else {
            phaseFor(effectivePlace)
        },
        activeEventId = event.eventId,
        handledTerminalEventIds = if (isTerminal) {
            (session?.handledTerminalEventIds.orEmpty() + event.eventId).toList().takeLast(16).toSet()
        } else {
            session?.handledTerminalEventIds.orEmpty()
        },
        pendingIncomingShare = session?.pendingIncomingShare,
    )
    return state.copy(
        editingLocation = if (preserveExistingDraft) state.editingLocation else null,
        form = form,
        validationMessage = when (effectivePlace.confidence) {
            SharedPlaceConfidence.UNRESOLVED,
            SharedPlaceConfidence.NETWORK_FAILURE,
            -> effectivePlace.warnings.firstOrNull()
            else -> null
        },
        registrationMessage = if (session?.pendingIncomingShare != null) {
            state.registrationMessage
                ?: "登録途中の地点があります。今回の共有を追加するか選んでください"
        } else when (effectivePlace.confidence) {
            SharedPlaceConfidence.RESOLVING -> "Googleマップのリンクを確認しています"
            SharedPlaceConfidence.HIGH_CONFIDENCE -> "共有内容から位置を取得しました。保存前に確認してください"
            SharedPlaceConfidence.NEEDS_CONFIRMATION -> "候補位置を取得しました。地図で確認してください"
            SharedPlaceConfidence.UNRESOLVED,
            SharedPlaceConfidence.NETWORK_FAILURE,
            -> null
        },
        sharedPlace = effectivePlace,
        sharedPlaceCandidateConfirmed = effectivePlace.confidence == SharedPlaceConfidence.HIGH_CONFIDENCE,
        sharedPlaceCoordinatesManuallyEdited = preserveManualCoordinates,
        sharedRegistrationSession = updatedSession,
        showDiscardRegistrationDialog = false,
    )
}

internal fun mergePendingSharedPlaceState(state: LocationsUiState): LocationsUiState {
    val session = state.sharedRegistrationSession ?: return state
    val pending = session.pendingIncomingShare ?: return state
    val place = SharedPlaceParser.mergePlaces(state.sharedPlace, pending.place)
    val hasCoordinates = pending.place.hasCoordinates
    val preserveCoordinates = state.sharedPlaceCoordinatesManuallyEdited ||
        RegistrationField.LATITUDE in session.userEditedFields ||
        RegistrationField.LONGITUDE in session.userEditedFields
    return state.copy(
        form = state.form.copy(
            latitude = if (hasCoordinates && !preserveCoordinates) pending.place.latitude.toString() else state.form.latitude,
            longitude = if (hasCoordinates && !preserveCoordinates) pending.place.longitude.toString() else state.form.longitude,
        ),
        sharedPlace = place,
        sharedPlaceCandidateConfirmed = false,
        sharedPlaceCoordinatesManuallyEdited = preserveCoordinates,
        sharedRegistrationSession = session.copy(
            phase = phaseFor(place),
            activeEventId = pending.eventId,
            handledTerminalEventIds = if (pending.place.confidence != SharedPlaceConfidence.RESOLVING) {
                (session.handledTerminalEventIds + pending.eventId).toList().takeLast(16).toSet()
            } else {
                session.handledTerminalEventIds
            },
            pendingIncomingShare = null,
        ),
        validationMessage = null,
        registrationMessage = if (hasCoordinates) {
            "共有された位置候補を現在の登録へ追加しました。地図で確認してください"
        } else {
            "共有情報を追加しましたが、位置はまだ特定できません"
        },
    )
}

private fun phaseFor(place: ParsedSharedPlace?): RegistrationSessionPhase = when {
    place == null -> RegistrationSessionPhase.EDITING
    place.confidence == SharedPlaceConfidence.RESOLVING -> RegistrationSessionPhase.RESOLVING
    place.hasCoordinates && place.confidence == SharedPlaceConfidence.HIGH_CONFIDENCE ->
        RegistrationSessionPhase.READY_TO_SAVE
    place.hasCoordinates -> RegistrationSessionPhase.CANDIDATE_REVIEW
    else -> RegistrationSessionPhase.EDITING
}

private fun changedRegistrationFields(
    previous: LocationFormState,
    next: LocationFormState,
): Set<RegistrationField> = buildSet {
    if (previous.name != next.name) add(RegistrationField.NAME)
    if (previous.message != next.message) add(RegistrationField.MESSAGE)
    if (previous.address != next.address) add(RegistrationField.ADDRESS)
    if (previous.latitude != next.latitude) add(RegistrationField.LATITUDE)
    if (previous.longitude != next.longitude) add(RegistrationField.LONGITUDE)
    if (previous.radiusMeters != next.radiusMeters) add(RegistrationField.RADIUS)
    if (previous.loiteringDelaySeconds != next.loiteringDelaySeconds) add(RegistrationField.LOITERING_DELAY)
    if (previous.cooldownHours != next.cooldownHours) add(RegistrationField.COOLDOWN)
    if (previous.enabled != next.enabled) add(RegistrationField.ENABLED)
    if (previous.tags != next.tags || previous.tagInput != next.tagInput) add(RegistrationField.TAGS)
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
