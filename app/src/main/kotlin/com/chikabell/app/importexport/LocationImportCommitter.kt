package com.chikabell.app.importexport

import com.chikabell.app.domain.repository.LocationRepository
import com.chikabell.app.geofence.ReconcileResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

fun LocationImportCandidate.toTransferRecord(): LocationTransferRecord = draft.let {
    LocationTransferRecord(originalId, it.name, it.message, it.latitude, it.longitude,
        it.radiusMeters, it.loiteringDelaySeconds, it.cooldownMinutes, it.enabled, it.sourceUrl, it.tags)
}

class ImportReviewChanged(val preview: LocationImportPreview) : IllegalStateException("import_review_changed")

/** Shared by file imports and AI review. The final user action authorizes only these drafts. */
class LocationImportCommitter(
    private val repository: LocationRepository,
    private val reconcile: suspend () -> ReconcileResult,
) {
    suspend fun commit(selected: List<LocationImportCandidate>, format: TransferFormat): String {
        require(selected.isNotEmpty())
        val current = repository.observeLocations().first()
        val checked = LocationImportPlanner.preview(selected.map { it.toTransferRecord() }, current, format)
        if (!checked.canApply || checked.candidates != selected) throw ImportReviewChanged(checked)
        // Once the atomic save starts, cancellation must not make a successful save look like a failure.
        return withContext(NonCancellable) {
            repository.addImportedLocations(selected)
            val count = selected.size
            try {
                when (val result = reconcile()) {
                    is ReconcileResult.Registered -> "${count}件を追加し、通知の設定を更新しました"
                    is ReconcileResult.Blocked -> "${count}件を保存しました。通知の設定が必要です: ${result.reason}"
                    is ReconcileResult.Failed -> "${count}件を保存しました。通知の設定を更新できません: ${result.message}"
                }
            } catch (_: Exception) {
                "${count}件を保存しました。通知の設定を確認してください"
            }
        }
    }
}
