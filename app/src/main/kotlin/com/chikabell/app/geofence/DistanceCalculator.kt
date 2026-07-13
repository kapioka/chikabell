package com.chikabell.app.geofence

object DistanceCalculator {
    fun distanceMeters(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double,
    ): Float {
        val earthRadiusMeters = 6_371_000.0
        val fromLatRad = Math.toRadians(fromLatitude)
        val toLatRad = Math.toRadians(toLatitude)
        val deltaLat = Math.toRadians(toLatitude - fromLatitude)
        val deltaLon = Math.toRadians(toLongitude - fromLongitude)
        val sinHalfLat = kotlin.math.sin(deltaLat / 2.0)
        val sinHalfLon = kotlin.math.sin(deltaLon / 2.0)
        val a = sinHalfLat * sinHalfLat +
            kotlin.math.cos(fromLatRad) * kotlin.math.cos(toLatRad) * sinHalfLon * sinHalfLon
        val c = 2.0 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1.0 - a))
        return (earthRadiusMeters * c).toFloat()
    }

    fun isWithinRadius(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double,
        radiusMeters: Int,
    ): Boolean {
        return distanceMeters(fromLatitude, fromLongitude, toLatitude, toLongitude) <= radiusMeters
    }
}
