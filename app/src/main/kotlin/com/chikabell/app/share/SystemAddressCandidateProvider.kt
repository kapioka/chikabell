package com.chikabell.app.share

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

data class AddressCoordinateCandidate(
    val label: String,
    val latitude: Double,
    val longitude: Double,
)

fun interface AddressCandidateProvider {
    suspend fun find(address: String): List<AddressCoordinateCandidate>
}

object UnavailableAddressCandidateProvider : AddressCandidateProvider {
    override suspend fun find(address: String): List<AddressCoordinateCandidate> = emptyList()
}

class SystemAddressCandidateProvider(context: Context) : AddressCandidateProvider {
    private val appContext = context.applicationContext

    override suspend fun find(address: String): List<AddressCoordinateCandidate> {
        val query = address.trim().take(256)
        if (query.length < 3 || !Geocoder.isPresent()) return emptyList()
        val geocoder = Geocoder(appContext, Locale.getDefault())
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocationName(query, MAX_RESULTS) { results ->
                    if (continuation.isActive) continuation.resume(results)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            withContext(Dispatchers.IO) {
                runCatching { geocoder.getFromLocationName(query, MAX_RESULTS).orEmpty() }.getOrDefault(emptyList())
            }
        }
        return addresses
            .asSequence()
            .filter(Address::hasLatitude)
            .filter(Address::hasLongitude)
            .map {
                AddressCoordinateCandidate(
                    label = buildLabel(it, query),
                    latitude = it.latitude,
                    longitude = it.longitude,
                )
            }
            .filter { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
            .distinctBy { "${"%.6f".format(Locale.US, it.latitude)}|${"%.6f".format(Locale.US, it.longitude)}" }
            .take(MAX_RESULTS)
            .toList()
    }

    private fun buildLabel(address: Address, fallback: String): String =
        buildList {
            repeat(address.maxAddressLineIndex + 1) { index ->
                address.getAddressLine(index)?.takeIf(String::isNotBlank)?.let(::add)
            }
        }.joinToString(" ").ifBlank { fallback }.take(256)

    private companion object {
        const val MAX_RESULTS = 5
    }
}
