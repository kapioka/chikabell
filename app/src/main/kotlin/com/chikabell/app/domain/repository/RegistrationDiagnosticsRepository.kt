package com.chikabell.app.domain.repository

/**
 * Persists only the outcome of a Geofencing API request. It cannot inspect the
 * private Play services geofence registry; REQUEST_ACCEPTED means that the API
 * accepted the request at that time.
 */
interface RegistrationDiagnosticsRepository {
    suspend fun start(source: String, requestedCount: Int): String
    suspend fun finishAccepted(id: String, acceptedCount: Int)
    suspend fun finishRejected(id: String, result: String, errorCode: String?, message: String?)
    suspend fun prune(referenceTimeMillis: Long = System.currentTimeMillis())
}
