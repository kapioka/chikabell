package com.chikabell.app

import android.view.WindowInsets
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chikabell.app.ai.*
import com.chikabell.app.importexport.*
import com.chikabell.app.share.AddressCoordinateCandidate
import com.chikabell.app.ui.theme.ChikaBellTheme
import com.chikabell.app.ui.locations.AddLocationMethodsSheet
import com.chikabell.siwc.ChatGptModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated emulator fixtures. No network, real location, clipboard, credentials or production data. */
@RunWith(AndroidJUnit4::class)
class AiWorkflowUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<UiWorkflowTestActivity>()
    private val place = AiPlaceToReview(LocationTransferRecord(null, "架空の施設", "通知メモ", 35.0, 135.0, 300, 60, 720, false, "https://example.com"), listOf(AddressCoordinateCandidate("テスト用の住所", 35.0, 135.0)))
    private val connected = AiSearchState(models = listOf(ChatGptModel("fake", "Test model")), selectedModel = "fake")
    private val reviewed = connected.copy(review = AiPlaceVerificationResult(listOf(place), emptyList()), searchedFor = "架空エリアのカフェ")

    @Composable private fun Search(state: AiSearchState = connected, search: () -> Unit = {}, account: () -> Unit = {}) {
        var conditions by remember { mutableStateOf("") }
        AiSearchFormScreen(state, "公園", {}, conditions, { conditions = it }, "架空のエリア", "最大5件", false, null, {}, account, {}, {}, {}, {}, search, {}, {})
    }

    @Test fun registrationFromUnifiedAddEntryUsesOneFinalReview() {
        var registered = 0
        val selection = AiReviewSelection()
        compose.setContent {
            var page by remember { mutableIntStateOf(0) }
            var addMethods by remember { mutableStateOf(false) }
            ChikaBellTheme {
                Box(Modifier.width(360.dp).fillMaxHeight()) {
                    when (page) {
                        0 -> Button(onClick = { addMethods = true }) { Text("地点を追加") }
                        1 -> Search(search = { page = 2 })
                        2 -> AiReviewScreen(reviewed, selection, { page = 1 }, {}, {}, {}, { _, _ -> registered++; page = 0 })
                    }
                    if (addMethods) AddLocationMethodsSheet(
                        { addMethods = false },
                        { addMethods = false; page = 1 },
                        { error("AI route must not open the manual editor") },
                    )
                }
            }
        }
        compose.onNodeWithText("地点を追加").performClick()
        compose.onNodeWithText("AIで探す").performClick()
        compose.onNodeWithText("ChatGPTで検索").performClick()
        compose.onNodeWithText("選択した0件を登録").assertIsNotEnabled()
        compose.onNodeWithText("架空の施設").performScrollTo().performClick()
        compose.onNodeWithText("選択した1件を登録").performClick()
        compose.onNodeWithText("地点を追加").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, registered) }
    }

    @Test fun searchActionRemainsAboveKeyboardWhileTyping() {
        var searches = 0
        compose.setContent { ChikaBellTheme { Box(Modifier.width(360.dp).fillMaxHeight()) { Search(search = { searches++ }) } } }
        compose.onNodeWithText("希望する条件（任意）").performScrollTo().performClick().performTextInput("静かな場所")
        waitForKeyboard()
        val action = compose.onNodeWithText("ChatGPTで検索").assertIsDisplayed().assertIsEnabled()
        assertAboveKeyboard(action)
        action.performClick()
        compose.runOnIdle { assertEquals(1, searches) }
    }

    @Test fun unconnectedSearchCanOpenConnectionInsteadOfBeingDisabled() {
        var connections = 0
        compose.setContent { ChikaBellTheme { Search(state = AiSearchState(), search = { connections++ }) } }
        compose.onNodeWithText("ChatGPTで検索").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, connections) }
    }

    @Test fun longJsonKeepsPreviewButtonAboveRealKeyboard() {
        var previews = 0
        compose.setContent {
            var text by remember { mutableStateOf("{\n" + "  \"fictional\": \"test only\",\n".repeat(100) + "}") }
            ChikaBellTheme { Box(Modifier.width(360.dp).fillMaxHeight()) { AiJsonPasteScreen(text, { text = it }, null, {}, {}, { previews++ }) } }
        }
        compose.onNode(hasSetTextAction()).performClick()
        waitForKeyboard()
        val action = compose.onNodeWithText("取り込む場所を確認").assertIsDisplayed().assertIsEnabled()
        assertAboveKeyboard(action)
        action.performClick()
        compose.runOnIdle { assertEquals(1, previews) }
    }

    @Test fun actualImportSheetCollapsesLongJsonAndRetainsActionWhileEditing() {
        var previews = 0
        compose.setContent {
            var text by remember { mutableStateOf("{\n" + "fake\n".repeat(500) + "}") }
            ChikaBellTheme { AiImportSheet(text, { text = it }, null, false, {}, {}, { previews++ }, {}) }
        }
        compose.onNodeWithText("取り込む場所を確認").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("JSON本文").assertDoesNotExist()
        compose.onNodeWithText("JSON本文を入力・編集").performScrollTo().performClick()
        compose.onNodeWithText("JSON本文").performScrollTo().performClick()
        waitForKeyboard()
        val action = compose.onNodeWithText("取り込む場所を確認").assertIsDisplayed()
        assertAboveKeyboard(action)
        action.performClick()
        compose.runOnIdle { assertEquals(1, previews) }
    }

    @Test fun pasteAtLargeFontInShortViewportRetainsEditorAndAction() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ChikaBellTheme { Box(Modifier.width(360.dp).height(520.dp)) { AiJsonPasteScreen("{\n" + "fake\n".repeat(100) + "}", {}, "JSON本文を確認してください", {}, {}, {}) } }
            }
        }
        val action = compose.onNodeWithText("取り込む場所を確認").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val editor = compose.onNode(hasSetTextAction()).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(editor.height > 40f)
        assertTrue(editor.bottom <= action.top)
    }

    @Test fun largeFontSearchKeepsPrimaryActionAndConditionsCanScrollIntoView() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ChikaBellTheme { Box(Modifier.width(360.dp).height(700.dp)) { Search() } }
            }
        }
        compose.onNodeWithText("ChatGPTで検索").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("希望する条件（任意）").performScrollTo().assertIsDisplayed()
    }

    @Test fun ambiguousLocationNeedsChoiceBeforeRegistration() {
        val selection = AiReviewSelection()
        val multiple = place.copy(coordinates = place.coordinates + AddressCoordinateCandidate("別の住所", 36.0, 135.0))
        compose.setContent { ChikaBellTheme { AiReviewScreen(reviewed.copy(review = AiPlaceVerificationResult(listOf(multiple), emptyList())), selection, {}, {}, {}, {}, { _, _ -> }) } }
        compose.onNodeWithText("架空の施設").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(selection.selected.isEmpty()) }
        compose.onAllNodesWithText("この位置を選択")[1].performScrollTo().performClick()
        compose.onNodeWithText("選択した1件を登録").assertIsEnabled()
        compose.runOnIdle { assertEquals(mapOf(0 to 1), selection.selected) }
    }

    @Test fun notificationSheetEditIsRetainedAndFinalActionRemainsAboveKeyboard() {
        val selection = AiReviewSelection()
        compose.setContent { ChikaBellTheme { AiReviewScreen(reviewed, selection, {}, {}, {}, {}, { _, _ -> }) } }
        compose.onNodeWithText("架空の施設").performScrollTo().performClick()
        compose.onNodeWithText("通知設定").performScrollTo().performClick()
        compose.onNodeWithText("通知半径（100〜5,000m）").performScrollTo().performClick().performTextReplacement("500")
        waitForKeyboard()
        val action = compose.onNodeWithText("この設定を使う").assertIsDisplayed()
        assertAboveKeyboard(action)
        action.performClick()
        compose.onNodeWithText("選択した1件を登録").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { assertEquals("500", selection.edits[0]?.radiusMeters) }
    }

    private fun keyboardWindow() = WindowInspector.getGlobalWindowViews().lastOrNull { it.hasWindowFocus() && it.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
    private fun waitForKeyboard() {
        compose.waitUntil(10_000) { keyboardWindow() != null }
        compose.waitForIdle()
    }
    private fun assertAboveKeyboard(action: SemanticsNodeInteraction) {
        val bottom = action.fetchSemanticsNode().boundsInWindow.bottom
        val decor = keyboardWindow() ?: error("IME not visible")
        val keyboardTop = decor.height - decor.rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom
        assertTrue("Action must be above keyboard: $bottom <= $keyboardTop", bottom <= keyboardTop + 1)
    }
}
