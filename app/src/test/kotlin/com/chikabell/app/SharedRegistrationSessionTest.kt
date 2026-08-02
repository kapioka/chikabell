package com.chikabell.app

import com.chikabell.app.domain.model.SourceType
import com.chikabell.app.share.CandidateReliability
import com.chikabell.app.share.CoordinateCandidate
import com.chikabell.app.share.CoordinateEvidenceFamily
import com.chikabell.app.share.CoordinateSemanticRole
import com.chikabell.app.share.ParsedSharedPlace
import com.chikabell.app.share.SharedPlaceConfidence
import com.chikabell.app.share.SharedPlaceParseMethod
import com.chikabell.app.ui.locations.LocationFormState
import com.chikabell.app.ui.locations.PendingSharedPlace
import com.chikabell.app.ui.locations.RegistrationField
import com.chikabell.app.ui.locations.RegistrationSessionPhase
import com.chikabell.app.ui.locations.RestoredSharedRegistration
import com.chikabell.app.ui.locations.SharedRegistrationSession
import com.chikabell.app.ui.locations.SharedRegistrationSessionCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SharedRegistrationSessionTest {
    @Test
    fun snapshotRoundTripPreservesBoundedDraftAndPendingShare() {
        val candidate = CoordinateCandidate(
            latitude = 35.6812,
            longitude = 139.7671,
            semanticRole = CoordinateSemanticRole.EXPLICIT_LOCATION,
            parseMethod = SharedPlaceParseMethod.EXPLICIT_COORDINATES,
            evidenceFamily = CoordinateEvidenceFamily.SHARED_TEXT,
            evidenceId = "shared-text:0:decimal:0",
            reliability = CandidateReliability.SUPPORTING,
            lineageId = "shared-text:0",
            uncertaintyMeters = 10.0,
        )
        val place = ParsedSharedPlace(
            nameCandidate = "テスト店舗",
            latitude = candidate.latitude,
            longitude = candidate.longitude,
            sourceUrl = "https://maps.app.goo.gl/example",
            rawText = "保存しない共有本文",
            parseMethod = candidate.parseMethod,
            confidence = SharedPlaceConfidence.NEEDS_CONFIRMATION,
            candidates = listOf(candidate),
            selectedCandidateIndex = 0,
        )
        val restored = RestoredSharedRegistration(
            session = SharedRegistrationSession(
                sessionId = "session-1",
                phase = RegistrationSessionPhase.PENDING_SHARE_DECISION,
                activeEventId = "event-1",
                handledTerminalEventIds = setOf("event-1"),
                userEditedFields = setOf(RegistrationField.NAME, RegistrationField.TAGS),
                pendingIncomingShare = PendingSharedPlace("event-2", place.copy(nameCandidate = "追加位置")),
            ),
            form = LocationFormState(
                name = "編集済み店舗",
                message = "通知メモ",
                address = "東京都千代田区",
                latitude = "35.6812",
                longitude = "139.7671",
                radiusMeters = "500",
                loiteringDelaySeconds = "45",
                cooldownHours = "6",
                enabled = true,
                sourceType = SourceType.MAP_SHARE,
                sourceUrl = place.sourceUrl,
                sourceText = "短い出所",
                tags = listOf("買い物"),
                tagInput = "途中",
            ),
            selectedPresetId = "bicycle",
            place = place,
            candidateConfirmed = false,
            coordinatesManuallyEdited = true,
        )

        val encoded = SharedRegistrationSessionCodec.encode(restored)
        val decoded = SharedRegistrationSessionCodec.decode(encoded)

        assertNotNull(decoded)
        assertEquals("編集済み店舗", decoded!!.form.name)
        assertEquals("東京都千代田区", decoded.form.address)
        assertEquals("bicycle", decoded.selectedPresetId)
        assertEquals(RegistrationSessionPhase.PENDING_SHARE_DECISION, decoded.session.phase)
        assertEquals("event-2", decoded.session.pendingIncomingShare?.eventId)
        assertEquals("shared-text:0", decoded.place.candidates.single().lineageId)
        assertEquals(10.0, decoded.place.candidates.single().uncertaintyMeters!!, 0.0)
        assertNull(decoded.place.rawText)
        assertFalse(decoded.candidateConfirmed)
    }

    @Test
    fun corruptUnknownOrOversizedSnapshotIsRejected() {
        assertNull(SharedRegistrationSessionCodec.decode("{"))
        assertNull(SharedRegistrationSessionCodec.decode("""{"schemaVersion":999}"""))
        assertNull(SharedRegistrationSessionCodec.decode("x".repeat(64_001)))
    }
}
