package com.chikabell.app.share

import java.net.URI
import java.net.URLDecoder
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class ParsedSharedPlace(
    val nameCandidate: String,
    val latitude: Double?,
    val longitude: Double?,
    val sourceUrl: String?,
    val rawText: String?,
    val parseMethod: SharedPlaceParseMethod,
    val shortUrl: String? = null,
    val warnings: List<String> = emptyList(),
    val confidence: SharedPlaceConfidence = SharedPlaceConfidence.UNRESOLVED,
    val candidates: List<CoordinateCandidate> = emptyList(),
    val selectedCandidateIndex: Int? = null,
    val failureReason: String? = null,
    val identityEvidence: List<PlaceIdentityEvidence> = emptyList(),
    val rootLineageId: String? = null,
) {
    val hasCoordinates: Boolean get() = latitude != null && longitude != null
    val requiresUserConfirmation: Boolean get() = confidence == SharedPlaceConfidence.NEEDS_CONFIRMATION
    val hasConflictingCandidates: Boolean get() = failureReason == "candidate_conflict"
}

enum class SharedPlaceConfidence {
    RESOLVING,
    HIGH_CONFIDENCE,
    NEEDS_CONFIRMATION,
    UNRESOLVED,
    NETWORK_FAILURE,
}

enum class SharedPlaceParseMethod {
    GEO_URI,
    MAPS_AT_COORDINATES,
    MAPS_DATA_COORDINATES,
    URL_QUERY_COORDINATES,
    EXPLICIT_COORDINATES,
    HTML_STRUCTURED_URL,
    HTML_PAGE_STATE,
    PLUS_CODE,
    CLIP_METADATA,
    SYSTEM_GEOCODER,
    MANUAL_REQUIRED,
}

enum class CoordinateSemanticRole {
    DESTINATION,
    SEARCH_TARGET,
    MARKER,
    EXPLICIT_LOCATION,
    PLUS_CODE_AREA,
    GEOCODE_RESULT,
    VIEWPORT_CENTER,
    UNKNOWN,
}

enum class CoordinateEvidenceFamily {
    GEO_URI,
    SHARED_TEXT,
    ORIGINAL_URL,
    REDIRECT_FINAL_URL,
    HTML_STRUCTURED_URL,
    HTML_PAGE_STATE,
    PLUS_CODE,
    CLIP_METADATA,
    SYSTEM_GEOCODER,
}

enum class CandidateReliability {
    STRONG,
    SUPPORTING,
    WEAK,
}

data class CoordinateCandidate(
    val latitude: Double,
    val longitude: Double,
    val semanticRole: CoordinateSemanticRole,
    val parseMethod: SharedPlaceParseMethod,
    val evidenceFamily: CoordinateEvidenceFamily,
    val evidenceId: String,
    val reliability: CandidateReliability,
    val lineageId: String = evidenceId,
    val uncertaintyMeters: Double? = null,
) {
    val userFacingSource: String
        get() = when (semanticRole) {
            CoordinateSemanticRole.DESTINATION -> "経路の目的地"
            CoordinateSemanticRole.SEARCH_TARGET -> "検索対象"
            CoordinateSemanticRole.MARKER -> "地点マーカー"
            CoordinateSemanticRole.EXPLICIT_LOCATION -> "明示された緯度・経度"
            CoordinateSemanticRole.PLUS_CODE_AREA -> "Plus Code"
            CoordinateSemanticRole.GEOCODE_RESULT -> "住所からの候補"
            CoordinateSemanticRole.VIEWPORT_CENTER -> "地図の表示中心"
            CoordinateSemanticRole.UNKNOWN -> "位置候補"
        }
}

data class SharedPlaceEvidence(
    val value: String,
    val family: CoordinateEvidenceFamily,
    val evidenceId: String,
    val lineageId: String = evidenceId,
)

enum class PlaceIdentityKind {
    NAME,
    ADDRESS,
    PHONE,
    FULL_PLUS_CODE,
    PLACE_ID,
    CID,
    FTID,
}

data class PlaceIdentityEvidence(
    val kind: PlaceIdentityKind,
    val normalizedValue: String,
    val evidenceFamily: CoordinateEvidenceFamily,
    val lineageId: String,
)

object SharedPlaceParser {
    private const val MAX_INPUT_LENGTH = 16_384
    private const val MAX_COORDINATE_CANDIDATES = 256
    private const val SAME_LOCATION_METERS = 25.0
    private val urlPattern = Regex("https://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)
    private val anyUrlPattern = Regex("https?://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)
    private val geoPattern = Regex("^geo:([+-]?\\d+(?:\\.\\d+)?),([+-]?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
    private val atPattern = Regex("@([+-]?\\d+(?:\\.\\d+)?),([+-]?\\d+(?:\\.\\d+)?)")
    private val mapsDataPattern = Regex("!3d([+-]?\\d+(?:\\.\\d+)?)!4d([+-]?\\d+(?:\\.\\d+)?)")
    private val explicitPattern = Regex("(?<![\\d.])([+-]?\\d{1,2}(?:\\.\\d+)?)[,\\t ]+([+-]?\\d{1,3}(?:\\.\\d+)?)(?![\\d.])")
    private val leadingCoordinatePattern = Regex("^\\s*([+-]?\\d{1,2}(?:\\.\\d+)?)[,\\t ]+([+-]?\\d{1,3}(?:\\.\\d+)?)")
    private val dmsPattern = Regex(
        """(?<!\d)(\d{1,2})\s*[°º]\s*(\d{1,2})\s*['′]\s*(\d{1,2}(?:\.\d+)?)\s*["″]\s*([NS])\s*[,;\s]+\s*(\d{1,3})\s*[°º]\s*(\d{1,2})\s*['′]\s*(\d{1,2}(?:\.\d+)?)\s*["″]\s*([EW])(?![A-Za-z])""",
        RegexOption.IGNORE_CASE,
    )
    private val fullPlusCodePattern = Regex(
        """(?<![023456789CFGHJMPQRVWX])([023456789CFGHJMPQRVWX]{8}\+(?:[23456789CFGHJMPQRVWX]{2,7})?)(?![023456789CFGHJMPQRVWX])""",
        RegexOption.IGNORE_CASE,
    )
    private val structuredTagPattern = Regex(
        "<(?:meta|link)\\b[^>]*(?:canonical|og:url|twitter:url)[^>]*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val phonePattern = Regex("""(?<!\d)(?:\+81[-\s]?|0)\d{1,4}[-\s]?\d{1,4}[-\s]?\d{3,4}(?!\d)""")
    private val addressLinePattern = Regex("""(?:〒\s*)?\d{3}-?\d{4}|.+[都道府県].+[市区町村]""")
    private val placeIdPattern = Regex("""(?:query_place_id|place_id)[:=]([A-Za-z0-9_-]{8,})""", RegexOption.IGNORE_CASE)
    private val cidPattern = Regex("""(?:cid|ludocid)=([0-9]{5,})""", RegexOption.IGNORE_CASE)
    private val ftidPattern = Regex("""!1s([^!&\s]{4,})""", RegexOption.IGNORE_CASE)

    fun parse(subject: String?, text: String?, uri: String?): ParsedSharedPlace {
        return parse(
            subject = subject,
            texts = listOfNotNull(text),
            uris = listOfNotNull(uri),
            htmlTexts = emptyList(),
        )
    }

    fun parse(
        subject: String?,
        texts: List<String>,
        uris: List<String>,
        htmlTexts: List<String>,
        metadataTexts: List<String> = emptyList(),
        rootLineageId: String = "shared-intent-payload",
    ): ParsedSharedPlace {
        val sharedPayloadLineage = rootLineageId.take(160)
        val safeSubject = subject.cleanInput()
        val safeTexts = texts.mapNotNull { it.cleanInput() }.distinct()
        val safeUris = uris.mapNotNull { it.cleanInput() }.distinct()
        val safeHtmlTexts = htmlTexts.mapNotNull { it.cleanInput() }.distinct()
        val safeMetadataTexts = metadataTexts.mapNotNull { it.cleanInput() }.distinct()
        val plainUrls = buildList {
            safeUris.filterTo(this) { it.startsWith("https://", true) }
            safeTexts.forEach { text ->
                urlPattern.findAll(text).map { it.value.trimUrlEnd() }.forEach(::add)
            }
        }.distinct()
        val structuredHtmlUrls = safeHtmlTexts.flatMap(::extractStructuredUrls).distinct()
        val allUrls = (plainUrls + structuredHtmlUrls).distinct()
        val shortUrl = allUrls.firstOrNull(::isApprovedShortMapsUrl)
        val sourceUrl = shortUrl
            ?: allUrls.firstOrNull(::isGoogleMapsUrl)
            ?: allUrls.firstOrNull()
        val name = nameCandidate(safeSubject, safeTexts, sourceUrl)
        val identityEvidence = collectIdentityEvidence(
            name = name,
            values = listOfNotNull(safeSubject) + safeTexts + safeUris + safeHtmlTexts + safeMetadataTexts,
            lineageId = sharedPayloadLineage,
        )
        val candidates = buildList {
            (safeUris + safeTexts.filter { it.startsWith("geo:", true) }).forEachIndexed { index, value ->
                addAll(collectGeoCandidates(value, "geo:$index", sharedPayloadLineage))
            }
            plainUrls.forEachIndexed { index, value ->
                addAll(
                    collectCandidates(
                        SharedPlaceEvidence(
                            value = value,
                            family = CoordinateEvidenceFamily.ORIGINAL_URL,
                            evidenceId = "original-url:$index",
                            lineageId = sharedPayloadLineage,
                        ),
                    ),
                )
            }
            structuredHtmlUrls.forEachIndexed { index, value ->
                addAll(
                    collectCandidates(
                        SharedPlaceEvidence(
                            value = value,
                            family = CoordinateEvidenceFamily.HTML_STRUCTURED_URL,
                            evidenceId = "shared-html-structured:$index",
                            lineageId = sharedPayloadLineage,
                        ),
                    ),
                )
            }
            safeSubject?.let { subject ->
                addAll(collectExplicitTextCandidates(subject, "shared-subject", sharedPayloadLineage))
                addAll(collectPlusCodeCandidates(subject, "shared-subject", sharedPayloadLineage))
            }
            safeTexts.forEachIndexed { index, value ->
                val withoutUrls = value.replace(anyUrlPattern, "")
                addAll(collectExplicitTextCandidates(withoutUrls, "shared-text:$index", sharedPayloadLineage))
                addAll(collectPlusCodeCandidates(withoutUrls, "shared-text:$index", sharedPayloadLineage))
            }
            safeHtmlTexts.forEachIndexed { index, value ->
                addAll(
                    collectCandidates(
                        SharedPlaceEvidence(
                            value = value,
                            family = CoordinateEvidenceFamily.HTML_PAGE_STATE,
                            evidenceId = "shared-html:$index",
                            lineageId = sharedPayloadLineage,
                        ),
                    ),
                )
            }
            safeMetadataTexts.forEachIndexed { index, value ->
                addAll(
                    collectCandidates(
                        SharedPlaceEvidence(
                            value = value,
                            family = CoordinateEvidenceFamily.CLIP_METADATA,
                            evidenceId = "clip-metadata:$index",
                            lineageId = sharedPayloadLineage,
                        ),
                    ),
                )
                addAll(
                    collectExplicitTextCandidates(
                        value = value.replace(anyUrlPattern, ""),
                        evidenceId = "clip-metadata:$index",
                        lineageId = sharedPayloadLineage,
                        family = CoordinateEvidenceFamily.CLIP_METADATA,
                        parseMethod = SharedPlaceParseMethod.CLIP_METADATA,
                    ),
                )
                addAll(collectPlusCodeCandidates(value, "clip-metadata:$index", sharedPayloadLineage))
            }
        }
        return resolve(
            name = name,
            rawText = safeTexts.firstOrNull(),
            sourceUrl = sourceUrl,
            shortUrl = shortUrl,
            candidates = candidates.take(MAX_COORDINATE_CANDIDATES),
            identityEvidence = identityEvidence,
            rootLineageId = sharedPayloadLineage,
        )
    }

    internal fun merge(
        place: ParsedSharedPlace,
        evidence: List<SharedPlaceEvidence>,
    ): ParsedSharedPlace {
        val additional = evidence.flatMap(::collectCandidates)
        return resolve(
            name = place.nameCandidate,
            rawText = place.rawText,
            sourceUrl = place.sourceUrl,
            shortUrl = place.shortUrl,
            candidates = place.candidates + additional,
            identityEvidence = place.identityEvidence,
            rootLineageId = place.rootLineageId,
        )
    }

    internal fun mergePlaces(
        current: ParsedSharedPlace?,
        incoming: ParsedSharedPlace,
    ): ParsedSharedPlace {
        if (current == null) return incoming
        val merged = resolve(
            name = current.nameCandidate.ifBlank { incoming.nameCandidate },
            rawText = current.rawText ?: incoming.rawText,
            sourceUrl = current.sourceUrl ?: incoming.sourceUrl,
            shortUrl = current.shortUrl ?: incoming.shortUrl,
            candidates = current.candidates + incoming.candidates,
            identityEvidence = (current.identityEvidence + incoming.identityEvidence).distinct(),
            rootLineageId = current.rootLineageId ?: incoming.rootLineageId,
        )
        return if (merged.hasCoordinates) {
            merged.copy(
                confidence = SharedPlaceConfidence.NEEDS_CONFIRMATION,
                warnings = listOf("追加した共有情報を含む候補位置です。地図で確認してください"),
            )
        } else {
            merged
        }
    }

    internal fun collectCandidates(evidence: SharedPlaceEvidence): List<CoordinateCandidate> {
        val decoded = decodeRepeatedly(evidence.value)
        val familyReliability = when (evidence.family) {
            CoordinateEvidenceFamily.HTML_STRUCTURED_URL -> CandidateReliability.SUPPORTING
            CoordinateEvidenceFamily.HTML_PAGE_STATE -> CandidateReliability.WEAK
            CoordinateEvidenceFamily.CLIP_METADATA -> CandidateReliability.WEAK
            else -> null
        }
        return buildList {
            if (decoded.startsWith("geo:", true)) {
                addAll(collectGeoCandidates(decoded, evidence.evidenceId, evidence.lineageId))
                return@buildList
            }
            val parsed = runCatching { URI(decoded) }.getOrNull()
            val isGoogleMapsUrl = parsed?.let(::isGoogleMapsUri) == true
            if (parsed?.scheme.equals("https", true) && isGoogleMapsUrl) {
                for (part in parsed.rawQuery.orEmpty().split('&')) {
                    val keyValue = part.split('=', limit = 2)
                    if (keyValue.size != 2) continue
                    val key = decodeRepeatedly(keyValue[0]).lowercase()
                    val value = decodeRepeatedly(keyValue[1])
                    val coordinates = leadingCoordinates(value) ?: continue
                    val role = when (key) {
                        "destination" -> CoordinateSemanticRole.DESTINATION
                        "q", "query" -> CoordinateSemanticRole.SEARCH_TARGET
                        "center", "ll" -> CoordinateSemanticRole.VIEWPORT_CENTER
                        else -> continue
                    }
                    val reliability = familyReliability ?: when (role) {
                        CoordinateSemanticRole.DESTINATION,
                        CoordinateSemanticRole.SEARCH_TARGET,
                        -> CandidateReliability.STRONG
                        CoordinateSemanticRole.VIEWPORT_CENTER -> CandidateReliability.WEAK
                        else -> CandidateReliability.SUPPORTING
                    }
                    add(
                        candidate(
                            coordinates = coordinates,
                            role = role,
                            method = SharedPlaceParseMethod.URL_QUERY_COORDINATES,
                            family = evidence.family,
                            evidenceId = evidence.evidenceId,
                            lineageId = evidence.lineageId,
                            reliability = reliability,
                        ),
                    )
                }
            }
            mapsDataPattern.findAll(decoded).forEachIndexed { index, match ->
                coordinates(match.groupValues[1], match.groupValues[2])?.let { coordinates ->
                    add(
                        candidate(
                            coordinates = coordinates,
                            role = CoordinateSemanticRole.MARKER,
                            method = if (evidence.family == CoordinateEvidenceFamily.HTML_PAGE_STATE) {
                                SharedPlaceParseMethod.HTML_PAGE_STATE
                            } else {
                                SharedPlaceParseMethod.MAPS_DATA_COORDINATES
                            },
                            family = evidence.family,
                            evidenceId = "${evidence.evidenceId}:marker:$index",
                            lineageId = evidence.lineageId,
                            reliability = familyReliability ?: CandidateReliability.SUPPORTING,
                        ),
                    )
                }
            }
            atPattern.findAll(decoded).forEachIndexed { index, match ->
                coordinates(match.groupValues[1], match.groupValues[2])?.let { coordinates ->
                    add(
                        candidate(
                            coordinates = coordinates,
                            role = CoordinateSemanticRole.VIEWPORT_CENTER,
                            method = if (evidence.family == CoordinateEvidenceFamily.HTML_PAGE_STATE) {
                                SharedPlaceParseMethod.HTML_PAGE_STATE
                            } else {
                                SharedPlaceParseMethod.MAPS_AT_COORDINATES
                            },
                            family = evidence.family,
                            evidenceId = "${evidence.evidenceId}:viewport:$index",
                            lineageId = evidence.lineageId,
                            reliability = CandidateReliability.WEAK,
                        ),
                    )
                }
            }
        }.distinctBy { it.dedupeKey }
    }

    private fun collectGeoCandidates(
        value: String,
        evidenceId: String,
        lineageId: String = evidenceId,
    ): List<CoordinateCandidate> {
        val match = geoPattern.find(value) ?: return emptyList()
        val query = value.substringAfter('?', "")
            .split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0].equals("q", true) }
            ?.get(1)
            ?.let(::decodeRepeatedly)
            ?.let(::leadingCoordinates)
        if (query != null) {
            return listOf(
                candidate(
                    coordinates = query,
                    role = CoordinateSemanticRole.EXPLICIT_LOCATION,
                    method = SharedPlaceParseMethod.GEO_URI,
                    family = CoordinateEvidenceFamily.GEO_URI,
                    evidenceId = "$evidenceId:q",
                    lineageId = lineageId,
                    reliability = CandidateReliability.STRONG,
                    uncertaintyMeters = 5.0,
                ),
            )
        }
        val base = coordinates(match.groupValues[1], match.groupValues[2]) ?: return emptyList()
        if (base.first == 0.0 && base.second == 0.0) return emptyList()
        return listOf(
            candidate(
                coordinates = base,
                role = CoordinateSemanticRole.EXPLICIT_LOCATION,
                method = SharedPlaceParseMethod.GEO_URI,
                family = CoordinateEvidenceFamily.GEO_URI,
                evidenceId = evidenceId,
                lineageId = lineageId,
                reliability = CandidateReliability.STRONG,
                uncertaintyMeters = 5.0,
            ),
        )
    }

    private fun collectExplicitTextCandidates(
        value: String,
        evidenceId: String,
        lineageId: String = evidenceId,
        family: CoordinateEvidenceFamily = CoordinateEvidenceFamily.SHARED_TEXT,
        parseMethod: SharedPlaceParseMethod = SharedPlaceParseMethod.EXPLICIT_COORDINATES,
    ): List<CoordinateCandidate> {
        val dmsCandidates = dmsPattern.findAll(value).mapIndexedNotNull { index, match ->
            dmsCoordinates(match)?.let { coordinates ->
                candidate(
                    coordinates = coordinates,
                    role = CoordinateSemanticRole.EXPLICIT_LOCATION,
                    method = parseMethod,
                    family = family,
                    evidenceId = "$evidenceId:dms:$index",
                    lineageId = lineageId,
                    reliability = CandidateReliability.SUPPORTING,
                    uncertaintyMeters = 15.0,
                )
            }
        }.toList()
        val withoutDms = dmsPattern.replace(value, " ")
        val decimalCandidates = explicitPattern.findAll(withoutDms).mapIndexedNotNull { index, match ->
            coordinates(match.groupValues[1], match.groupValues[2])?.let { coordinates ->
                candidate(
                    coordinates = coordinates,
                    role = CoordinateSemanticRole.EXPLICIT_LOCATION,
                    method = parseMethod,
                    family = family,
                    evidenceId = "$evidenceId:decimal:$index",
                    lineageId = lineageId,
                    reliability = CandidateReliability.SUPPORTING,
                    uncertaintyMeters = 10.0,
                )
            }
        }.toList()
        return dmsCandidates + decimalCandidates
    }

    internal fun isCoordinateOnlyLabel(value: String): Boolean {
        val safeValue = value.trim()
        if (safeValue.isBlank()) return false
        val hasCoordinates = collectExplicitTextCandidates(
            value = safeValue,
            evidenceId = "coordinate-label-check",
        ).isNotEmpty()
        if (!hasCoordinates) return false
        val residue = explicitPattern
            .replace(dmsPattern.replace(safeValue, " "), " ")
            .replace(Regex("[\\s,，;/|()\\[\\]{}]+"), "")
        return residue.isBlank()
    }

    private fun collectPlusCodeCandidates(
        value: String,
        evidenceId: String,
        lineageId: String = evidenceId,
    ): List<CoordinateCandidate> =
        fullPlusCodePattern.findAll(value).mapIndexedNotNull { index, match ->
            val area = OpenLocationCodeDecoder.decode(match.groupValues[1]) ?: return@mapIndexedNotNull null
            candidate(
                coordinates = area.centerLatitude to area.centerLongitude,
                role = CoordinateSemanticRole.PLUS_CODE_AREA,
                method = SharedPlaceParseMethod.PLUS_CODE,
                family = CoordinateEvidenceFamily.PLUS_CODE,
                evidenceId = "$evidenceId:plus-code:$index",
                lineageId = lineageId,
                reliability = CandidateReliability.SUPPORTING,
                uncertaintyMeters = area.uncertaintyMeters,
            )
        }.toList()

    private fun resolve(
        name: String,
        rawText: String?,
        sourceUrl: String?,
        shortUrl: String?,
        candidates: List<CoordinateCandidate>,
        identityEvidence: List<PlaceIdentityEvidence> = emptyList(),
        rootLineageId: String? = null,
    ): ParsedSharedPlace {
        val unique = candidates
            .asSequence()
            .distinctBy { it.dedupeKey }
            .take(MAX_COORDINATE_CANDIDATES)
            .toList()
        if (unique.isEmpty()) {
            val warning = if (shortUrl != null) {
                "Googleマップの短縮URLを確認しています"
            } else {
                "共有内容から位置を特定できませんでした。緯度・経度を入力してください"
            }
            return ParsedSharedPlace(
                nameCandidate = name,
                latitude = null,
                longitude = null,
                sourceUrl = sourceUrl,
                shortUrl = shortUrl,
                rawText = rawText,
                parseMethod = SharedPlaceParseMethod.MANUAL_REQUIRED,
                warnings = listOf(warning),
                confidence = SharedPlaceConfidence.UNRESOLVED,
                candidates = emptyList(),
                failureReason = "no_coordinate_candidate",
                identityEvidence = identityEvidence,
                rootLineageId = rootLineageId,
            )
        }

        val clusters = cluster(unique)
        val highConfidenceClusters = clusters.filter(::hasHighConfidenceEvidence)
        if (highConfidenceClusters.size > 1 || (highConfidenceClusters.isEmpty() && clusters.size > 1)) {
            return ParsedSharedPlace(
                nameCandidate = name,
                latitude = null,
                longitude = null,
                sourceUrl = sourceUrl,
                shortUrl = shortUrl,
                rawText = rawText,
                parseMethod = SharedPlaceParseMethod.MANUAL_REQUIRED,
                warnings = listOf("複数の異なる位置候補があります。自動選択していません"),
                confidence = SharedPlaceConfidence.UNRESOLVED,
                candidates = unique,
                failureReason = "candidate_conflict",
                identityEvidence = identityEvidence,
                rootLineageId = rootLineageId,
            )
        }

        val selectedCluster = highConfidenceClusters.singleOrNull() ?: clusters.single()
        val selected = selectedCluster.sortedWith(candidatePreference).first()
        val selectedIndex = unique.indexOf(selected)
        val confidence = if (highConfidenceClusters.size == 1) {
            SharedPlaceConfidence.HIGH_CONFIDENCE
        } else {
            SharedPlaceConfidence.NEEDS_CONFIRMATION
        }
        val warnings = when (confidence) {
            SharedPlaceConfidence.NEEDS_CONFIRMATION ->
                listOf("候補位置を取得しました。地図で確認してから保存してください")
            SharedPlaceConfidence.HIGH_CONFIDENCE -> {
                if (clusters.size > 1) {
                    listOf("目的地点と異なる表示中心も含まれています。保存前に位置を確認してください")
                } else {
                    emptyList()
                }
            }
            else -> emptyList()
        }
        return ParsedSharedPlace(
            nameCandidate = name,
            latitude = selected.latitude,
            longitude = selected.longitude,
            sourceUrl = sourceUrl,
            shortUrl = shortUrl,
            rawText = rawText,
            parseMethod = selected.parseMethod,
            warnings = warnings,
            confidence = confidence,
            candidates = unique,
            selectedCandidateIndex = selectedIndex,
            identityEvidence = identityEvidence,
            rootLineageId = rootLineageId,
        )
    }

    private fun collectIdentityEvidence(
        name: String,
        values: List<String>,
        lineageId: String,
    ): List<PlaceIdentityEvidence> = buildList {
        fun add(kind: PlaceIdentityKind, value: String) {
            val normalized = value.trim().replace(Regex("\\s+"), " ").lowercase().take(256)
            if (normalized.isNotBlank()) {
                add(PlaceIdentityEvidence(kind, normalized, CoordinateEvidenceFamily.SHARED_TEXT, lineageId))
            }
        }
        add(PlaceIdentityKind.NAME, name)
        values.forEach { value ->
            value.lineSequence()
                .map(String::trim)
                .filter { it.length in 5..256 && addressLinePattern.containsMatchIn(it) }
                .forEach { add(PlaceIdentityKind.ADDRESS, it) }
            phonePattern.findAll(value).forEach { add(PlaceIdentityKind.PHONE, it.value) }
            fullPlusCodePattern.findAll(value).forEach { add(PlaceIdentityKind.FULL_PLUS_CODE, it.groupValues[1]) }
            placeIdPattern.findAll(value).forEach { add(PlaceIdentityKind.PLACE_ID, it.groupValues[1]) }
            cidPattern.findAll(value).forEach { add(PlaceIdentityKind.CID, it.groupValues[1]) }
            ftidPattern.findAll(value).forEach { add(PlaceIdentityKind.FTID, it.groupValues[1]) }
        }
    }.distinct().take(24)

    private fun hasHighConfidenceEvidence(cluster: List<CoordinateCandidate>): Boolean {
        if (cluster.any { it.reliability == CandidateReliability.STRONG }) return true
        val independentSupporting = cluster.filter {
            it.reliability == CandidateReliability.SUPPORTING &&
                it.evidenceFamily !in setOf(
                    CoordinateEvidenceFamily.HTML_STRUCTURED_URL,
                    CoordinateEvidenceFamily.HTML_PAGE_STATE,
                )
        }
        return independentSupporting.map(CoordinateCandidate::evidenceFamily).distinct().size >= 2 &&
            independentSupporting.map(CoordinateCandidate::lineageId).distinct().size >= 2
    }

    private fun cluster(candidates: List<CoordinateCandidate>): List<List<CoordinateCandidate>> {
        val sorted = candidates.sortedWith(compareBy(CoordinateCandidate::latitude, CoordinateCandidate::longitude))
        val unvisited = sorted.toMutableSet()
        val clusters = mutableListOf<List<CoordinateCandidate>>()
        while (unvisited.isNotEmpty()) {
            val seed = unvisited.first()
            val cluster = mutableSetOf(seed)
            val queue = ArrayDeque<CoordinateCandidate>()
            queue.add(seed)
            unvisited.remove(seed)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                val neighbours = unvisited.filter { candidate ->
                    distanceMeters(current, candidate) <= clusterToleranceMeters(current, candidate)
                }
                neighbours.forEach {
                    unvisited.remove(it)
                    cluster.add(it)
                    queue.add(it)
                }
            }
            clusters += cluster.toList()
        }
        return clusters
    }

    private fun leadingCoordinates(value: String): Pair<Double, Double>? {
        val match = leadingCoordinatePattern.find(value) ?: return null
        return coordinates(match.groupValues[1], match.groupValues[2])
    }

    private fun isGoogleMapsUri(uri: URI): Boolean {
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        return host == "google.com" || host.endsWith(".google.com") ||
            host == "google.co.jp" || host.endsWith(".google.co.jp")
    }

    private fun isGoogleMapsUrl(value: String): Boolean =
        runCatching { isGoogleMapsUri(URI(value)) }.getOrDefault(false)

    private fun extractStructuredUrls(html: String): List<String> {
        val normalized = html.replace("\\/", "/").replace("&amp;", "&")
        return structuredTagPattern.findAll(normalized)
            .flatMap { tag -> urlPattern.findAll(tag.value) }
            .map { it.value.trimUrlEnd() }
            .filter(::isGoogleMapsUrl)
            .toList()
    }

    private fun distanceMeters(first: CoordinateCandidate, second: CoordinateCandidate): Double {
        val earthRadiusMeters = 6_371_000.0
        val lat1 = Math.toRadians(first.latitude)
        val lat2 = Math.toRadians(second.latitude)
        val deltaLat = lat2 - lat1
        val deltaLon = Math.toRadians(second.longitude - first.longitude)
        val a = sin(deltaLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(deltaLon / 2).pow(2)
        return 2 * earthRadiusMeters * asin(sqrt(a))
    }

    private fun clusterToleranceMeters(
        first: CoordinateCandidate,
        second: CoordinateCandidate,
    ): Double = max(
        SAME_LOCATION_METERS,
        (first.uncertaintyMeters ?: 0.0) + (second.uncertaintyMeters ?: 0.0),
    )

    private fun candidate(
        coordinates: Pair<Double, Double>,
        role: CoordinateSemanticRole,
        method: SharedPlaceParseMethod,
        family: CoordinateEvidenceFamily,
        evidenceId: String,
        lineageId: String = evidenceId,
        reliability: CandidateReliability,
        uncertaintyMeters: Double? = null,
    ) = CoordinateCandidate(
        latitude = coordinates.first,
        longitude = coordinates.second,
        semanticRole = role,
        parseMethod = method,
        evidenceFamily = family,
        evidenceId = evidenceId,
        reliability = reliability,
        lineageId = lineageId,
        uncertaintyMeters = uncertaintyMeters,
    )

    private val candidatePreference = compareByDescending<CoordinateCandidate> {
        when (it.reliability) {
            CandidateReliability.STRONG -> 3
            CandidateReliability.SUPPORTING -> 2
            CandidateReliability.WEAK -> 1
        }
    }.thenByDescending {
        when (it.semanticRole) {
            CoordinateSemanticRole.DESTINATION -> 6
            CoordinateSemanticRole.SEARCH_TARGET -> 5
            CoordinateSemanticRole.MARKER -> 4
            CoordinateSemanticRole.EXPLICIT_LOCATION -> 3
            CoordinateSemanticRole.PLUS_CODE_AREA -> 3
            CoordinateSemanticRole.GEOCODE_RESULT -> 2
            CoordinateSemanticRole.VIEWPORT_CENTER -> 2
            CoordinateSemanticRole.UNKNOWN -> 1
        }
    }

    private val CoordinateCandidate.dedupeKey: String
        get() = "$latitude|$longitude|$semanticRole|$evidenceFamily|$lineageId|$evidenceId"

    private fun parseQuery(url: String?): Pair<Double, Double>? {
        if (url == null) return null
        val parsed = runCatching { URI(url) }.getOrNull() ?: return null
        if (!parsed.scheme.equals("https", true)) return null
        for (part in parsed.rawQuery.orEmpty().split('&')) {
            val keyValue = part.split('=', limit = 2)
            if (keyValue.size != 2) continue
            val key = URLDecoder.decode(keyValue[0], "UTF-8").lowercase()
            if (key !in setOf("q", "query", "destination")) continue
            val value = URLDecoder.decode(keyValue[1], "UTF-8")
            leadingCoordinates(value)?.let { return it }
        }
        return null
    }

    private fun coordinates(latitude: String, longitude: String): Pair<Double, Double>? {
        val lat = latitude.toDoubleOrNull() ?: return null
        val lon = longitude.toDoubleOrNull() ?: return null
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
    }

    private fun dmsCoordinates(match: MatchResult): Pair<Double, Double>? {
        val latitude = dmsToDecimal(
            degrees = match.groupValues[1],
            minutes = match.groupValues[2],
            seconds = match.groupValues[3],
            direction = match.groupValues[4],
            maximumDegrees = 90,
        ) ?: return null
        val longitude = dmsToDecimal(
            degrees = match.groupValues[5],
            minutes = match.groupValues[6],
            seconds = match.groupValues[7],
            direction = match.groupValues[8],
            maximumDegrees = 180,
        ) ?: return null
        return latitude to longitude
    }

    private fun dmsToDecimal(
        degrees: String,
        minutes: String,
        seconds: String,
        direction: String,
        maximumDegrees: Int,
    ): Double? {
        val degreeValue = degrees.toIntOrNull() ?: return null
        val minuteValue = minutes.toIntOrNull() ?: return null
        val secondValue = seconds.toDoubleOrNull() ?: return null
        if (degreeValue !in 0..maximumDegrees || minuteValue !in 0..59 || secondValue !in 0.0..<60.0) return null
        if (degreeValue == maximumDegrees && (minuteValue != 0 || secondValue != 0.0)) return null
        val decimal = degreeValue + minuteValue / 60.0 + secondValue / 3_600.0
        return if (direction.equals("S", true) || direction.equals("W", true)) -decimal else decimal
    }

    private fun decodeRepeatedly(value: String): String {
        var decoded = value
        repeat(2) {
            decoded = runCatching { URLDecoder.decode(decoded, "UTF-8") }.getOrDefault(decoded)
        }
        return decoded
    }

    private fun nameCandidate(subject: String?, texts: List<String>, sourceUrl: String?): String {
        if (!subject.isNullOrBlank()) return subject.take(100)
        return texts.asSequence()
            .flatMap(String::lineSequence)
            .map(String::trim)
            .firstOrNull { it.isNotBlank() && it != sourceUrl && !it.startsWith("http", true) && !it.startsWith("geo:", true) }
            ?.take(100)
            .orEmpty()
    }

    private fun isApprovedShortMapsUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", true) || uri.userInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        return host == "maps.app.goo.gl" ||
            (host == "goo.gl" && uri.path.orEmpty().startsWith("/maps/"))
    }

    private fun String?.cleanInput(): String? = this?.trim()?.take(MAX_INPUT_LENGTH)?.takeIf(String::isNotEmpty)
    private fun String.trimUrlEnd(): String = trimEnd('\\', '.', ',', ')', ']', '}', '"', '\'')
}
