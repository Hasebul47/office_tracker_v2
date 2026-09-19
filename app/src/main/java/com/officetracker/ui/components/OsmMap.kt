package com.officetracker.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
import androidx.compose.runtime.remember
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
import com.officetracker.core.model.RoutePoint
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

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

private val Dhaka = GeoPoint(23.8103, 90.4125)

/**
 * OpenStreetMap view (no API key needed). [fitKey] controls when the camera re-frames to the
 * content: the map zooms to fit once per distinct key, so live updates do not fight the user.
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
) {
    val context = LocalContext.current
    val routeColor = MaterialTheme.colorScheme.primary.toArgb()
    val mapView = rememberMapView(context)
    val fitted = remember(fitKey) { booleanArrayOf(false) }

    AndroidView(
        modifier = modifier,
        factory = {
            mapView.apply {
                // Let the map pan inside scrolling screens.
                setOnTouchListener { v, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }
            }
        },
        update = { map ->
            map.overlays.clear()
            circles.forEach { c ->
                map.overlays.add(Polygon(map).apply {
                    setPoints(Polygon.pointsAsCircle(GeoPoint(c.latitude, c.longitude), c.radiusMeters))
                    fillPaint.color = c.color.copy(alpha = 0.15f).toArgb()
                    outlinePaint.color = c.color.copy(alpha = 0.7f).toArgb()
                    outlinePaint.strokeWidth = 3f
                })
            }
            if (route.size >= 2) {
                map.overlays.add(Polyline(map).apply {
                    setPoints(route.map { GeoPoint(it.latitude, it.longitude) })
                    outlinePaint.color = routeColor
                    outlinePaint.strokeWidth = 11f
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                    outlinePaint.isAntiAlias = true
                })
            }
            markers.forEach { m ->
                map.overlays.add(Marker(map).apply {
                    position = GeoPoint(m.latitude, m.longitude)
                    title = m.title
                    snippet = m.snippet
                    setIcon(markerIcon(context, m.color.toArgb(), m.label, ring = m.pulse))
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { marker, _ ->
                        if (onMarkerClick != null) onMarkerClick(m.id) else marker.showInfoWindow()
                        true
                    }
                })
            }
            if (!fitted[0]) {
                val points = route.map { GeoPoint(it.latitude, it.longitude) } +
                    markers.map { GeoPoint(it.latitude, it.longitude) } +
                    circles.map { GeoPoint(it.latitude, it.longitude) }
                if (points.isNotEmpty()) {
                    fitted[0] = true
                    frame(map, points)
                }
            }
            map.invalidate()
        },
    )
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
        if (points.size == 1 || BoundingBox.fromGeoPointsSafe(points).let { it.latitudeSpan < 0.0005 && it.longitudeSpan < 0.0005 }) {
            map.controller.setZoom(16.5)
            map.controller.setCenter(points.last())
        } else {
            map.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(points).increaseByScale(1.35f), false)
            if (map.zoomLevelDouble > 17.5) map.controller.setZoom(17.5)
        }
    }
    if (map.width > 0 && map.height > 0) action() else map.addOnFirstLayoutListener { _, _, _, _, _ -> action() }
}

private val iconCache = HashMap<String, BitmapDrawable>()

private fun markerIcon(context: Context, color: Int, label: String?, ring: Boolean): BitmapDrawable {
    val key = "$color|$label|$ring"
    iconCache[key]?.let { return it }
    val density = context.resources.displayMetrics.density
    val size = ((if (label.isNullOrEmpty()) 22 else 30) * density).toInt()
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
