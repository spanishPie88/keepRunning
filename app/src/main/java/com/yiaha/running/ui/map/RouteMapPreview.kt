package com.yiaha.running.ui.map

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.yiaha.running.data.local.RunPointEntity
import kotlin.math.roundToInt

private const val PRIVACY_PREFERENCES = "map_privacy"
private const val AMAP_PRIVACY_AGREED = "amap_privacy_agreed"
private const val AMAP_PRIVACY_URL = "https://lbs.amap.com/pages/privacy/"
private val TrackBlue = Color(0xFF246BFD)
private val StartGreen = Color(0xFF16A34A)
private val EndRed = Color(0xFFDC2626)

@Composable
fun RouteMapPreview(points: List<RunPointEntity>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("运动轨迹", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            RouteLegend()
            Spacer(modifier = Modifier.height(12.dp))
            if (points.size < 2) {
                Text("暂无足够点位绘制地图。", style = MaterialTheme.typography.bodySmall)
                return@Column
            }

            AmapRouteMap(points, followLatest = false, heightDp = 280)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "高德地图 · GPS 坐标已转换并贴合道路显示",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(16.dp))
            OfflineRoutePreview(points)
        }
    }
}

@Composable
fun LiveRouteMapPreview(points: List<RunPointEntity>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("实时轨迹", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "有效点 ${points.size} · 地图跟随当前位置",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(12.dp))
            AmapRouteMap(points, followLatest = true, heightDp = 220)
        }
    }
}

@Composable
private fun RouteLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendDot(StartGreen, "起点")
        LegendDot(TrackBlue, "路线")
        LegendDot(EndRed, "终点")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(modifier = Modifier.size(9.dp)) { drawCircle(color) }
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AmapRouteMap(
    points: List<RunPointEntity>,
    followLatest: Boolean,
    heightDp: Int
) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences(PRIVACY_PREFERENCES, 0) }
    var privacyAgreed by remember {
        mutableStateOf(preferences.getBoolean(AMAP_PRIVACY_AGREED, false))
    }
    var showPrivacyDialog by remember { mutableStateOf(!privacyAgreed) }

    if (!privacyAgreed) {
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { showPrivacyDialog = true }
        ) {
            Text("启用高德地图轨迹")
        }
        if (showPrivacyDialog) {
            AlertDialog(
                onDismissRequest = { showPrivacyDialog = false },
                title = { Text("启用高德地图") },
                text = {
                    Text("地图展示会使用高德地图 SDK，并处理轨迹坐标、设备与网络信息。是否同意并继续？")
                },
                confirmButton = {
                    TextButton(onClick = {
                        preferences.edit().putBoolean(AMAP_PRIVACY_AGREED, true).apply()
                        privacyAgreed = true
                        showPrivacyDialog = false
                    }) { Text("同意并启用") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AMAP_PRIVACY_URL)))
                        }) { Text("隐私政策") }
                        TextButton(onClick = { showPrivacyDialog = false }) { Text("暂不") }
                    }
                }
            )
        }
        return
    }

    NativeAmapView(points, followLatest, heightDp)
}

@Composable
private fun NativeAmapView(
    points: List<RunPointEntity>,
    followLatest: Boolean,
    heightDp: Int
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val contentId = remember(points, followLatest) { 31 * points.hashCode() + followLatest.hashCode() }
    val mapView = remember {
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
        MapView(context).apply { onCreate(Bundle()) }
    }

    DisposableEffect(mapView, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            mapView.onResume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDestroy()
        }
    }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp),
        factory = { mapView },
        update = { view ->
            if (followLatest) {
                val renderState = (view.tag as? LiveMapRenderState)
                    ?: LiveMapRenderState().also { view.tag = it }
                renderLiveRoute(view, points, renderState)
            } else if (view.tag != contentId) {
                view.tag = contentId
                renderRoute(view, points)
            }
        }
    )
}

private data class LiveMapRenderState(
    var sessionId: String? = null,
    var sourcePointCount: Int = 0,
    val route: MutableList<LatLng> = mutableListOf(),
    var outline: Polyline? = null,
    var track: Polyline? = null,
    var startMarker: Marker? = null,
    var currentMarker: Marker? = null
)

private fun renderLiveRoute(
    mapView: MapView,
    points: List<RunPointEntity>,
    state: LiveMapRenderState
) {
    if (points.isEmpty()) return
    val sessionId = points.first().sessionId
    if (state.sessionId != sessionId || points.size < state.sourcePointCount) {
        mapView.map.clear()
        state.sessionId = sessionId
        state.sourcePointCount = 0
        state.route.clear()
        state.outline = null
        state.track = null
        state.startMarker = null
        state.currentMarker = null
    }
    if (points.size == state.sourcePointCount) return

    configureMap(mapView.map)
    val converter = CoordinateConverter(mapView.context)
    points.drop(state.sourcePointCount).forEach { point ->
        state.route += converter
            .from(CoordinateConverter.CoordType.GPS)
            .coord(LatLng(point.latitude, point.longitude))
            .convert()
    }
    state.sourcePointCount = points.size

    if (state.route.size >= 2) {
        state.outline = state.outline?.also { it.points = state.route.toList() }
            ?: mapView.map.addPolyline(
                PolylineOptions().addAll(state.route).width(18f)
                    .color(AndroidColor.argb(210, 255, 255, 255)).zIndex(10f)
            )
        state.track = state.track?.also { it.points = state.route.toList() }
            ?: mapView.map.addPolyline(
                PolylineOptions().addAll(state.route).width(11f)
                    .color(AndroidColor.rgb(36, 107, 253)).zIndex(11f)
            )
    }

    val first = state.route.first()
    val latest = state.route.last()
    if (state.startMarker == null) {
        state.startMarker = createRouteMarker(
            mapView.map,
            first,
            AndroidColor.rgb(22, 163, 74),
            "起点",
            mapView
        )
    }
    state.currentMarker = state.currentMarker?.also { it.position = latest }
        ?: createRouteMarker(
            mapView.map,
            latest,
            AndroidColor.rgb(36, 107, 253),
            "当前位置",
            mapView
        )

    mapView.post {
        mapView.map.moveCamera(CameraUpdateFactory.newLatLngZoom(latest, 17f))
    }
}

private fun renderRoute(mapView: MapView, points: List<RunPointEntity>) {
    val aMap = mapView.map
    val converter = CoordinateConverter(mapView.context)
    val route = points.map {
        converter
            .from(CoordinateConverter.CoordType.GPS)
            .coord(LatLng(it.latitude, it.longitude))
            .convert()
    }
    if (route.size < 2) return

    aMap.clear()
    configureMap(aMap)
    aMap.addPolyline(
        PolylineOptions().addAll(route).width(18f)
            .color(AndroidColor.argb(210, 255, 255, 255)).zIndex(10f)
    )
    aMap.addPolyline(
        PolylineOptions().addAll(route).width(11f)
            .color(AndroidColor.rgb(36, 107, 253)).zIndex(11f)
    )

    createRouteMarker(aMap, route.first(), AndroidColor.rgb(22, 163, 74), "起点", mapView)
    createRouteMarker(aMap, route.last(), AndroidColor.rgb(220, 38, 38), "终点", mapView)

    val boundsBuilder = LatLngBounds.builder()
    route.forEach(boundsBuilder::include)
    mapView.post {
        aMap.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 96))
    }
}

private fun configureMap(aMap: AMap) {
    aMap.mapType = AMap.MAP_TYPE_NORMAL
    aMap.uiSettings.apply {
        isZoomControlsEnabled = false
        isCompassEnabled = false
        isScaleControlsEnabled = true
        isRotateGesturesEnabled = false
    }
}

private fun createRouteMarker(
    aMap: AMap,
    position: LatLng,
    color: Int,
    title: String,
    mapView: MapView
): Marker {
    return aMap.addMarker(
        MarkerOptions()
            .position(position)
            .title(title)
            .anchor(0.5f, 0.5f)
            .icon(BitmapDescriptorFactory.fromBitmap(createDotBitmap(color, mapView.resources.displayMetrics.density)))
    )
}

private fun createDotBitmap(color: Int, density: Float): Bitmap {
    val size = (24f * density).roundToInt().coerceAtLeast(24)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val center = size / 2f
    paint.color = AndroidColor.WHITE
    canvas.drawCircle(center, center, center, paint)
    paint.color = color
    canvas.drawCircle(center, center, center * 0.66f, paint)
    return bitmap
}

@Composable
private fun OfflineRoutePreview(points: List<RunPointEntity>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("离线轨迹", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(12.dp))
        val route = remember(points) { normalizeRoute(points) }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
        ) {
            val canvasPadding = 18.dp.toPx()
            val width = size.width - canvasPadding * 2
            val height = size.height - canvasPadding * 2
            val path = Path()
            route.forEachIndexed { index, normalized ->
                val point = Offset(
                    x = canvasPadding + normalized.x * width,
                    y = canvasPadding + normalized.y * height
                )
                if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }

            drawPath(path, TrackBlue, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
            val start = route.first()
            val end = route.last()
            drawCircle(StartGreen, 6.dp.toPx(), Offset(canvasPadding + start.x * width, canvasPadding + start.y * height))
            drawCircle(EndRed, 6.dp.toPx(), Offset(canvasPadding + end.x * width, canvasPadding + end.y * height))
        }
    }
}

private data class NormalizedPoint(val x: Float, val y: Float)

private fun normalizeRoute(points: List<RunPointEntity>): List<NormalizedPoint> {
    val minLat = points.minOf { it.latitude }
    val maxLat = points.maxOf { it.latitude }
    val minLon = points.minOf { it.longitude }
    val maxLon = points.maxOf { it.longitude }
    val latRange = (maxLat - minLat).takeIf { it > 0.0 } ?: 1.0
    val lonRange = (maxLon - minLon).takeIf { it > 0.0 } ?: 1.0
    return points.map {
        NormalizedPoint(
            x = ((it.longitude - minLon) / lonRange).toFloat().coerceIn(0f, 1f),
            y = (1f - ((it.latitude - minLat) / latRange).toFloat()).coerceIn(0f, 1f)
        )
    }
}
