package com.chikabell.app.domain.model

data class NotificationHistory(
    val id: String,
    val locationId: String?,
    val locationNameSnapshot: String,
    val messageSnapshot: String,
    val latitudeSnapshot: Double,
    val longitudeSnapshot: Double,
    val radiusSnapshot: Int,
    val deviceLatitude: Double?,
    val deviceLongitude: Double?,
    val deviceAccuracyMeters: Float?,
    val deviceLocationAt: Long?,
    val deviceLocationProvider: String?,
    val transitionType: TransitionType,
    val eventAt: Long,
    val postedAt: Long?,
    val deliveryStatus: DeliveryStatus,
    val deliveryReason: String?,
    val userState: HistoryUserState,
    val createdAt: Long,
    /** Accepted Geofencing API registration generation active when this event arrived. */
    val registrationGenerationId: String? = null,
)

enum class DeliveryStatus {
    TRACKED,
    POSTED,
    SUPPRESSED,
    FAILED,
}

enum class HistoryUserState {
    UNREAD,
    READ,
    COMPLETED,
}

data class HistoryFilter(
    val locationQuery: String = "",
    val deliveryStatus: DeliveryStatus? = null,
    val period: HistoryPeriod = HistoryPeriod.ALL,
) {
    fun cutoffEpochMillis(now: Long = System.currentTimeMillis()): Long = when (period) {
        HistoryPeriod.ALL -> 0L
        HistoryPeriod.TODAY -> now - 24L * 60L * 60L * 1_000L
        HistoryPeriod.SEVEN_DAYS -> now - 7L * 24L * 60L * 60L * 1_000L
        HistoryPeriod.THIRTY_DAYS -> now - 30L * 24L * 60L * 60L * 1_000L
    }
}

enum class HistoryPeriod {
    ALL,
    TODAY,
    SEVEN_DAYS,
    THIRTY_DAYS,
}
