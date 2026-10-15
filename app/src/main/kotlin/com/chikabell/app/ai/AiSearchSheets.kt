@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.chikabell.app.ai

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.chikabell.app.ui.components.displayCoordinates
import java.text.DateFormat
import java.util.Date

/** Scrollable body and an independent action area inside the same sheet. */
@Composable
internal fun AiSheet(title: String, onDismiss: () -> Unit, footer: @Composable ColumnScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), sheetMaxWidth = 412.dp, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { focus.clearFocus(); keyboard?.hide(); onDismiss() }) { Text("閉じる") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = footer)
        }
    }
}

@Composable
internal fun AiRangeSheet(area: String, onArea: (String) -> Unit, mode: SearchAreaMode, onMode: (SearchAreaMode) -> Unit, km: Int, onKm: (Int) -> Unit,
    external: Boolean, origin: SearchOrigin?, locating: Boolean, message: String?, onLocate: () -> Unit, onDismiss: () -> Unit) {
    AiSheet("探す範囲", onDismiss, footer = {
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("この範囲を使う") }
    }) {
        RangeChoice(mode == SearchAreaMode.AREA, "エリアを入力", { onMode(SearchAreaMode.AREA) })
        if (mode == SearchAreaMode.AREA) OutlinedTextField(area, onArea, label = { Text("エリア") }, placeholder = { Text("例：大阪駅の周辺") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        RangeChoice(mode == SearchAreaMode.DEVICE_LOCATION, "端末の現在地の近く", { onMode(SearchAreaMode.DEVICE_LOCATION) })
        if (external) RangeChoice(mode == SearchAreaMode.CHATGPT_LOCATION, "ChatGPTの位置共有を使う", { onMode(SearchAreaMode.CHATGPT_LOCATION) })
        if (mode != SearchAreaMode.AREA) {
            Text("探す距離")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(1, 3, 5).forEach { distance -> FilterChip(km == distance, { onKm(distance) }, label = { Text("${distance}km以内") }) } }
        }
        if (mode == SearchAreaMode.DEVICE_LOCATION) {
            Text("「現在地も送って検索」を押したときだけ、取得した座標・精度・時刻を検索条件に含めます。", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onLocate, enabled = !locating, modifier = Modifier.fillMaxWidth()) { Text(if (locating) "現在地を取得中…" else if (origin == null) "現在地を取得" else "現在地を取得し直す") }
            origin?.let {
                Text(displayCoordinates(it.latitude, it.longitude))
                Text("精度 約${it.accuracyMeters.toInt()}m · ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.capturedAt))}取得", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (mode == SearchAreaMode.CHATGPT_LOCATION) Text("ChatGPT側の共有設定に従います。距離は目安です。")
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun RangeChoice(selected: Boolean, label: String, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = action).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null)
        Text(label, Modifier.padding(start = 12.dp))
    }
}

@Composable
internal fun AiOptionsSheet(state: AiSearchState, count: String, onCount: (String) -> Unit, web: Boolean, onWeb: (Boolean) -> Unit, external: Boolean, onModel: (String) -> Unit, onDismiss: () -> Unit) {
    AiSheet("詳細条件", onDismiss, footer = { Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("この条件を使う") } }) {
        OutlinedTextField(count, onCount, label = { Text("最大件数（1〜20）") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done))
        if (!external) {
            Text("使用するモデル", style = MaterialTheme.typography.titleMedium)
            if (state.models.isEmpty()) Text("ChatGPTへの接続後に、利用できるモデルを表示します。")
            else if (state.models.size == 1 && state.selectedModel == state.models.first().slug) Text(state.models.first().name)
            else {
                if (state.models.none { it.slug == state.selectedModel }) Text("前のモデルは利用できません。選び直してください。", color = MaterialTheme.colorScheme.error)
                state.models.forEach { model -> RangeChoice(state.selectedModel == model.slug, model.name) { onModel(model.slug) } }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Webで出典を調べる"); Text("モデルや接続の対応状況によります", style = MaterialTheme.typography.bodySmall) }
                Switch(web, onWeb)
            }
        }
    }
}

@Composable
internal fun AiAccountSheet(state: AiSearchState, onSelect: (String) -> Unit, onConnect: (String?) -> Unit, onDisconnect: () -> Unit, onCancel: () -> Unit, onUsage: () -> Unit, onDismiss: () -> Unit) {
    var disconnect by remember { mutableStateOf(false) }
    AiSheet(if (state.models.isEmpty()) "ChatGPTに接続" else "接続・利用状況", onDismiss, footer = {
        if (state.busy) OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("接続をキャンセル") }
        else Button(onClick = { onConnect(if (state.models.isEmpty()) state.activeClient else null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (state.models.isEmpty()) "Continue with ChatGPT" else "別のアカウントを接続") }
    }) {
        Text("ご自身のChatGPTプランで場所を探せます。接続後は同じ画面に戻り、検索ボタンで送信します。")
        Text("接続情報はこのアプリ内に保護して保存します。保存済みの地点や履歴は送信しません。", style = MaterialTheme.typography.bodyMedium)
        Text(state.phase, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.accounts.forEach { account ->
            OutlinedButton(onClick = { onSelect(account.clientId) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text((if (account.clientId == state.activeClient) "使用中 · " else "") + account.label) }
            TextButton(onClick = { onConnect(account.clientId) }, enabled = !state.busy) { Text("このアカウントに再接続") }
        }
        TextButton(onClick = onUsage) { Text("ChatGPTの利用状況を開く") }
        if (state.activeClient != null) TextButton(onClick = { disconnect = true }, enabled = !state.busy) { Text("このアカウントの接続を解除") }
    }
    if (disconnect) AlertDialog(onDismissRequest = { disconnect = false }, title = { Text("接続を解除しますか？") }, text = { Text("端末内の接続情報を削除し、ChatGPTへ解除を要求します。登録した場所は保持します。") },
        confirmButton = { TextButton(onClick = { disconnect = false; onDisconnect() }) { Text("接続を解除") } }, dismissButton = { TextButton(onClick = { disconnect = false }) { Text("戻る") } })
}

@Composable
internal fun AiImportSheet(text: String, onText: (String) -> Unit, error: String?, busy: Boolean, onPaste: () -> Unit, onFile: () -> Unit, onPreview: () -> Unit, onDismiss: () -> Unit) {
    var edit by rememberSaveable { mutableStateOf(false) }
    AiSheet("結果を取り込む", onDismiss, footer = {
        Button(onClick = onPreview, enabled = text.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (busy) "確認しています…" else "取り込む場所を確認") }
    }) {
        Text("ChatGPTでコピーしたJSON、または地点ファイルを読み込み、登録する場所を確認します。")
        OutlinedButton(onClick = onPaste, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("コピーしたJSONを貼り付ける") }
        OutlinedButton(onClick = onFile, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("JSON・CSVファイルを選ぶ") }
        if (text.isNotBlank()) Text("${text.length}文字のJSONを読み込みました", style = MaterialTheme.typography.bodyMedium)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = { edit = !edit }) { Text(if (edit) "本文を閉じる" else "JSON本文を入力・編集") }
        if (edit) OutlinedTextField(text, onText, label = { Text("JSON本文") }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp), isError = error != null)
    }
}
