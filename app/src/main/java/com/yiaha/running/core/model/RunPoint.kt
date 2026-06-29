package com.yiaha.running.core.model

data class RunPoint(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val accuracyMeters: Float,
    val speedMetersPerSecond: Float?,
    val elapsedRealtimeNanos: Long,
    val wallClockMillis: Long,
    val source: LocationSource = LocationSource.Gps,
    val accepted: Boolean = true,
    val rejectReason: String? = null
)

enum class LocationSource {
    Gps,
    Network,
    Fused,
    SensorEstimated
}

