package com.chikabell.app

import com.chikabell.app.share.CandidateReliability
import com.chikabell.app.share.CoordinateCandidate
import com.chikabell.app.share.CoordinateEvidenceFamily
import com.chikabell.app.share.CoordinateSemanticRole
import com.chikabell.app.share.ParsedSharedPlace
import com.chikabell.app.share.SharedPlaceConfidence
import com.chikabell.app.share.SharedPlaceParseMethod
import com.chikabell.app.ui.locations.LocationFormState
import com.chikabell.app.ui.locations.LocationSaveBlockReason
import com.chikabell.app.ui.locations.locationSaveBlockReason
import com.chikabell.app.ui.locations.locationSaveGuidance
import com.chikabell.app.ui.locations.hasUserAuthoredLocationDraft
import com.chikabell.app.ui.locations.mergeResolvedSharedPlaceForm
import com.chikabell.app.ui.locations.sharedPlaceReviewTitle
import com.chikabell.app.ui.locations.sharedCoordinatesManuallyEdited
import com.chikabell.app.ui.locations.sharedPlaceRequiresConfirmation
import com.chikabell.app.ui.locations.shouldStackCoordinateFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedPlaceReviewPolicyTest {
    @Test
    fun saveBlockReasonSeparatesMissingInvalidAndUnconfirmedCoordinates() {
        assertEquals(
            LocationSaveBlockReason.MISSING_COORDINATES,
            locationSaveBlockReason(LocationFormState(), null, false, false),
        )
        assertEquals(
            LocationSaveBlockReason.INVALID_COORDINATES,
            locationSaveBlockReason(
                LocationFormState(latitude = "91", longitude = "181"),
                null,
                false,
                false,
            ),
        )
        assertEquals(
            LocationSaveBlockReason.CANDIDATE_CONFIRMATION_REQUIRED,
            locationSaveBlockReason(
                LocationFormState(latitude = "34.1", longitude = "135.2"),
                candidatePlace(),
                false,
                false,
            ),
        )
    }

    @Test
    fun validConfirmedOrManualCoordinatesCanBeSaved() {
        val form = LocationFormState(latitude = "34.1", longitude = "135.2")
        val place = candidatePlace()

        assertEquals(null, locationSaveBlockReason(form, place, true, false))
        assertEquals(null, locationSaveBlockReason(form, place, false, true))
        assertEquals(null, locationSaveBlockReason(form, null, false, false))
    }

    @Test
    fun sharedPlaceTitleDistinguishesSearchCandidatesFailureAndConfirmation() {
        assertEquals(
            "位置候補を検索中",
            sharedPlaceReviewTitle(SharedPlaceConfidence.UNRESOLVED, 0, true, false, false),
        )
        assertEquals(
            "位置候補が2件見つかりました",
            sharedPlaceReviewTitle(SharedPlaceConfidence.UNRESOLVED, 2, false, false, false),
        )
        assertEquals(
            "位置を設定できませんでした",
            sharedPlaceReviewTitle(SharedPlaceConfidence.UNRESOLVED, 0, false, false, false),
        )
        assertEquals(
            "位置を確認しました",
            sharedPlaceReviewTitle(SharedPlaceConfidence.NEEDS_CONFIRMATION, 0, false, true, false),
        )
    }

    @Test
    fun blockedSaveGuidanceKeepsRecoveryInsideTheForm() {
        assertEquals(
            "保存するには位置を設定してください",
            locationSaveGuidance(LocationSaveBlockReason.MISSING_COORDINATES),
        )
        assertEquals(
            "緯度・経度の入力を確認してください",
            locationSaveGuidance(LocationSaveBlockReason.INVALID_COORDINATES),
        )
        assertEquals(
            "保存するには位置を確定してください",
            locationSaveGuidance(LocationSaveBlockReason.CANDIDATE_CONFIRMATION_REQUIRED),
        )
    }

    @Test
    fun coordinateFieldsStackOnPhoneOrLargeText() {
        assertTrue(shouldStackCoordinateFields(411f, 1f))
        assertTrue(shouldStackCoordinateFields(700f, 1.5f))
        assertFalse(shouldStackCoordinateFields(700f, 1f))
    }

    @Test
    fun coordinateEditAndRevertDoesNotStayConfirmed() {
        val place = candidatePlace()

        assertFalse(
            sharedCoordinatesManuallyEdited(
                place,
                LocationFormState(latitude = "34.1", longitude = "135.2"),
            ),
        )
        assertTrue(
            sharedCoordinatesManuallyEdited(
                place,
                LocationFormState(latitude = "34.2", longitude = "135.2"),
            ),
        )
        assertFalse(
            sharedCoordinatesManuallyEdited(
                place,
                LocationFormState(latitude = "34.1", longitude = "135.2"),
            ),
        )
    }

    @Test
    fun confirmationIsRequiredUntilCandidateIsConfirmedOrCoordinatesAreManual() {
        val place = candidatePlace()

        assertTrue(sharedPlaceRequiresConfirmation(place, candidateConfirmed = false, coordinatesManuallyEdited = false))
        assertFalse(sharedPlaceRequiresConfirmation(place, candidateConfirmed = true, coordinatesManuallyEdited = false))
        assertFalse(sharedPlaceRequiresConfirmation(place, candidateConfirmed = false, coordinatesManuallyEdited = true))
    }

    @Test
    fun asyncResolutionPreservesManualCoordinatesAndOtherFormEdits() {
        val existing = LocationFormState(
            name = "手修正した名前",
            message = "手修正したメモ",
            latitude = "35.0",
            longitude = "136.0",
            tags = listOf("確認中"),
        )

        val merged = mergeResolvedSharedPlaceForm(
            existingForm = existing,
            place = candidatePlace(),
            preserveManualCoordinates = true,
        )

        assertEquals("手修正した名前", merged.name)
        assertEquals("手修正したメモ", merged.message)
        assertEquals("35.0", merged.latitude)
        assertEquals("136.0", merged.longitude)
        assertEquals(listOf("確認中"), merged.tags)
    }

    @Test
    fun asyncResolutionFillsCoordinatesWhenUserDidNotEditThem() {
        val merged = mergeResolvedSharedPlaceForm(
            existingForm = LocationFormState(name = "名前を保持"),
            place = candidatePlace(),
            preserveManualCoordinates = false,
        )

        assertEquals("名前を保持", merged.name)
        assertEquals("34.1", merged.latitude)
        assertEquals("135.2", merged.longitude)
    }

    @Test
    fun newShareUsesPlaceLabelForNameButDoesNotCopyItIntoMemo() {
        val merged = mergeResolvedSharedPlaceForm(
            existingForm = null,
            place = candidatePlace(),
            preserveManualCoordinates = false,
        )

        assertEquals("候補", merged.name)
        assertEquals("", merged.message)
    }

    @Test
    fun notificationChangesCountAsAUserAuthoredDraft() {
        assertTrue(LocationFormState(radiusMeters = "800").hasUserAuthoredLocationDraft())
        assertTrue(LocationFormState(loiteringDelaySeconds = "120").hasUserAuthoredLocationDraft())
        assertTrue(LocationFormState(cooldownHours = "24").hasUserAuthoredLocationDraft())
        assertTrue(LocationFormState(enabled = false).hasUserAuthoredLocationDraft())
        assertFalse(LocationFormState().hasUserAuthoredLocationDraft())
    }

    @Test
    fun unresolvedShareCopiesExistingMemoIntoEmptySearchInput() {
        val merged = mergeResolvedSharedPlaceForm(
            existingForm = LocationFormState(
                name = "登録名",
                message = "サンプル百貨店 枚方",
            ),
            place = unresolvedPlace(),
            preserveManualCoordinates = false,
        )

        assertEquals("サンプル百貨店 枚方", merged.address)
    }

    @Test
    fun unresolvedShareDoesNotRestoreSearchInputClearedByUser() {
        val merged = mergeResolvedSharedPlaceForm(
            existingForm = LocationFormState(
                message = "サンプル百貨店 枚方",
                address = "",
            ),
            place = unresolvedPlace(),
            preserveManualCoordinates = false,
            preserveSearchInput = true,
        )

        assertEquals("", merged.address)
    }

    private fun candidatePlace(): ParsedSharedPlace {
        val candidate = CoordinateCandidate(
            latitude = 34.1,
            longitude = 135.2,
            semanticRole = CoordinateSemanticRole.VIEWPORT_CENTER,
            parseMethod = SharedPlaceParseMethod.MAPS_AT_COORDINATES,
            evidenceFamily = CoordinateEvidenceFamily.ORIGINAL_URL,
            evidenceId = "candidate",
            reliability = CandidateReliability.WEAK,
        )
        return ParsedSharedPlace(
            nameCandidate = "候補",
            latitude = candidate.latitude,
            longitude = candidate.longitude,
            sourceUrl = "https://www.google.com/maps/place/x/@34.1,135.2,17z",
            rawText = null,
            parseMethod = candidate.parseMethod,
            confidence = SharedPlaceConfidence.NEEDS_CONFIRMATION,
            candidates = listOf(candidate),
            selectedCandidateIndex = 0,
        )
    }

    private fun unresolvedPlace() = ParsedSharedPlace(
        nameCandidate = "共有された施設名",
        latitude = null,
        longitude = null,
        sourceUrl = "https://maps.app.goo.gl/example",
        rawText = null,
        parseMethod = SharedPlaceParseMethod.MANUAL_REQUIRED,
        confidence = SharedPlaceConfidence.UNRESOLVED,
    )
}
