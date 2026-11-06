package com.chikabell.app.importexport

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chikabell.app.ChikaBellApplication
import com.chikabell.app.ai.AiImportText
import com.chikabell.app.ai.AiPlaceToReview
import com.chikabell.app.ai.AiPlaceVerificationResult
import com.chikabell.app.ai.AiReviewSelection
import com.chikabell.app.ai.selectedReviewRecords
import com.chikabell.app.share.AddressCoordinateCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class LocationImportState(
    val json: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val review: AiPlaceVerificationResult? = null,
    val format: TransferFormat = TransferFormat.JSON,
    val duplicateCount: Int = 0,
    val savedMessage: String? = null,
)

internal class LocationImportViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as ChikaBellApplication).container
    private val repository = container.locationRepository
    private val committer = LocationImportCommitter(repository) { container.reconcileGeofencesUseCase.execute() }
    private val mutable = MutableStateFlow(LocationImportState())
    val state = mutable.asStateFlow()
    val selection = AiReviewSelection()

    fun editJson(value: String) {
        if (!state.value.busy) mutable.update { it.copy(json = value.take(MAX_PASTE_CHARS + 1), error = null) }
    }

    fun pasteJson(value: CharSequence?) {
        if (state.value.busy) return
        if (value.isNullOrBlank()) showError("クリップボードにJSONがありません。JSON本文をコピーしてください。")
        else editJson(value.take(MAX_PASTE_CHARS + 1).toString())
    }

    fun reviewJson() {
        val json = state.value.json
        if (json.length > MAX_PASTE_CHARS) {
            showError("貼り付けは10万文字までです。大きなJSONはファイルとして保存して選んでください。")
            return
        }
        run("JSONを確認できませんでした。本文を確認してもう一度お試しください。") {
            prepareReview(json, TransferFormat.JSON, "JSON本文を修正するか、コピーし直してください。")
        }
    }

    fun reviewFile(uri: Uri) = run("ファイルを開けませんでした。保存先を確認して、もう一度ファイルを選んでください。") {
        val application = getApplication<Application>()
        val resolver = application.contentResolver
        val (content, format) = withContext(Dispatchers.IO) {
            val content = readTransferText(application, uri)
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }.orEmpty()
            content to detectTransferFormat(name, resolver.getType(uri), content)
        }
        if (format == null) showError("ファイル形式を判別できません。地点データのJSONまたはCSVファイルを選んでください。")
        else prepareReview(content, format, if (format == TransferFormat.CSV) {
            "CSVを修正して選び直してください。データ管理から書き出したCSVをひな形にできます。"
        } else {
            "JSONを修正するか、対応する地点ファイルを選び直してください。"
        })
    }

    private suspend fun prepareReview(content: String, format: TransferFormat, guidance: String) {
        val parsed = withContext(Dispatchers.Default) {
            if (format == TransferFormat.JSON) AiImportText.parse(content) else LocationTransferCodec.parseCsv(content)
        }
        val records = when (parsed) {
            is TransferParseResult.Success -> parsed.records
            is TransferParseResult.Failure -> {
                showError("${parsed.message}\n$guidance")
                return
            }
        }
        val existing = repository.observeLocations().first()
        val preview = withContext(Dispatchers.Default) { LocationImportPlanner.preview(records, existing, format) }
        if (preview.errors.isNotEmpty()) {
            showError(preview.errors.joinToString("\n") + "\n" + guidance)
            return
        }
        if (preview.candidates.isEmpty()) {
            showError(if (preview.duplicateCount > 0) {
                "追加できる新しい地点がありません。重複 ${preview.duplicateCount}件"
            } else {
                "地点が含まれていません。追加する地点が入ったデータを選んでください。"
            })
            return
        }
        val places = preview.candidates.map { candidate ->
            val record = candidate.toTransferRecord()
            AiPlaceToReview(record, listOf(AddressCoordinateCandidate("取り込んだデータの位置", record.latitude, record.longitude)))
        }
        selection.reset()
        mutable.update { it.copy(review = AiPlaceVerificationResult(places, emptyList()), format = format, duplicateCount = preview.duplicateCount) }
    }

    fun backToInput() {
        if (state.value.busy) return
        selection.reset()
        mutable.update { it.copy(review = null, error = null) }
    }

    fun register() {
        val current = state.value
        val review = current.review ?: return
        if (current.busy || selection.selected.isEmpty()) return
        run("地点を保存できませんでした。登録内容を確認してもう一度お試しください。") {
            val records = try {
                selectedReviewRecords(review.candidates, selection.selected, selection.edits)
            } catch (_: IllegalArgumentException) {
                showError("選択した地点の通知半径・メモ・タグを確認してください。")
                return@run
            } catch (_: IllegalStateException) {
                showError("選択した地点の通知設定を確認してください。")
                return@run
            }
            val checked = LocationImportPlanner.preview(records, emptyList(), current.format)
            val message = committer.commit(checked.candidates, current.format)
            mutable.update { it.copy(savedMessage = message) }
        }
    }

    private fun run(failureMessage: String, action: suspend () -> Unit) {
        if (state.value.busy || state.value.savedMessage != null) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: TransferFileTooLarge) {
                showError("ファイルが2MB相当の上限を超えています。小さいファイルに分けて選んでください。")
            } catch (changed: ImportReviewChanged) {
                val details = changed.preview.errors + listOfNotNull(
                    changed.preview.duplicateCount.takeIf { it > 0 }?.let { "重複が${it}件あります。" },
                )
                showError((details + "登録地点が変わりました。「戻る」から読み込み直してください。").joinToString("\n"))
            } catch (_: Exception) {
                showError(failureMessage)
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    private fun showError(message: String) = mutable.update { it.copy(error = message) }

    companion object {
        const val MAX_PASTE_CHARS = 100_000
    }
}
