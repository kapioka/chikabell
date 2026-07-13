package com.chikabell.app.share

import java.net.URI
import java.net.URLDecoder

data class ParsedSharedPlace(
    val nameCandidate: String,
    val latitude: Double?,
    val longitude: Double?,
    val sourceUrl: String?,
    val rawText: String?,
    val parseMethod: SharedPlaceParseMethod,
    val warnings: List<String> = emptyList(),
) {
    val hasCoordinates: Boolean get() = latitude != null && longitude != null
}

enum class SharedPlaceParseMethod {
    GEO_URI,
    MAPS_AT_COORDINATES,
    MAPS_DATA_COORDINATES,
    URL_QUERY_COORDINATES,
    EXPLICIT_COORDINATES,
    MANUAL_REQUIRED,
}

object SharedPlaceParser {
    private const val MAX_INPUT_LENGTH = 16_384
    private val urlPattern = Regex("https://[^\\s]+", RegexOption.IGNORE_CASE)
    private val anyUrlPattern = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
    private val geoPattern = Regex("^geo:([+-]?\\d+(?:\\.\\d+)?),([+-]?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
    private val atPattern = Regex("@([+-]?\\d+(?:\\.\\d+)?),([+-]?\\d+(?:\\.\\d+)?)")
    private val mapsDataPattern = Regex("!3d([+-]?\\d+(?:\\.\\d+)?)!4d([+-]?\\d+(?:\\.\\d+)?)")
    private val explicitPattern = Regex("(?<![\\d.])([+-]?\\d{1,2}(?:\\.\\d+)?)[,\\s]+([+-]?\\d{1,3}(?:\\.\\d+)?)(?![\\d.])")
    private val coordinateQueryKeys = setOf("q", "query", "ll", "center", "destination")

    fun parse(subject: String?, text: String?, uri: String?): ParsedSharedPlace {
        val safeSubject = subject.cleanInput()
        val safeText = text.cleanInput()
        val safeUri = uri.cleanInput()
        val combined = listOfNotNull(safeUri, safeText).joinToString("\n")
        val decodedCombined = decodeRepeatedly(combined)
        val sourceUrl = listOfNotNull(safeUri?.takeIf { it.startsWith("https://", true) }, safeText?.let(urlPattern::find)?.value)
            .firstOrNull()
            ?.trimEnd('.', ',', ')', ']', '}')
        val name = nameCandidate(safeSubject, safeText, sourceUrl)

        parseGeo(safeUri)?.let { return result(name, it, sourceUrl, safeText, SharedPlaceParseMethod.GEO_URI) }
        parseGeo(safeText)?.let { return result(name, it, sourceUrl, safeText, SharedPlaceParseMethod.GEO_URI) }
        parseRegex(atPattern, combined)?.let { return result(name, it, sourceUrl, safeText, SharedPlaceParseMethod.MAPS_AT_COORDINATES) }
        parseRegex(atPattern, decodedCombined)?.let { return result(name, it, sourceUrl, safeText, SharedPlaceParseMethod.MAPS_AT_COORDINATES) }
        parseRegex(mapsDataPattern, combined)?.let { return result(name, it, sourceUrl, safeText, SharedPlaceParseMethod.MAPS_DATA_COORDINATES) }
        parseRegex(mapsDataPattern, decodedCombined)?.let { return result(name, it, sourceUrl, safeText, SharedPlaceParseMethod.MAPS_DATA_COORDINATES) }
        parseQuery(sourceUrl)?.let { return result(name, it, sourceUrl, safeText, SharedPlaceParseMethod.URL_QUERY_COORDINATES) }
        val textWithoutUrls = safeText.orEmpty().replace(anyUrlPattern, "")
        parseRegex(explicitPattern, textWithoutUrls)?.let { return result(name, it, sourceUrl, safeText, SharedPlaceParseMethod.EXPLICIT_COORDINATES) }

        val warning = if (sourceUrl?.let(::isApprovedShortMapsUrl) == true) {
            "短縮URLはネットワーク展開せず、座標を手入力してください"
        } else {
            "共有内容から座標を確認できません。緯度・経度を手入力してください"
        }
        return ParsedSharedPlace(
            nameCandidate = name,
            latitude = null,
            longitude = null,
            sourceUrl = sourceUrl,
            rawText = safeText,
            parseMethod = SharedPlaceParseMethod.MANUAL_REQUIRED,
            warnings = listOf(warning),
        )
    }

    private fun parseGeo(value: String?): Pair<Double, Double>? {
        val match = value?.let(geoPattern::find) ?: return null
        return coordinates(match.groupValues[1], match.groupValues[2])
    }

    private fun parseRegex(pattern: Regex, value: String): Pair<Double, Double>? {
        val match = pattern.find(value) ?: return null
        return coordinates(match.groupValues[1], match.groupValues[2])
    }

    private fun parseQuery(url: String?): Pair<Double, Double>? {
        if (url == null) return null
        val parsed = runCatching { URI(url) }.getOrNull() ?: return null
        if (!parsed.scheme.equals("https", true)) return null
        for (part in parsed.rawQuery.orEmpty().split('&')) {
            val keyValue = part.split('=', limit = 2)
            if (keyValue.size != 2) continue
            val key = URLDecoder.decode(keyValue[0], "UTF-8").lowercase()
            if (key !in coordinateQueryKeys) continue
            val value = URLDecoder.decode(keyValue[1], "UTF-8")
            parseRegex(explicitPattern, value)?.let { return it }
        }
        return null
    }

    private fun coordinates(latitude: String, longitude: String): Pair<Double, Double>? {
        val lat = latitude.toDoubleOrNull() ?: return null
        val lon = longitude.toDoubleOrNull() ?: return null
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
    }

    private fun decodeRepeatedly(value: String): String {
        var decoded = value
        repeat(2) {
            decoded = runCatching { URLDecoder.decode(decoded, "UTF-8") }.getOrDefault(decoded)
        }
        return decoded
    }

    private fun result(
        name: String,
        coordinates: Pair<Double, Double>,
        sourceUrl: String?,
        rawText: String?,
        method: SharedPlaceParseMethod,
    ) = ParsedSharedPlace(name, coordinates.first, coordinates.second, sourceUrl, rawText, method)

    private fun nameCandidate(subject: String?, text: String?, sourceUrl: String?): String {
        if (!subject.isNullOrBlank()) return subject.take(100)
        return text.orEmpty()
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotBlank() && it != sourceUrl && !it.startsWith("http", true) && !it.startsWith("geo:", true) }
            ?.take(100)
            .orEmpty()
    }

    private fun isApprovedShortMapsUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme.equals("https", true) && uri.host.equals("maps.app.goo.gl", true)
    }

    private fun String?.cleanInput(): String? = this?.trim()?.take(MAX_INPUT_LENGTH)?.takeIf(String::isNotEmpty)
}
