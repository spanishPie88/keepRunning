package com.yiaha.running.core.tracker

import java.util.Locale
import kotlin.math.floor

object RunMetricFormatter {
    fun distance(distanceMeters: Double): String {
        return if (distanceMeters < 1_000) {
            "${distanceMeters.toInt()} m"
        } else {
            String.format(Locale.US, "%.2f km", distanceMeters / 1_000.0)
        }
    }

    fun duration(elapsedMillis: Long): String {
        val totalSeconds = (elapsedMillis / 1_000).coerceAtLeast(0L)
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun pace(secondsPerKm: Double?): String {
        if (secondsPerKm == null || secondsPerKm.isNaN() || secondsPerKm.isInfinite()) {
            return "--'--\""
        }
        val minutes = floor(secondsPerKm / 60).toInt()
        val seconds = (secondsPerKm % 60).toInt()
        return String.format(Locale.US, "%d'%02d\"/km", minutes, seconds)
    }
}

