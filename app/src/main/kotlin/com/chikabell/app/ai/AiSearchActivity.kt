package com.chikabell.app.ai

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chikabell.app.MainActivity
import com.chikabell.app.geofence.CurrentLocationReader
import com.chikabell.app.importexport.LocationImportActivity
import com.chikabell.app.ui.theme.ChikaBellTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class AiSearchActivity : ComponentActivity() {
    private var runtime: AiSearchViewModel? = null
    override fun onResume() { super.onResume(); runtime?.foreground?.value = true }
    override fun onPause() { runtime?.foreground?.value = false; super.onPause() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true }
        setContent {
            ChikaBellTheme {
                val vm: AiSearchViewModel = viewModel()
                runtime = vm
                val state by vm.state.collectAsStateWithLifecycle()
                val preferences = remember { getSharedPreferences("ai-search-ui", MODE_PRIVATE) }
                var web by rememberSaveable { mutableStateOf(preferences.getBoolean("web", true)) }
                var area by rememberSaveable { mutableStateOf("") }
                var target by rememberSaveable { mutableStateOf("") }
                var conditions by rememberSaveable { mutableStateOf("") }
                var count by rememberSaveable { mutableStateOf("5") }
                var modeName by rememberSaveable { mutableStateOf(SearchAreaMode.AREA.name) }
                var km by rememberSaveable { mutableIntStateOf(3) }
                var external by rememberSaveable { mutableStateOf(false) }
                var page by rememberSaveable { mutableStateOf(AiSearchPage.SEARCH) }
                var sheet by rememberSaveable { mutableStateOf(AiSheetPage.NONE) }
                // Precise location is transient and never enters saved instance state.
                var origin by remember { mutableStateOf<SearchOrigin?>(null) }
                var message by remember { mutableStateOf<String?>(null) }
                var locating by remember { mutableStateOf(false) }
                var confirmReplace by remember { mutableStateOf(false) }
                var seenReviewVersion by rememberSaveable { mutableIntStateOf(0) }
                val focus = LocalFocusManager.current
                val keyboard = LocalSoftwareKeyboardController.current
                val mode = SearchAreaMode.valueOf(modeName)
                fun hideKeyboard() { focus.clearFocus(); keyboard?.hide() }
                fun open(intent: Intent) { if (runCatching { startActivity(intent) }.isFailure) message = "対応アプリを開けませんでした" }
                fun usage() = open(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage")))
                fun request() = AiSearchRequest(area, target, conditions, count.toIntOrNull() ?: 0, mode, km, if (mode == SearchAreaMode.DEVICE_LOCATION) origin else null)
                fun locate() {
                    if (locating) return
                    locating = true
                    origin = null
                    lifecycleScope.launch {
                        try {
                            val fix = CurrentLocationReader(this@AiSearchActivity).readCurrentLocation()
                            origin = fix?.let { SearchOrigin.from(it, System.currentTimeMillis()) }
                            message = if (origin == null) "現在地を取得できないか精度不足です。エリア指定も使えます" else null
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { message = "現在地を取得できません。エリア指定も使えます" }
                        finally { locating = false }
                    }
                }
                val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
                    if (granted.values.any { it }) locate() else message = "位置情報が許可されていません。エリアを入力して検索できます"
                }
                LaunchedEffect(vm) { vm.foreground.value = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
                LaunchedEffect(state.reviewVersion) {
                    if (state.review != null && seenReviewVersion != state.reviewVersion) {
                        seenReviewVersion = state.reviewVersion
                        page = AiSearchPage.RESULTS; sheet = AiSheetPage.NONE; hideKeyboard()
                    }
                }
                LaunchedEffect(state.savedMessage) {
                    state.savedMessage?.let {
                        Toast.makeText(this@AiSearchActivity, it, Toast.LENGTH_LONG).show()
                        startActivity(Intent(this@AiSearchActivity, MainActivity::class.java).setAction(ACTION_SHOW_LOCATIONS).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        finish()
                    }
                }
                BackHandler(enabled = state.busy || sheet != AiSheetPage.NONE || page == AiSearchPage.RESULTS) {
                    when {
                        state.operation == AiOperation.SAVE -> Unit
                        sheet != AiSheetPage.NONE -> { hideKeyboard(); sheet = AiSheetPage.NONE }
                        page == AiSearchPage.RESULTS -> page = AiSearchPage.SEARCH
                        else -> vm.cancel()
                    }
                }
                fun launchExternal() {
                    val value = request()
                    val now = System.currentTimeMillis()
                    value.validate(now)?.let { message = it; if (mode == SearchAreaMode.DEVICE_LOCATION || area.isBlank()) sheet = AiSheetPage.RANGE; return }
                    val prompt = value.prompt(now)
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ちかベルの検索依頼", prompt))
                    sheet = AiSheetPage.NONE
                    LocationImportActivity.open(this@AiSearchActivity)
                    val direct = packageManager.getLaunchIntentForPackage("com.openai.chatgpt")
                    if (direct == null || runCatching { startActivity(direct) }.isFailure) open(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/")))
                    message = "依頼文をコピーしました。ChatGPTに貼り付けて送信し、結果のJSONをコピーして戻ってください"
                }
                fun searchNow() {
                    hideKeyboard(); message = null
                    if (external) { launchExternal(); return }
                    if (state.models.isEmpty()) { sheet = AiSheetPage.ACCOUNT; return }
                    if (state.models.none { it.slug == state.selectedModel }) { sheet = AiSheetPage.OPTIONS; return }
                    val value = request()
                    val error = value.validate(System.currentTimeMillis())
                    if (error != null) { message = error; if (mode == SearchAreaMode.DEVICE_LOCATION || area.isBlank()) sheet = AiSheetPage.RANGE; return }
                    vm.search(value, state.selectedModel, web)
                }
                if (page == AiSearchPage.SEARCH) AiSearchFormScreen(
                    state = state, target = target, onTarget = { target = it.take(500); message = null },
                    conditions = conditions, onConditions = { conditions = it.take(1000); message = null },
                    rangeLabel = when (mode) { SearchAreaMode.AREA -> area.ifBlank { "エリアを選んでください" }; SearchAreaMode.DEVICE_LOCATION -> "現在地から${km}km以内" + if (origin == null) " · 未取得" else ""; SearchAreaMode.CHATGPT_LOCATION -> "ChatGPTの位置共有 · ${km}km" },
                    detailLabel = "最大${count}件" + if (!external && web) " · Webで出典を確認" else "",
                    external = external, message = message, onBack = { finish() }, onAccount = { sheet = AiSheetPage.ACCOUNT },
                    onRange = { sheet = AiSheetPage.RANGE }, onOptions = { sheet = AiSheetPage.OPTIONS }, onMethods = { sheet = AiSheetPage.METHODS },
                    onResults = { page = AiSearchPage.RESULTS }, onSearch = { if (state.review != null && !external) confirmReplace = true else searchNow() }, onCancel = vm::cancel, onUsage = ::usage,
                ) else AiReviewScreen(state, vm.selection, { page = AiSearchPage.SEARCH }, vm::cancel, { vm.clearReview(); vm.selection.reset(); page = AiSearchPage.SEARCH }, ::open, { _, _ -> vm.register() })

                when (sheet) {
                    AiSheetPage.NONE -> Unit
                    AiSheetPage.RANGE -> AiRangeSheet(area, { area = it.take(200); message = null }, mode, { modeName = it.name; origin = null; message = null }, km, { km = it }, external, origin, locating, message,
                        onLocate = {
                            if (ContextCompat.checkSelfPermission(this@AiSearchActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this@AiSearchActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) locate()
                            else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }, onDismiss = { hideKeyboard(); sheet = AiSheetPage.NONE })
                    AiSheetPage.OPTIONS -> AiOptionsSheet(state, count, { count = it.take(2) }, web, { web = it; preferences.edit().putBoolean("web", it).apply() }, external, vm::chooseModel, { hideKeyboard(); sheet = AiSheetPage.NONE })
                    AiSheetPage.ACCOUNT -> AiAccountSheet(state, { vm.select(it); sheet = AiSheetPage.NONE }, { client -> vm.connect(client) { open(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }; sheet = AiSheetPage.NONE }, vm::disconnect, vm::cancel, ::usage, { sheet = AiSheetPage.NONE })
                    AiSheetPage.METHODS -> AiSheet("探し方・取り込み", { sheet = AiSheetPage.NONE }) {
                        SummaryAction("ちかベル内で検索", "候補の確認から登録までアプリ内で", {
                            external = false; if (mode == SearchAreaMode.CHATGPT_LOCATION) modeName = SearchAreaMode.AREA.name; sheet = AiSheetPage.NONE
                        })
                        SummaryAction("ChatGPTアプリで探す", "依頼文をコピーして結果を持ち帰る", { external = true; sheet = AiSheetPage.NONE })
                        SummaryAction("結果を取り込む", "JSONを貼り付ける・ファイルを開く", {
                            sheet = AiSheetPage.NONE
                            LocationImportActivity.open(this@AiSearchActivity)
                        })
                    }
                }
                if (confirmReplace) AlertDialog(onDismissRequest = { confirmReplace = false }, title = { Text("新しい条件で検索しますか？") }, text = { Text("成功したら確認中の候補を新しい結果に置き換えます。失敗・キャンセルの場合は候補を保持します。") },
                    confirmButton = { TextButton(onClick = { confirmReplace = false; searchNow() }) { Text("検索する") } }, dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("戻る") } })
                if (state.welcome) AlertDialog(onDismissRequest = vm::acknowledgeWelcome, title = { Text("ChatGPTプランで検索できます") }, text = { Text("検索にはご自身のChatGPT利用枠を使います。送信する条件を確認してから検索してください。利用状況は検索画面から確認できます。") },
                    confirmButton = { TextButton(onClick = vm::acknowledgeWelcome) { Text("はじめる") } }, dismissButton = { TextButton(onClick = ::usage) { Text("利用状況") } })
            }
        }
    }
    companion object {
        const val EXTRA_IMPORT_JSON = "com.chikabell.app.IMPORT_JSON"
        const val MAX_AI_TEXT_CHARS = 100_000
        const val ACTION_SHOW_LOCATIONS = "com.chikabell.app.SHOW_LOCATIONS"
    }
}
