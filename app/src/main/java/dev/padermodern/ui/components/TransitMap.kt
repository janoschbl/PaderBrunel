package dev.padermodern.ui.components

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.padermodern.data.Station
import dev.padermodern.ui.theme.LocalTransitColors
import org.osmdroid.config.Configuration
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.io.File

data class MapLine(val points: List<Pair<Double, Double>>, val color: Color)

private class TransitOverlay(private val density: Float) : Overlay() {
    var stops: List<Station> = emptyList()
    var lines: List<MapLine> = emptyList()
    var user: Pair<Double, Double>? = null
    var stopColor = 0xFF2E5E8A.toInt()
    var stationColor = 0xFF8A2E5E.toInt()
    var haloColor = 0xFFFFFFFF.toInt()
    var labelColor = 0xFF202124.toInt()
    var onStop: ((Station) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density * 1.1f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val pt = Point()
    private val gp = GeoPoint(0.0, 0.0)

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = map.projection
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        lines.forEach { l ->
            if (l.points.size < 2) return@forEach
            path.rewind()
            l.points.forEachIndexed { i, p ->
                proj.toPixels(GeoPoint(p.first, p.second), pt)
                if (i == 0) path.moveTo(pt.x.toFloat(), pt.y.toFloat()) else path.lineTo(pt.x.toFloat(), pt.y.toFloat())
            }
            paint.color = haloColor; paint.strokeWidth = 9f * density
            c.drawPath(path, paint)
            paint.color = l.color.toArgb(); paint.strokeWidth = 5.5f * density
            c.drawPath(path, paint)
        }
        val zoom = map.zoomLevelDouble
        val box = map.boundingBox
        val padLat = (box.latNorth - box.latSouth) * 0.05
        val padLon = (box.lonEast - box.lonWest) * 0.05
        var labelBudget = 60
        stops.forEach { s ->
            if (s.latitude > box.latNorth + padLat || s.latitude < box.latSouth - padLat ||
                s.longitude > box.lonEast + padLon || s.longitude < box.lonWest - padLon
            ) return@forEach
            val station = s.isRailStation
            if (!station && zoom < 12.5) return@forEach
            gp.setCoords(s.latitude, s.longitude)
            proj.toPixels(gp, pt)
            val big = if (station) 1.3f else if (zoom < 14.5) 0.7f else 1f
            paint.style = Paint.Style.FILL
            paint.color = haloColor
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 9.5f * density * big, paint)
            paint.color = if (station) stationColor else stopColor
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 7f * density * big, paint)
            val showLabel = if (station) zoom >= 12.5 else zoom >= 15.2
            if (showLabel && labelBudget > 0 && !s.name.isNullOrBlank()) {
                labelBudget--
                val y = pt.y + 24f * density
                text.style = Paint.Style.STROKE
                text.strokeWidth = 3f * density
                text.color = haloColor
                c.drawText(s.name, pt.x.toFloat(), y, text)
                text.style = Paint.Style.FILL
                text.color = labelColor
                c.drawText(s.name, pt.x.toFloat(), y, text)
            }
        }
        user?.let { u ->
            proj.toPixels(GeoPoint(u.first, u.second), pt)
            paint.style = Paint.Style.FILL
            paint.color = 0x2E1A73E8
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 16f * density, paint)
            paint.color = 0xFFFFFFFF.toInt()
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 9f * density, paint)
            paint.color = 0xFF1A73E8.toInt()
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 6.5f * density, paint)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
        val handler = onStop ?: return false
        val proj = map.projection
        val reach = 24f * density
        val hit = stops.minByOrNull { s ->
            gp.setCoords(s.latitude, s.longitude)
            proj.toPixels(gp, pt)
            val dx = pt.x - e.x
            val dy = pt.y - e.y
            dx * dx + dy * dy
        }?.takeIf { s ->
            gp.setCoords(s.latitude, s.longitude)
            proj.toPixels(gp, pt)
            val dx = pt.x - e.x
            val dy = pt.y - e.y
            dx * dx + dy * dy <= reach * reach
        } ?: return false
        handler(hit)
        return true
    }
}

@Composable
fun TransitMap(
    modifier: Modifier = Modifier,
    stops: List<Station> = emptyList(),
    lines: List<MapLine> = emptyList(),
    user: Pair<Double, Double>? = null,
    center: Pair<Double, Double>,
    zoom: Double = 15.0,
    fitToContent: Boolean = false,
    recenter: Int = 0,
    onCameraIdle: ((Double, Double, Int) -> Unit)? = null,
    onStopClick: ((Station) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val dark = LocalTransitColors.current.dark
    val stopColor = MaterialTheme.colorScheme.primary.toArgb()
    val stationColor = MaterialTheme.colorScheme.tertiary.toArgb()
    val halo = if (dark) 0xFF1B1B1F.toInt() else 0xFFFFFFFF.toInt()
    val idle by rememberUpdatedState(onCameraIdle)
    val click by rememberUpdatedState(onStopClick)

    val overlay = remember { TransitOverlay(ctx.resources.displayMetrics.density) }
    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = ctx.packageName
            val base = File(ctx.cacheDir, "osmdroid")
            osmdroidBasePath = base
            osmdroidTileCache = File(base, "tiles")
            tileFileSystemCacheMaxBytes = 150L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 120L * 1024 * 1024
            tileDownloadThreads = 6
            tileFileSystemThreads = 4
        }
        MapView(ctx).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setTileSource(TileSourceFactory.MAPNIK)
            isTilesScaledToDpi = true
            minZoomLevel = 4.0
            maxZoomLevel = 19.0
            setUseDataConnection(true)
            controller.setZoom(zoom)
            controller.setCenter(GeoPoint(center.first, center.second))
            overlays.add(overlay)
            // keep map gestures inside scrolling lists
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE -> v.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
            addMapListener(
                DelayedMapListener(
                    object : MapListener {
                        override fun onScroll(event: ScrollEvent?): Boolean = report()
                        override fun onZoom(event: ZoomEvent?): Boolean = report()
                        private fun report(): Boolean {
                            val bb = boundingBox
                            val c = mapCenter
                            val r = GeoPoint(c.latitude, c.longitude).distanceToAsDouble(GeoPoint(bb.latNorth, bb.lonEast)).toInt()
                            idle?.invoke(c.latitude, c.longitude, r)
                            return false
                        }
                    },
                    350,
                ),
            )
            post { idle?.let { cb -> val bb = boundingBox; val c = mapCenter; cb(c.latitude, c.longitude, GeoPoint(c.latitude, c.longitude).distanceToAsDouble(GeoPoint(bb.latNorth, bb.lonEast)).toInt()) } }
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        mapView.onResume()
        onDispose {
            lifecycle.removeObserver(obs)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    LaunchedEffect(dark) {
        // no free dark tile style, invert the standard tiles
        mapView.overlayManager.tilesOverlay.setColorFilter(if (dark) TilesOverlay.INVERT_COLORS else null)
        mapView.invalidate()
    }
    LaunchedEffect(stops, lines, user, dark, stopColor, stationColor) {
        overlay.stops = stops
        overlay.lines = lines
        overlay.user = user
        overlay.stopColor = stopColor
        overlay.stationColor = stationColor
        overlay.haloColor = halo
        overlay.labelColor = if (dark) 0xFFE6E6E6.toInt() else 0xFF202124.toInt()
        overlay.onStop = { s -> click?.invoke(s) }
        mapView.invalidate()
    }
    LaunchedEffect(fitToContent, lines, stops) {
        if (!fitToContent) return@LaunchedEffect
        val pts = lines.flatMap { it.points } + stops.map { it.latitude to it.longitude }
        if (pts.size < 2) return@LaunchedEffect
        val box = BoundingBox.fromGeoPoints(pts.map { GeoPoint(it.first, it.second) })
        mapView.post { mapView.zoomToBoundingBox(box, false, (48 * ctx.resources.displayMetrics.density).toInt()) }
    }
    LaunchedEffect(recenter) {
        if (recenter == 0) return@LaunchedEffect
        mapView.controller.animateTo(GeoPoint(center.first, center.second), 16.0, 600L)
    }

    Box(modifier.clipToBounds()) {
        AndroidView(factory = { mapView }, modifier = Modifier.matchParentSize())
        Text(
            "© OpenStreetMap-Mitwirkende",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 4.dp),
        )
    }
}
