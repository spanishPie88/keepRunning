package com.yiaha.running.core.tracker

import com.yiaha.running.core.model.RunPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunSessionEngineTest {
    @Test
    fun resumeUsesFirstPointAsNewBaseline() {
        val engine = RunSessionEngine()
        engine.start(nowMillis = 1_000L)
        engine.onLocation(point(31.0, 121.0, elapsedSeconds = 1))
        engine.pause(nowMillis = 2_000L)
        engine.resume(nowMillis = 10_000L)

        engine.onLocation(point(31.001, 121.0, elapsedSeconds = 11))
        assertEquals(0.0, engine.snapshot.value.distanceMeters, 0.01)
        assertEquals(2, engine.snapshot.value.acceptedPointCount)

        engine.onLocation(point(31.001045, 121.0, elapsedSeconds = 12))
        assertTrue(engine.snapshot.value.distanceMeters in 4.0..6.5)
    }

    private fun point(latitude: Double, longitude: Double, elapsedSeconds: Long) = RunPoint(
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = null,
        accuracyMeters = 8f,
        speedMetersPerSecond = 5f,
        elapsedRealtimeNanos = elapsedSeconds * 1_000_000_000L,
        wallClockMillis = elapsedSeconds * 1_000L
    )
}
