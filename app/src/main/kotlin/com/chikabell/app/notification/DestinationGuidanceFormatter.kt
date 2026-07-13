package com.chikabell.app.notification

import com.chikabell.app.geofence.DistanceCalculator
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

object DestinationGuidanceFormatter {
    fun format(
        currentLatitude: Double?,
        currentLongitude: Double?,
        destinationLatitude: Double,
        destinationLongitude: Double,
    ): String? {
        if (currentLatitude == null || currentLongitude == null) return null

        val distanceKilometers = DistanceCalculator.distanceMeters(
            currentLatitude,
            currentLongitude,
            destinationLatitude,
            destinationLongitude,
        ) / 1_000.0
        val direction = directionName(
            currentLatitude,
            currentLongitude,
            destinationLatitude,
            destinationLongitude,
        )
        val distance = String.format(Locale.JAPAN, "%.1f", distanceKilometers)
        return "目的地は${direction}方向に${distance}kmです"
    }

    private fun directionName(
        currentLatitude: Double,
        currentLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
    ): String {
        val currentLatRadians = Math.toRadians(currentLatitude)
        val destinationLatRadians = Math.toRadians(destinationLatitude)
        val longitudeDeltaRadians = Math.toRadians(destinationLongitude - currentLongitude)
        val y = sin(longitudeDeltaRadians) * cos(destinationLatRadians)
        val x = cos(currentLatRadians) * sin(destinationLatRadians) -
            sin(currentLatRadians) * cos(destinationLatRadians) * cos(longitudeDeltaRadians)
        val bearing = (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        val directions = arrayOf("北", "北東", "東", "南東", "南", "南西", "西", "北西")
        return directions[((bearing + 22.5) / 45.0).toInt() % directions.size]
    }
}
