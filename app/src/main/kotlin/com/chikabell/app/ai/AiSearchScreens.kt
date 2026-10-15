package com.chikabell.app.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.chikabell.app.ui.components.WorkflowScaffold

internal enum class AiSearchPage { SEARCH, RESULTS }
internal enum class AiSheetPage { NONE, RANGE, OPTIONS, ACCOUNT, METHODS, IMPORT }

@Composable
internal fun AiSearchFormScreen(
    state: AiSearchState,
    target: String, onTarget: (String) -> Unit,
    conditions: String, onConditions: (String) -> Unit,
    rangeLabel: String, detailLabel: String,
    external: Boolean, message: String?,
    onBack: () -> Unit, onAccount: () -> Unit,
    onRange: () -> Unit, onOptions: () -> Unit, onMethods: () -> Unit,
    onResults: () -> Unit, onSearch: () -> Unit, onCancel: () -> Unit, onUsage: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit(action: () -> Unit) { focus.clearFocus(); keyboard?.hide(); action() }
    WorkflowScaffold(
        title = "AIで場所を探す", onBack = onBack,
        actions = { TextButton(onClick = { submit(onAccount) }) { Text(if (state.models.isNotEmpty()) "接続済み" else "ChatGPT") } },
        footer = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (external) "ChatGPTアプリで検索" else if (state.models.isNotEmpty()) "ChatGPTプランを使用中" else "ご自身のChatGPTプランで検索", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onUsage) { Text("利用状況") }
            }
            if (!state.busy) Text(if (rangeLabel.startsWith("現在地")) "検索条件と取得した現在地を送ります" else "入力したエリアと検索条件を送ります", style = MaterialTheme.typography.bodySmall)
            if (state.usageLimit) Button(onClick = onUsage, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("利用状況を確認") }
            else if (state.busy) OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("処理をキャンセル") }
            else Button(onClick = { submit(onSearch) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (external) "依頼文をコピーしてChatGPTを開く" else if (rangeLabel.startsWith("現在地")) "現在地も送って検索" else "ChatGPTで検索")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("次の寄り道を見つけよう", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("探したあと、場所と通知を確認して登録します。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(target, onTarget, label = { Text("探したい場所") }, placeholder = { Text("例：ゆっくり話せるカフェ") }, modifier = Modifier.fillMaxWidth(), maxLines = 3,
                enabled = !state.busy, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
            SummaryAction("探す範囲", rangeLabel, onRange, enabled = !state.busy)
            OutlinedTextField(conditions, onConditions, label = { Text("希望する条件（任意）") }, placeholder = { Text("例：禁煙・ランチあり") }, modifier = Modifier.fillMaxWidth(), maxLines = 3,
                enabled = !state.busy, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }))
            SummaryAction("詳細条件", detailLabel, onOptions, enabled = !state.busy)
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.phase, style = MaterialTheme.typography.bodyMedium)
                if (state.operation == AiOperation.CONNECTION) Text("認証を開いた場合は、完了後にちかベルへ戻ってください。", style = MaterialTheme.typography.bodySmall)
            }
            (message ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (state.usageLimit && !state.busy) TextButton(onClick = { submit(onSearch) }) { Text("利用枠を確認したので、もう一度検索") }
            if (!state.busy && state.selectedModel.isNotBlank() && state.models.none { it.slug == state.selectedModel }) {
                Text("前に選んだモデルを利用できません。詳細条件でモデルを選び直してください。", color = MaterialTheme.colorScheme.error)
            }
            if (state.review != null) OutlinedButton(onClick = { submit(onResults) }, modifier = Modifier.fillMaxWidth()) { Text("確認中の候補に戻る") }
            TextButton(onClick = { submit(onMethods) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("別の方法で探す・結果を取り込む") }
        }
    }
}

@Composable
internal fun SummaryAction(title: String, summary: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedCard(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(summary, style = MaterialTheme.typography.bodyLarge)
            }
            Text("変更", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Bounded editor: neither a long paste nor the IME may move the action off screen. */
@Composable
internal fun AiJsonPasteScreen(text: String, onText: (String) -> Unit, error: String?, onBack: () -> Unit, onPasteClipboard: () -> Unit, onPreview: () -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    WorkflowScaffold(title = "JSONを取り込む", onBack = onBack,
        actions = { TextButton(onClick = onPasteClipboard) { Text("貼り付け") } },
        footer = {
            Button(onClick = { focus.clearFocus(); keyboard?.hide(); onPreview() }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("取り込む場所を確認") }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { Text(it, Modifier.heightIn(max = 80.dp).verticalScroll(rememberScrollState()), color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(text, onText, modifier = Modifier.fillMaxWidth().weight(1f), label = { Text("JSON本文") }, isError = error != null, textStyle = MaterialTheme.typography.bodyMedium)
        }
    }
}
