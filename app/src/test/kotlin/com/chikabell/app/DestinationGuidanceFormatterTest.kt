package com.chikabell.app

import com.chikabell.app.notification.DestinationGuidanceFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DestinationGuidanceFormatterTest {
    @Test
    fun formatsNorthEastDirectionAndDistance() {
        assertEquals(
            "目的地は北東方向に0.3kmです",
            DestinationGuidanceFormatter.format(
                currentLatitude = 35.0,
                currentLongitude = 135.0,
                destinationLatitude = 35.0019,
                destinationLongitude = 135.0023,
            ),
        )
    }

    @Test
    fun formatsEachCardinalDirection() {
        assertEquals("目的地は北方向に0.1kmです", format(35.001, 135.0))
        assertEquals("目的地は東方向に0.1kmです", format(35.0, 135.001))
        assertEquals("目的地は南方向に0.1kmです", format(34.999, 135.0))
        assertEquals("目的地は西方向に0.1kmです", format(35.0, 134.999))
    }

    @Test
    fun omitsGuidanceWhenCurrentLocationIsUnavailable() {
        assertNull(
            DestinationGuidanceFormatter.format(
                currentLatitude = null,
                currentLongitude = null,
                destinationLatitude = 35.0,
                destinationLongitude = 135.0,
            ),
        )
    }

    private fun format(latitude: Double, longitude: Double): String? {
        return DestinationGuidanceFormatter.format(35.0, 135.0, latitude, longitude)
    }
}
