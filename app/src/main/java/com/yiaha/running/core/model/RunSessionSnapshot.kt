package com.yiaha.running.core.model

import java.util.UUID

data class RunSessionSnapshot(
    val id: String = UUID.randomUUID().toString(),
    val state: RunState = RunState.Idle,
    val startedAtMillis: Long? = null,
    val endedAtMillis: Long? = null,
    val elapsedMillis: Long = 0L,
    val distanceMeters: Double = 0.0,
    val averagePaceSecondsPerKm: Double? = null,
    val latestPoint: RunPoint? = null,
    val acceptedPointCount: Int = 0,
    val rejectedPointCount: Int = 0
)

enum class RunState {
    Idle,
    Preparing,
    Running,
    Paused,
    Finished,
    Recovering
}

