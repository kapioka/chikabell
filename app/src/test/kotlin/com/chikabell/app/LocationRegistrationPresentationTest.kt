package com.chikabell.app

import com.chikabell.app.share.ParsedSharedPlace
import com.chikabell.app.share.SharedPlaceConfidence
import com.chikabell.app.share.SharedPlaceParseMethod
import com.chikabell.app.ui.locations.LocationFormSection
import com.chikabell.app.ui.locations.LocationFormState
import com.chikabell.app.ui.locations.LocationRegistrationMode
import com.chikabell.app.ui.locations.LocationSaveBlockReason
import com.chikabell.app.ui.locations.LocationsUiState
import com.chikabell.app.ui.locations.RegistrationSessionPhase
import com.chikabell.app.ui.locations.SharedRegistrationSession
import com.chikabell.app.ui.locations.locationFormSectionsForValidation
import com.chikabell.app.ui.locations.locationRegistrationPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationRegistrationPresentationTest {
    @Test
    fun unconfirmedCandidateUsesOnlyTheBodyPrimaryAction() {
        val presentation = locationRegistrationPresentation(
            stateFor(candidate(), confirmed = false),
        )

        assertEquals(LocationRegistrationMode.NEEDS_CONFIRMATION, presentation.mode)
        assertFalse(presentation.topSaveEnabled)
        assertTrue(presentation.showConfirmAndSave)
        assertEquals("サンプルカフェ 梅田店", presentation.candidateTitle)
        assertEquals("大阪府大阪市北区サンプル1-2-3", presentation.candidateAddress)
        assertEquals("位置未確定・保存できません", presentation.topSaveStateDescription)
    }

    @Test
    fun mapRoundTripChangesOnlyTheSecondaryActionLabel() {
        val state = stateFor(candidate(), confirmed = false).copy(
            sharedRegistrationSession = SharedRegistrationSession(
                sessionId = "session",
                phase = RegistrationSessionPhase.AWAITING_MAP_CONFIRMATION,
                activeEventId = "event",
            ),
        )

        val presentation = locationRegistrationPresentation(state)

        assertEquals("もう一度Googleマップで確認", presentation.mapActionLabel)
        assertFalse(presentation.topSaveEnabled)
        assertTrue(presentation.showConfirmAndSave)
    }

    @Test
    fun confirmedCandidateEnablesOnlyTheTopSaveAction() {
        val presentation = locationRegistrationPresentation(
            stateFor(candidate(), confirmed = true),
        )

        assertEquals(LocationRegistrationMode.READY, presentation.mode)
        assertTrue(presentation.topSaveEnabled)
        assertFalse(presentation.showConfirmAndSave)
        assertEquals("保存できます", presentation.topSaveStateDescription)
    }

    @Test
    fun validManualCoordinatesEnableTopSave() {
        val presentation = locationRegistrationPresentation(
            LocationsUiState(
                form = LocationFormState(
                    name = "手動地点",
                    latitude = "34.696833",
                    longitude = "135.511917",
                ),
            ),
        )

        assertEquals(LocationRegistrationMode.READY, presentation.mode)
        assertTrue(presentation.topSaveEnabled)
        assertFalse(presentation.showConfirmAndSave)
    }

    @Test
    fun missingCoordinatesKeepTopSaveDisabled() {
        val presentation = locationRegistrationPresentation(
            LocationsUiState(form = LocationFormState(name = "座標なし")),
        )

        assertEquals(LocationRegistrationMode.NEEDS_LOCATION, presentation.mode)
        assertFalse(presentation.topSaveEnabled)
        assertFalse(presentation.showConfirmAndSave)
        assertEquals(
            "住所・施設名から候補を検索するか、緯度・経度を入力してください",
            presentation.guidance,
        )
    }

    @Test
    fun sharedPlaceFailureUsesFriendlyCoordinateGuidance() {
        val presentation = locationRegistrationPresentation(
            LocationsUiState(
                form = LocationFormState(name = "共有された店舗"),
                sharedPlace = candidate().copy(
                    latitude = null,
                    longitude = null,
                    confidence = SharedPlaceConfidence.UNRESOLVED,
                    failureReason = "dead_link",
                ),
            ),
        )

        assertEquals(LocationRegistrationMode.NEEDS_LOCATION, presentation.mode)
        assertEquals("共有リンクから緯度・経度を取得できませんでした。", presentation.guidance)
    }

    @Test
    fun savingDisablesBothSaveEntrypoints() {
        val presentation = locationRegistrationPresentation(
            stateFor(candidate(), confirmed = true).copy(isSaving = true),
        )

        assertEquals(LocationRegistrationMode.SAVING, presentation.mode)
        assertFalse(presentation.topSaveEnabled)
        assertFalse(presentation.showConfirmAndSave)
    }

    @Test
    fun coordinateOnlySharedLabelNeverBecomesTheCandidateTitleOrAddress() {
        val coordinateOnly = candidate().copy(nameCandidate = "34.696833,135.511917")

        val presentation = locationRegistrationPresentation(
            stateFor(coordinateOnly, confirmed = false).copy(
                form = LocationFormState(
                    name = "最初に入力した店舗名",
                    address = "34.696833,135.511917",
                    latitude = "34.696833",
                    longitude = "135.511917",
                ),
            ),
        )

        assertEquals("最初に入力した店舗名", presentation.candidateTitle)
        assertNull(presentation.candidateAddress)
    }

    @Test
    fun validationErrorsRevealOnlyRelevantDisclosureSections() {
        assertEquals(
            setOf(LocationFormSection.NAME),
            locationFormSectionsForValidation("名前は1文字以上100文字以内で入力してください", null),
        )
        assertEquals(
            setOf(LocationFormSection.NOTIFICATION),
            locationFormSectionsForValidation("半径は100mから5000mで入力してください", null),
        )
        assertEquals(
            setOf(LocationFormSection.COORDINATES),
            locationFormSectionsForValidation(
                "緯度は-90から90で入力してください",
                LocationSaveBlockReason.INVALID_COORDINATES,
            ),
        )
        assertEquals(
            setOf(LocationFormSection.NOTIFICATION, LocationFormSection.COORDINATES),
            locationFormSectionsForValidation("数値の入力を確認してください", null),
        )
        assertEquals(
            setOf(LocationFormSection.DETAILS, LocationFormSection.NOTIFICATION),
            locationFormSectionsForValidation("メモとタグ、半径を確認してください", null),
        )
    }

    private fun stateFor(place: ParsedSharedPlace, confirmed: Boolean): LocationsUiState =
        LocationsUiState(
            form = LocationFormState(
                name = "サンプルカフェ 梅田店",
                address = "大阪府大阪市北区サンプル1-2-3",
                latitude = place.latitude.toString(),
                longitude = place.longitude.toString(),
            ),
            sharedPlace = place,
            sharedPlaceCandidateConfirmed = confirmed,
        )

    private fun candidate(): ParsedSharedPlace = ParsedSharedPlace(
        nameCandidate = "サンプルカフェ 梅田店",
        latitude = 34.696833,
        longitude = 135.511917,
        sourceUrl = "https://www.google.com/maps/search/?api=1&query=34.696833,135.511917",
        rawText = null,
        parseMethod = SharedPlaceParseMethod.URL_QUERY_COORDINATES,
        confidence = SharedPlaceConfidence.NEEDS_CONFIRMATION,
    )
}
