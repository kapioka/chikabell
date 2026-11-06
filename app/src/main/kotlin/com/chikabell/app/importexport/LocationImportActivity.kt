package com.chikabell.app.importexport

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chikabell.app.ai.AiOperation
import com.chikabell.app.ai.AiReviewScreen
import com.chikabell.app.ai.AiSearchState
import com.chikabell.app.ui.components.WorkflowScaffold
import com.chikabell.app.ui.theme.ChikaBellTheme

class LocationImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChikaBellTheme {
                val vm: LocationImportViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val focus = LocalFocusManager.current
                val keyboard = LocalSoftwareKeyboardController.current
                val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) vm.reviewFile(uri)
                }
                val review = state.review
                fun hideKeyboard() {
                    focus.clearFocus()
                    keyboard?.hide()
                }
                BackHandler(enabled = state.busy || review != null) {
                    if (!state.busy) vm.backToInput()
                }
                LaunchedEffect(state.savedMessage) {
                    state.savedMessage?.let {
                        Toast.makeText(this@LocationImportActivity, it, Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
                if (review == null) {
                    LocationImportScreen(
                        text = state.json,
                        onText = vm::editJson,
                        error = state.error,
                        busy = state.busy,
                        onPaste = {
                            val clipboard = getSystemService(ClipboardManager::class.java)
                            val clip = clipboard?.primaryClip
                            vm.pasteJson(clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text)
                        },
                        onFile = {
                            hideKeyboard()
                            filePicker.launch(arrayOf(
                                "application/json", "text/json", "text/csv", "text/comma-separated-values",
                                "text/plain", "application/octet-stream",
                            ))
                        },
                        onPreview = { hideKeyboard(); vm.reviewJson() },
                        onBack = ::finish,
                    )
                } else {
                    AiReviewScreen(
                        state = AiSearchState(
                            review = review,
                            imported = true,
                            busy = state.busy,
                            operation = if (state.busy) AiOperation.SAVE else AiOperation.NONE,
                            error = state.error,
                            searchedFor = "${state.format.name} · 重複${state.duplicateCount}件を除外",
                            searchEvidence = "取り込んだデータの位置です。地図や出典で確認できます。",
                        ),
                        selection = vm.selection,
                        onBack = vm::backToInput,
                        onCancel = {},
                        onDiscard = vm::backToInput,
                        open = { intent ->
                            if (runCatching { startActivity(intent) }.isFailure) {
                                Toast.makeText(this@LocationImportActivity, "対応アプリを開けませんでした", Toast.LENGTH_SHORT).show()
                            }
                        },
                        preview = { _, _ -> vm.register() },
                    )
                }
            }
        }
    }

    companion object {
        fun open(context: Context) = context.startActivity(Intent(context, LocationImportActivity::class.java))
    }
}

@Composable
internal fun LocationImportScreen(
    text: String,
    onText: (String) -> Unit,
    error: String?,
    busy: Boolean,
    onPaste: () -> Unit,
    onFile: () -> Unit,
    onPreview: () -> Unit,
    onBack: () -> Unit,
) {
    var edit by rememberSaveable { mutableStateOf(false) }
    WorkflowScaffold(title = "地点を取り込む", onBack = onBack, backEnabled = !busy, footer = {
        Button(
            onClick = onPreview,
            enabled = text.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(if (busy) "確認しています…" else "取り込む場所を確認") }
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onPaste, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("コピーしたJSONを貼り付ける")
            }
            Column {
                OutlinedButton(onClick = onFile, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("JSON・CSVファイルを選ぶ")
                }
                Text("バックアップJSONやPCで編集したCSVを選べます。", style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (text.isNotBlank()) Text("JSON本文 ${text.length}文字", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { edit = !edit }, enabled = !busy) {
                Text(if (edit) "本文を閉じる" else "JSON本文を入力・編集")
            }
            if (edit) OutlinedTextField(
                text, onText, enabled = !busy, label = { Text("JSON本文") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp),
            )
        }
    }
}
