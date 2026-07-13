package com.chikabell.app.importexport

import com.chikabell.app.domain.model.LocationDraft
import com.chikabell.app.domain.model.SavedLocation
import com.chikabell.app.domain.model.SourceType
import com.chikabell.app.domain.validation.LocationValidator
import com.chikabell.app.geofence.DistanceCalculator

object LocationImportPlanner {
    const val MAX_ENABLED_LOCATIONS = 100

    fun preview(
        records: List<LocationTransferRecord>,
        existing: List<SavedLocation>,
        format: TransferFormat,
    ): LocationImportPreview {
        var duplicateCount = 0
        val errors = mutableListOf<String>()
        val candidates = mutableListOf<LocationImportCandidate>()

        records.forEachIndexed { index, record ->
            val draft = LocationDraft(
                name = record.name,
                message = record.message,
                latitude = record.latitude,
                longitude = record.longitude,
                radiusMeters = record.radiusMeters,
                loiteringDelaySeconds = record.loiteringDelaySeconds,
                cooldownMinutes = record.cooldownMinutes,
                enabled = record.enabled,
                sourceType = if (format == TransferFormat.JSON) SourceType.JSON_IMPORT else SourceType.CSV_IMPORT,
                sourceUrl = record.sourceUrl,
                tags = record.tags,
            )
            val validationErrors = LocationValidator.validate(draft)
            if (validationErrors.isNotEmpty()) {
                errors += "${index + 1}件目の地点データが不正です: ${validationErrors.joinToString { it.name }}"
                return@forEachIndexed
            }
            val candidate = LocationImportCandidate(record.id, draft)
            if (isDuplicate(record.id, draft, existing, candidates)) {
                duplicateCount++
            } else {
                candidates += candidate
            }
        }

        val enabledAfterImport = existing.count { it.enabled } + candidates.count { it.draft.enabled }
        if (enabledAfterImport > MAX_ENABLED_LOCATIONS) {
            errors += "有効地点が${MAX_ENABLED_LOCATIONS}件の上限を超えます（予定: ${enabledAfterImport}件）"
        }
        return LocationImportPreview(format, records.size, candidates, duplicateCount, errors)
    }

    private fun isDuplicate(
        id: String?,
        draft: LocationDraft,
        existing: List<SavedLocation>,
        accepted: List<LocationImportCandidate>,
    ): Boolean {
        if (id != null && existing.any { it.id == id }) return true
        if (id != null && accepted.any { it.originalId == id }) return true
        if (draft.sourceUrl != null && existing.any { it.sourceUrl == draft.sourceUrl }) return true
        if (draft.sourceUrl != null && accepted.any { it.draft.sourceUrl == draft.sourceUrl }) return true
        return existing.any { sameNameAndNearby(draft, it.name, it.latitude, it.longitude) } ||
            accepted.any { sameNameAndNearby(draft, it.draft.name, it.draft.latitude, it.draft.longitude) }
    }

    private fun sameNameAndNearby(draft: LocationDraft, name: String, latitude: Double, longitude: Double): Boolean {
        if (!draft.name.trim().equals(name.trim(), ignoreCase = true)) return false
        return DistanceCalculator.distanceMeters(draft.latitude, draft.longitude, latitude, longitude) <= 50f
    }
}
