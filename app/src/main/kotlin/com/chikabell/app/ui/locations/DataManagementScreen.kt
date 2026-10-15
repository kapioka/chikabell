@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.chikabell.app.ui.locations

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chikabell.app.ai.AiImportText
import com.chikabell.app.ai.AiSearchActivity
import com.chikabell.app.ui.components.WorkflowScaffold
import com.chikabell.app.ui.components.WorkflowSectionTitle
import com.chikabell.app.ui.components.displayCoordinates

@Composable
internal fun DataManagementScreen(
    uiState: LocationsUiState,
    onBack: () -> Unit,
    onExportJson: () -> Unit,
    onExportCsv: () -> Unit,
    onImportJson: () -> Unit,
    onImportCsv: () -> Unit,
    onConfirmImport: () -> Unit,
    onToggleImportCandidate: (Int) -> Unit,
    onCancelImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview = uiState.importPreview
    val context = LocalContext.current
    var openError by remember { mutableStateOf<String?>(null) }
    fun open(url: String) {
        if (runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isFailure) {
            openError = "対応アプリを開けませんでした。"
        }
    }
    BackHandler(enabled = preview != null) { if (!uiState.isImporting) onCancelImport() }
    if (preview != null) {
        WorkflowScaffold(
            title = "取り込む場所を確認", modifier = modifier,
            onBack = onCancelImport, backEnabled = !uiState.isImporting,
            footer = {
                if (uiState.isImporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("${uiState.importSelectedIndices.size}件を選択中 · 既存の地点は保持します", style = MaterialTheme.typography.bodySmall)
                Button(
                    enabled = preview.canApply && uiState.importSelectedIndices.isNotEmpty() && !uiState.isImporting,
                    onClick = onConfirmImport, modifier = Modifier.fillMaxWidth(),
                ) { Text(if (uiState.isImporting) "追加しています…" else "選択した${uiState.importSelectedIndices.size}件を追加") }
                TextButton(enabled = !uiState.isImporting, onClick = onCancelImport, modifier = Modifier.fillMaxWidth()) { Text("取り込みをキャンセル") }
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    WorkflowSectionTitle("登録前の最終確認", "場所・位置・通知内容を確認してください。不要な候補は選択を外せます。")
                    Spacer(Modifier.height(12.dp))
                    Text("読み込み ${preview.totalCount}件 · 新規 ${preview.candidates.size}件 · 重複 ${preview.duplicateCount}件", style = MaterialTheme.typography.bodyMedium)
                    Text("重複する地点は追加されません。既存地点の削除・上書きは行いません。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (preview.errors.isNotEmpty()) item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("このままでは追加できません", fontWeight = FontWeight.SemiBold)
                            preview.errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Text("取り込みをキャンセルし、元のデータを修正して読み込み直してください。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (preview.candidates.isEmpty()) item { Text("追加できる新しい地点がありません。") }
                uiState.registrationMessage?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                openError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                itemsIndexed(preview.candidates) { index, candidate ->
                    val draft = candidate.draft
                    val selected = index in uiState.importSelectedIndices
                    var details by rememberSaveable(preview, index) { mutableStateOf(false) }
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                Modifier.fillMaxWidth().toggleable(selected, enabled = !uiState.isImporting, role = Role.Checkbox, onValueChange = { onToggleImportCandidate(index) }),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(selected, onCheckedChange = null, enabled = !uiState.isImporting)
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(draft.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(if (selected) "この場所を追加する" else "今回は追加しない", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Text(displayCoordinates(draft.latitude, draft.longitude), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = { open("https://www.google.com/maps/search/?api=1&query=${draft.latitude},${draft.longitude}") }, modifier = Modifier.fillMaxWidth()) { Text("地図で位置を確認") }
                            draft.sourceUrl?.let { source ->
                                TextButton(enabled = AiImportText.safeSource(source), onClick = { open(source) }, modifier = Modifier.fillMaxWidth()) { Text("施設の出典を開く") }
                            }
                            HorizontalDivider()
                            Text("${if (draft.enabled) "通知オン" else "通知オフ"} · 半径${draft.radiusMeters}m", style = MaterialTheme.typography.labelLarge)
                            if (draft.message.isNotBlank()) Text(draft.message, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { details = !details }) { Text(if (details) "通知の詳細を閉じる" else "通知の詳細を確認") }
                            if (details) {
                                Text("滞在判定：${draft.loiteringDelaySeconds}秒\n再通知まで：${draft.cooldownMinutes / 60.0}時間", style = MaterialTheme.typography.bodySmall)
                                if (draft.tags.isNotEmpty()) Text("タグ：${draft.tags.joinToString("・")}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        return
    }
    WorkflowScaffold(title = "データ管理", onBack = onBack, modifier = modifier) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            uiState.registrationMessage?.let { item {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Text(it, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
            } }
            item {
                WorkflowSectionTitle("場所を取り込む", "ChatGPTの結果や地点ファイルから追加します。次に場所と通知を確認します。")
                OutlinedButton(onClick = {
                    context.startActivity(Intent(context, AiSearchActivity::class.java).putExtra(AiSearchActivity.EXTRA_PASTE, true))
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("JSONを貼り付ける・ファイルを開く") }
            }
            item {
                DataTransferSection("地点のバックアップ", "地点をJSONファイルに保存します。読み込むと、新しい地点だけを追加します。",
                    "バックアップを保存（JSON）", "JSONファイルから追加", onExportJson, onImportJson)
            }
            item {
                DataTransferSection("PCで編集・一括追加", "地点をCSVに書き出してPCで編集し、まとめて追加できます。",
                    "編集用CSVを書き出す", "CSVファイルから追加", onExportCsv, onImportCsv)
            }
            item {
                Text("保存したファイルには位置情報やメモが含まれます。安全な場所で管理してください。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DataTransferSection(title: String, description: String, exportLabel: String, importLabel: String, onExport: () -> Unit, onImport: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WorkflowSectionTitle(title, description)
        OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text(exportLabel) }
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text(importLabel) }
    }
}
