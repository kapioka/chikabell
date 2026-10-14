package com.chikabell.app.ai

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chikabell.app.MainActivity
import com.chikabell.app.geofence.CurrentLocationReader
import com.chikabell.app.importexport.TransferParseResult
import com.chikabell.app.ui.theme.ChikaBellTheme
import kotlinx.coroutines.launch

class AiSearchActivity : ComponentActivity() {
    private var runtime: AiSearchViewModel? = null
    override fun onResume() { super.onResume(); runtime?.foreground?.value = true }
    override fun onPause() { runtime?.foreground?.value = false; super.onPause() }
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChikaBellTheme {
                val vm: AiSearchViewModel = viewModel()
                runtime = vm
                val nativeState by vm.state.collectAsStateWithLifecycle()
                var nativeModel by remember { mutableStateOf("") }
                var webSearch by rememberSaveable { mutableStateOf(true) }
                var nativePending by remember { mutableStateOf<AiSearchRequest?>(null) }
                LaunchedEffect(nativeState.models) { if (nativeModel !in nativeState.models.map { it.slug }) nativeModel = nativeState.models.firstOrNull()?.slug.orEmpty() }
                var area by rememberSaveable { mutableStateOf("") }
                var target by rememberSaveable { mutableStateOf("") }
                var conditions by rememberSaveable { mutableStateOf("") }
                var count by rememberSaveable { mutableStateOf("5") }
                var mode by rememberSaveable { mutableStateOf(SearchAreaMode.AREA.name) }
                var km by rememberSaveable { mutableIntStateOf(3) }
                // Location and prompts are transient and never persisted in saved state.
                var origin by remember { mutableStateOf<SearchOrigin?>(null) }
                var message by remember { mutableStateOf<String?>(null) }
                var locating by remember { mutableStateOf(false) }
                var jsonText by remember { mutableStateOf("") }
                var showPaste by rememberSaveable { mutableStateOf(false) }
                var pendingPrompt by remember { mutableStateOf<String?>(null) }
                var sharePrompt by remember { mutableStateOf(false) }
                val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    message = "位置情報の許可後に「現在地を取得」を選んでください。拒否した場合もエリア指定が使えます"
                }
                fun request() = AiSearchRequest(area, target, conditions, count.toIntOrNull() ?: 0, SearchAreaMode.valueOf(mode), km,
                    if (mode == SearchAreaMode.DEVICE_LOCATION.name) origin else null)
                fun openExternal(intent: Intent) { if (runCatching { startActivity(intent) }.isFailure) message = "対応アプリを開けませんでした" }
                fun openPreview(json: String) {
                    startActivity(Intent(this@AiSearchActivity, MainActivity::class.java).putExtra(EXTRA_IMPORT_JSON, json)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                }
                nativePending?.let { value ->
                    AlertDialog(onDismissRequest = { nativePending = null }, title = { Text("この検索条件をChatGPTへ送りますか？") },
                        text = { Text("${value.inferenceInput(value.origin?.capturedAt ?: System.currentTimeMillis())}\n\n保存済み地点や履歴は含めません。${if (webSearch) "Web検索を要求します。対応していない場合は失敗を表示します。" else "Web検索は要求しません。根拠を確認できない候補は除外します。"}") },
                        confirmButton = { TextButton(onClick = {
                            val error = value.validate(System.currentTimeMillis())
                            if (error == null) vm.search(value, nativeModel, webSearch) else message = error
                            nativePending = null
                        }) { Text("この条件で検索する") } },
                        dismissButton = { TextButton(onClick = { nativePending = null }) { Text("キャンセル") } })
                }
                fun launchChatGpt(prompt: String, shared: Boolean) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("ちかベルの検索依頼", prompt))
                    val direct = if (shared) Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, prompt); setPackage("com.openai.chatgpt")
                    } else packageManager.getLaunchIntentForPackage("com.openai.chatgpt")
                    val opened = direct != null && runCatching { startActivity(direct) }.isSuccess
                    if (!opened) runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/"))) }
                    message = "依頼文をコピーしました。ChatGPTに貼り付けて送信し、結果のJSONをコピーして戻ってください"
                }
                pendingPrompt?.let { prompt ->
                    AlertDialog(onDismissRequest = { pendingPrompt = null }, title = { Text("この現在地をChatGPTへ送りますか？") },
                        text = { Column { Text("正確な座標・精度・取得時刻と、入力した検索条件を依頼文に含めます。保存済み地点や履歴は含めません。");
                            origin?.let { Text("緯度 ${it.latitude}\n経度 ${it.longitude}\n精度 ${it.accuracyMeters}m\n取得時刻 ${it.capturedAt}") } } },
                        confirmButton = { TextButton(onClick = {
                            if (request().validate(System.currentTimeMillis()) == null) launchChatGpt(prompt, sharePrompt)
                            else message = "現在地が古くなりました。取得し直してください"
                            pendingPrompt = null
                        }) { Text("この検索だけに送信") } },
                        dismissButton = { TextButton(onClick = { pendingPrompt = null }) { Text("キャンセル") } })
                }
                Scaffold(topBar = { TopAppBar(title = { Text("AIで場所を探す") }, navigationIcon = {
                    TextButton(onClick = { finish() }) { Text("戻る") }
                }) }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("ChatGPTで候補を探し、選んだ場所を確認して登録します。", style = MaterialTheme.typography.bodyLarge)
                        AiConnectionPanel(nativeState, vm, nativeModel, { nativeModel = it }, ::openExternal)
                        Text("検索の基準", style = MaterialTheme.typography.titleMedium)
                        listOf(SearchAreaMode.AREA to "エリアを指定", SearchAreaMode.CHATGPT_LOCATION to "ChatGPT側の現在地共有を使う", SearchAreaMode.DEVICE_LOCATION to "端末の現在地を確認して送る").forEach { (value, label) ->
                            Row { RadioButton(selected = mode == value.name, onClick = { mode = value.name; origin = null });
                                TextButton(onClick = { mode = value.name; origin = null }) { Text(label) } }
                        }
                        if (mode == SearchAreaMode.AREA.name) OutlinedTextField(area, { area = it.take(200) }, label = { Text("エリア") }, modifier = Modifier.fillMaxWidth())
                        else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(1, 3, 5).forEach { distance -> FilterChip(km == distance, { km = distance }, label = { Text("${distance}km") }) }
                            }
                            if (mode == SearchAreaMode.CHATGPT_LOCATION.name) Text("ChatGPT自身の設定に依存します。ちかベルはChatGPT側の位置を取得しません。距離は目安です。")
                            else {
                                OutlinedButton(enabled = !locating, onClick = {
                                    locating = true; origin = null
                                    lifecycleScope.launch {
                                        try {
                                            val fix = CurrentLocationReader(this@AiSearchActivity).readCurrentLocation()
                                            origin = fix?.let { SearchOrigin.from(it, System.currentTimeMillis()) }
                                            message = if (origin != null) "現在地を取得しました。送信前に確認できます" else "位置を取得できないか精度不足です。エリア指定も利用できます"
                                        } finally { locating = false }
                                    }
                                }) { Text(if (locating) "取得中" else "現在地を取得") }
                                TextButton(onClick = { locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("位置情報の許可") }
                            }
                        }
                        OutlinedTextField(target, { target = it.take(500) }, label = { Text("探したい場所") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(conditions, { conditions = it.take(1000) }, label = { Text("条件（任意）") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(count, { count = it.take(2) }, label = { Text("最大件数（1〜20）") })
                        Row { Checkbox(webSearch, { webSearch = it }, enabled = !nativeState.busy); Text("アプリ内検索でWeb検索を要求する") }
                        Button(modifier = Modifier.fillMaxWidth(), enabled = !nativeState.busy && nativeModel.isNotBlank() && mode != SearchAreaMode.CHATGPT_LOCATION.name, onClick = {
                            val value = request(); val error = value.validate(System.currentTimeMillis())
                            if (error != null) message = error else nativePending = value
                        }) { Text("アプリ内で検索") }
                        if (mode == SearchAreaMode.CHATGPT_LOCATION.name) Text("ChatGPT側の現在地設定は下のコピー・共有経路で使用してください。アプリ内検索からは参照できません。")
                        AiReviewPanel(nativeState, vm, ::openExternal, ::openPreview)
                        HorizontalDivider()
                        Text("コピー・共有で探す", style = MaterialTheme.typography.titleMedium)
                        fun send(shared: Boolean) {
                            val value = request(); val now = System.currentTimeMillis(); val error = value.validate(now)
                            if (error != null) { message = error; return }
                            val prompt = value.prompt(now)
                            if (value.areaMode == SearchAreaMode.DEVICE_LOCATION) { sharePrompt = shared; pendingPrompt = prompt }
                            else launchChatGpt(prompt, shared)
                        }
                        Button(modifier = Modifier.fillMaxWidth(), onClick = { send(false) }) { Text("依頼文をコピーしてChatGPTを開く") }
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { send(true) }) { Text("ChatGPTに共有して探す") }
                        HorizontalDivider()
                        Text("検索結果を戻す", style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { showPaste = true }) { Text("JSONを貼り付け") }
                        if (showPaste) {
                            OutlinedTextField(jsonText, { jsonText = it.take(MAX_AI_TEXT_CHARS + 1) }, modifier = Modifier.fillMaxWidth(), label = { Text("コピーしたJSON本文") }, minLines = 5)
                            Button(onClick = {
                                if (jsonText.length > MAX_AI_TEXT_CHARS) { message = "AI結果が大きすぎます。件数を減らしてください"; return@Button }
                                val parsed = AiImportText.parse(jsonText)
                                if (parsed is TransferParseResult.Failure) message = parsed.message
                                else {
                                    openPreview(AiImportText.normalize(jsonText))
                                }
                            }) { Text("取り込みを確認") }
                        }
                        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_IMPORT_JSON = "com.chikabell.app.IMPORT_JSON"
        const val MAX_AI_TEXT_CHARS = 100_000
    }
}
