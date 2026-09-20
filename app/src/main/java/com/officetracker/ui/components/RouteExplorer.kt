package com.officetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.officetracker.core.model.DayDetail
import com.officetracker.core.model.RouteMath
import com.officetracker.core.model.SpeedBand
import com.officetracker.core.model.WorkStatus
import com.officetracker.core.model.effectiveDistance
import com.officetracker.core.util.Dates
import com.officetracker.core.util.Format
import com.officetracker.ui.theme.Brand
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Start / visits / end / live markers. When the phone had no location at the moment the day
 * started or ended, the first / last recorded GPS point is used so both ends always show.
 */
fun DayDetail.mapMarkers(showLive: Boolean): List<MapMarker> = buildList {
    val w = workday
    val first = route.firstOrNull()
    val last = route.lastOrNull()
    val startLat = w?.startLatitude ?: first?.latitude
    val startLng = w?.startLongitude ?: first?.longitude
    if (w != null && startLat != null && startLng != null) {
        add(
            MapMarker(
                "start", startLat, startLng, "Day started · ${Dates.time(w.startedAt)}",
                w.startName ?: "Start", Brand.Success, "S",
            )
        )
    }
    stays.sortedBy { it.arrivalAt }.forEachIndexed { i, s ->
        add(
            MapMarker(
                "stay:${s.id}", s.latitude, s.longitude, "${i + 1}. ${s.name}",
                "${Dates.time(s.arrivalAt)} – ${s.departureAt?.let { Dates.time(it) } ?: "now"} · " +
                    Dates.duration(s.durationMillis(System.currentTimeMillis())),
                s.category.color(), (i + 1).toString(),
            )
        )
    }
    val ended = w?.endedAt
    if (w != null && ended != null) {
        val endLat = w.endLatitude ?: last?.latitude
        val endLng = w.endLongitude ?: last?.longitude
        if (endLat != null && endLng != null) {
            add(MapMarker("end", endLat, endLng, "Day ended · ${Dates.time(ended)}", w.endName ?: "End", Brand.Danger, "E"))
        }
    }
    if (showLive && w?.status == WorkStatus.ACTIVE && last != null) {
        add(MapMarker("live", last.latitude, last.longitude, "Now", Dates.time(last.time), Brand.Route, pulse = true))
    }
}

/** Compact map for a day, with zoom/fit controls, tap-to-inspect and a full-screen player. */
@Composable
fun DayMapCard(detail: DayDetail, height: Dp = 280.dp, showLive: Boolean = true, fitKey: Any? = detail.workday?.date) {
    var fullscreen by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth().height(height).clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        if (detail.route.isEmpty() && detail.mapMarkers(showLive).isEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Route, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text("Route appears here once tracking starts", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            RouteExplorer(detail, showLive, fitKey, fullscreen = false, onExpand = { fullscreen = true })
        }
    }
    if (fullscreen) {
        Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                RouteExplorer(detail, showLive, "full", fullscreen = true, onClose = { fullscreen = false })
            }
        }
    }
}

@Composable
private fun RouteExplorer(
    detail: DayDetail,
    showLive: Boolean,
    fitKey: Any?,
    fullscreen: Boolean,
    onExpand: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    val route = detail.route
    val handle = rememberMapHandle()
    val dark = isSystemInDarkTheme()
    var layer by rememberSaveable { mutableStateOf(if (dark) MapLayer.DARK else MapLayer.STANDARD) }
    var layersMenu by remember { mutableStateOf(false) }
    // Keyed by day, not by the route list: a live day gets a new list with every GPS fix,
    // which would otherwise clear the tapped point and restart playback from the beginning.
    var index by remember(detail.workday?.date) { mutableStateOf<Int?>(null) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableIntStateOf(8) }
    val cumulative = remember(route) { RouteMath.cumulative(route) }
    val markers = remember(detail, showLive) { detail.mapMarkers(showLive) }

    // Playback: advance the cursor along the recorded points.
    LaunchedEffect(playing, speed, route) {
        if (!playing || route.size < 2) return@LaunchedEffect
        var i = index ?: 0
        if (i >= route.lastIndex) i = 0
        while (playing && i < route.lastIndex) {
            index = i
            delay(60)
            i = (i + speed).coerceAtMost(route.lastIndex)
        }
        index = route.lastIndex
        playing = false
    }

    Box(Modifier.fillMaxSize()) {
        OsmMap(
            Modifier.fillMaxSize(),
            route = route,
            markers = markers,
            fitKey = fitKey,
            cursor = index?.let { route.getOrNull(it) },
            followCursor = playing,
            onRouteTap = { i -> playing = false; index = i },
            layer = layer,
            handle = handle,
        )

        // Map controls
        Column(
            Modifier.align(Alignment.TopEnd).then(if (fullscreen) Modifier.statusBarsPadding() else Modifier).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (fullscreen) {
                FilledTonalIconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close map") }
            } else {
                FilledTonalIconButton(onClick = onExpand) { Icon(Icons.Default.Fullscreen, "Full screen map") }
            }
            FilledTonalIconButton(onClick = { handle.zoomIn() }) { Icon(Icons.Default.Add, "Zoom in") }
            FilledTonalIconButton(onClick = { handle.zoomOut() }) { Icon(Icons.Default.Remove, "Zoom out") }
            FilledTonalIconButton(onClick = { handle.fit() }) { Icon(Icons.Default.CenterFocusStrong, "Show whole route") }
            Box {
                FilledTonalIconButton(onClick = { layersMenu = true }) { Icon(Icons.Default.Layers, "Map style") }
                DropdownMenu(expanded = layersMenu, onDismissRequest = { layersMenu = false }) {
                    MapLayer.entries.forEach { l ->
                        DropdownMenuItem(text = { Text(l.label) }, onClick = { layer = l; layersMenu = false })
                    }
                }
            }
        }

        // Bottom panel: tapped point / playback read-out
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .then(if (fullscreen) Modifier.navigationBarsPadding() else Modifier)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val i = index
            if (i != null && i in route.indices) {
                val p = route[i]
                val kmh = if (i > 0) RouteMath.speedMps(route[i - 1], p) * 3.6 else 0.0
                Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 3.dp, shadowElevation = 3.dp) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(Dates.time(p.time), style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${String.format(Locale.ENGLISH, "%.0f", kmh)} km/h · ${Format.distance(cumulative[i])} from start" +
                                    if (p.mock) " · fake GPS" else "",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = { index = null; playing = false }) { Icon(Icons.Default.Close, "Clear", Modifier.size(18.dp)) }
                    }
                }
            }
            if (fullscreen && route.size >= 2) {
                Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 4.dp) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { playing = !playing }) {
                                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play route")
                            }
                            Slider(
                                value = (index ?: 0).toFloat(),
                                onValueChange = { playing = false; index = it.toInt().coerceIn(0, route.lastIndex) },
                                valueRange = 0f..route.lastIndex.toFloat(),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${Dates.time(route.first().time)} – ${Dates.time(route.last().time)} · " +
                                    "${detail.stays.size} visits · ${Format.distance(detail.effectiveDistance())}",
                                style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
                            )
                            listOf(2, 8, 32).forEach { s ->
                                FilterChip(
                                    selected = speed == s, onClick = { speed = s },
                                    label = { Text("${s}x") }, modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }
                        SpeedLegend()
                    }
                }
            } else if (!fullscreen && route.size >= 2 && index == null) {
                SpeedLegend(compact = true)
            }
        }
    }
}

@Composable
fun SpeedLegend(compact: Boolean = false) {
    val bands = listOf(SpeedBand.SLOW, SpeedBand.CITY, SpeedBand.FAST, SpeedBand.GAP)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (compact) 0.85f else 0f),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bands.forEach { b ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 14.dp, height = 4.dp).background(b.color(), CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        when (b) {
                            SpeedBand.SLOW -> "Walk"
                            SpeedBand.CITY -> "City"
                            SpeedBand.FAST -> "Fast"
                            SpeedBand.GAP -> "No signal"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
