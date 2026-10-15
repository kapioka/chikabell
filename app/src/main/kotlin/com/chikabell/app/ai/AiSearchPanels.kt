@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.chikabell.app.ai

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chikabell.app.ui.components.WorkflowScaffold
import com.chikabell.app.ui.components.displayCoordinates

/** Owned by the ViewModel so sheets, browser returns and configuration changes retain edits. */
internal class AiReviewSelection {
    var selected by mutableStateOf<Map<Int, Int>>(emptyMap())
    var edits by mutableStateOf<Map<Int, AiPlaceEdits>>(emptyMap())
    var validationError by mutableStateOf<String?>(null)
    fun reset() { selected = emptyMap(); edits = emptyMap(); validationError = null }
    fun toggle(index: Int, place: AiPlaceToReview): Boolean {
        validationError = null
        if (index in selected) { selected = selected - index; return true }
        if (place.coordinates.size != 1) return false
        selected = selected + (index to 0)
        return true
    }
}

@Composable
internal fun AiReviewScreen(state: AiSearchState, selection: AiReviewSelection, onBack: () -> Unit, onCancel: () -> Unit, onDiscard: () -> Unit, open: (Intent) -> Unit, preview: (Map<Int, Int>, Map<Int, AiPlaceEdits>) -> Unit) {
    val review = state.review
    var placeIndex by remember { mutableStateOf<Int?>(null) }
    var notificationIndex by remember { mutableStateOf<Int?>(null) }
    var discardConfirm by remember { mutableStateOf(false) }
    var showExcluded by remember { mutableStateOf(false) }
    WorkflowScaffold(title = "場所を確認して登録", onBack = onBack, backEnabled = !state.busy,
        actions = { TextButton(enabled = !state.busy, onClick = { discardConfirm = true }) { Text("破棄") } },
        footer = {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            (selection.validationError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Text(if (selection.selected.isEmpty()) "場所と位置を確認し、登録する候補を選んでください" else "${selection.selected.size}件選択中 · 各候補の通知設定を使います", style = MaterialTheme.typography.bodySmall)
            Button(enabled = selection.selected.isNotEmpty() && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                try { preview(selection.selected, selection.edits) }
                catch (_: Exception) { selection.validationError = "通知半径・メモ・タグを確認してください" }
            }) { Text(if (state.operation == AiOperation.SAVE) "登録しています…" else "選択した${selection.selected.size}件を登録") }
        }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            item {
                Text(state.searchedFor, style = MaterialTheme.typography.bodyMedium)
                Text("${review?.candidates?.size ?: 0}件の候補", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                state.searchEvidence?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            itemsIndexed(review?.candidates.orEmpty()) { index, place ->
                val selected = index in selection.selected
                val chosen = place.coordinates.getOrNull(selection.selected[index] ?: if (place.coordinates.size == 1) 0 else -1)
                val edits = selection.edits[index] ?: place.defaultEdits()
                OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth().toggleable(selected, enabled = !state.busy, role = Role.Checkbox, onValueChange = {
                            if (!selection.toggle(index, place)) placeIndex = index
                        }), verticalAlignment = Alignment.Top) {
                            Checkbox(selected, null, enabled = !state.busy)
                            Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(place.record.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(if (selected) "登録する" else if (chosen == null) "位置の選択が必要" else "この場所を選択", color = if (chosen == null) Color(0xFF8D4F15) else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        if (place.record.message.isNotBlank()) Text(place.record.message, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                        if (chosen != null) Text(chosen.label, style = MaterialTheme.typography.bodyMedium)
                        else Text("住所候補 ${place.coordinates.size}件 · 同じ施設の位置を選んでください", style = MaterialTheme.typography.bodyMedium)
                        Text("${if (edits.enabled ?: place.record.enabled) "通知オン" else "通知オフ"} · 半径${edits.radiusMeters}m", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { placeIndex = index }, enabled = !state.busy) { Text("場所を確認") }
                            TextButton(onClick = { notificationIndex = index }, enabled = !state.busy) { Text("通知設定") }
                        }
                    }
                }
            }
            if (review?.excludedReasons?.isNotEmpty() == true) item {
                TextButton(onClick = { showExcluded = !showExcluded }) { Text("除外した候補 ${review.excludedReasons.size}件") }
                if (showExcluded) review.excludedReasons.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    placeIndex?.let { index -> review?.candidates?.getOrNull(index)?.let { place ->
        AiSheet("場所を確認", { placeIndex = null }, footer = {
            OutlinedButton(onClick = { selection.selected = selection.selected - index; placeIndex = null }, modifier = Modifier.fillMaxWidth()) { Text("この候補の選択を外す") }
        }) {
            Text(place.record.name, style = MaterialTheme.typography.titleLarge)
            Text(place.record.message)
            Text(if (state.imported) "ファイルに記載された位置です。地図と出典で確認してください。" else "住所検索の結果です。施設と位置が一致するか確認してください。")
            place.record.sourceUrl?.takeIf(AiImportText::safeSource)?.let { url ->
                TextButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text("施設の出典を開く") }
            }
            place.coordinates.forEachIndexed { coordinateIndex, coordinate ->
                OutlinedCard(onClick = {
                    selection.selected = selection.selected + (index to coordinateIndex)
                    selection.validationError = null
                    placeIndex = null
                }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(coordinate.label)
                        Text(displayCoordinates(coordinate.latitude, coordinate.longitude), style = MaterialTheme.typography.bodySmall)
                        Text(if (selection.selected[index] == coordinateIndex) "選択済み" else "この位置を選択", color = MaterialTheme.colorScheme.primary)
                    }
                }
                TextButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${coordinate.latitude},${coordinate.longitude}"))) }) { Text("この位置を地図で確認") }
            }
        }
    } }
    notificationIndex?.let { index -> review?.candidates?.getOrNull(index)?.let { place ->
        AiNotificationSheet(place, selection.edits[index] ?: place.defaultEdits(), { selection.edits = selection.edits + (index to it); selection.validationError = null }, { notificationIndex = null })
    } }
    if (discardConfirm) AlertDialog(onDismissRequest = { discardConfirm = false }, title = { Text("候補を破棄しますか？") }, text = { Text("選択と通知設定の編集を破棄します。検索条件は保持します。") },
        confirmButton = { TextButton(onClick = { discardConfirm = false; onDiscard() }) { Text("破棄する") } }, dismissButton = { TextButton(onClick = { discardConfirm = false }) { Text("戻る") } })
}

internal fun AiPlaceToReview.defaultEdits() = AiPlaceEdits(record.radiusMeters.toString(), record.message, record.tags.joinToString("|"), record.loiteringDelaySeconds.toString(), record.cooldownMinutes.toString(), record.enabled)

@Composable
internal fun AiNotificationSheet(place: AiPlaceToReview, edit: AiPlaceEdits, onEdit: (AiPlaceEdits) -> Unit, onDismiss: () -> Unit) {
    var details by remember { mutableStateOf(false) }
    val valid = runCatching { AiPlaceVerifier(com.chikabell.app.share.UnavailableAddressCandidateProvider).selectedJson(listOf(place), mapOf(0 to 0), mapOf(0 to edit)) }.isSuccess
    AiSheet("通知設定", onDismiss, footer = {
        if (!valid) Text("半径・メモ・タグ・通知の詳細を確認してください", color = MaterialTheme.colorScheme.error)
        Button(onClick = onDismiss, enabled = valid, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("この設定を使う") }
    }) {
        Text(place.record.name, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("近づいたら通知", Modifier.weight(1f)); Switch(edit.enabled ?: place.record.enabled, { onEdit(edit.copy(enabled = it)) }) }
        OutlinedTextField(edit.radiusMeters, { onEdit(edit.copy(radiusMeters = it.take(6))) }, label = { Text("通知半径（100〜5,000m）") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(edit.message, { onEdit(edit.copy(message = it.take(501))) }, label = { Text("通知メモ（500字以内）") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
        OutlinedTextField(edit.tags, { onEdit(edit.copy(tags = it.take(1000))) }, label = { Text("タグ（|で区切る・最大5件）") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
        TextButton(onClick = { details = !details }) { Text("滞在時間・再通知の詳細") }
        if (details) {
            OutlinedTextField(edit.loiteringDelaySeconds ?: place.record.loiteringDelaySeconds.toString(), { onEdit(edit.copy(loiteringDelaySeconds = it.take(6))) }, label = { Text("滞在判定まで（秒）") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(edit.cooldownMinutes ?: place.record.cooldownMinutes.toString(), { onEdit(edit.copy(cooldownMinutes = it.take(10))) }, label = { Text("再通知まで（分）") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
    }
}
