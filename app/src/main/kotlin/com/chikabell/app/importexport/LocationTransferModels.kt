package com.chikabell.app.importexport

import com.chikabell.app.domain.model.LocationDraft

enum class TransferFormat { JSON, CSV }

data class LocationTransferRecord(
    val id: String?,
    val name: String,
    val message: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val loiteringDelaySeconds: Int,
    val cooldownMinutes: Long,
    val enabled: Boolean,
    val sourceUrl: String?,
    val tags: List<String> = emptyList(),
)

data class LocationImportCandidate(
    val originalId: String?,
    val draft: LocationDraft,
)

data class LocationImportPreview(
    val format: TransferFormat,
    val totalCount: Int,
    val candidates: List<LocationImportCandidate>,
    val duplicateCount: Int,
    val errors: List<String>,
) {
    val canApply: Boolean get() = candidates.isNotEmpty() && errors.isEmpty()
}

sealed interface TransferParseResult {
    data class Success(val records: List<LocationTransferRecord>) : TransferParseResult
    data class Failure(val message: String) : TransferParseResult
}
