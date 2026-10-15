package com.chikabell.app

import com.chikabell.app.ai.*
import com.chikabell.app.importexport.*
import com.chikabell.app.share.*
import com.chikabell.siwc.ChatGptModel
import org.junit.Assert.*
import org.junit.Test

class AiSearchExperienceTest {
    private val record = LocationTransferRecord("import-id", "架空の施設", "memo", 1.0, 2.0, 300, 60, 720, false, "https://example.com")
    private val place = AiPlaceToReview(record, listOf(AddressCoordinateCandidate("確認した住所", 35.0, 135.0)))

    @Test fun savedModelIsReusedAndUnavailableSelectionIsNotSilentlyReplaced() {
        val models = listOf(ChatGptModel("a", "Model A"), ChatGptModel("b", "Model B"))
        assertEquals("a", resolveAiModel(null, models))
        assertEquals("b", resolveAiModel("b", models))
        assertEquals("gone", resolveAiModel("gone", models))
        assertEquals("b", resolveAiModel("b", emptyList()))
    }

    @Test fun startingConnectionSearchOrImportRetainsExistingReviewAndEvidence() {
        val review = AiPlaceVerificationResult(listOf(place), listOf("excluded"))
        val before = AiSearchState(review = review, searchedFor = "previous request", searchEvidence = "evidence", error = "old error")
        for (kind in AiOperation.entries.filter { it != AiOperation.NONE }) {
            val during = before.startOperation(kind)
            assertSame(review, during.review)
            assertEquals(before.searchedFor, during.searchedFor)
            assertEquals(before.searchEvidence, during.searchEvidence)
            assertTrue(during.busy)
            assertEquals(kind, during.operation)
            assertNull(during.error)
        }
    }

    @Test fun uniquePositionNeedsOneExplicitSelectionAndAmbiguousPositionNeedsChoice() {
        val selection = AiReviewSelection()
        assertTrue(selection.selected.isEmpty())
        assertTrue(selection.toggle(0, place))
        assertEquals(mapOf(0 to 0), selection.selected)
        val ambiguous = place.copy(coordinates = place.coordinates + AddressCoordinateCandidate("他の住所", 36.0, 135.0))
        assertFalse(selection.toggle(1, ambiguous))
        assertEquals(mapOf(0 to 0), selection.selected)
        assertTrue(selection.toggle(0, place))
        assertTrue(selection.selected.isEmpty())
    }

    @Test fun importIdentityAndNotificationDetailsSurviveTheSharedReview() {
        val verifier = AiPlaceVerifier(UnavailableAddressCandidateProvider)
        val edits = AiPlaceEdits("500", "updated", "coffee|trip", "120", "1440", true)
        val json = verifier.selectedJson(listOf(place), mapOf(0 to 0), mapOf(0 to edits))
        val saved = (AiImportText.parse(json) as TransferParseResult.Success).records.single()
        assertEquals("import-id", saved.id)
        assertEquals(35.0, saved.latitude, 0.0)
        assertEquals(120, saved.loiteringDelaySeconds)
        assertEquals(1440L, saved.cooldownMinutes)
        assertTrue(saved.enabled)
        assertEquals(listOf("coffee", "trip"), saved.tags)
        assertEquals(record.sourceUrl, saved.sourceUrl)
    }

    @Test fun invalidNotificationDetailsCannotReachImporter() {
        val verifier = AiPlaceVerifier(UnavailableAddressCandidateProvider)
        listOf(AiPlaceEdits("300", "", "", "14"), AiPlaceEdits("300", "", "", "3601"), AiPlaceEdits("300", "", "", cooldownMinutes = "43201")).forEach { edit ->
            assertThrows(IllegalArgumentException::class.java) { verifier.selectedJson(listOf(place), mapOf(0 to 0), mapOf(0 to edit)) }
        }
    }
}
