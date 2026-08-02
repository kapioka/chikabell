package com.chikabell.app.ui.locations

import com.chikabell.app.domain.model.SourceType
import com.chikabell.app.share.CandidateReliability
import com.chikabell.app.share.CoordinateCandidate
import com.chikabell.app.share.CoordinateEvidenceFamily
import com.chikabell.app.share.CoordinateSemanticRole
import com.chikabell.app.share.ParsedSharedPlace
import com.chikabell.app.share.SharedPlaceConfidence
import com.chikabell.app.share.SharedPlaceParseMethod
import com.chikabell.app.share.PlaceIdentityEvidence
import com.chikabell.app.share.PlaceIdentityKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class RegistrationSessionPhase {
    EDITING,
    RESOLVING,
    AWAITING_MAP_CONFIRMATION,
    AWAITING_RECOVERY_SHARE,
    PENDING_SHARE_DECISION,
    GEOCODING,
    CANDIDATE_REVIEW,
    READY_TO_SAVE,
}

enum class RegistrationField {
    NAME,
    MESSAGE,
    ADDRESS,
    LATITUDE,
    LONGITUDE,
    RADIUS,
    LOITERING_DELAY,
    COOLDOWN,
    ENABLED,
    TAGS,
}

data class PendingSharedPlace(
    val eventId: String,
    val place: ParsedSharedPlace,
)

data class SharedRegistrationSession(
    val sessionId: String,
    val phase: RegistrationSessionPhase,
    val activeEventId: String,
    val handledTerminalEventIds: Set<String> = emptySet(),
    val userEditedFields: Set<RegistrationField> = emptySet(),
    val pendingIncomingShare: PendingSharedPlace? = null,
)

data class RestoredSharedRegistration(
    val session: SharedRegistrationSession,
    val form: LocationFormState,
    val selectedPresetId: String,
    val place: ParsedSharedPlace,
    val candidateConfirmed: Boolean,
    val coordinatesManuallyEdited: Boolean,
)

internal object SharedRegistrationSessionCodec {
    private const val SCHEMA_VERSION = 1
    private const val MAX_SNAPSHOT_CHARS = 64_000
    private const val MAX_TEXT_CHARS = 2_048
    private const val MAX_URL_CHARS = 4_096
    private const val MAX_CANDIDATES = 16
    private const val MAX_EVENT_IDS = 16
    private const val MAX_WARNINGS = 4
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(value: RestoredSharedRegistration): String? {
        val encoded = buildJsonObject {
            put("schemaVersion", JsonPrimitive(SCHEMA_VERSION))
            put("session", encodeSession(value.session))
            put("form", encodeForm(value.form))
            put("selectedPresetId", JsonPrimitive(value.selectedPresetId.take(64)))
            put("place", encodePlace(value.place))
            put("candidateConfirmed", JsonPrimitive(value.candidateConfirmed))
            put("coordinatesManuallyEdited", JsonPrimitive(value.coordinatesManuallyEdited))
        }.toString()
        return encoded.takeIf { it.length <= MAX_SNAPSHOT_CHARS }
    }

    fun decode(encoded: String?): RestoredSharedRegistration? {
        if (encoded.isNullOrBlank() || encoded.length > MAX_SNAPSHOT_CHARS) return null
        return runCatching {
            val root = json.parseToJsonElement(encoded).jsonObject
            if (root.int("schemaVersion") != SCHEMA_VERSION) return null
            val session = decodeSession(root.objectValue("session")) ?: return null
            val form = decodeForm(root.objectValue("form")) ?: return null
            val place = decodePlace(root.objectValue("place")) ?: return null
            RestoredSharedRegistration(
                session = session,
                form = form,
                selectedPresetId = root.string("selectedPresetId")?.take(64) ?: "walk",
                place = place,
                candidateConfirmed = root.boolean("candidateConfirmed") ?: false,
                coordinatesManuallyEdited = root.boolean("coordinatesManuallyEdited") ?: false,
            )
        }.getOrNull()
    }

    private fun encodeSession(value: SharedRegistrationSession): JsonObject = buildJsonObject {
        put("sessionId", JsonPrimitive(value.sessionId.take(128)))
        put("phase", JsonPrimitive(value.phase.name))
        put("activeEventId", JsonPrimitive(value.activeEventId.take(128)))
        put(
            "handledTerminalEventIds",
            buildJsonArray {
                value.handledTerminalEventIds.toList().takeLast(MAX_EVENT_IDS).forEach {
                    add(JsonPrimitive(it.take(128)))
                }
            },
        )
        put(
            "userEditedFields",
            buildJsonArray {
                value.userEditedFields.forEach { add(JsonPrimitive(it.name)) }
            },
        )
        value.pendingIncomingShare?.let { pending ->
            put(
                "pendingIncomingShare",
                buildJsonObject {
                    put("eventId", JsonPrimitive(pending.eventId.take(128)))
                    put("place", encodePlace(pending.place))
                },
            )
        }
    }

    private fun decodeSession(value: JsonObject?): SharedRegistrationSession? {
        value ?: return null
        val sessionId = value.string("sessionId")?.takeIf(String::isNotBlank) ?: return null
        val phase = enumValueOrNull<RegistrationSessionPhase>(value.string("phase")) ?: return null
        val activeEventId = value.string("activeEventId")?.takeIf(String::isNotBlank) ?: return null
        val handled = value.arrayValue("handledTerminalEventIds")
            .mapNotNull { it.asString()?.take(128) }
            .takeLast(MAX_EVENT_IDS)
            .toSet()
        val edited = value.arrayValue("userEditedFields")
            .mapNotNull { enumValueOrNull<RegistrationField>(it.asString()) }
            .toSet()
        val pending = value.objectValue("pendingIncomingShare")?.let { pendingObject ->
            val eventId = pendingObject.string("eventId") ?: return@let null
            val place = decodePlace(pendingObject.objectValue("place")) ?: return@let null
            PendingSharedPlace(eventId.take(128), place)
        }
        return SharedRegistrationSession(
            sessionId = sessionId.take(128),
            phase = phase,
            activeEventId = activeEventId.take(128),
            handledTerminalEventIds = handled,
            userEditedFields = edited,
            pendingIncomingShare = pending,
        )
    }

    private fun encodeForm(value: LocationFormState): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(value.name.take(100)))
        put("message", JsonPrimitive(value.message.take(500)))
        put("address", JsonPrimitive(value.address.take(500)))
        put("latitude", JsonPrimitive(value.latitude.take(64)))
        put("longitude", JsonPrimitive(value.longitude.take(64)))
        put("radiusMeters", JsonPrimitive(value.radiusMeters.take(32)))
        put("loiteringDelaySeconds", JsonPrimitive(value.loiteringDelaySeconds.take(32)))
        put("cooldownHours", JsonPrimitive(value.cooldownHours.take(32)))
        put("enabled", JsonPrimitive(value.enabled))
        put("sourceType", JsonPrimitive(value.sourceType.name))
        value.sourceUrl?.let { put("sourceUrl", JsonPrimitive(it.take(MAX_URL_CHARS))) }
        value.sourceText?.let { put("sourceText", JsonPrimitive(it.take(MAX_TEXT_CHARS))) }
        put(
            "tags",
            buildJsonArray {
                value.tags.take(5).forEach { add(JsonPrimitive(it.take(50))) }
            },
        )
        put("tagInput", JsonPrimitive(value.tagInput.take(50)))
    }

    private fun decodeForm(value: JsonObject?): LocationFormState? {
        value ?: return null
        return LocationFormState(
            name = value.string("name").orEmpty().take(100),
            message = value.string("message").orEmpty().take(500),
            address = value.string("address").orEmpty().take(500),
            latitude = value.string("latitude").orEmpty().take(64),
            longitude = value.string("longitude").orEmpty().take(64),
            radiusMeters = value.string("radiusMeters")?.take(32) ?: "300",
            loiteringDelaySeconds = value.string("loiteringDelaySeconds")?.take(32) ?: "60",
            cooldownHours = value.string("cooldownHours")?.take(32) ?: "12",
            enabled = value.boolean("enabled") ?: true,
            sourceType = enumValueOrNull<SourceType>(value.string("sourceType")) ?: SourceType.MAP_SHARE,
            sourceUrl = value.string("sourceUrl")?.take(MAX_URL_CHARS),
            sourceText = value.string("sourceText")?.take(MAX_TEXT_CHARS),
            tags = value.arrayValue("tags").mapNotNull { it.asString()?.take(50) }.take(5),
            tagInput = value.string("tagInput").orEmpty().take(50),
        )
    }

    private fun encodePlace(value: ParsedSharedPlace): JsonObject = buildJsonObject {
        put("nameCandidate", JsonPrimitive(value.nameCandidate.take(100)))
        value.latitude?.let { put("latitude", JsonPrimitive(it)) }
        value.longitude?.let { put("longitude", JsonPrimitive(it)) }
        value.sourceUrl?.let { put("sourceUrl", JsonPrimitive(it.take(MAX_URL_CHARS))) }
        value.shortUrl?.let { put("shortUrl", JsonPrimitive(it.take(MAX_URL_CHARS))) }
        put("parseMethod", JsonPrimitive(value.parseMethod.name))
        put("confidence", JsonPrimitive(value.confidence.name))
        put(
            "warnings",
            buildJsonArray {
                value.warnings.take(MAX_WARNINGS).forEach { add(JsonPrimitive(it.take(300))) }
            },
        )
        put(
            "candidates",
            buildJsonArray {
                value.candidates.take(MAX_CANDIDATES).forEach { add(encodeCandidate(it)) }
            },
        )
        put(
            "identityEvidence",
            buildJsonArray {
                value.identityEvidence.take(24).forEach { evidence ->
                    add(
                        buildJsonObject {
                            put("kind", JsonPrimitive(evidence.kind.name))
                            put("normalizedValue", JsonPrimitive(evidence.normalizedValue.take(256)))
                            put("evidenceFamily", JsonPrimitive(evidence.evidenceFamily.name))
                            put("lineageId", JsonPrimitive(evidence.lineageId.take(160)))
                        },
                    )
                }
            },
        )
        value.selectedCandidateIndex?.takeIf { it in 0 until MAX_CANDIDATES }?.let {
            put("selectedCandidateIndex", JsonPrimitive(it))
        }
        value.failureReason?.let { put("failureReason", JsonPrimitive(it.take(100))) }
        value.rootLineageId?.let { put("rootLineageId", JsonPrimitive(it.take(160))) }
    }

    private fun decodePlace(value: JsonObject?): ParsedSharedPlace? {
        value ?: return null
        val parseMethod = enumValueOrNull<SharedPlaceParseMethod>(value.string("parseMethod")) ?: return null
        val confidence = enumValueOrNull<SharedPlaceConfidence>(value.string("confidence")) ?: return null
        val candidates = value.arrayValue("candidates")
            .mapNotNull { element -> decodeCandidate(runCatching { element.jsonObject }.getOrNull()) }
            .take(MAX_CANDIDATES)
        val selected = value.int("selectedCandidateIndex")?.takeIf { it in candidates.indices }
        val identityEvidence = value.arrayValue("identityEvidence").mapNotNull { element ->
            val objectValue = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            PlaceIdentityEvidence(
                kind = enumValueOrNull<PlaceIdentityKind>(objectValue.string("kind")) ?: return@mapNotNull null,
                normalizedValue = objectValue.string("normalizedValue")?.take(256) ?: return@mapNotNull null,
                evidenceFamily = enumValueOrNull<CoordinateEvidenceFamily>(objectValue.string("evidenceFamily"))
                    ?: return@mapNotNull null,
                lineageId = objectValue.string("lineageId")?.take(160) ?: return@mapNotNull null,
            )
        }.take(24)
        return ParsedSharedPlace(
            nameCandidate = value.string("nameCandidate").orEmpty().take(100),
            latitude = value.double("latitude"),
            longitude = value.double("longitude"),
            sourceUrl = value.string("sourceUrl")?.take(MAX_URL_CHARS),
            rawText = null,
            parseMethod = parseMethod,
            shortUrl = value.string("shortUrl")?.take(MAX_URL_CHARS),
            warnings = value.arrayValue("warnings").mapNotNull { it.asString()?.take(300) }.take(MAX_WARNINGS),
            confidence = confidence,
            candidates = candidates,
            selectedCandidateIndex = selected,
            failureReason = value.string("failureReason")?.take(100),
            identityEvidence = identityEvidence,
            rootLineageId = value.string("rootLineageId")?.take(160),
        )
    }

    private fun encodeCandidate(value: CoordinateCandidate): JsonObject = buildJsonObject {
        put("latitude", JsonPrimitive(value.latitude))
        put("longitude", JsonPrimitive(value.longitude))
        put("semanticRole", JsonPrimitive(value.semanticRole.name))
        put("parseMethod", JsonPrimitive(value.parseMethod.name))
        put("evidenceFamily", JsonPrimitive(value.evidenceFamily.name))
        put("evidenceId", JsonPrimitive(value.evidenceId.take(160)))
        put("reliability", JsonPrimitive(value.reliability.name))
        put("lineageId", JsonPrimitive(value.lineageId.take(160)))
        value.uncertaintyMeters?.let { put("uncertaintyMeters", JsonPrimitive(it)) }
    }

    private fun decodeCandidate(value: JsonObject?): CoordinateCandidate? {
        value ?: return null
        return CoordinateCandidate(
            latitude = value.double("latitude") ?: return null,
            longitude = value.double("longitude") ?: return null,
            semanticRole = enumValueOrNull<CoordinateSemanticRole>(value.string("semanticRole")) ?: return null,
            parseMethod = enumValueOrNull<SharedPlaceParseMethod>(value.string("parseMethod")) ?: return null,
            evidenceFamily = enumValueOrNull<CoordinateEvidenceFamily>(value.string("evidenceFamily")) ?: return null,
            evidenceId = value.string("evidenceId")?.take(160) ?: return null,
            reliability = enumValueOrNull<CandidateReliability>(value.string("reliability")) ?: return null,
            lineageId = value.string("lineageId")?.take(160).orEmpty(),
            uncertaintyMeters = value.double("uncertaintyMeters"),
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String?): T? =
        value?.let { candidate -> enumValues<T>().firstOrNull { it.name == candidate } }

    private fun JsonObject.string(key: String): String? = this[key]?.asString()
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.double(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.objectValue(key: String): JsonObject? =
        this[key]?.let { runCatching { it.jsonObject }.getOrNull() }

    private fun JsonObject.arrayValue(key: String): JsonArray =
        this[key]?.let { runCatching { it.jsonArray }.getOrNull() } ?: JsonArray(emptyList())

    private fun kotlinx.serialization.json.JsonElement.asString(): String? =
        runCatching { jsonPrimitive.contentOrNull }.getOrNull()
}
