package com.chikabell.app

import com.chikabell.app.share.GoogleMapsShortLinkResolver
import com.chikabell.app.share.SharedPlaceParser
import com.chikabell.app.share.SharedPlaceConfidence
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleMapsShortLinkResolverTest {
    @Test
    fun allowsOnlyHttpsGoogleMapsRedirectHosts() {
        assertTrue(GoogleMapsShortLinkResolver.isAllowedUrl("https://maps.app.goo.gl/abc"))
        assertTrue(GoogleMapsShortLinkResolver.isAllowedUrl("https://goo.gl/maps/abc"))
        assertTrue(GoogleMapsShortLinkResolver.isAllowedUrl("https://www.google.com/maps/place/x"))
        assertTrue(GoogleMapsShortLinkResolver.isAllowedUrl("https://maps.google.co.jp/maps?q=x"))
        assertFalse(GoogleMapsShortLinkResolver.isAllowedUrl("http://maps.app.goo.gl/abc"))
        assertFalse(GoogleMapsShortLinkResolver.isAllowedUrl("https://goo.gl/not-maps/abc"))
        assertFalse(GoogleMapsShortLinkResolver.isAllowedUrl("https://google.com.evil.example/maps"))
        assertFalse(GoogleMapsShortLinkResolver.isAllowedUrl("https://user@google.com/maps"))
        assertFalse(GoogleMapsShortLinkResolver.isAllowedUrl("https://google.com:8443/maps"))
    }

    @Test
    fun acceptsOnlyHtmlContentTypes() {
        assertTrue(GoogleMapsShortLinkResolver.isAllowedContentType("text/html; charset=UTF-8"))
        assertTrue(GoogleMapsShortLinkResolver.isAllowedContentType("application/xhtml+xml"))
        assertFalse(GoogleMapsShortLinkResolver.isAllowedContentType("application/json"))
        assertFalse(GoogleMapsShortLinkResolver.isAllowedContentType(null))
    }

    @Test
    fun rejectsResponseBodyOverByteLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleMapsShortLinkResolver.readLimitedResponse(
                ByteArrayInputStream(ByteArray(512_001)),
            )
        }
    }

    @Test
    fun extractsCoordinateCandidatesFromGoogleMapsHtml() {
        val response = GoogleMapsShortLinkResolver.ResolvedResponse(
            finalUrl = "https://maps.app.goo.gl/abc",
            html = """<meta property="og:url" content="https:\/\/www.google.com\/maps\/place\/x\/@34.123,135.456,17z">""",
        )

        val candidates = GoogleMapsShortLinkResolver.coordinateCandidates(response)

        assertTrue(candidates.any { it.contains("@34.123,135.456") })
    }

    @Test
    fun retriesAutomaticallyUntilCoordinatesBecomeAvailable() = runBlocking {
        val place = SharedPlaceParser.parse(
            subject = null,
            texts = listOf("地点\nhttps://maps.app.goo.gl/abc"),
            uris = emptyList(),
            htmlTexts = emptyList(),
            rootLineageId = "share-event:event-a",
        )
        var attempts = 0

        val resolved = GoogleMapsShortLinkResolver.resolveWithRetry(
            place = place,
            shortUrl = place.sourceUrl!!,
            retryDelaysMs = longArrayOf(0L, 0L),
        ) {
            attempts += 1
            val url = if (attempts < 3) {
                "https://www.google.com/maps/place/no-coordinates"
            } else {
                "https://www.google.com/maps/place/x/@34.123,135.456,17z"
            }
            GoogleMapsShortLinkResolver.ResolvedResponse(url, null)
        }

        assertEquals(3, attempts)
        assertEquals(34.123, resolved!!.latitude!!, 0.0)
        assertEquals(135.456, resolved.longitude!!, 0.0)
        assertEquals(SharedPlaceConfidence.NEEDS_CONFIRMATION, resolved.confidence)
    }

    @Test
    fun doesNotSelectFirstCoordinateWhenHtmlPageStateContainsConflicts() = runBlocking {
        val place = SharedPlaceParser.parse(null, "地点\nhttps://maps.app.goo.gl/abc", null)

        val resolved = GoogleMapsShortLinkResolver.resolveWithRetry(
            place = place,
            shortUrl = place.sourceUrl!!,
            retryDelaysMs = longArrayOf(),
        ) {
            GoogleMapsShortLinkResolver.ResolvedResponse(
                finalUrl = "https://www.google.com/maps/place/no-coordinates",
                html = """<script>state="/@34.1,135.2,17z"; other="/@35.1,136.2,17z";</script>""",
            )
        }

        assertEquals(SharedPlaceConfidence.UNRESOLVED, resolved!!.confidence)
        assertFalse(resolved.hasCoordinates)
        assertEquals(2, resolved.candidates.size)
    }

    @Test
    fun structuredHtmlUrlSupportsMatchingFinalUrlWithoutCountingAsConflict() = runBlocking {
        val place = SharedPlaceParser.parse(null, "地点\nhttps://maps.app.goo.gl/abc", null)

        val resolved = GoogleMapsShortLinkResolver.resolveWithRetry(
            place = place,
            shortUrl = place.sourceUrl!!,
            retryDelaysMs = longArrayOf(),
        ) {
            GoogleMapsShortLinkResolver.ResolvedResponse(
                finalUrl = "https://www.google.com/maps/search/?api=1&query=34.1%2C135.2",
                html = """<meta property="og:url" content="https:\/\/www.google.com\/maps\/search\/?api=1&amp;query=34.1%2C135.2">""",
            )
        }

        assertEquals(SharedPlaceConfidence.HIGH_CONFIDENCE, resolved!!.confidence)
        assertEquals(34.1, resolved.latitude!!, 0.0)
        assertEquals(135.2, resolved.longitude!!, 0.0)
    }

    @Test
    fun deadLinkFailureDoesNotConsumeRetryBudget() = runBlocking {
        val place = SharedPlaceParser.parse(null, "地点\nhttps://maps.app.goo.gl/abc", null)
        var attempts = 0

        val resolved = GoogleMapsShortLinkResolver.resolveWithRetry(
            place = place,
            shortUrl = place.sourceUrl!!,
            retryDelaysMs = longArrayOf(0L, 0L),
        ) {
            attempts += 1
            throw GoogleMapsShortLinkResolver.ShortLinkFetchException(
                GoogleMapsShortLinkResolver.ShortLinkFailureKind.DEAD_LINK,
                "HTTP 404",
            )
        }

        assertEquals(1, attempts)
        assertEquals(null, resolved)
    }

    @Test
    fun networkFailureRetriesAndCanRecover() = runBlocking {
        val place = SharedPlaceParser.parse(null, "地点\nhttps://maps.app.goo.gl/abc", null)
        var attempts = 0

        val resolved = GoogleMapsShortLinkResolver.resolveWithRetry(
            place = place,
            shortUrl = place.sourceUrl!!,
            retryDelaysMs = longArrayOf(0L),
        ) {
            attempts += 1
            if (attempts == 1) throw IOException("offline")
            GoogleMapsShortLinkResolver.ResolvedResponse(
                "https://www.google.com/maps/search/?api=1&query=34.1%2C135.2",
                null,
            )
        }

        assertEquals(2, attempts)
        assertEquals(34.1, resolved!!.latitude!!, 0.0)
    }

    @Test
    fun evidenceFromOneHttpResponseSharesOneLineage() = runBlocking {
        val place = SharedPlaceParser.parse(
            subject = null,
            texts = listOf("地点\nhttps://maps.app.goo.gl/abc"),
            uris = emptyList(),
            htmlTexts = emptyList(),
            rootLineageId = "share-event:event-a",
        )

        val resolved = GoogleMapsShortLinkResolver.resolveWithRetry(
            place = place,
            shortUrl = place.sourceUrl!!,
            retryDelaysMs = longArrayOf(),
        ) {
            GoogleMapsShortLinkResolver.ResolvedResponse(
                finalUrl = "https://www.google.com/maps/place/no-coordinates",
                html = """<script>state="/@34.1,135.2,17z"; other="/@35.1,136.2,17z";</script>""",
            )
        }

        assertEquals(1, resolved!!.candidates.map { it.lineageId }.distinct().size)
        assertEquals("share-event:event-a", resolved.candidates.first().lineageId)
    }
}
