package com.chikabell.app.share

import kotlin.math.cos
import kotlin.math.sqrt

data class OpenLocationCodeArea(
    val southLatitude: Double,
    val westLongitude: Double,
    val northLatitude: Double,
    val eastLongitude: Double,
) {
    val centerLatitude: Double
        get() = ((southLatitude + northLatitude) / 2.0).coerceAtMost(90.0)
    val centerLongitude: Double
        get() = (westLongitude + eastLongitude) / 2.0
    val uncertaintyMeters: Double
        get() {
            val latitudeMeters = (northLatitude - southLatitude) * 111_320.0
            val longitudeMeters =
                (eastLongitude - westLongitude) * 111_320.0 * cos(Math.toRadians(centerLatitude))
            return sqrt(latitudeMeters * latitudeMeters + longitudeMeters * longitudeMeters) / 2.0
        }
}

object OpenLocationCodeDecoder {
    private const val ALPHABET = "23456789CFGHJMPQRVWX"
    private const val SEPARATOR = '+'
    private const val SEPARATOR_POSITION = 8
    private const val MAX_CODE_DIGITS = 15
    private const val GRID_ROWS = 5
    private const val GRID_COLUMNS = 4
    private val pairResolutions = doubleArrayOf(20.0, 1.0, 0.05, 0.0025, 0.000125)

    fun decode(value: String): OpenLocationCodeArea? {
        val normalized = value.trim().uppercase()
        if (normalized.count { it == SEPARATOR } != 1) return null
        if (normalized.indexOf(SEPARATOR) != SEPARATOR_POSITION) return null
        val beforeSeparator = normalized.substringBefore(SEPARATOR)
        val afterSeparator = normalized.substringAfter(SEPARATOR)
        val paddingStart = beforeSeparator.indexOf('0')
        val digits = if (paddingStart >= 0) {
            if (afterSeparator.isNotEmpty() ||
                paddingStart < 2 ||
                paddingStart % 2 != 0 ||
                beforeSeparator.substring(paddingStart).any { it != '0' }
            ) {
                return null
            }
            beforeSeparator.substring(0, paddingStart)
        } else {
            beforeSeparator + afterSeparator
        }
        if (paddingStart < 0 && digits.length !in SEPARATOR_POSITION..MAX_CODE_DIGITS) return null
        if (digits.length < 2 || digits.length % 2 != 0 && digits.length < 10) return null
        if (digits.any { it !in ALPHABET }) return null

        val pairLength = minOf(digits.length, 10)
        if (pairLength % 2 != 0) return null

        var south = -90.0
        var west = -180.0
        var latitudeResolution = pairResolutions.first()
        var longitudeResolution = pairResolutions.first()
        var index = 0
        while (index < pairLength) {
            val place = pairResolutions[index / 2]
            south += ALPHABET.indexOf(digits[index]) * place
            west += ALPHABET.indexOf(digits[index + 1]) * place
            latitudeResolution = place
            longitudeResolution = place
            index += 2
        }

        while (index < digits.length) {
            val alphabetIndex = ALPHABET.indexOf(digits[index])
            latitudeResolution /= GRID_ROWS
            longitudeResolution /= GRID_COLUMNS
            south += (alphabetIndex / GRID_COLUMNS) * latitudeResolution
            west += (alphabetIndex % GRID_COLUMNS) * longitudeResolution
            index += 1
        }

        val north = (south + latitudeResolution).coerceAtMost(90.0)
        val east = west + longitudeResolution
        if (south !in -90.0..<90.0 || west !in -180.0..<180.0) return null
        if (north <= south || east <= west || east > 180.0) return null
        return OpenLocationCodeArea(
            southLatitude = south,
            westLongitude = west,
            northLatitude = north,
            eastLongitude = east,
        )
    }
}
