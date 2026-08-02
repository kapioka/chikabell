package com.chikabell.app.ui.locations

import com.chikabell.app.share.SharedPlaceConfidence
import com.chikabell.app.share.SharedPlaceParser

internal enum class LocationRegistrationMode {
    RESOLVING,
    NEEDS_LOCATION,
    NEEDS_CONFIRMATION,
    READY,
    SAVING,
}

internal enum class LocationFormSection {
    DETAILS,
    NOTIFICATION,
    COORDINATES,
}

internal data class LocationRegistrationPresentation(
    val mode: LocationRegistrationMode,
    val topSaveEnabled: Boolean,
    val topSaveStateDescription: String,
    val candidateTitle: String,
    val candidateAddress: String?,
    val mapActionLabel: String,
    val guidance: String,
) {
    val showConfirmAndSave: Boolean
        get() = mode == LocationRegistrationMode.NEEDS_CONFIRMATION
}

internal fun locationRegistrationPresentation(
    uiState: LocationsUiState,
): LocationRegistrationPresentation {
    val blockReason = locationSaveBlockReason(
        form = uiState.form,
        place = uiState.sharedPlace,
        candidateConfirmed = uiState.sharedPlaceCandidateConfirmed,
        coordinatesManuallyEdited = uiState.sharedPlaceCoordinatesManuallyEdited,
    )
    val resolving = uiState.sharedPlace?.confidence == SharedPlaceConfidence.RESOLVING ||
        uiState.sharedRegistrationSession?.phase == RegistrationSessionPhase.RESOLVING
    val mode = when {
        uiState.isSaving -> LocationRegistrationMode.SAVING
        resolving -> LocationRegistrationMode.RESOLVING
        blockReason == LocationSaveBlockReason.CANDIDATE_CONFIRMATION_REQUIRED ->
            LocationRegistrationMode.NEEDS_CONFIRMATION
        blockReason == LocationSaveBlockReason.MISSING_COORDINATES ||
            blockReason == LocationSaveBlockReason.INVALID_COORDINATES ->
            LocationRegistrationMode.NEEDS_LOCATION
        else -> LocationRegistrationMode.READY
    }
    val sharedLabel = uiState.sharedPlace
        ?.let(::sharedPlaceDraftLabel)
        .orEmpty()
    val candidateTitle = sequenceOf(
        sharedLabel,
        uiState.form.name.trim(),
        uiState.form.address.trim(),
    ).firstOrNull { value ->
        value.isNotBlank() && !SharedPlaceParser.isCoordinateOnlyLabel(value)
    } ?: "選択した位置"
    val candidateAddress = uiState.form.address
        .trim()
        .takeIf { value ->
            value.isNotBlank() &&
                value != candidateTitle &&
                !SharedPlaceParser.isCoordinateOnlyLabel(value)
        }
    val mapWasOpened = uiState.sharedRegistrationSession?.phase ==
        RegistrationSessionPhase.AWAITING_MAP_CONFIRMATION

    return LocationRegistrationPresentation(
        mode = mode,
        topSaveEnabled = mode == LocationRegistrationMode.READY,
        topSaveStateDescription = when (mode) {
            LocationRegistrationMode.READY -> "保存できます"
            LocationRegistrationMode.SAVING -> "保存中"
            LocationRegistrationMode.RESOLVING -> "位置を確認中・保存できません"
            LocationRegistrationMode.NEEDS_LOCATION -> "位置未設定・保存できません"
            LocationRegistrationMode.NEEDS_CONFIRMATION -> "位置未確定・保存できません"
        },
        candidateTitle = candidateTitle,
        candidateAddress = candidateAddress,
        mapActionLabel = if (mapWasOpened) "もう一度Googleマップで確認" else "Googleマップで確認",
        guidance = when (mode) {
            LocationRegistrationMode.RESOLVING -> "共有リンクから位置を確認しています"
            LocationRegistrationMode.NEEDS_LOCATION ->
                "住所・施設名から候補を検索するか、緯度・経度を入力してください"
            LocationRegistrationMode.NEEDS_CONFIRMATION -> "必要なら地図で位置を見直せます"
            LocationRegistrationMode.READY -> "位置を設定しました"
            LocationRegistrationMode.SAVING -> "この内容を保存しています"
        },
    )
}

internal fun locationFormSectionsForValidation(
    validationMessage: String?,
    blockReason: LocationSaveBlockReason?,
): Set<LocationFormSection> {
    if (
        blockReason == LocationSaveBlockReason.MISSING_COORDINATES ||
        blockReason == LocationSaveBlockReason.INVALID_COORDINATES
    ) {
        return setOf(LocationFormSection.COORDINATES)
    }
    val message = validationMessage.orEmpty()
    if (message.isBlank()) return emptySet()

    return buildSet {
        if (listOf("名前", "メモ", "タグ").any(message::contains)) {
            add(LocationFormSection.DETAILS)
        }
        if (listOf("半径", "滞在", "再通知", "通知間隔", "クールダウン").any(message::contains)) {
            add(LocationFormSection.NOTIFICATION)
        }
        if (listOf("位置", "緯度", "経度").any(message::contains)) {
            add(LocationFormSection.COORDINATES)
        }
        if (message.contains("数値の入力")) {
            add(LocationFormSection.NOTIFICATION)
            add(LocationFormSection.COORDINATES)
        }
    }
}
