package com.chikabell.app.share

import android.content.Intent
import android.net.Uri

/**
 * Explicit, versioned intake contract for place suggestions coming from Personal Context.
 *
 * ChikaBell treats this only as input to the existing shared-place review flow. Receiving
 * this Intent must never silently persist a Location or register a geofence.
 */
object PersonalContextPlaceImportContract {
    const val ACTION_IMPORT_PLACE = "com.chikabell.app.action.IMPORT_PLACE"
    const val CONTRACT_VERSION = "1"
    const val SOURCE_PERSONAL_CONTEXT = "personal-context"

    const val EXTRA_CONTRACT_VERSION = "com.chikabell.app.extra.CONTRACT_VERSION"
    const val EXTRA_SOURCE = "com.chikabell.app.extra.SOURCE"
    const val EXTRA_SOURCE_TASK_ID = "com.chikabell.app.extra.SOURCE_TASK_ID"
    const val EXTRA_SOURCE_PLACE_ID = "com.chikabell.app.extra.SOURCE_PLACE_ID"
    const val EXTRA_NAME = "com.chikabell.app.extra.NAME"
    const val EXTRA_LATITUDE = "com.chikabell.app.extra.LATITUDE"
    const val EXTRA_LONGITUDE = "com.chikabell.app.extra.LONGITUDE"
    const val EXTRA_ADDRESS = "com.chikabell.app.extra.ADDRESS"
    const val EXTRA_NOTE = "com.chikabell.app.extra.NOTE"
    const val EXTRA_DEDUPE_KEY = "com.chikabell.app.extra.DEDUPE_KEY"

    private const val DATA_SCHEME = "chikabell"
    private const val DATA_HOST = "import-place"

    data class NormalizedPayload(
        val subject: String?,
        val texts: List<String>,
        val uris: List<String>,
        val rootLineageId: String,
    )

    fun normalize(intent: Intent): NormalizedPayload? {
        if (intent.action != ACTION_IMPORT_PLACE) return null
        val data = intent.data ?: return null
        if (!data.scheme.equals(DATA_SCHEME, ignoreCase = true) || !data.host.equals(DATA_HOST, ignoreCase = true)) return null
        if (intent.getStringExtra(EXTRA_CONTRACT_VERSION) != CONTRACT_VERSION) return null
        if (intent.getStringExtra(EXTRA_SOURCE) != SOURCE_PERSONAL_CONTEXT) return null

        val dedupeKey = intent.getStringExtra(EXTRA_DEDUPE_KEY)?.trim().orEmpty()
        if (dedupeKey.isBlank() || dedupeKey.length > 512) return null
        val pathKey = data.pathSegments.firstOrNull()?.trim().orEmpty()
        if (pathKey.isBlank() || pathKey != dedupeKey) return null

        val name = intent.getStringExtra(EXTRA_NAME)?.trim()?.takeIf { it.isNotEmpty() }?.take(300)
        val address = intent.getStringExtra(EXTRA_ADDRESS)?.trim()?.takeIf { it.isNotEmpty() }?.take(1000)
        val note = intent.getStringExtra(EXTRA_NOTE)?.trim()?.takeIf { it.isNotEmpty() }?.take(2000)
        val hasLatitude = intent.hasExtra(EXTRA_LATITUDE)
        val hasLongitude = intent.hasExtra(EXTRA_LONGITUDE)
        if (hasLatitude != hasLongitude) return null

        val latitude = if (hasLatitude) intent.getDoubleExtra(EXTRA_LATITUDE, Double.NaN) else null
        val longitude = if (hasLongitude) intent.getDoubleExtra(EXTRA_LONGITUDE, Double.NaN) else null
        if (latitude != null && (!latitude.isFinite() || latitude !in -90.0..90.0)) return null
        if (longitude != null && (!longitude.isFinite() || longitude !in -180.0..180.0)) return null
        if (name == null && address == null && latitude == null) return null

        val texts = buildList {
            address?.let { add(it) }
            note?.let { add(it) }
            if (latitude != null && longitude != null) add("緯度経度 $latitude,$longitude")
        }.distinct()
        val uris = if (latitude != null && longitude != null) {
            val label = Uri.encode(name ?: address ?: "Personal Context")
            listOf("geo:$latitude,$longitude?q=$latitude,$longitude($label)")
        } else {
            emptyList()
        }

        return NormalizedPayload(
            subject = name,
            texts = texts,
            uris = uris,
            rootLineageId = dedupeKey,
        )
    }

    /** Helper for a trusted sender; ChikaBell itself does not call this to send data. */
    fun dataUri(dedupeKey: String): Uri =
        Uri.Builder().scheme(DATA_SCHEME).authority(DATA_HOST).appendPath(dedupeKey).build()
}
