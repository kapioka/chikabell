package com.chikabell.app

import com.chikabell.app.share.ParsedSharedPlace
import com.chikabell.app.share.SharedPlaceConfidence
import com.chikabell.app.share.SharedPlaceEvent
import com.chikabell.app.share.SharedPlaceParser
import com.chikabell.app.ui.locations.LocationFormState
import com.chikabell.app.ui.locations.LocationsUiState
import com.chikabell.app.ui.locations.PendingSharedPlace
import com.chikabell.app.ui.locations.RegistrationField
import com.chikabell.app.ui.locations.RegistrationSessionPhase
import com.chikabell.app.ui.locations.SharedRegistrationSession
import com.chikabell.app.ui.locations.automaticAddressSearchQuery
import com.chikabell.app.ui.locations.mergePendingSharedPlaceState
import com.chikabell.app.ui.locations.repairInterruptedSharedResolution
import com.chikabell.app.ui.locations.reduceSharedPlaceEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedRegistrationReducerTest {
    @Test
    fun restoredPendingResolutionIsDowngradedWithoutLosingDecision() {
        val active = placeAt(34.1, 135.2)
        val pending = unresolved("店B").copy(confidence = SharedPlaceConfidence.RESOLVING)
        val restored = activeState(active, "event-a").copy(
            sharedRegistrationSession = SharedRegistrationSession(
                sessionId = "session",
                phase = RegistrationSessionPhase.PENDING_SHARE_DECISION,
                activeEventId = "event-a",
                handledTerminalEventIds = setOf("event-a"),
                pendingIncomingShare = PendingSharedPlace("event-b", pending),
            ),
        )

        val repaired = repairInterruptedSharedResolution(restored)

        assertEquals(active, repaired.sharedPlace)
        assertEquals(
            SharedPlaceConfidence.NETWORK_FAILURE,
            repaired.sharedRegistrationSession!!.pendingIncomingShare!!.place.confidence,
        )
        assertEquals(
            "resolution_interrupted",
            repaired.sharedRegistrationSession!!.pendingIncomingShare!!.place.failureReason,
        )
        assertEquals(
            RegistrationSessionPhase.PENDING_SHARE_DECISION,
            repaired.sharedRegistrationSession!!.phase,
        )
    }

    @Test
    fun pendingResolvingEventIsUpdatedByItsTerminalResult() {
        val initial = activeState(placeAt(34.1, 135.2), "event-a")
        val resolving = unresolved("店B").copy(confidence = SharedPlaceConfidence.RESOLVING)
        val pending = reduceSharedPlaceEvent(initial, SharedPlaceEvent("event-b", resolving))

        val terminal = reduceSharedPlaceEvent(pending, SharedPlaceEvent("event-b", placeAt(35.1, 136.2)))

        assertEquals(SharedPlaceConfidence.HIGH_CONFIDENCE, terminal.sharedRegistrationSession!!.pendingIncomingShare!!.place.confidence)
        assertEquals(RegistrationSessionPhase.PENDING_SHARE_DECISION, terminal.sharedRegistrationSession!!.phase)
    }

    @Test
    fun activeTerminalResultDoesNotDeleteDifferentPendingShare() {
        val resolvingA = unresolved("店A").copy(confidence = SharedPlaceConfidence.RESOLVING)
        val activeA = LocationsUiState(
            form = LocationFormState(name = "店A"),
            sharedPlace = resolvingA,
            sharedRegistrationSession = SharedRegistrationSession(
                sessionId = "session",
                phase = RegistrationSessionPhase.RESOLVING,
                activeEventId = "event-a",
            ),
        )
        val withPendingB = reduceSharedPlaceEvent(
            activeA,
            SharedPlaceEvent("event-b", placeAt(35.1, 136.2)),
        )

        val completedA = reduceSharedPlaceEvent(
            withPendingB,
            SharedPlaceEvent("event-a", placeAt(34.1, 135.2)),
        )

        assertEquals("event-b", completedA.sharedRegistrationSession!!.pendingIncomingShare?.eventId)
        assertEquals(RegistrationSessionPhase.PENDING_SHARE_DECISION, completedA.sharedRegistrationSession!!.phase)
        assertEquals(34.1, completedA.sharedPlace!!.latitude!!, 0.0)
    }

    @Test
    fun mergedResolvingEventAcceptsLaterTerminalResultAndRequiresReview() {
        val initial = activeState(placeAt(34.1, 135.2), "event-a")
        val pending = reduceSharedPlaceEvent(
            initial,
            SharedPlaceEvent("event-b", unresolved("店B").copy(confidence = SharedPlaceConfidence.RESOLVING)),
        )
        val merged = mergePendingSharedPlaceState(pending)

        assertFalse("event-b" in merged.sharedRegistrationSession!!.handledTerminalEventIds)
        val terminal = reduceSharedPlaceEvent(merged, SharedPlaceEvent("event-b", placeAt(35.1, 136.2)))

        assertTrue("event-b" in terminal.sharedRegistrationSession!!.handledTerminalEventIds)
        assertEquals(SharedPlaceConfidence.UNRESOLVED, terminal.sharedPlace!!.confidence)
        assertTrue(terminal.sharedPlace!!.hasConflictingCandidates)
    }

    @Test
    fun mergePreservesCoordinatesEditedByUser() {
        val current = activeState(placeAt(34.1, 135.2), "event-a").copy(
            form = LocationFormState(latitude = "33.0", longitude = "134.0"),
            sharedPlaceCoordinatesManuallyEdited = true,
            sharedRegistrationSession = SharedRegistrationSession(
                sessionId = "session",
                phase = RegistrationSessionPhase.PENDING_SHARE_DECISION,
                activeEventId = "event-a",
                userEditedFields = setOf(RegistrationField.LATITUDE, RegistrationField.LONGITUDE),
                pendingIncomingShare = PendingSharedPlace("event-b", placeAt(35.1, 136.2)),
            ),
        )

        val merged = mergePendingSharedPlaceState(current)

        assertEquals("33.0", merged.form.latitude)
        assertEquals("134.0", merged.form.longitude)
        assertTrue(merged.sharedPlaceCoordinatesManuallyEdited)
        assertFalse(merged.sharedPlaceCandidateConfirmed)
    }

    @Test
    fun coordinateReshareWithoutSessionPreservesTheExistingDraft() {
        val initial = LocationsUiState(
            form = LocationFormState(
                name = "最初に共有した店舗名",
                message = "最初に入力したメモ",
                address = "検索に使った店舗名",
                radiusMeters = "500",
                tags = listOf("買い物"),
            ),
        )
        val coordinateShare = coordinateOnlyPlace()

        val applied = reduceSharedPlaceEvent(
            initial,
            SharedPlaceEvent("coordinate-reshare", coordinateShare),
        )

        assertEquals("最初に共有した店舗名", applied.form.name)
        assertEquals("最初に入力したメモ", applied.form.message)
        assertEquals("検索に使った店舗名", applied.form.address)
        assertEquals("500", applied.form.radiusMeters)
        assertEquals(listOf("買い物"), applied.form.tags)
        assertEquals(coordinateShare.latitude.toString(), applied.form.latitude)
        assertEquals(coordinateShare.longitude.toString(), applied.form.longitude)
    }

    @Test
    fun coordinateResharePreservesNotificationOnlyDraftWithoutAnActiveSession() {
        val initial = LocationsUiState(
            form = LocationFormState(
                radiusMeters = "900",
                loiteringDelaySeconds = "180",
                cooldownHours = "24",
                enabled = false,
            ),
        )

        val applied = reduceSharedPlaceEvent(
            initial,
            SharedPlaceEvent("coordinate-reshare", coordinateOnlyPlace()),
        )

        assertEquals("900", applied.form.radiusMeters)
        assertEquals("180", applied.form.loiteringDelaySeconds)
        assertEquals("24", applied.form.cooldownHours)
        assertFalse(applied.form.enabled)
    }

    @Test
    fun pendingCoordinateShareAddsOnlyCoordinatesToTheCurrentDraft() {
        val current = activeState(placeAt(34.1, 135.2), "event-a").copy(
            form = LocationFormState(
                name = "最初に共有した店舗名",
                message = "消してはいけないメモ",
                address = "検索済みの店舗名",
                radiusMeters = "1000",
                tags = listOf("用事"),
            ),
            sharedRegistrationSession = SharedRegistrationSession(
                sessionId = "session",
                phase = RegistrationSessionPhase.PENDING_SHARE_DECISION,
                activeEventId = "event-a",
                pendingIncomingShare = PendingSharedPlace("event-b", coordinateOnlyPlace()),
            ),
        )

        val merged = mergePendingSharedPlaceState(current)

        assertEquals("最初に共有した店舗名", merged.form.name)
        assertEquals("消してはいけないメモ", merged.form.message)
        assertEquals("検索済みの店舗名", merged.form.address)
        assertEquals("1000", merged.form.radiusMeters)
        assertEquals(listOf("用事"), merged.form.tags)
        assertFalse(merged.form.name.contains("°"))
    }

    @Test
    fun handledDismissedEventCannotReappearAsPending() {
        val initial = activeState(placeAt(34.1, 135.2), "event-a").copy(
            sharedRegistrationSession = SharedRegistrationSession(
                sessionId = "session",
                phase = RegistrationSessionPhase.EDITING,
                activeEventId = "event-a",
                handledTerminalEventIds = setOf("event-b"),
            ),
        )

        val next = reduceSharedPlaceEvent(initial, SharedPlaceEvent("event-b", placeAt(35.1, 136.2)))

        assertEquals(initial, next)
    }

    @Test
    fun unresolvedTerminalEventStartsAutomaticSearchOnlyOnce() {
        val event = SharedPlaceEvent("event-a", unresolved("サンプル百貨店 枚方"))
        val initial = LocationsUiState()
        val applied = reduceSharedPlaceEvent(initial, event)

        assertEquals("サンプル百貨店 枚方", applied.form.address)
        assertEquals(
            "サンプル百貨店 枚方",
            automaticAddressSearchQuery(initial, applied, event),
        )

        val duplicate = reduceSharedPlaceEvent(applied, event)
        assertEquals(null, automaticAddressSearchQuery(applied, duplicate, event))
    }

    @Test
    fun unresolvedTerminalEventDoesNotSearchAfterUserEditedSearchInput() {
        val event = SharedPlaceEvent("event-a", unresolved("サンプル百貨店 枚方"))
        val previous = LocationsUiState(
            form = LocationFormState(
                message = "サンプル百貨店 枚方",
                address = "",
            ),
            sharedPlace = unresolved("サンプル百貨店 枚方").copy(
                confidence = SharedPlaceConfidence.RESOLVING,
            ),
            sharedRegistrationSession = SharedRegistrationSession(
                sessionId = "session",
                phase = RegistrationSessionPhase.RESOLVING,
                activeEventId = "event-a",
                userEditedFields = setOf(RegistrationField.ADDRESS),
            ),
        )

        val applied = reduceSharedPlaceEvent(previous, event)

        assertEquals("", applied.form.address)
        assertEquals(null, automaticAddressSearchQuery(previous, applied, event))
    }

    private fun activeState(place: ParsedSharedPlace, eventId: String) = LocationsUiState(
        form = LocationFormState(
            name = place.nameCandidate,
            latitude = place.latitude.toString(),
            longitude = place.longitude.toString(),
        ),
        sharedPlace = place,
        sharedPlaceCandidateConfirmed = true,
        sharedRegistrationSession = SharedRegistrationSession(
            sessionId = "session",
            phase = RegistrationSessionPhase.READY_TO_SAVE,
            activeEventId = eventId,
            handledTerminalEventIds = setOf(eventId),
        ),
    )

    private fun placeAt(latitude: Double, longitude: Double): ParsedSharedPlace =
        SharedPlaceParser.parse(
            subject = "店",
            text = "https://www.google.com/maps/search/?api=1&query=$latitude,$longitude",
            uri = null,
        )

    private fun coordinateOnlyPlace(): ParsedSharedPlace =
        SharedPlaceParser.parse(
            subject = "34°41'48.6\"N 135°30'42.9\"E",
            text = "https://www.google.com/maps/search/?api=1&query=34.696833,135.511917",
            uri = null,
        )

    private fun unresolved(name: String) = SharedPlaceParser.parse(name, "住所だけ", null)
}
