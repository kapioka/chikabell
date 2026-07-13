package com.chikabell.app.domain.validation

import com.chikabell.app.domain.model.LocationDraft

object LocationValidator {
    const val MIN_RADIUS_METERS = 100
    const val MAX_RADIUS_METERS = 5000
    const val MIN_LOITERING_SECONDS = 15
    const val MAX_LOITERING_SECONDS = 3600
    const val MAX_COOLDOWN_MINUTES = 720L * 60L

    fun validate(draft: LocationDraft): List<LocationValidationError> {
        val errors = mutableListOf<LocationValidationError>()
        val nameLength = draft.name.trim().length
        val messageLength = draft.message.trim().length

        if (nameLength !in 1..100) {
            errors += LocationValidationError.Name
        }
        if (messageLength > 500) {
            errors += LocationValidationError.Message
        }
        if (draft.latitude !in -90.0..90.0) {
            errors += LocationValidationError.Latitude
        }
        if (draft.longitude !in -180.0..180.0) {
            errors += LocationValidationError.Longitude
        }
        if (draft.radiusMeters !in MIN_RADIUS_METERS..MAX_RADIUS_METERS) {
            errors += LocationValidationError.Radius
        }
        if (draft.loiteringDelaySeconds !in MIN_LOITERING_SECONDS..MAX_LOITERING_SECONDS) {
            errors += LocationValidationError.LoiteringDelay
        }
        if (draft.cooldownMinutes !in 0..MAX_COOLDOWN_MINUTES) {
            errors += LocationValidationError.Cooldown
        }

        return errors
    }
}

enum class LocationValidationError {
    Name,
    Message,
    Latitude,
    Longitude,
    Radius,
    LoiteringDelay,
    Cooldown,
}
