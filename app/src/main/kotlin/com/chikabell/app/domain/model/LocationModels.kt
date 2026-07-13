package com.chikabell.app.domain.model

data class SavedLocation(
    val id: String,
    val name: String,
    val message: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val transitionType: TransitionType,
    val loiteringDelayMs: Int?,
    val cooldownMinutes: Long,
    val enabled: Boolean,
    val sourceType: SourceType,
    val sourceUrl: String?,
    val sourceText: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastNotifiedAt: Long?,
    val lastEventAt: Long?,
    val registrationStatus: RegistrationStatus,
    val registrationErrorCode: String?,
    val registrationErrorMessage: String?,
    val lastRegisteredAt: Long?,
    val sortOrder: Long,
    val registrationGenerationId: String? = null,
    val tags: List<LocationTag> = emptyList(),
)

data class LocationDraft @JvmOverloads constructor(
    val name: String,
    val message: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val loiteringDelaySeconds: Int,
    val cooldownMinutes: Long,
    val enabled: Boolean,
    val sourceType: SourceType = SourceType.MANUAL,
    val sourceUrl: String? = null,
    val sourceText: String? = null,
    val tags: List<String> = emptyList(),
)

data class LocationTag(
    val id: String,
    val name: String,
    val normalizedName: String,
    val usageCount: Int = 0,
)

enum class TransitionType {
    ENTER,
    DWELL,
    EXIT,
}

enum class SourceType {
    MANUAL,
    MAP_SHARE,
    JSON_IMPORT,
    CSV_IMPORT,
}

enum class RegistrationStatus {
    INACTIVE,
    PENDING,
    REGISTERED,
    ERROR,
}

object LocationNotificationDefaults {
    const val COOLDOWN_MINUTES: Long = 12L * 60L
    const val LOITERING_DELAY_SECONDS: Int = 60
    const val LOITERING_DELAY_MS: Int = LOITERING_DELAY_SECONDS * 1_000
}

data class NotificationPreset(
    val id: String,
    val name: String,
    val radiusMeters: Int,
    val loiteringDelaySeconds: Int,
    val cooldownMinutes: Long,
    val sortOrder: Int,
)
