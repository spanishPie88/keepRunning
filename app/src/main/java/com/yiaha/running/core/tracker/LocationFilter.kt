package com.yiaha.running.core.tracker

import com.yiaha.running.core.model.RunPoint

class LocationFilter(
    private val maxAccuracyMeters: Float = 50f,
    private val maxHumanRunningSpeedMetersPerSecond: Double = 10.0,
    private val minimumMovementMeters: Double = 3.0,
    private val accuracyMovementFactor: Double = 0.35
) {
    fun evaluate(previousAccepted: RunPoint?, candidate: RunPoint): RunPoint {
        if (!candidate.accuracyMeters.isFinite() ||
            candidate.accuracyMeters <= 0f ||
            candidate.accuracyMeters > maxAccuracyMeters
        ) {
            return candidate.copy(accepted = false, rejectReason = "accuracy_too_low")
        }

        if (previousAccepted == null) {
            return candidate.copy(accepted = true, rejectReason = null)
        }

        val deltaNanos = candidate.elapsedRealtimeNanos - previousAccepted.elapsedRealtimeNanos
        if (deltaNanos <= 0L) {
            return candidate.copy(accepted = false, rejectReason = "invalid_timestamp")
        }

        val seconds = deltaNanos / 1_000_000_000.0
        val meters = DistanceCalculator.distanceMeters(previousAccepted, candidate)
        val speed = meters / seconds

        if (speed > maxHumanRunningSpeedMetersPerSecond) {
            return candidate.copy(accepted = false, rejectReason = "unrealistic_speed")
        }

        val accuracyAwareMovementThreshold = maxOf(
            minimumMovementMeters,
            minOf(previousAccepted.accuracyMeters, candidate.accuracyMeters) * accuracyMovementFactor
        )
        if (meters < accuracyAwareMovementThreshold) {
            return candidate.copy(accepted = false, rejectReason = "stationary_drift")
        }

        return candidate.copy(accepted = true, rejectReason = null)
    }
}
