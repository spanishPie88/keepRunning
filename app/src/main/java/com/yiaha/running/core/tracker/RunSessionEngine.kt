package com.yiaha.running.core.tracker

import android.util.Log
import com.yiaha.running.core.model.RunPoint
import com.yiaha.running.core.model.RunSessionSnapshot
import com.yiaha.running.core.model.RunState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class RunSessionEngine(
    private val locationFilter: LocationFilter = LocationFilter()
) {
    private val tag = "RunSessionEngine"

    private val mutableSnapshot = MutableStateFlow(RunSessionSnapshot())
    val snapshot: StateFlow<RunSessionSnapshot> = mutableSnapshot.asStateFlow()

    private var lastAcceptedPoint: RunPoint? = null
    private var pausedAtMillis: Long? = null
    private var accumulatedPausedMillis: Long = 0L

    fun start(nowMillis: Long) {
        Log.i(tag, "run started")
        lastAcceptedPoint = null
        pausedAtMillis = null
        accumulatedPausedMillis = 0L
        mutableSnapshot.value = RunSessionSnapshot(
            state = RunState.Running,
            startedAtMillis = nowMillis
        )
    }

    fun restore(snapshot: RunSessionSnapshot, lastAccepted: RunPoint?, nowMillis: Long) {
        Log.i(tag, "run restored: ${snapshot.id}")
        lastAcceptedPoint = lastAccepted
        pausedAtMillis = nowMillis
        accumulatedPausedMillis = 0L
        mutableSnapshot.value = snapshot.copy(
            state = RunState.Paused,
            startedAtMillis = nowMillis - snapshot.elapsedMillis,
            endedAtMillis = null
        )
    }

    fun pause(nowMillis: Long) {
        if (mutableSnapshot.value.state != RunState.Running) return
        Log.i(tag, "run paused")
        pausedAtMillis = nowMillis
        mutableSnapshot.update { it.copy(state = RunState.Paused) }
    }

    fun resume(nowMillis: Long) {
        if (mutableSnapshot.value.state != RunState.Paused) return
        Log.i(tag, "run resumed")
        pausedAtMillis?.let { accumulatedPausedMillis += nowMillis - it }
        pausedAtMillis = null
        // 暂停期间用户可能移动，恢复后的首个定位点只作为新基线，不累计暂停位移。
        lastAcceptedPoint = null
        mutableSnapshot.update { it.copy(state = RunState.Running) }
    }

    fun finish(nowMillis: Long) {
        Log.i(tag, "run finished")
        mutableSnapshot.update {
            it.copy(
                state = RunState.Finished,
                endedAtMillis = nowMillis,
                elapsedMillis = calculateElapsedMillis(nowMillis, it.startedAtMillis)
            )
        }
    }

    fun onLocation(point: RunPoint): RunPoint? {
        val current = mutableSnapshot.value
        if (current.state != RunState.Running) return null

        val evaluated = locationFilter.evaluate(lastAcceptedPoint, point)
        if (!evaluated.accepted) {
            Log.d(tag, "point rejected: ${evaluated.rejectReason}")
            mutableSnapshot.update {
                it.copy(
                    latestPoint = evaluated,
                    rejectedPointCount = it.rejectedPointCount + 1
                )
            }
            return evaluated
        }

        val distanceDelta = lastAcceptedPoint?.let {
            DistanceCalculator.distanceMeters(it, evaluated)
        } ?: 0.0
        lastAcceptedPoint = evaluated

        mutableSnapshot.update {
            val elapsed = calculateElapsedMillis(
                nowMillis = evaluated.wallClockMillis,
                startedAtMillis = it.startedAtMillis
            )
            val distance = it.distanceMeters + distanceDelta
            Log.d(
                tag,
                "point accepted, delta=${distanceDelta.toInt()}m, " +
                    "distance=${distance.toInt()}m, elapsed=${elapsed}ms"
            )
            it.copy(
                latestPoint = evaluated,
                elapsedMillis = elapsed,
                distanceMeters = distance,
                averagePaceSecondsPerKm = PaceCalculator.averagePaceSecondsPerKm(elapsed, distance),
                acceptedPointCount = it.acceptedPointCount + 1
            )
        }
        return evaluated
    }

    fun tick(nowMillis: Long) {
        val current = mutableSnapshot.value
        if (current.state != RunState.Running) return
        val elapsed = calculateElapsedMillis(nowMillis, current.startedAtMillis)
        mutableSnapshot.update {
            it.copy(
                elapsedMillis = elapsed,
                averagePaceSecondsPerKm = PaceCalculator.averagePaceSecondsPerKm(
                    elapsed,
                    it.distanceMeters
                )
            )
        }
    }

    private fun calculateElapsedMillis(nowMillis: Long, startedAtMillis: Long?): Long {
        if (startedAtMillis == null) return 0L
        return (nowMillis - startedAtMillis - accumulatedPausedMillis).coerceAtLeast(0L)
    }
}
