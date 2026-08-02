package com.chikabell.app

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chikabell.app.share.SharedIntentNormalizer
import com.chikabell.app.share.SharedPlaceConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedIntentNormalizerTest {
    @Test
    fun receivesCharSequenceTextAndCreatesANewEventEachTime() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, SpannableString("緯度経度 34.1,135.2"))
        }

        val first = SharedIntentNormalizer.normalize(intent)
        val second = SharedIntentNormalizer.normalize(intent)

        assertNotNull(first)
        assertEquals(34.1, first!!.place.latitude!!, 0.0)
        assertNotEquals(first.eventId, second!!.eventId)
    }

    @Test
    fun receivesStructuredHtmlAsSupportingEvidence() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(
                Intent.EXTRA_HTML_TEXT,
                """<meta property="og:url" content="https://www.google.com/maps/search/?api=1&amp;query=34.1%2C135.2">""",
            )
        }

        val event = SharedIntentNormalizer.normalize(intent)

        assertEquals(SharedPlaceConfidence.NEEDS_CONFIRMATION, event!!.place.confidence)
    }

    @Test
    fun receivesClipDataText() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            clipData = ClipData.newPlainText("location", "緯度経度 34.1,135.2")
        }

        val event = SharedIntentNormalizer.normalize(intent)

        assertEquals(34.1, event!!.place.latitude!!, 0.0)
        assertEquals(135.2, event.place.longitude!!, 0.0)
    }

    @Test
    fun receivesGeoActionViewAndPrefersQueryCoordinates() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=34.99,-106.61(Label)"))

        val event = SharedIntentNormalizer.normalize(intent)

        assertEquals(SharedPlaceConfidence.HIGH_CONFIDENCE, event!!.place.confidence)
        assertEquals(34.99, event.place.latitude!!, 0.0)
        assertEquals(-106.61, event.place.longitude!!, 0.0)
    }
}
