package com.yiaha.running.core.tracker

object PaceCalculator {
    fun averagePaceSecondsPerKm(elapsedMillis: Long, distanceMeters: Double): Double? {
        if (elapsedMillis <= 0L || distanceMeters < 1.0) return null
        val elapsedSeconds = elapsedMillis / 1000.0
        return elapsedSeconds / (distanceMeters / 1000.0)
    }
}

