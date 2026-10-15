package com.chikabell.app.ai

import com.chikabell.app.importexport.*
import com.chikabell.app.share.AddressCandidateProvider
import com.chikabell.app.share.AddressCoordinateCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

data class AiPlaceToReview(val record: LocationTransferRecord, val coordinates: List<AddressCoordinateCandidate>)
data class AiPlaceVerificationResult(val candidates: List<AiPlaceToReview>, val excludedReasons: List<String>)
data class AiPlaceEdits(val radiusMeters: String, val message: String, val tags: String,
    val loiteringDelaySeconds: String? = null, val cooldownMinutes: String? = null, val enabled: Boolean? = null)

/** The AI's coordinates are never the authority. Independent Android address candidates require a user choice. */
class AiPlaceVerifier(private val addressProvider: AddressCandidateProvider) {
    suspend fun verify(content: String, request: AiSearchRequest, sentAt: Long): AiPlaceVerificationResult {
        require(request.validate(sentAt) == null && request.areaMode != SearchAreaMode.CHATGPT_LOCATION) { "検索条件を確認してください" }
        val parsed = AiImportText.parse(content)
        if (parsed is TransferParseResult.Failure) return AiPlaceVerificationResult(emptyList(), listOf(parsed.message))
        val records = (parsed as TransferParseResult.Success).records
        if (records.size > request.count) return AiPlaceVerificationResult(emptyList(), listOf("依頼した最大件数を超えています"))
        val validation = LocationImportPlanner.preview(records, emptyList(), TransferFormat.JSON)
        if (validation.errors.isNotEmpty()) return AiPlaceVerificationResult(emptyList(), validation.errors)
        val accepted = mutableListOf<AiPlaceToReview>()
        val excluded = mutableListOf<String>()
        records.forEachIndexed { index, record ->
            if (record.id != null || !AiImportText.safeSource(record.sourceUrl)) {
                excluded += "${index + 1}件目: 根拠URLがない、または新規地点としての形式が不正です"
                return@forEachIndexed
            }
            val query = (request.area.takeIf { request.areaMode == SearchAreaMode.AREA }.orEmpty() + " " + record.name).trim()
            val coordinates = try { withTimeoutOrNull(10_000) { addressProvider.find(query) }.orEmpty() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList() }
            val eligible = coordinates.filter {
                it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 &&
                    (request.origin == null || request.origin.includes(it.latitude, it.longitude, request.distanceKm))
            }.distinctBy { it.latitude to it.longitude }.take(5)
            if (eligible.isEmpty()) excluded += "${index + 1}件目: 住所検索で位置を確認できないか、指定半径外です。Googleマップ共有または手動登録で確認してください"
            else accepted += AiPlaceToReview(record, eligible)
        }
        return AiPlaceVerificationResult(accepted, excluded)
    }

    fun selectedJson(candidates: List<AiPlaceToReview>, selectedCoordinates: Map<Int, Int>, edits: Map<Int, AiPlaceEdits> = emptyMap()): String {
        require(selectedCoordinates.isNotEmpty()) { "登録する地点と位置候補を選んでください" }
        val selected = selectedCoordinates.entries.sortedBy { it.key }.map { (index, coordinateIndex) ->
            val candidate = candidates.getOrNull(index) ?: error("選択が不正です")
            val coordinate = candidate.coordinates.getOrNull(coordinateIndex) ?: error("位置選択が不正です")
            val edited = edits[index]
            candidate.record.copy(latitude = coordinate.latitude, longitude = coordinate.longitude,
                radiusMeters = edited?.radiusMeters?.toIntOrNull() ?: if (edited == null) candidate.record.radiusMeters else error("通知半径を整数で入力してください"),
                message = edited?.message ?: candidate.record.message,
                loiteringDelaySeconds = edited?.loiteringDelaySeconds?.let { it.toIntOrNull() ?: error("滞在時間を整数で入力してください") } ?: candidate.record.loiteringDelaySeconds,
                cooldownMinutes = edited?.cooldownMinutes?.let { it.toLongOrNull() ?: error("再通知までの時間を整数で入力してください") } ?: candidate.record.cooldownMinutes,
                enabled = edited?.enabled ?: candidate.record.enabled,
                tags = edited?.tags?.split('|')?.map(String::trim)?.filter(String::isNotEmpty) ?: candidate.record.tags)
        }
        val preview = LocationImportPlanner.preview(selected, emptyList(), TransferFormat.JSON)
        require(preview.errors.isEmpty()) { "通知半径・メモ・タグを確認してください。タグは最大5件・各30文字です" }
        return LocationTransferCodec.encodeImportJson(selected)
    }
}
