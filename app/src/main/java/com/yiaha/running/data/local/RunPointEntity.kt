package com.yiaha.running.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yiaha.running.core.model.LocationSource
import com.yiaha.running.core.model.RunPoint

@Entity(
    tableName = "run_points",
    indices = [
        Index(value = ["sessionId", "wallClockMillis"])
    ]
)
data class RunPointEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val sessionId: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val accuracyMeters: Float,
    val speedMetersPerSecond: Float?,
    val elapsedRealtimeNanos: Long,
    val wallClockMillis: Long,
    val source: String,
    val accepted: Boolean,
    val rejectReason: String?
)

fun RunPoint.toEntity(sessionId: String): RunPointEntity {
    return RunPointEntity(
        sessionId = sessionId,
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = altitudeMeters,
        accuracyMeters = accuracyMeters,
        speedMetersPerSecond = speedMetersPerSecond,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        wallClockMillis = wallClockMillis,
        source = source.name,
        accepted = accepted,
        rejectReason = rejectReason
    )
}

fun RunPointEntity.toModel(): RunPoint {
    return RunPoint(
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = altitudeMeters,
        accuracyMeters = accuracyMeters,
        speedMetersPerSecond = speedMetersPerSecond,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        wallClockMillis = wallClockMillis,
        source = LocationSource.valueOf(source),
        accepted = accepted,
        rejectReason = rejectReason
    )
}

