package com.officetracker.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.officetracker.core.model.RouteMath
import com.officetracker.core.model.RoutePoint
import com.officetracker.core.model.SpeedBand
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay

data class MapMarker(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val snippet: String? = null,
    val color: Color,
    val label: String? = null,
    val pulse: Boolean = false,
)

data class MapCircle(val latitude: Double, val longitude: Double, val radiusMeters: Double, val color: Color)

enum class MapLayer(val label: String) { STANDARD("Standard"), TOPO("Terrain"), DARK("Dark") }

private val Dhaka = GeoPoint(23.8103, 90.4125)

/** Free, key-less tile sources. "Dark" is the standard map with inverted colours. */
private object Tiles {
    fun of(layer: MapLayer): ITileSource = when (layer) {
        MapLayer.STANDARD, MapLayer.DARK -> TileSourceFactory.MAPNIK
        MapLayer.TOPO -> TileSourceFactory.OpenTopo
    }
}

/** Colours of the speed bands on the route line (shared with the legend). */
fun SpeedBand.color(): Color = when (this) {
    SpeedBand.SLOW -> Color(0xFF2E9E5B)
    SpeedBand.CITY -> Color(0xFF1E6FD9)
    SpeedBand.FAST -> Color(0xFF8E44AD)
    SpeedBand.GAP -> Color(0xFF9AA5B1)
}

/** Imperative handle for map buttons (zoom, fit) that live outside the AndroidView. */
class MapHandle {
    internal var view: MapView? = null
    internal var fitPoints: List<GeoPoint> = emptyList()

    fun zoomIn() { view?.controller?.zoomIn() }
    fun zoomOut() { view?.controller?.zoomOut() }
    fun fit() { view?.let { v -> if (fitPoints.isNotEmpty()) frame(v, fitPoints) } }
    fun centerOn(lat: Double, lng: Double, zoom: Double? = null) {
        view?.controller?.let { c ->
            zoom?.let { c.setZoom(it) }
            c.animateTo(GeoPoint(lat, lng))
        }
    }
}

@Composable
fun rememberMapHandle(): MapHandle = remember { MapHandle() }

/** Overlays are rebuilt only when their inputs change, so moving the playback cursor is cheap. */
private class OverlayCache {
    var layer: MapLayer? = null
    var route: List<RoutePoint>? = null
    var colorBySpeed: Boolean? = null
    var routeColor: Int = 0
    var markers: List<MapMarker>? = null
    var circles: List<MapCircle>? = null
    var routeOverlays: List<Overlay> = emptyList()
    var markerOverlays: List<Overlay> = emptyList()
    var circleOverlays: List<Overlay> = emptyList()
    var events: MapEventsOverlay? = null
    var cursor: Marker? = null
    var fitted = false
    var fitKey: Any? = Unit
}

/**
 * OpenStreetMap view (no API key). The route is coloured by speed with dashed "no signal" gaps,
 * tapping near the line reports the closest recorded point, and [cursor] shows a moving
 * marker for playback. The camera frames the content once per distinct [fitKey].
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun OsmMap(
    modifier: Modifier = Modifier,
    route: List<RoutePoint> = emptyList(),
    markers: List<MapMarker> = emptyList(),
    circles: List<MapCircle> = emptyList(),
    fitKey: Any? = Unit,
    onMarkerClick: ((String) -> Unit)? = null,
    cursor: RoutePoint? = null,
    followCursor: Boolean = false,
    onRouteTap: ((Int) -> Unit)? = null,
    colorBySpeed: Boolean = true,
    layer: MapLayer = MapLayer.STANDARD,
    handle: MapHandle? = null,
) {
    val context = LocalContext.current
    val routeColor = MaterialTheme.colorScheme.primary.toArgb()
    val mapView = rememberMapView(context)
    val cache = remember { OverlayCache() }
    val markerClick by rememberUpdatedState(onMarkerClick)
    val routeTap by rememberUpdatedState(onRouteTap)

    AndroidView(
        modifier = modifier,
        factory = {
            mapView.apply {
                setOnTouchListener { v, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }
            }
        },
        update = { map ->
            handle?.view = map
            var rebuild = false

            if (cache.layer != layer) {
                map.setTileSource(Tiles.of(layer))
                map.overlayManager.tilesOverlay.setColorFilter(if (layer == MapLayer.DARK) TilesOverlay.INVERTED_COLORS else null)
                cache.layer = layer
            }
            if (cache.events == null) {
                cache.events = MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                        val r = cache.route ?: return false
                        val tap = routeTap ?: return false
                        val tolerance = RouteMath.metersPerPixel(p.latitude, map.zoomLevelDouble) * 36 *
                            context.resources.displayMetrics.density / 2.5
                        val idx = RouteMath.nearestIndex(r, p.latitude, p.longitude, tolerance.coerceAtLeast(25.0)) ?: return false
                        tap(idx)
                        return true
                    }

                    override fun longPressHelper(p: GeoPoint): Boolean = false
                })
                rebuild = true
            }
            if (cache.route !== route || cache.colorBySpeed != colorBySpeed || cache.routeColor != routeColor) {
                cache.route = route
                cache.colorBySpeed = colorBySpeed
                cache.routeColor = routeColor
                cache.routeOverlays = buildRoute(map, route, colorBySpeed, routeColor)
                rebuild = true
            }
            if (cache.circles != circles) {
                cache.circles = circles
                cache.circleOverlays = circles.map { c ->
                    Polygon(map).apply {
                        setPoints(Polygon.pointsAsCircle(GeoPoint(c.latitude, c.longitude), c.radiusMeters))
                        fillPaint.color = c.color.copy(alpha = 0.15f).toArgb()
                        outlinePaint.color = c.color.copy(alpha = 0.7f).toArgb()
                        outlinePaint.strokeWidth = 3f
                    }
                }
                rebuild = true
            }
            if (cache.markers != markers) {
                cache.markers = markers
                cache.markerOverlays = markers.map { m ->
                    Marker(map).apply {
                        position = GeoPoint(m.latitude, m.longitude)
                        title = m.title
                        snippet = m.snippet
                        setIcon(markerIcon(context, m.color.toArgb(), m.label, ring = m.pulse))
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        setOnMarkerClickListener { marker, _ ->
                            val click = markerClick
                            if (click != null) click(m.id) else if (marker.isInfoWindowShown) marker.closeInfoWindow() else marker.showInfoWindow()
                            true
                        }
                    }
                }
                rebuild = true
            }
            if (cache.cursor == null) {
                cache.cursor = Marker(map).apply {
                    setIcon(markerIcon(context, 0xFFE8590C.toInt(), null, ring = true))
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setInfoWindow(null)
                }
            }
            if (rebuild) {
                map.overlays.clear()
                cache.events?.let { map.overlays.add(it) }
                map.overlays.addAll(cache.circleOverlays)
                map.overlays.addAll(cache.routeOverlays)
                map.overlays.addAll(cache.markerOverlays)
            }
            val cursorMarker = cache.cursor!!
            if (cursor != null) {
                cursorMarker.position = GeoPoint(cursor.latitude, cursor.longitude)
                if (!map.overlays.contains(cursorMarker)) map.overlays.add(cursorMarker)
                if (followCursor) map.controller.setCenter(cursorMarker.position)
            } else {
                map.overlays.remove(cursorMarker)
            }

            val points = route.map { GeoPoint(it.latitude, it.longitude) } +
                markers.map { GeoPoint(it.latitude, it.longitude) } +
                circles.map { GeoPoint(it.latitude, it.longitude) }
            handle?.fitPoints = points
            if (cache.fitKey != fitKey) {
                cache.fitKey = fitKey
                cache.fitted = false
            }
            if (!cache.fitted && points.isNotEmpty()) {
                cache.fitted = true
                frame(map, points)
            }
            map.invalidate()
        },
    )
}

private fun buildRoute(map: MapView, route: List<RoutePoint>, colorBySpeed: Boolean, primary: Int): List<Overlay> {
    if (route.size < 2) return emptyList()
    val density = map.context.resources.displayMetrics.density
    fun line(points: List<RoutePoint>, color: Int, dashed: Boolean, width: Float) = Polyline(map).apply {
        setPoints(points.map { GeoPoint(it.latitude, it.longitude) })
        outlinePaint.color = color
        outlinePaint.strokeWidth = width
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.strokeJoin = Paint.Join.ROUND
        outlinePaint.isAntiAlias = true
        if (dashed) outlinePaint.pathEffect = DashPathEffect(floatArrayOf(6 * density, 6 * density), 0f)
        setInfoWindow(null)
    }
    // White casing under the whole route keeps it readable on any map style.
    val casing = line(route, android.graphics.Color.WHITE, dashed = false, width = 7.5f * density)
    if (!colorBySpeed) return listOf(casing, line(route, primary, false, 4.5f * density))
    return listOf(casing) + RouteMath.segments(route).map { seg ->
        line(seg.points, seg.band.color().toArgb(), dashed = seg.band == SpeedBand.GAP, width = 4.5f * density)
    }
}

/** Map with a fixed centre pin, for choosing a location by panning. */
@Composable
fun PlacePickerMap(
    initialLatitude: Double?,
    initialLongitude: Double?,
    radiusMeters: Double,
    onCenterChanged: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapView = rememberMapView(context)
    val accent = MaterialTheme.colorScheme.primary
    val started = remember { booleanArrayOf(false) }
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                mapView.apply {
                    setOnTouchListener { v, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                        false
                    }
                    addMapListener(object : MapListener {
                        override fun onScroll(event: ScrollEvent?): Boolean {
                            val c = mapCenter
                            onCenterChanged(c.latitude, c.longitude)
                            return false
                        }

                        override fun onZoom(event: ZoomEvent?): Boolean = false
                    })
                }
            },
            update = { map ->
                if (!started[0]) {
                    started[0] = true
                    val start = if (initialLatitude != null && initialLongitude != null) GeoPoint(initialLatitude, initialLongitude) else Dhaka
                    map.controller.setZoom(17.0)
                    map.controller.setCenter(start)
                    onCenterChanged(start.latitude, start.longitude)
                }
                map.overlays.clear()
                val c = map.mapCenter
                map.overlays.add(Polygon(map).apply {
                    setPoints(Polygon.pointsAsCircle(GeoPoint(c.latitude, c.longitude), radiusMeters))
                    fillPaint.color = accent.copy(alpha = 0.12f).toArgb()
                    outlinePaint.color = accent.toArgb()
                    outlinePaint.strokeWidth = 3f
                })
                map.invalidate()
            },
        )
        Icon(
            Icons.Default.Place, contentDescription = "Selected location", tint = accent,
            modifier = Modifier.align(Alignment.Center).size(40.dp).offset(y = (-20).dp),
        )
    }
}

@Composable
private fun rememberMapView(context: Context): MapView {
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setMinZoomLevel(4.0)
            setMaxZoomLevel(19.5)
            controller.setZoom(12.0)
            controller.setCenter(Dhaka)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }
    return mapView
}

private fun frame(map: MapView, points: List<GeoPoint>) {
    val action: () -> Unit = {
        val box = BoundingBox.fromGeoPointsSafe(points)
        if (points.size == 1 || (box.latitudeSpan < 0.0005 && box.longitudeSpan < 0.0005)) {
            map.controller.setZoom(16.5)
            map.controller.setCenter(points.last())
        } else {
            map.zoomToBoundingBox(box.increaseByScale(1.3f), false)
            if (map.zoomLevelDouble > 17.5) map.controller.setZoom(17.5)
        }
    }
    if (map.width > 0 && map.height > 0) action() else map.addOnFirstLayoutListener { _, _, _, _, _ -> action() }
}

private val iconCache = HashMap<String, BitmapDrawable>()

internal fun markerIcon(context: Context, color: Int, label: String?, ring: Boolean): BitmapDrawable {
    val key = "$color|$label|$ring"
    iconCache[key]?.let { return it }
    val density = context.resources.displayMetrics.density
    val size = ((if (label.isNullOrEmpty()) 20 else 30) * density).toInt()
    val pad = if (ring) (8 * density).toInt() else 0
    val full = size + pad * 2
    val bmp = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val c = full / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    if (ring) {
        paint.color = color
        paint.alpha = 60
        canvas.drawCircle(c, c, full / 2f, paint)
    }
    paint.alpha = 255
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(c, c, size / 2f, paint)
    paint.color = color
    canvas.drawCircle(c, c, size / 2f - 2.5f * density, paint)
    if (!label.isNullOrEmpty()) {
        paint.color = android.graphics.Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = (if (label.length > 2) 10 else 12) * density
        val y = c - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(label, c, y, paint)
    }
    return BitmapDrawable(context.resources, bmp).also { iconCache[key] = it }
}
