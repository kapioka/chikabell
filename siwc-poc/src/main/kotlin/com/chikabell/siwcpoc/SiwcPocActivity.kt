package com.chikabell.siwcpoc

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chikabell.siwc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PocState(val phase: String = "未接続", val accounts: List<Pair<String, String>> = emptyList(), val activeLabel: String? = null, val models: List<ChatGptModel> = emptyList(), val output: String = "", val busy: Boolean = false)

class SiwcPocViewModel(application: Application) : AndroidViewModel(application) {
    private val storage = SiwcStorage(application)
    private val transport = SiwcTransport()
    private val mutex = Mutex()
    private var operation: Job? = null
    val foreground = MutableStateFlow(false)
    private val mutable = MutableStateFlow(PocState())
    val state: StateFlow<PocState> = mutable.asStateFlow()
    init { viewModelScope.launch { runCatching { accountLabels() }.onFailure { mutable.value = PocState(phase = "保護保存を開けません。新規接続で上書きせず原因を確認してください") } } }
    private suspend fun accountLabels() = withContext(Dispatchers.IO) {
        val active = storage.active()
        val labels = storage.accounts().map { it.clientId to (it.email.ifBlank { "ChatGPT" } + " / " + it.clientId.takeLast(8)) }
        mutable.update { it.copy(accounts = labels, activeLabel = labels.find { pair -> pair.first == active?.clientId }?.second) }
    }
    private fun run(action: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, output = "") }
        operation = viewModelScope.launch {
            try { mutex.withLock { action() } }
            catch (cancelled: CancellationException) { mutable.update { it.copy(phase = "キャンセルしました") }; throw cancelled }
            catch (error: Exception) {
                val reason = when {
                    error is java.net.UnknownHostException -> "名前解決に失敗"
                    error is java.net.SocketTimeoutException -> "通信時間切れ"
                    error is javax.net.ssl.SSLException -> "TLS接続を検証できません"
                    error is java.net.SocketException -> "ネットワーク接続が中断されました"
                    error.message == "response_too_large" -> "応答が受信サイズ上限を超えました"
                    error.message == "response_timeout" -> "一覧応答の受信時間が上限を超えました"
                    error.message == "response_json_invalid" -> "一覧応答がJSON形式ではありません"
                    error.message == "response_object_invalid" -> "一覧応答がJSONオブジェクトではありません"
                    error.message == "model_catalog_invalid" -> "モデル一覧のmodels配列がありません"
                    error.message == "model_catalog_empty" -> "表示可能なモデルがありません"
                    error.message?.matches(Regex("http_[0-9]{3}")) == true -> "HTTP " + error.message!!.removePrefix("http_")
                    error.message in setOf("plan_permission_missing", "reauthorize", "authorization_denied", "authorization_timeout", "inference_failed", "stream_interrupted", "empty_response", "account_mismatch") -> when (error.message) {
                        "plan_permission_missing" -> "ChatGPTプラン利用の許可がありません"
                        "reauthorize" -> "再接続が必要です"
                        "authorization_denied" -> "認証が拒否されました"
                        "authorization_timeout" -> "認証待ちの時間切れ"
                        "inference_failed" -> "推論が失敗または未完了"
                        "stream_interrupted" -> "完了イベント前に通信が終了"
                        "empty_response" -> "完了応答の本文が空"
                        else -> "アカウントが一致しません"
                    }
                    else -> "応答または保護保存を検証できません"
                }
                mutable.update { it.copy(phase = it.phase + " / " + reason) }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun connect(client: String?, openBrowser: (String) -> Unit) = run {
        val selected = withContext(Dispatchers.IO) { storage.accounts().find { it.clientId == client } }
        mutable.update { it.copy(phase = "ブラウザ認証待ち") }
        LoopbackAuthorization().use { listener ->
            val host = withContext(Dispatchers.IO) { storage.hostId() }
            openBrowser(SiwcProtocol.authorization(listener.attempt, "ちかベル ChatGPT 接続確認", host, selected))
            val code = listener.await(selected, getApplication<Application>().packageName)
            mutable.update { it.copy(phase = "認証応答受領 / アプリへの復帰待ち") }
            withTimeout(60_000) { foreground.first { it } }
            kotlin.coroutines.coroutineContext.ensureActive()
            mutable.update { it.copy(phase = "コード交換・ID token検証") }
            val account = transport.exchange(code, listener.attempt)
            require(selected == null || account.subject == selected.subject) { "account_mismatch" }
            require(account.canInfer) { "plan_permission_missing" }
            kotlin.coroutines.coroutineContext.ensureActive()
            withContext(Dispatchers.IO) { storage.save(account) }
            accountLabels()
            mutable.update { it.copy(phase = "認証成功 / モデル取得") }
            mutable.update { it.copy(models = transport.models(account), phase = "認証・許可・モデル取得成功。短文応答は未確認") }
        }
    }
    private suspend fun active(): ChatGptAccount {
        val current = withContext(Dispatchers.IO) { storage.active() } ?: error("reauthorize")
        if (current.expiresAt > System.currentTimeMillis() + 60_000) return current
        mutable.update { it.copy(phase = "接続期限を更新") }
        val refreshed = transport.refresh(current)
        kotlin.coroutines.coroutineContext.ensureActive()
        withContext(Dispatchers.IO) { storage.save(refreshed) }
        return refreshed
    }
    fun select(client: String) = run {
        val selected = withContext(Dispatchers.IO) { storage.accounts().find { it.clientId == client } } ?: error("reauthorize")
        val usable = if (selected.expiresAt > System.currentTimeMillis() + 60_000) selected else transport.refresh(selected)
        require(usable.canInfer) { "plan_permission_missing" }
        val models = transport.models(usable)
        kotlin.coroutines.coroutineContext.ensureActive()
        withContext(Dispatchers.IO) { storage.save(usable) }
        accountLabels()
        mutable.update { it.copy(models = models, phase = "アカウントを切り替えました") }
    }
    fun infer(model: String, text: String, webSearch: Boolean) = run {
        require(text.isNotBlank() && text.length <= 1000)
        val account = active()
        require(model in transport.models(account).map { it.slug }) { "model_unavailable" }
        mutable.update { it.copy(phase = "応答受信中") }
        val result = transport.infer(account, model, text, "接続確認です。利用者の問いに短く答えてください。", webSearch)
        kotlin.coroutines.coroutineContext.ensureActive()
        mutable.update { it.copy(phase = "response.completed と本文受信成功" + (if (webSearch) "（Web検索指定）" else ""), output = result) }
    }
    fun signOut() = run {
        val account = withContext(Dispatchers.IO) { storage.active() } ?: return@run
        mutable.update { it.copy(phase = "接続解除") }
        val revoked = transport.revoke(account)
        withContext(Dispatchers.IO) { storage.clearTokens(account.clientId) }
        accountLabels()
        mutable.update { it.copy(models = emptyList(), phase = if (revoked) "接続解除済み" else "端末の接続情報を削除。遠隔解除は未確認です。ChatGPT設定からも解除できます") }
    }
    fun cancel() { operation?.cancel() }
}

class SiwcPocActivity : ComponentActivity() {
    private var runtime: SiwcPocViewModel? = null
    override fun onResume() { super.onResume(); runtime?.foreground?.value = true }
    override fun onPause() { runtime?.foreground?.value = false; super.onPause() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: SiwcPocViewModel = viewModel()
            runtime = vm
            val state by vm.state.collectAsStateWithLifecycle()
            var model by remember { mutableStateOf("") }
            var text by remember { mutableStateOf("日本語で、接続確認成功、と短く返してください。") }
            var webSearch by remember { mutableStateOf(false) }
            var choosingModel by remember { mutableStateOf(false) }
            LaunchedEffect(state.models) { if (model !in state.models.map { it.slug }) model = state.models.firstOrNull()?.slug.orEmpty() }
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("ChatGPT接続確認", style = MaterialTheme.typography.headlineSmall)
                        Text("地点や通知履歴を読み書きしない独立した確認用アプリです。本人のChatGPT利用同意が必要です。")
                        Text(state.phase)
                        state.activeLabel?.let { Text("現在の接続: $it") }
                        Button(enabled = !state.busy, onClick = { vm.connect(null) { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) { Text("Continue with ChatGPT") }
                        state.accounts.forEach { (client, label) ->
                            Row {
                                TextButton(enabled = !state.busy, onClick = { vm.connect(client) { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) { Text("再接続: $label") }
                                TextButton(enabled = !state.busy, onClick = { vm.select(client) }) { Text("選択") }
                            }
                        }
                        if (state.models.isNotEmpty()) TextButton(enabled = !state.busy, onClick = { choosingModel = true }) {
                            Text("モデル: " + state.models.find { it.slug == model }?.name.orEmpty() + "（変更）")
                        }
                        OutlinedTextField(text, { text = it.take(1000) }, label = { Text("確認用の短い問い") }, modifier = Modifier.fillMaxWidth())
                        Row { Checkbox(webSearch, { webSearch = it }); Text("Web検索の可否も試す（利用枠を使用）") }
                        Button(enabled = !state.busy && model.isNotBlank(), onClick = { vm.infer(model, text, webSearch) }) { Text("この問いを送信") }
                        if (state.busy) TextButton(onClick = vm::cancel) { Text("キャンセル") }
                        TextButton(enabled = !state.busy && state.activeLabel != null, onClick = vm::signOut) { Text("接続解除") }
                        if (state.output.isNotBlank()) Text(state.output)
                        TextButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage"))) }) { Text("ChatGPTの利用状況を開く") }
                    }
                    if (choosingModel) AlertDialog(onDismissRequest = { choosingModel = false }, title = { Text("使用するモデル") },
                        text = { LazyColumn(Modifier.heightIn(max = 400.dp)) { items(state.models) { item ->
                            TextButton(onClick = { model = item.slug; choosingModel = false }) { Text(item.name) }
                        } } }, confirmButton = { TextButton(onClick = { choosingModel = false }) { Text("閉じる") } })
                }
            }
        }
    }
}
