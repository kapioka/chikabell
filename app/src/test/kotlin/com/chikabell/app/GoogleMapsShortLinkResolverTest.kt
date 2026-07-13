package com.chikabell.app

import com.chikabell.app.share.GoogleMapsShortLinkResolver
import com.chikabell.app.share.SharedPlaceParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleMapsShortLinkResolverTest {
    @Test
    fun allowsOnlyHttpsGoogleMapsRedirectHosts() {
        assertTrue(GoogleMapsShortLinkResolver.isAllowedUrl("https://maps.app.goo.gl/abc"))
        assertTrue(GoogleMapsShortLinkResolver.isAllowedUrl("https://www.google.com/maps/place/x"))
        assertTrue(GoogleMapsShortLinkResolver.isAllowedUrl("https://maps.google.co.jp/maps?q=x"))
        assertFalse(GoogleMapsShortLinkResolver.isAllowedUrl("http://maps.app.goo.gl/abc"))
        assertFalse(GoogleMapsShortLinkResolver.isAllowedUrl("https://google.com.evil.example/maps"))
        assertFalse(GoogleMapsShortLinkResolver.isAllowedUrl("https://user@google.com/maps"))
        assertFalse(GoogleMapsShortLinkResolver.isAllowedUrl("https://google.com:8443/maps"))
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
        val place = SharedPlaceParser.parse(null, "地点\nhttps://maps.app.goo.gl/abc", null)
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
    }
}
