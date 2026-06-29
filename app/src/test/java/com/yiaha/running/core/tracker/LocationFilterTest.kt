package com.yiaha.running.core.tracker

import com.yiaha.running.core.model.RunPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFilterTest {
    private val filter = LocationFilter()

    @Test
    fun rejectsLowAccuracyPoint() {
        val result = filter.evaluate(null, point(31.0, 121.0, accuracy = 80f))
        assertFalse(result.accepted)
        assertEquals("accuracy_too_low", result.rejectReason)
    }

    @Test
    fun rejectsSmallAccuracyNoiseAsStationaryDrift() {
        val previous = point(31.0, 121.0, elapsedSeconds = 0)
        val candidate = point(31.00001, 121.0, elapsedSeconds = 1)
        val result = filter.evaluate(previous, candidate)
        assertFalse(result.accepted)
        assertEquals("stationary_drift", result.rejectReason)
    }

    @Test
    fun acceptsPlausibleRunningMovement() {
        val previous = point(31.0, 121.0, elapsedSeconds = 0)
        val candidate = point(31.000045, 121.0, elapsedSeconds = 1)
        assertTrue(filter.evaluate(previous, candidate).accepted)
    }

    @Test
    fun rejectsUnrealisticSpeed() {
        val previous = point(31.0, 121.0, elapsedSeconds = 0)
        val candidate = point(31.001, 121.0, elapsedSeconds = 1)
        val result = filter.evaluate(previous, candidate)
        assertFalse(result.accepted)
        assertEquals("unrealistic_speed", result.rejectReason)
    }

    private fun point(
        latitude: Double,
        longitude: Double,
        elapsedSeconds: Long = 0,
        accuracy: Float = 8f
    ) = RunPoint(
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = null,
        accuracyMeters = accuracy,
        speedMetersPerSecond = null,
        elapsedRealtimeNanos = elapsedSeconds * 1_000_000_000L,
        wallClockMillis = elapsedSeconds * 1_000L
    )
}
