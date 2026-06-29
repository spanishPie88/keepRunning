package com.yiaha.running.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.yiaha.running.MainActivity
import com.yiaha.running.R
import com.yiaha.running.core.location.AndroidGpsLocationProvider
import com.yiaha.running.core.location.RunLocationProvider
import com.yiaha.running.core.model.LocationSource
import com.yiaha.running.core.model.RunPoint
import com.yiaha.running.core.model.RunSessionSnapshot
import com.yiaha.running.core.model.RunState
import com.yiaha.running.core.tracker.RunMetricFormatter
import com.yiaha.running.core.tracker.RunSessionEngine
import com.yiaha.running.data.local.RunningDatabase
import com.yiaha.running.data.local.toEntity
import com.yiaha.running.data.local.toModel
import com.yiaha.running.data.local.toSnapshot
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RunTrackingService : Service() {

    private val tag = "RunTrackingService"
    private val engine = RunSessionEngine()
    private val persistenceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RunPersistenceThread")
    }
    private lateinit var locationProvider: RunLocationProvider
    private lateinit var database: RunningDatabase
    private lateinit var voiceCoach: RunVoiceCoach
    private val mainHandler = Handler(Looper.getMainLooper())
    private var trackingMode: TrackingMode = TrackingMode.Real
    private var announcedKilometers = 0
    private var lastMilestoneElapsedMillis = 0L
    private val engineLock = Any()
    @Volatile private var tickerRunning: Boolean = false
    private var tickerThread: Thread? = null
    private var simulationThread: Thread? = null
    @Volatile private var simulationPointIndex: Int = 0

    override fun onCreate() {
        super.onCreate()
        locationProvider = AndroidGpsLocationProvider(applicationContext)
        database = RunningDatabase.getInstance(applicationContext)
        voiceCoach = RunVoiceCoach(applicationContext)
        ensureNotificationChannel()
        startTicker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.i(tag, "service restarted by system; recovering active run")
            startForeground(
                NOTIFICATION_ID,
                buildNotification(engine.snapshot.value, "正在恢复未结束跑步…")
            )
            recoverLatestActiveSession()
            return START_STICKY
        }

        return when (intent?.action) {
            ACTION_START -> {
                Log.i(tag, "ACTION_START")
                trackingMode = TrackingMode.Real
                synchronized(engineLock) {
                    engine.start(System.currentTimeMillis())
                    publishSnapshotLocked()
                }
                resetVoiceMilestones()
                voiceCoach.announceStart()
                finishOtherActiveSessions(engine.snapshot.value.id)
                persistSessionSnapshot()
                Log.i(tag, "calling startForeground for real run")
                startForeground(NOTIFICATION_ID, buildNotification(engine.snapshot.value))
                Log.i(tag, "startForeground completed for real run")
                startLocationUpdates()
                START_STICKY
            }

            ACTION_START_SIMULATED -> {
                Log.i(tag, "ACTION_START_SIMULATED")
                trackingMode = TrackingMode.Simulated
                simulationPointIndex = 0
                synchronized(engineLock) {
                    engine.start(System.currentTimeMillis())
                    publishSnapshotLocked()
                }
                resetVoiceMilestones()
                voiceCoach.announceStart()
                finishOtherActiveSessions(engine.snapshot.value.id)
                persistSessionSnapshot()
                Log.i(tag, "calling startForeground for simulated run")
                startForeground(NOTIFICATION_ID, buildNotification(engine.snapshot.value))
                Log.i(tag, "startForeground completed for simulated run")
                startSimulatedLocationUpdates()
                START_STICKY
            }

            ACTION_PAUSE -> {
                Log.i(tag, "ACTION_PAUSE")
                val wasRunning = engine.snapshot.value.state == RunState.Running
                synchronized(engineLock) {
                    engine.pause(System.currentTimeMillis())
                    publishSnapshotLocked()
                }
                if (wasRunning) voiceCoach.announcePause()
                persistSessionSnapshot()
                locationProvider.stop()
                stopSimulation()
                updateNotification(engine.snapshot.value)
                START_STICKY
            }

            ACTION_RESUME -> {
                Log.i(tag, "ACTION_RESUME")
                val wasPaused = engine.snapshot.value.state == RunState.Paused
                synchronized(engineLock) {
                    engine.resume(System.currentTimeMillis())
                    publishSnapshotLocked()
                }
                if (wasPaused) voiceCoach.announceResume()
                persistSessionSnapshot()
                updateNotification(engine.snapshot.value)
                when (trackingMode) {
                    TrackingMode.Simulated -> startSimulatedLocationUpdates()
                    TrackingMode.Real -> startLocationUpdates()
                }
                START_STICKY
            }

            ACTION_RECOVER_RESUME -> {
                Log.i(tag, "ACTION_RECOVER_RESUME")
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                startForeground(NOTIFICATION_ID, buildNotification(engine.snapshot.value))
                recoverSession(
                    sessionId = sessionId,
                    resumeAfterRecovery = true,
                    finishAfterRecovery = false
                )
                START_STICKY
            }

            ACTION_RECOVER_FINISH -> {
                Log.i(tag, "ACTION_RECOVER_FINISH")
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                startForeground(NOTIFICATION_ID, buildNotification(engine.snapshot.value))
                recoverSession(
                    sessionId = sessionId,
                    resumeAfterRecovery = false,
                    finishAfterRecovery = true
                )
                START_NOT_STICKY
            }

            ACTION_STOP -> {
                Log.i(tag, "ACTION_STOP")
                locationProvider.stop()
                stopSimulation()
                synchronized(engineLock) {
                    engine.finish(System.currentTimeMillis())
                    publishSnapshotLocked()
                }
                voiceCoach.announceFinish(engine.snapshot.value.distanceMeters)
                persistSessionSnapshot()
                stopForeground(STOP_FOREGROUND_REMOVE)
                mainHandler.postDelayed({ stopSelf() }, FINISH_ANNOUNCEMENT_GRACE_MILLIS)
                START_NOT_STICKY
            }

            else -> START_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        locationProvider.stop()
        stopSimulation()
        stopTicker()
        persistenceExecutor.shutdown()
        runCatching {
            persistenceExecutor.awaitTermination(PERSISTENCE_SHUTDOWN_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        }
        voiceCoach.shutdown()
        super.onDestroy()
    }

    private fun startLocationUpdates() {
        stopSimulation()
        locationProvider.start(
            onPoint = { point ->
                handlePoint(point)
            },
            onError = {
                Log.e(tag, "Location provider error", it)
                updateNotification(
                    engine.snapshot.value,
                    overrideText = "定位异常：请检查 GPS 和定位权限"
                )
            }
        )
    }

    private fun startSimulatedLocationUpdates() {
        locationProvider.stop()
        stopSimulation()
        Log.i(tag, "simulated updates starting")
        simulationThread = Thread({
            Log.i(tag, "simulation thread entered")
            while (!Thread.currentThread().isInterrupted && simulationPointIndex < 60) {
                val index = simulationPointIndex
                Log.d(tag, "simulated point index=$index")
                val point = RunPoint(
                    latitude = 31.2304 + index * 0.000045,
                    longitude = 121.4737,
                    altitudeMeters = null,
                    accuracyMeters = 8f,
                    speedMetersPerSecond = 5f,
                    elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos(),
                    wallClockMillis = System.currentTimeMillis(),
                    source = LocationSource.SensorEstimated
                )
                handlePoint(point)
                simulationPointIndex = index + 1
                try {
                    Thread.sleep(1_000L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "RunSimulationThread").also {
            it.start()
            Log.i(tag, "simulation thread started")
        }
    }

    private fun stopSimulation() {
        simulationThread?.interrupt()
        simulationThread = null
    }

    private fun handlePoint(point: RunPoint) {
        val result = synchronized(engineLock) {
            val result = engine.onLocation(point)
            publishSnapshotLocked()
            result?.let { Triple(it, engine.snapshot.value, trackingMode.name) }
        }
        result?.let { (evaluated, snapshot, mode) ->
            if (evaluated.accepted) announceDistanceMilestones(snapshot)
            persistenceExecutor.execute {
                database.runPointDao().insertBlocking(evaluated.toEntity(snapshot.id))
                database.runSessionDao().upsertBlocking(snapshot.toEntity(mode))
                Log.d(tag, "point persisted, session=${snapshot.id}, accepted=${evaluated.accepted}")
            }
        }
    }

    private fun persistSessionSnapshot() {
        val snapshot = engine.snapshot.value
        val mode = trackingMode.name
        persistenceExecutor.execute {
            database.runSessionDao().upsertBlocking(snapshot.toEntity(mode))
        }
    }

    private fun recoverLatestActiveSession() {
        persistenceExecutor.execute {
            val activeSession = database.runSessionDao().getActiveSessionBlocking()
            mainHandler.post {
                if (activeSession == null) {
                    Log.i(tag, "no active run found after system restart")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    recoverSession(
                        sessionId = activeSession.id,
                        resumeAfterRecovery = activeSession.state == RunState.Running.name,
                        finishAfterRecovery = false
                    )
                }
            }
        }
    }

    private fun recoverSession(
        sessionId: String?,
        resumeAfterRecovery: Boolean,
        finishAfterRecovery: Boolean
    ) {
        if (sessionId.isNullOrBlank()) return

        Thread({
            val sessionEntity = database.runSessionDao().getByIdBlocking(sessionId) ?: return@Thread
            val points = database.runPointDao().getPointsBlocking(sessionId)
            val lastAccepted = points.lastOrNull { it.accepted }?.toModel()
            trackingMode = TrackingMode.valueOf(sessionEntity.trackingMode)
            if (trackingMode == TrackingMode.Simulated) {
                simulationPointIndex = points.count { it.accepted }.coerceAtMost(60)
            }

            synchronized(engineLock) {
                engine.restore(
                    snapshot = sessionEntity.toSnapshot(),
                    lastAccepted = lastAccepted,
                    nowMillis = System.currentTimeMillis()
                )

                if (finishAfterRecovery) {
                    engine.finish(System.currentTimeMillis())
                } else if (resumeAfterRecovery) {
                    engine.resume(System.currentTimeMillis())
                }

                publishSnapshotLocked()
            }

            announcedKilometers = (engine.snapshot.value.distanceMeters / 1_000.0).toInt()
            lastMilestoneElapsedMillis = engine.snapshot.value.elapsedMillis

            persistSessionSnapshot()

            if (finishAfterRecovery) {
                database.runSessionDao().finishAllActiveBlocking(System.currentTimeMillis())
                locationProvider.stop()
                stopSimulation()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else if (resumeAfterRecovery) {
                updateNotification(engine.snapshot.value)
                when (trackingMode) {
                    TrackingMode.Simulated -> startSimulatedLocationUpdates()
                    TrackingMode.Real -> startLocationUpdates()
                }
            }
        }, "RunRecoveryThread").start()
    }

    private fun finishOtherActiveSessions(currentSessionId: String) {
        persistenceExecutor.execute {
            database.runSessionDao().finishOtherActiveBlocking(
                currentSessionId = currentSessionId,
                endedAtMillis = System.currentTimeMillis()
            )
        }
    }

    private fun resetVoiceMilestones() {
        announcedKilometers = 0
        lastMilestoneElapsedMillis = 0L
    }

    private fun announceDistanceMilestones(snapshot: RunSessionSnapshot) {
        val completedKilometers = (snapshot.distanceMeters / 1_000.0).toInt()
        while (announcedKilometers < completedKilometers) {
            announcedKilometers += 1
            val splitElapsed = (snapshot.elapsedMillis - lastMilestoneElapsedMillis).coerceAtLeast(0L)
            voiceCoach.announceKilometer(announcedKilometers, splitElapsed)
            lastMilestoneElapsedMillis = snapshot.elapsedMillis
        }
    }

    private fun startTicker() {
        stopTicker()
        tickerRunning = true
        tickerThread = Thread({
            Log.i(tag, "ticker thread entered")
            while (tickerRunning) {
                synchronized(engineLock) {
                    engine.tick(System.currentTimeMillis())
                    publishSnapshotLocked()
                }
                try {
                    Thread.sleep(1_000L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "RunTickerThread").also {
            it.start()
            Log.i(tag, "ticker thread started")
        }
    }

    private fun stopTicker() {
        tickerRunning = false
        tickerThread?.interrupt()
        tickerThread = null
    }

    private fun publishSnapshotLocked() {
        val snapshot = engine.snapshot.value
        RunTrackingStateStore.update(snapshot)
        if (snapshot.state == RunState.Running || snapshot.state == RunState.Paused) {
            updateNotification(snapshot)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "跑步记录",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "跑步过程中持续展示记录状态"
        }

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun updateNotification(snapshot: RunSessionSnapshot, overrideText: String? = null) {
        Log.d(tag, "notification: ${overrideText ?: buildNotificationText(snapshot)}")
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(snapshot, overrideText))
    }

    private fun buildNotification(
        snapshot: RunSessionSnapshot,
        overrideText: String? = null
    ): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseOrResumeAction = if (snapshot.state == RunState.Paused) {
            notificationAction(ACTION_RESUME, "继续")
        } else {
            notificationAction(ACTION_PAUSE, "暂停")
        }

        val contentText = overrideText ?: buildNotificationText(snapshot)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(buildNotificationTitle(snapshot))
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .addAction(pauseOrResumeAction)
            .addAction(notificationAction(ACTION_STOP, "结束"))
            .build()
    }

    private fun notificationAction(action: String, title: String): NotificationCompat.Action {
        val requestCode = action.hashCode()
        val pendingIntent = PendingIntent.getService(
            this,
            requestCode,
            Intent(this, RunTrackingService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_launcher_foreground,
            title,
            pendingIntent
        ).build()
    }

    private fun buildNotificationTitle(snapshot: RunSessionSnapshot): String {
        return when (snapshot.state) {
            RunState.Paused -> "跑步已暂停"
            RunState.Running -> "正在记录跑步"
            else -> "Running"
        }
    }

    private fun buildNotificationText(snapshot: RunSessionSnapshot): String {
        return listOf(
            RunMetricFormatter.distance(snapshot.distanceMeters),
            RunMetricFormatter.duration(snapshot.elapsedMillis),
            RunMetricFormatter.pace(snapshot.averagePaceSecondsPerKm)
        ).joinToString(" · ")
    }

    companion object {
        const val ACTION_START = "com.yiaha.running.action.START"
        const val ACTION_START_SIMULATED = "com.yiaha.running.action.START_SIMULATED"
        const val ACTION_PAUSE = "com.yiaha.running.action.PAUSE"
        const val ACTION_RESUME = "com.yiaha.running.action.RESUME"
        const val ACTION_STOP = "com.yiaha.running.action.STOP"
        const val ACTION_RECOVER_RESUME = "com.yiaha.running.action.RECOVER_RESUME"
        const val ACTION_RECOVER_FINISH = "com.yiaha.running.action.RECOVER_FINISH"
        const val EXTRA_SESSION_ID = "session_id"

        private const val CHANNEL_ID = "run_tracking"
        private const val NOTIFICATION_ID = 1001
        private const val FINISH_ANNOUNCEMENT_GRACE_MILLIS = 2_500L
        private const val PERSISTENCE_SHUTDOWN_WAIT_MILLIS = 1_500L
    }

    private enum class TrackingMode {
        Real,
        Simulated
    }
}
