package com.chikabell.app

import com.chikabell.app.share.SharedPlaceParseMethod
import com.chikabell.app.share.SharedPlaceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedPlaceParserTest {
    @Test
    fun parsesGeoUri() {
        val result = SharedPlaceParser.parse("駅", null, "geo:34.123,135.456?q=駅")

        assertEquals(SharedPlaceParseMethod.GEO_URI, result.parseMethod)
        assertEquals(34.123, result.latitude!!, 0.0)
        assertEquals(135.456, result.longitude!!, 0.0)
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
        assertEquals(34.1, result.latitude!!, 0.0)
        assertEquals(135.2, result.longitude!!, 0.0)
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
