package com.yiaha.running.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.yiaha.running.core.model.RunSessionSnapshot
import com.yiaha.running.core.model.RunState

@Entity(tableName = "run_sessions")
data class RunSessionEntity(
    @PrimaryKey
    val id: String,
    val state: String,
    val trackingMode: String,
    val startedAtMillis: Long?,
    val endedAtMillis: Long?,
    val elapsedMillis: Long,
    val distanceMeters: Double,
    val averagePaceSecondsPerKm: Double?,
    val acceptedPointCount: Int,
    val rejectedPointCount: Int
)

fun RunSessionSnapshot.toEntity(): RunSessionEntity {
    return toEntity(trackingMode = "Real")
}

fun RunSessionSnapshot.toEntity(trackingMode: String): RunSessionEntity {
    return RunSessionEntity(
        id = id,
        state = state.name,
        trackingMode = trackingMode,
        startedAtMillis = startedAtMillis,
        endedAtMillis = endedAtMillis,
        elapsedMillis = elapsedMillis,
        distanceMeters = distanceMeters,
        averagePaceSecondsPerKm = averagePaceSecondsPerKm,
        acceptedPointCount = acceptedPointCount,
        rejectedPointCount = rejectedPointCount
    )
}

fun RunSessionEntity.toSnapshot(): RunSessionSnapshot {
    return RunSessionSnapshot(
        id = id,
        state = RunState.valueOf(state),
        startedAtMillis = startedAtMillis,
        endedAtMillis = endedAtMillis,
        elapsedMillis = elapsedMillis,
        distanceMeters = distanceMeters,
        averagePaceSecondsPerKm = averagePaceSecondsPerKm,
        acceptedPointCount = acceptedPointCount,
        rejectedPointCount = rejectedPointCount
    )
}
