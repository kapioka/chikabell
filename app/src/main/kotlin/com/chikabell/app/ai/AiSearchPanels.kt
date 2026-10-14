package com.chikabell.app.ai

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AiConnectionPanel(state: AiSearchState, vm: AiSearchViewModel, model: String, chooseModel: (String) -> Unit, open: (Intent) -> Unit) {
    var accountMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var disconnectConfirm by remember { mutableStateOf(false) }
    Text("アプリ内でChatGPTを使う", style = MaterialTheme.typography.titleMedium)
    Text("利用者ご自身のChatGPT利用枠を使います。接続情報はこのアプリだけに保護保存します。")
    Text(state.phase)
    TextButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage"))) }) { Text("ChatGPTの利用状況") }
    if (state.accounts.isEmpty()) {
        Button(enabled = !state.busy, onClick = { vm.connect(null) { open(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }) { Text("Continue with ChatGPT") }
    } else {
        val active = state.accounts.find { it.clientId == state.activeClient }
        active?.let { Text("接続: ${it.label}") }
        Row {
            TextButton(enabled = !state.busy && active != null, onClick = { active?.let { vm.select(it.clientId) } }) { Text("保存済み接続を確認") }
            Box {
                TextButton(enabled = !state.busy, onClick = { accountMenu = true }) { Text("接続の選択") }
                DropdownMenu(accountMenu, { accountMenu = false }) {
                    state.accounts.forEach { item ->
                        DropdownMenuItem(text = { Text(item.label) }, onClick = { accountMenu = false; vm.select(item.clientId) })
                        DropdownMenuItem(text = { Text("再接続: ${item.label}") }, onClick = {
                            accountMenu = false; vm.connect(item.clientId) { open(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                        })
                    }
                    DropdownMenuItem(text = { Text("別のアカウントを接続") }, onClick = { accountMenu = false; vm.connect(null) { open(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } })
                }
            }
        }
        TextButton(enabled = !state.busy && active != null, onClick = { disconnectConfirm = true }) { Text("接続解除") }
    }
    if (state.models.isNotEmpty()) Box {
        OutlinedButton(enabled = !state.busy, onClick = { modelMenu = true }) { Text("モデル: " + state.models.find { it.slug == model }?.name.orEmpty()) }
        DropdownMenu(modelMenu, { modelMenu = false }) {
            state.models.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = { chooseModel(item.slug); modelMenu = false }) }
        }
    }
    if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = vm::cancel) { Text("キャンセル") } }
    if (disconnectConfirm) AlertDialog(onDismissRequest = { disconnectConfirm = false }, title = { Text("ChatGPTの接続を解除しますか？") },
        text = { Text("この接続の端末内情報を削除し、ChatGPTへ接続解除を要求します。登録済み地点は保持します。") },
        confirmButton = { TextButton(onClick = { disconnectConfirm = false; vm.disconnect() }) { Text("接続解除") } },
        dismissButton = { TextButton(onClick = { disconnectConfirm = false }) { Text("キャンセル") } })
}

@Composable
fun AiReviewPanel(state: AiSearchState, vm: AiSearchViewModel, open: (Intent) -> Unit, preview: (String) -> Unit) {
    val review = state.review ?: return
    var selected by remember(review) { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var edits by remember(review) { mutableStateOf<Map<Int, AiPlaceEdits>>(emptyMap()) }
    var validationError by remember(review) { mutableStateOf<String?>(null) }
    Text("位置を確認して選択", style = MaterialTheme.typography.titleMedium)
    Text("検索条件: ${state.searchedFor}")
    state.searchEvidence?.let { Text(it) }
    Text("AIの座標を採用せず、住所検索で取得した位置を表示しています。出典と地図で同じ施設か確認してください。")
    review.excludedReasons.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
    if (review.candidates.isEmpty()) Text("位置を確認できる候補がありません。条件を変えるか、Googleマップから共有して登録してください。")
    review.candidates.forEachIndexed { index, place ->
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(place.record.name, style = MaterialTheme.typography.titleSmall)
            Text(place.record.message)
            val edited = edits[index] ?: AiPlaceEdits(place.record.radiusMeters.toString(), place.record.message, place.record.tags.joinToString("|"))
            OutlinedTextField(edited.radiusMeters, { edits = edits + (index to edited.copy(radiusMeters = it.take(6))) }, label = { Text("通知半径（100〜5,000m）") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(edited.message, { edits = edits + (index to edited.copy(message = it.take(501))) }, label = { Text("通知メモ（500字以内）") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(edited.tags, { edits = edits + (index to edited.copy(tags = it.take(1000))) }, label = { Text("タグ（|で区切る・最大5件）") }, modifier = Modifier.fillMaxWidth())
            place.record.sourceUrl?.let { source -> TextButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse(source))) }) { Text("実在性と座標の出典を確認") } }
            place.coordinates.forEachIndexed { coordinateIndex, coordinate ->
                Row {
                    RadioButton(selected[index] == coordinateIndex, onClick = { selected = if (selected[index] == coordinateIndex) selected - index else selected + (index to coordinateIndex) })
                    Column {
                        Text(coordinate.label)
                        Text("${coordinate.latitude}, ${coordinate.longitude}")
                        TextButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${coordinate.latitude},${coordinate.longitude}"))) }) { Text("地図で位置を確認") }
                    }
                }
            }
            if (index in selected) TextButton(onClick = { selected = selected - index }) { Text("この候補を選択から外す") }
        } }
    }
    validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(enabled = selected.isNotEmpty() && !state.busy, onClick = {
        try { preview(vm.selectedJson(selected, edits)) }
        catch (_: IllegalArgumentException) { validationError = "通知半径・メモ・タグを確認してください。タグは最大5件・各30文字です" }
        catch (_: IllegalStateException) { validationError = "通知半径を整数で入力し、候補と位置を選択してください" }
    }) { Text("選択した${selected.size}件の取り込みを確認") }
    TextButton(onClick = vm::clearReview) { Text("候補を破棄") }
}
