package com.yiaha.running

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.yiaha.running.core.model.RunSessionSnapshot
import com.yiaha.running.core.model.RunState
import com.yiaha.running.core.tracker.DistanceCalculator
import com.yiaha.running.core.tracker.RunMetricFormatter
import com.yiaha.running.data.RunHistoryRepository
import com.yiaha.running.data.local.RunPointEntity
import com.yiaha.running.data.local.RunSessionEntity
import com.yiaha.running.data.local.toModel
import com.yiaha.running.service.RunTrackingService
import com.yiaha.running.service.RunTrackingStateStore
import com.yiaha.running.ui.map.RouteMapPreview
import com.yiaha.running.ui.map.LiveRouteMapPreview
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshReadiness()
            startRunTrackingService()
        }

    private val backgroundLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshReadiness()
        }

    private var readinessState = mutableStateOf(AppReadiness())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshReadiness()

        setContent {
            val historyRepository = remember { RunHistoryRepository(applicationContext) }
            var selectedRun by remember { mutableStateOf<RunSessionEntity?>(null) }
            var showHistory by remember { mutableStateOf(false) }
            val snapshot by RunTrackingStateStore.snapshot.collectAsState()
            val recentRuns by historyRepository
                .observeRecentRuns()
                .collectAsState(initial = emptyList())
            val activeRun by historyRepository
                .observeActiveRun()
                .collectAsState(initial = null)
            val selectedPoints by remember(selectedRun?.id) {
                selectedRun?.let { historyRepository.observeRunPoints(it.id) }
                    ?: flowOf(emptyList())
            }.collectAsState(initial = emptyList())
            val liveRunPoints by remember(snapshot.id, snapshot.state) {
                if (snapshot.state in listOf(RunState.Running, RunState.Paused)) {
                    historyRepository.observeRunPoints(snapshot.id)
                } else {
                    flowOf(emptyList())
                }
            }.collectAsState(initial = emptyList())
            RunningApp(
                snapshot = snapshot,
                readiness = readinessState.value,
                recentRuns = recentRuns,
                activeRun = activeRun,
                selectedRun = selectedRun,
                showHistory = showHistory,
                selectedRunPoints = selectedPoints,
                liveRunPoints = liveRunPoints,
                onStartRun = {
                    if (isSystemLocationEnabled()) {
                        requestRuntimePermissions()
                    } else {
                        openLocationSettings()
                    }
                },
                onStartSimulatedRun = {
                    requestRuntimePermissions(startSimulated = true)
                },
                onPause = {
                    sendTrackingAction(RunTrackingService.ACTION_PAUSE)
                },
                onResume = {
                    sendTrackingAction(RunTrackingService.ACTION_RESUME)
                },
                onStop = {
                    sendTrackingAction(RunTrackingService.ACTION_STOP)
                },
                onRequestBackgroundLocation = {
                    requestBackgroundLocationPermission()
                },
                onOpenBatterySettings = {
                    openBatteryOptimizationSettings()
                },
                onOpenAppSettings = {
                    openAppSettings()
                },
                onOpenLocationSettings = {
                    openLocationSettings()
                },
                onRecoverResume = { sessionId ->
                    sendTrackingAction(
                        action = RunTrackingService.ACTION_RECOVER_RESUME,
                        sessionId = sessionId
                    )
                },
                onRecoverFinish = { sessionId ->
                    sendTrackingAction(
                        action = RunTrackingService.ACTION_RECOVER_FINISH,
                        sessionId = sessionId
                    )
                },
                onSelectRun = { run ->
                    selectedRun = run
                },
                onOpenHistory = {
                    showHistory = true
                },
                onBackFromHistory = {
                    showHistory = false
                },
                onBackFromDetail = {
                    selectedRun = null
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshReadiness()
    }

    private fun requestRuntimePermissions(startSimulated: Boolean = false) {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        pendingStartSimulated = startSimulated
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private var pendingStartSimulated: Boolean = false

    private fun startRunTrackingService() {
        val hasLocationPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasLocationPermission) return

        val action = if (pendingStartSimulated) {
            RunTrackingService.ACTION_START_SIMULATED
        } else {
            RunTrackingService.ACTION_START
        }
        pendingStartSimulated = false
        val intent = Intent(this, RunTrackingService::class.java)
            .setAction(action)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!hasFineLocationPermission()) {
            requestRuntimePermissions()
            return
        }
        backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun openBatteryOptimizationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }
        runCatching { startActivity(intent) }
            .onFailure {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
        startActivity(intent)
    }

    private fun openLocationSettings() {
        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    private fun refreshReadiness() {
        readinessState.value = AppReadiness(
            fineLocationGranted = hasFineLocationPermission(),
            systemLocationEnabled = isSystemLocationEnabled(),
            backgroundLocationGranted = hasBackgroundLocationPermission(),
            ignoringBatteryOptimizations = isIgnoringBatteryOptimizations()
        )
    }

    private fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isSystemLocationEnabled(): Boolean {
        val locationManager = getSystemService(LocationManager::class.java) ?: return false
        val gpsEnabled = runCatching {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
        val masterEnabled = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            Settings.Secure.getInt(
                contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF
            ) != Settings.Secure.LOCATION_MODE_OFF
        }
        return masterEnabled && gpsEnabled
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = getSystemService(PowerManager::class.java)
        return powerManager?.isIgnoringBatteryOptimizations(packageName) == true
    }

    private fun sendTrackingAction(action: String, sessionId: String? = null) {
        val intent = Intent(this, RunTrackingService::class.java)
            .setAction(action)
        if (sessionId != null) {
            intent.putExtra(RunTrackingService.EXTRA_SESSION_ID, sessionId)
        }
        ContextCompat.startForegroundService(this, intent)
    }
}

private data class AppReadiness(
    val fineLocationGranted: Boolean = false,
    val systemLocationEnabled: Boolean = false,
    val backgroundLocationGranted: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q,
    val ignoringBatteryOptimizations: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
) {
    val hasIssues: Boolean
        get() = !fineLocationGranted || !systemLocationEnabled ||
            !backgroundLocationGranted || !ignoringBatteryOptimizations
}

private val RunningLightColors = lightColorScheme(
    primary = Color(0xFF246BFD),
    onPrimary = Color.White,
    background = Color(0xFFF8F7FB),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFF8F7FB),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFF0EEF4),
    onSurfaceVariant = Color(0xFF49454F),
    error = Color(0xFFBA1A1A)
)

@Composable
private fun RunningApp(
    snapshot: RunSessionSnapshot,
    readiness: AppReadiness,
    recentRuns: List<RunSessionEntity>,
    activeRun: RunSessionEntity?,
    selectedRun: RunSessionEntity?,
    showHistory: Boolean,
    selectedRunPoints: List<RunPointEntity>,
    liveRunPoints: List<RunPointEntity>,
    onStartRun: () -> Unit,
    onStartSimulatedRun: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onRecoverResume: (String) -> Unit,
    onRecoverFinish: (String) -> Unit,
    onSelectRun: (RunSessionEntity) -> Unit,
    onOpenHistory: () -> Unit,
    onBackFromHistory: () -> Unit,
    onBackFromDetail: () -> Unit
) {
    MaterialTheme(colorScheme = RunningLightColors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (selectedRun != null) {
                BackHandler(onBack = onBackFromDetail)
                RunDetailScreen(
                    run = selectedRun,
                    points = selectedRunPoints,
                    onBack = onBackFromDetail
                )
                return@Surface
            }

            if (showHistory) {
                BackHandler(onBack = onBackFromHistory)
                HistoryScreen(
                    recentRuns = recentRuns,
                    onSelectRun = onSelectRun,
                    onBack = onBackFromHistory
                )
                return@Surface
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Running",
                            style = MaterialTheme.typography.headlineLarge
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        MetricBlock(snapshot = snapshot)

                        if (snapshot.state in listOf(RunState.Running, RunState.Paused)) {
                            Spacer(modifier = Modifier.height(20.dp))
                            RunDiagnosticsCard(
                                snapshot = snapshot,
                                systemLocationEnabled = readiness.systemLocationEnabled,
                                onOpenLocationSettings = onOpenLocationSettings
                            )

                            val acceptedLivePoints = liveRunPoints.filter { it.accepted }
                            if (acceptedLivePoints.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(20.dp))
                                LiveRouteMapPreview(points = acceptedLivePoints)
                            }
                        }

                        if (readiness.hasIssues && snapshot.state !in listOf(RunState.Running, RunState.Paused)) {
                            Spacer(modifier = Modifier.height(20.dp))
                            ReadinessCard(
                                readiness = readiness,
                                onRequestBackgroundLocation = onRequestBackgroundLocation,
                                onOpenBatterySettings = onOpenBatterySettings,
                                onOpenAppSettings = onOpenAppSettings,
                                onOpenLocationSettings = onOpenLocationSettings
                            )
                        }

                        if (activeRun != null && snapshot.state !in listOf(RunState.Running, RunState.Paused)) {
                            Spacer(modifier = Modifier.height(20.dp))
                            RecoveryCard(
                                activeRun = activeRun,
                                onRecoverResume = onRecoverResume,
                                onRecoverFinish = onRecoverFinish
                            )
                        }

                        Spacer(modifier = Modifier.height(28.dp))

                        when (snapshot.state) {
                            RunState.Running -> {
                                Text("跑步中", style = MaterialTheme.typography.bodyMedium)
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(horizontalArrangement = Arrangement.Center) {
                                    OutlinedButton(onClick = onPause) {
                                        Text("暂停")
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Button(
                                        onClick = onStop,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error
                                        )
                                    ) {
                                        Text("结束")
                                    }
                                }
                            }

                            RunState.Paused -> {
                                Text("已暂停", style = MaterialTheme.typography.bodyMedium)
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(horizontalArrangement = Arrangement.Center) {
                                    Button(onClick = onResume) {
                                        Text("继续")
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    OutlinedButton(onClick = onStop) {
                                        Text("结束")
                                    }
                                }
                            }

                            RunState.Finished -> {
                                Text("本次跑步已结束", style = MaterialTheme.typography.bodyMedium)
                                Spacer(modifier = Modifier.height(16.dp))
                                StartButtons(onStartRun, onStartSimulatedRun)
                            }

                            else -> {
                                Text(
                                    text = "先把一次可靠的户外跑记录下来。",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.height(20.dp))
                                StartButtons(onStartRun, onStartSimulatedRun)
                            }
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onOpenHistory
                        ) {
                            val countText = if (recentRuns.isEmpty()) "" else "（${recentRuns.size}）"
                            Text("历史记录$countText")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunDiagnosticsCard(
    snapshot: RunSessionSnapshot,
    systemLocationEnabled: Boolean,
    onOpenLocationSettings: () -> Unit
) {
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snapshot.state) {
        while (snapshot.state in listOf(RunState.Running, RunState.Paused)) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("跑步诊断", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            val latestPoint = snapshot.latestPoint
            if (latestPoint == null) {
                val waitingSeconds = snapshot.startedAtMillis
                    ?.let { ((nowMillis - it) / 1_000L).coerceAtLeast(0L) }
                    ?: 0L
                if (!systemLocationEnabled) {
                    Text(
                        "系统定位未开启，无法接收 GPS 点位。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onOpenLocationSettings
                    ) {
                        Text("打开系统定位")
                    }
                } else if (waitingSeconds >= 10L) {
                    Text(
                        "已搜索 ${waitingSeconds} 秒，仍未获得 GPS 定位。请到室外开阔区域并保持设备朝上。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onOpenLocationSettings
                    ) {
                        Text("检查定位设置")
                    }
                } else {
                    Text("正在搜索 GPS 定位…", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                DiagnosticRow("最近定位", formatPointAge(latestPoint.wallClockMillis, nowMillis))
                DiagnosticRow("来源", latestPoint.source.name)
                DiagnosticRow("精度", "${latestPoint.accuracyMeters.toInt()}m")
                DiagnosticRow(
                    label = "点位",
                    value = "有效 ${snapshot.acceptedPointCount} · 过滤 ${snapshot.rejectedPointCount}"
                )
                if (!latestPoint.accepted) {
                    DiagnosticRow("过滤原因", rejectReasonText(latestPoint.rejectReason))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "锁屏实跑时如果“最近定位”持续变大，通常是系统限制或定位 provider 中断。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatPointAge(pointWallClockMillis: Long, nowMillis: Long): String {
    val ageMillis = (nowMillis - pointWallClockMillis).coerceAtLeast(0L)
    return when {
        ageMillis < 2_000L -> "刚刚"
        ageMillis < 60_000L -> "${ageMillis / 1_000L} 秒前"
        else -> "${ageMillis / 60_000L} 分钟前"
    }
}

private fun rejectReasonText(reason: String?): String {
    return when (reason) {
        "accuracy_too_low" -> "精度过低"
        "invalid_timestamp" -> "时间戳异常"
        "unrealistic_speed" -> "速度异常"
        "stationary_drift" -> "静止漂移"
        null -> "--"
        else -> reason
    }
}

@Composable
private fun ReadinessCard(
    readiness: AppReadiness,
    onRequestBackgroundLocation: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("真跑前检查", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "为了锁屏后不丢公里，建议把定位和电池限制先放行。",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(12.dp))
            ReadinessRow("定位权限", readiness.fineLocationGranted)
            ReadinessRow("系统定位", readiness.systemLocationEnabled)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ReadinessRow("后台定位", readiness.backgroundLocationGranted)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ReadinessRow("电池优化放行", readiness.ignoringBatteryOptimizations)
            }

            Spacer(modifier = Modifier.height(12.dp))
            Column {
                if (!readiness.systemLocationEnabled) {
                    OutlinedButton(
                        onClick = onOpenLocationSettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("打开系统定位")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (!readiness.backgroundLocationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    OutlinedButton(
                        onClick = onRequestBackgroundLocation,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("开启后台定位")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (!readiness.ignoringBatteryOptimizations && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    OutlinedButton(
                        onClick = onOpenBatterySettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("允许后台耗电")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                OutlinedButton(
                    onClick = onOpenAppSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("应用设置")
                }
            }
        }
    }
}

@Composable
private fun ReadinessRow(label: String, ready: Boolean) {
    Text(
        text = "${if (ready) "✓" else "!"} $label：${if (ready) "已就绪" else "待开启"}",
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun RecoveryCard(
    activeRun: RunSessionEntity,
    onRecoverResume: (String) -> Unit,
    onRecoverFinish: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("发现未结束跑步", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "${RunMetricFormatter.distance(activeRun.distanceMeters)} · " +
                "${RunMetricFormatter.duration(activeRun.elapsedMillis)} · " +
                RunMetricFormatter.pace(activeRun.averagePaceSecondsPerKm),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            Button(onClick = { onRecoverResume(activeRun.id) }) {
                Text("继续")
            }
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(onClick = { onRecoverFinish(activeRun.id) }) {
                Text("结束保存")
            }
        }
    }
}

@Composable
private fun HistoryScreen(
    recentRuns: List<RunSessionEntity>,
    onSelectRun: (RunSessionEntity) -> Unit,
    onBack: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        item {
            OutlinedButton(onClick = onBack) {
                Text("返回")
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text("历史记录", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (recentRuns.isEmpty()) "还没有跑步记录" else "共 ${recentRuns.size} 次跑步",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider()
            if (recentRuns.isEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Text("完成一次跑步后，记录会出现在这里。", style = MaterialTheme.typography.bodyMedium)
            }
        }

        items(recentRuns, key = { it.id }) { run ->
            HistoryRow(run = run, onClick = { onSelectRun(run) })
        }
    }
}

@Composable
private fun HistoryRow(run: RunSessionEntity, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(RunMetricFormatter.distance(run.distanceMeters), style = MaterialTheme.typography.titleMedium)
            Text(RunMetricFormatter.duration(run.elapsedMillis), style = MaterialTheme.typography.bodyMedium)
            Text(RunMetricFormatter.pace(run.averagePaceSecondsPerKm), style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "点位 ${run.acceptedPointCount}/${run.acceptedPointCount + run.rejectedPointCount} · 点击查看详情",
            style = MaterialTheme.typography.bodySmall
        )
        HorizontalDivider(modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun RunDetailScreen(
    run: RunSessionEntity,
    points: List<RunPointEntity>,
    onBack: () -> Unit
) {
    val acceptedPoints = points.filter { it.accepted }
    val rejectedCount = points.size - acceptedPoints.size
    val splits = remember(points) { buildSplits(points) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        item {
            OutlinedButton(onClick = onBack) {
                Text("返回")
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text("跑步详情", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(formatRunTime(run), style = MaterialTheme.typography.bodySmall)

            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SmallMetric("距离", RunMetricFormatter.distance(run.distanceMeters))
                SmallMetric("时间", RunMetricFormatter.duration(run.elapsedMillis))
                SmallMetric("配速", RunMetricFormatter.pace(run.averagePaceSecondsPerKm))
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            Text("轨迹点", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "有效 ${acceptedPoints.size} · 过滤 $rejectedCount · 总计 ${points.size}",
                style = MaterialTheme.typography.bodyMedium
            )
            if (acceptedPoints.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "来源 ${acceptedPoints.last().source} · 精度 ${acceptedPoints.last().accuracyMeters.toInt()}m",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            RouteMapPreview(points = acceptedPoints)

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            Text("分段", style = MaterialTheme.typography.titleMedium)
            if (splits.isEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("暂无足够点位生成分段。", style = MaterialTheme.typography.bodySmall)
            }
        }

        items(splits, key = { it.index }) { split ->
            SplitRow(split)
        }
    }
}

@Composable
private fun SplitRow(split: RunSplit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("第 ${split.index} 段", style = MaterialTheme.typography.bodyMedium)
            Text(RunMetricFormatter.distance(split.distanceMeters), style = MaterialTheme.typography.bodyMedium)
            Text(RunMetricFormatter.duration(split.elapsedMillis), style = MaterialTheme.typography.bodyMedium)
            Text(RunMetricFormatter.pace(split.paceSecondsPerKm), style = MaterialTheme.typography.bodyMedium)
        }
        HorizontalDivider(modifier = Modifier.padding(top = 10.dp))
    }
}

private data class RunSplit(
    val index: Int,
    val distanceMeters: Double,
    val elapsedMillis: Long,
    val paceSecondsPerKm: Double?
)

private fun buildSplits(points: List<RunPointEntity>): List<RunSplit> {
    val accepted = points
        .filter { it.accepted }
        .map { it.toModel() }
        .sortedBy { it.wallClockMillis }
    if (accepted.size < 2) return emptyList()

    val splits = mutableListOf<RunSplit>()
    var splitIndex = 1
    var splitDistance = 0.0
    var splitElapsed = 0L
    var previous = accepted.first()

    accepted.drop(1).forEach { point ->
        val distance = DistanceCalculator.distanceMeters(previous, point)
        val elapsed = (point.wallClockMillis - previous.wallClockMillis).coerceAtLeast(0L)
        splitDistance += distance
        splitElapsed += elapsed

        if (splitDistance >= 1_000.0) {
            splits += RunSplit(
                index = splitIndex,
                distanceMeters = splitDistance,
                elapsedMillis = splitElapsed,
                paceSecondsPerKm = averagePaceSecondsPerKm(splitElapsed, splitDistance)
            )
            splitIndex += 1
            splitDistance = 0.0
            splitElapsed = 0L
        }

        previous = point
    }

    if (splitDistance > 0.0) {
        splits += RunSplit(
            index = splitIndex,
            distanceMeters = splitDistance,
            elapsedMillis = splitElapsed,
            paceSecondsPerKm = averagePaceSecondsPerKm(splitElapsed, splitDistance)
        )
    }

    return splits
}

private fun averagePaceSecondsPerKm(elapsedMillis: Long, distanceMeters: Double): Double? {
    if (elapsedMillis <= 0L || distanceMeters < 1.0) return null
    return (elapsedMillis / 1000.0) / (distanceMeters / 1000.0)
}

private fun formatRunTime(run: RunSessionEntity): String {
    val timestamp = run.startedAtMillis ?: run.endedAtMillis ?: return "时间未知"
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return formatter.format(Date(timestamp))
}

@Composable
private fun MetricBlock(snapshot: RunSessionSnapshot) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = RunMetricFormatter.distance(snapshot.distanceMeters),
            style = MaterialTheme.typography.displayMedium
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            SmallMetric("时间", RunMetricFormatter.duration(snapshot.elapsedMillis))
            Spacer(modifier = Modifier.width(24.dp))
            SmallMetric("配速", RunMetricFormatter.pace(snapshot.averagePaceSecondsPerKm))
        }
        Spacer(modifier = Modifier.height(12.dp))
        val source = snapshot.latestPoint?.source?.name ?: "--"
        Text(
            text = "点位 ${snapshot.acceptedPointCount}/${snapshot.acceptedPointCount + snapshot.rejectedPointCount} · 来源 $source",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SmallMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Text(text = value, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun StartButtons(
    onStartRun: () -> Unit,
    onStartSimulatedRun: () -> Unit
) {
    Button(onClick = onStartRun) {
        Text("开始户外跑")
    }
    OutlinedButton(
        onClick = onStartSimulatedRun,
        modifier = Modifier.padding(top = 12.dp)
    ) {
        Text("模拟跑步")
    }
}
