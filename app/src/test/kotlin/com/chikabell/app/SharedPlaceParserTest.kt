package com.chikabell.app

import com.chikabell.app.share.SharedPlaceParseMethod
import com.chikabell.app.share.SharedPlaceParser
import com.chikabell.app.share.SharedPlaceEvidence
import com.chikabell.app.share.PlaceIdentityKind
import com.chikabell.app.share.CoordinateEvidenceFamily
import com.chikabell.app.share.SharedPlaceConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedPlaceParserTest {
    @Test
    fun capsCoordinateCandidatesFromCraftedSharedText() {
        val text = buildString {
            repeat(600) { index ->
                append("34.")
                append(index.toString().padStart(5, '0'))
                append(",135.")
                append(index.toString().padStart(5, '0'))
                append(' ')
            }
        }

        val parsed = SharedPlaceParser.parse(null, text, null)

        assertTrue(parsed.candidates.size <= 256)
    }

    @Test
    fun capsCoordinateCandidatesWhenMergingResolvedEvidence() {
        val initial = SharedPlaceParser.parse(null, null, null)
        val evidence = (0 until 600).map { index ->
            SharedPlaceEvidence(
                value = "https://www.google.com/maps/place/x/data=!3d34.${index.toString().padStart(5, '0')}!4d135.${index.toString().padStart(5, '0')}",
                family = CoordinateEvidenceFamily.HTML_PAGE_STATE,
                evidenceId = "html:$index",
            )
        }

        val merged = SharedPlaceParser.merge(initial, evidence)

        assertTrue(merged.candidates.size <= 256)
    }

    @Test
    fun capsCoordinateCandidatesWhenMergingPlaces() {
        fun crowdedText(start: Int) = buildString {
            repeat(300) { offset ->
                val coordinate = (start + offset).toString().padStart(5, '0')
                append("34.$coordinate,135.$coordinate ")
            }
        }
        val current = SharedPlaceParser.parse(null, crowdedText(0), null)
        val incoming = SharedPlaceParser.parse(null, crowdedText(300), null)

        val merged = SharedPlaceParser.mergePlaces(current, incoming)

        assertTrue(merged.candidates.size <= 256)
    }

    @Test
    fun coordinateOnlyLabelsAreDistinguishedFromPlaceNames() {
        assertTrue(SharedPlaceParser.isCoordinateOnlyLabel("34°41'48.6\"N 135°30'42.9\"E"))
        assertTrue(SharedPlaceParser.isCoordinateOnlyLabel("34.696833, 135.511917"))
        assertFalse(SharedPlaceParser.isCoordinateOnlyLabel("サンプルカフェ 梅田店"))
        assertFalse(SharedPlaceParser.isCoordinateOnlyLabel("サンプルカフェ 34号店"))
    }

    @Test
    fun extractsIdentityEvidenceWithoutInventingCoordinates() {
        val result = SharedPlaceParser.parse(
            subject = "テスト商店",
            text = """
                東京都千代田区丸の内1-1
                03-1234-5678
                https://www.google.com/maps/search/?api=1&query_place_id=ChIJ1234567890&cid=123456789
            """.trimIndent(),
            uri = null,
        )

        assertFalse(result.hasCoordinates)
        assertEquals(SharedPlaceConfidence.UNRESOLVED, result.confidence)
        val kinds = result.identityEvidence.map { it.kind }.toSet()
        assertTrue(PlaceIdentityKind.NAME in kinds)
        assertTrue(PlaceIdentityKind.ADDRESS in kinds)
        assertTrue(PlaceIdentityKind.PHONE in kinds)
        assertTrue(PlaceIdentityKind.PLACE_ID in kinds)
        assertTrue(PlaceIdentityKind.CID in kinds)
    }

    @Test
    fun attachmentMetadataCandidateNeverBecomesHighConfidenceByItself() {
        val result = SharedPlaceParser.parse(
            subject = "共有画像",
            texts = emptyList(),
            uris = emptyList(),
            htmlTexts = emptyList(),
            metadataTexts = listOf("https://www.google.com/maps/search/?api=1&query=34.1,135.2"),
        )

        assertEquals(SharedPlaceConfidence.NEEDS_CONFIRMATION, result.confidence)
        assertEquals(CoordinateEvidenceFamily.CLIP_METADATA, result.candidates.single().evidenceFamily)
    }

    @Test
    fun callerSuppliedRootLineageScopesAllEvidenceToOneShareEvent() {
        val result = SharedPlaceParser.parse(
            subject = null,
            texts = listOf("34.1,135.2 https://www.google.com/maps/place/x/data=!3d34.1!4d135.2"),
            uris = emptyList(),
            htmlTexts = emptyList(),
            rootLineageId = "share-event:event-a",
        )

        assertTrue(result.candidates.isNotEmpty())
        assertEquals(setOf("share-event:event-a"), result.candidates.map { it.lineageId }.toSet())
        assertEquals("share-event:event-a", result.rootLineageId)
    }

    @Test
    fun parsesGeoUri() {
        val result = SharedPlaceParser.parse("駅", null, "geo:34.123,135.456?q=駅")

        assertEquals(SharedPlaceParseMethod.GEO_URI, result.parseMethod)
        assertEquals(SharedPlaceConfidence.HIGH_CONFIDENCE, result.confidence)
        assertEquals(34.123, result.latitude!!, 0.0)
        assertEquals(135.456, result.longitude!!, 0.0)
    }

    @Test
    fun geoQueryCoordinatesTakePriorityOverZeroPlaceholder() {
        val result = SharedPlaceParser.parse(null, null, "geo:0,0?q=34.99,-106.61(Label)")

        assertEquals(SharedPlaceConfidence.HIGH_CONFIDENCE, result.confidence)
        assertEquals(34.99, result.latitude!!, 0.0)
        assertEquals(-106.61, result.longitude!!, 0.0)
    }

    @Test
    fun parsesCoordinatesFromGoogleMapsAtUrl() {
        val result = SharedPlaceParser.parse(
            null,
            "大阪城\nhttps://www.google.com/maps/place/foo/@34.6873,135.5262,17z",
            null,
        )

        assertEquals("大阪城", result.nameCandidate)
        assertEquals(SharedPlaceParseMethod.MAPS_AT_COORDINATES, result.parseMethod)
        assertEquals(SharedPlaceConfidence.NEEDS_CONFIRMATION, result.confidence)
        assertEquals(34.6873, result.latitude!!, 0.0)
        assertEquals(135.5262, result.longitude!!, 0.0)
    }

    @Test
    fun parsesMapsDataCoordinates() {
        val result = SharedPlaceParser.parse(
            "目的地",
            "https://www.google.com/maps/place/x/data=!3d34.5!4d135.5",
            null,
        )

        assertEquals(SharedPlaceParseMethod.MAPS_DATA_COORDINATES, result.parseMethod)
        assertEquals(SharedPlaceConfidence.NEEDS_CONFIRMATION, result.confidence)
        assertEquals(34.5, result.latitude!!, 0.0)
        assertEquals(135.5, result.longitude!!, 0.0)
    }

    @Test
    fun parsesPercentEncodedMapsCoordinates() {
        val result = SharedPlaceParser.parse(
            "目的地",
            null,
            "https://www.google.com/maps/place/x/%4034.5001%2C135.5002%2C17z",
        )

        assertEquals(34.5001, result.latitude!!, 0.0)
        assertEquals(135.5002, result.longitude!!, 0.0)
    }

    @Test
    fun parsesEncodedQueryCoordinates() {
        val result = SharedPlaceParser.parse(
            null,
            "https://www.google.com/maps/search/?api=1&query=34.1%2C135.2",
            null,
        )

        assertEquals(SharedPlaceParseMethod.URL_QUERY_COORDINATES, result.parseMethod)
        assertEquals(SharedPlaceConfidence.HIGH_CONFIDENCE, result.confidence)
        assertEquals(34.1, result.latitude!!, 0.0)
        assertEquals(135.2, result.longitude!!, 0.0)
    }

    @Test
    fun destinationBeatsDifferentViewportWithoutTreatingItAsAnotherVote() {
        val result = SharedPlaceParser.parse(
            null,
            "https://www.google.com/maps/dir/?api=1&destination=34.1%2C135.2&center=35.0%2C136.0",
            null,
        )

        assertEquals(SharedPlaceConfidence.HIGH_CONFIDENCE, result.confidence)
        assertEquals(34.1, result.latitude!!, 0.0)
        assertEquals(135.2, result.longitude!!, 0.0)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun conflictingStrongCoordinatesAreNotAutomaticallySelected() {
        val result = SharedPlaceParser.parse(
            null,
            """
                https://www.google.com/maps/search/?api=1&query=34.1%2C135.2
                https://www.google.com/maps/dir/?api=1&destination=35.1%2C136.2
            """.trimIndent(),
            null,
        )

        assertEquals(SharedPlaceConfidence.UNRESOLVED, result.confidence)
        assertFalse(result.hasCoordinates)
        assertTrue(result.hasConflictingCandidates)
        assertEquals(2, result.candidates.size)
    }

    @Test
    fun differingViewportAndMarkerCandidatesRequireExplicitSelection() {
        val result = SharedPlaceParser.parse(
            null,
            "https://www.google.com/maps/place/x/@35.0,136.0,17z/data=!3d34.1!4d135.2",
            null,
        )

        assertEquals(SharedPlaceConfidence.UNRESOLVED, result.confidence)
        assertFalse(result.hasCoordinates)
        assertEquals(2, result.candidates.size)
    }

    @Test
    fun unstructuredHtmlUrlIsNotPromotedToStrongEvidence() {
        val result = SharedPlaceParser.parse(
            subject = null,
            texts = emptyList(),
            uris = emptyList(),
            htmlTexts = listOf(
                """<script>hidden = "https://www.google.com/maps/search/?api=1&query=34.1%2C135.2"</script>""",
            ),
        )

        assertEquals(SharedPlaceConfidence.UNRESOLVED, result.confidence)
        assertFalse(result.hasCoordinates)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun structuredHtmlUrlAloneRemainsConfirmationRequired() {
        val result = SharedPlaceParser.parse(
            subject = null,
            texts = emptyList(),
            uris = emptyList(),
            htmlTexts = listOf(
                """<meta property="og:url" content="https://www.google.com/maps/search/?api=1&amp;query=34.1%2C135.2">""",
            ),
        )

        assertEquals(SharedPlaceConfidence.NEEDS_CONFIRMATION, result.confidence)
        assertEquals(34.1, result.latitude!!, 0.0)
        assertEquals(135.2, result.longitude!!, 0.0)
    }

    @Test
    fun urlAndCoordinatesFromOneSharedPayloadAreNotIndependentEvidence() {
        val result = SharedPlaceParser.parse(
            null,
            "緯度経度 34.1,135.2\nhttps://www.google.com/maps/place/x/data=!3d34.1!4d135.2",
            null,
        )

        assertEquals(SharedPlaceConfidence.NEEDS_CONFIRMATION, result.confidence)
        assertTrue(result.hasCoordinates)
    }

    @Test
    fun duplicateSupportingEvidenceFromOneFamilyDoesNotBecomeHighConfidence() {
        val result = SharedPlaceParser.parse(
            null,
            """
                https://www.google.com/maps/place/a/data=!3d34.1!4d135.2
                https://www.google.com/maps/place/b/data=!3d34.1!4d135.2
            """.trimIndent(),
            null,
        )

        assertEquals(SharedPlaceConfidence.NEEDS_CONFIRMATION, result.confidence)
    }

    @Test
    fun findsShortMapsUrlEvenWhenItIsNotTheFirstUrl() {
        val result = SharedPlaceParser.parse(
            null,
            "参考 https://example.com/info\n地図 https://maps.app.goo.gl/abc",
            null,
        )

        assertEquals("https://maps.app.goo.gl/abc", result.shortUrl)
        assertEquals("https://maps.app.goo.gl/abc", result.sourceUrl)
    }

    @Test
    fun recognizesCurrentMapsLegacyShortUrlOnlyUnderMapsPath() {
        val accepted = SharedPlaceParser.parse(
            null,
            "共有地点\nhttps://goo.gl/maps/AbCdEf",
            null,
        )
        val rejected = SharedPlaceParser.parse(
            null,
            "参考\nhttps://goo.gl/not-maps/AbCdEf",
            null,
        )

        assertEquals("https://goo.gl/maps/AbCdEf", accepted.shortUrl)
        assertEquals("https://goo.gl/maps/AbCdEf", accepted.sourceUrl)
        assertEquals(null, rejected.shortUrl)
    }

    @Test
    fun parsesCurrentMapsDmsCoordinatesWithoutTreatingFragmentsAsDecimalPairs() {
        val result = SharedPlaceParser.parse(
            """35°40'52.5"N 139°46'01.7"E""",
            "https://goo.gl/maps/AbCdEf",
            null,
        )

        assertEquals(SharedPlaceConfidence.NEEDS_CONFIRMATION, result.confidence)
        assertEquals(35.68125, result.latitude!!, 0.000001)
        assertEquals(139.7671389, result.longitude!!, 0.000001)
        assertEquals(1, result.candidates.size)
        assertEquals("https://goo.gl/maps/AbCdEf", result.shortUrl)
    }

    @Test
    fun rejectsOutOfRangeDmsMinutesAndSeconds() {
        val result = SharedPlaceParser.parse(
            null,
            """35°60'00.0"N 139°46'60.0"E""",
            null,
        )

        assertFalse(result.hasCoordinates)
    }

    @Test
    fun parsesExplicitCoordinates() {
        val result = SharedPlaceParser.parse("公園", "緯度経度 34.11, 135.22", null)

        assertEquals(SharedPlaceParseMethod.EXPLICIT_COORDINATES, result.parseMethod)
        assertTrue(result.hasCoordinates)
    }

    @Test
    fun keepsShortUrlForManualInputWithoutResolvingNetwork() {
        val result = SharedPlaceParser.parse(
            null,
            "お店\nhttps://maps.app.goo.gl/AbCdEf",
            null,
        )

        assertEquals(SharedPlaceParseMethod.MANUAL_REQUIRED, result.parseMethod)
        assertFalse(result.hasCoordinates)
        assertEquals("https://maps.app.goo.gl/AbCdEf", result.sourceUrl)
        assertTrue(result.warnings.single().contains("短縮URL"))
    }

    @Test
    fun rejectsOutOfRangeCoordinates() {
        val result = SharedPlaceParser.parse("不正", "100.0, 200.0", null)

        assertEquals(SharedPlaceParseMethod.MANUAL_REQUIRED, result.parseMethod)
        assertFalse(result.hasCoordinates)
    }

    @Test
    fun doesNotAcceptPlainHttpAsSourceUrl() {
        val result = SharedPlaceParser.parse(null, "場所\nhttp://example.com/?q=34.1,135.2", null)

        assertEquals(SharedPlaceParseMethod.MANUAL_REQUIRED, result.parseMethod)
        assertEquals(null, result.sourceUrl)
    }
}
