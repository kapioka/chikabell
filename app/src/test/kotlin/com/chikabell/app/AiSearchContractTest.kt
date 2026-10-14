package com.chikabell.app

import com.chikabell.app.ai.*
import com.chikabell.app.importexport.TransferParseResult
import org.junit.Assert.*
import org.junit.Test

class AiSearchContractTest {
    private val valid = """{"schemaVersion":2,"locations":[]}"""
    @Test fun acceptsWholeJsonAndOuterFence() {
        assertEquals(valid, AiImportText.normalize("```json\n$valid\n```"))
        assertTrue(AiImportText.parse(valid) is TransferParseResult.Success)
    }
    @Test fun rejectsProseBrokenJsonAndUnknownVersion() {
        for (text in listOf("結果です\n$valid", "$valid\n補足", "```text\n$valid\n```", "{broken}", """{"schemaVersion":99,"locations":[]}""")) {
            assertTrue(text, AiImportText.parse(text) is TransferParseResult.Failure)
        }
    }
    @Test fun requiresFreshAccurateExplicitOrigin() {
        val request = AiSearchRequest(target = "喫茶店", areaMode = SearchAreaMode.DEVICE_LOCATION)
        assertNotNull(request.validate(1000000L))
        val origin = SearchOrigin(35.0, 135.0, 30f, 1000000L)
        assertNull(request.copy(origin = origin).validate(1000001L))
        assertNotNull(request.copy(origin = origin).validate(1120001L))
        assertNotNull(request.copy(origin = origin.copy(accuracyMeters = 201f)).validate(1000001L))
    }
    @Test fun promptOnlyIncludesSelectedSearchInput() {
        val request = AiSearchRequest(area = "大阪", target = "喫茶店")
        val prompt = request.prompt(0L)
        assertTrue(prompt.contains(AiSearchRequest.AI_ENTRY_URL))
        assertTrue(prompt.contains("取得・解釈に失敗"))
        assertFalse(prompt.contains("緯度"))
    }
    @Test fun checksRadiusAndHttpsSource() {
        val origin = SearchOrigin(35.0, 135.0, 5f, 0)
        assertTrue(origin.includes(35.0, 135.0, 1))
        assertFalse(origin.includes(36.0, 135.0, 5))
        assertFalse(AiImportText.safeSource("https://user:secret@example.com/"))
        assertFalse(AiImportText.safeSource("javascript:alert(1)"))
        assertTrue(AiImportText.safeSource("https://example.com/place"))
    }
    @Test fun nativeInferenceSeparatesConditionsFromContractAndRejectsChatGptLocation() {
        val request = AiSearchRequest(area = "大阪", target = "喫茶店", conditions = "車椅子利用可", count = 3)
        val input = request.inferenceInput(0)
        assertTrue(input.contains("エリア: 大阪"))
        assertTrue(input.contains("最大件数: 3"))
        assertFalse(input.contains(AiSearchRequest.AI_ENTRY_URL))
        assertThrows(IllegalArgumentException::class.java) { request.copy(areaMode = SearchAreaMode.CHATGPT_LOCATION).inferenceInput(0) }
    }
    @Test fun nativeInferenceCannotSendStaleLocation() {
        val request = AiSearchRequest(target = "喫茶店", areaMode = SearchAreaMode.DEVICE_LOCATION, distanceKm = 1,
            origin = SearchOrigin(35.0, 135.0, 10f, 1000L))
        assertTrue(request.inferenceInput(1001L).contains("半径1km"))
        assertThrows(IllegalArgumentException::class.java) { request.inferenceInput(121001L) }
    }
}
