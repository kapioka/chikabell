package com.chikabell.app

import com.chikabell.app.share.OpenLocationCodeDecoder
import com.chikabell.app.share.SharedPlaceConfidence
import com.chikabell.app.share.SharedPlaceParseMethod
import com.chikabell.app.share.SharedPlaceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenLocationCodeDecoderTest {
    @Test
    fun decodesCompletePlusCodeLocally() {
        val area = OpenLocationCodeDecoder.decode("849VCWC8+R9")

        assertNotNull(area)
        assertEquals(37.42206, area!!.centerLatitude, 0.00002)
        assertEquals(-122.08406, area.centerLongitude, 0.00002)
        assertTrue(area.uncertaintyMeters in 5.0..15.0)
    }

    @Test
    fun parserReturnsPlusCodeAsConfirmationRequiredCandidate() {
        val result = SharedPlaceParser.parse(
            subject = "Google",
            text = "Plus Code 849VCWC8+R9",
            uri = null,
        )

        assertEquals(SharedPlaceParseMethod.PLUS_CODE, result.parseMethod)
        assertEquals(SharedPlaceConfidence.NEEDS_CONFIRMATION, result.confidence)
        assertTrue(result.candidates.single().uncertaintyMeters!! > 0.0)
    }

    @Test
    fun rejectsShortCompoundAndMalformedCodes() {
        assertNull(OpenLocationCodeDecoder.decode("CWC8+R9"))
        assertNull(OpenLocationCodeDecoder.decode("849V0WC8+R9"))
        assertNull(OpenLocationCodeDecoder.decode("849VCWC8R9"))
    }

    @Test
    fun acceptsValidPaddedFullCodeAsCoarseArea() {
        val area = OpenLocationCodeDecoder.decode("849VCW00+")

        assertNotNull(area)
        assertTrue(area!!.uncertaintyMeters > 1_000.0)
    }
}
