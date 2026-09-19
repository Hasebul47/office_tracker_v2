@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.officetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Factory
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.officetracker.core.model.DayDetail
import com.officetracker.core.model.Features
import com.officetracker.core.model.LiveState
import com.officetracker.core.model.PlaceCategory
import com.officetracker.core.model.Stay
import com.officetracker.core.model.TimelineEntry
import com.officetracker.core.model.WorkStatus
import com.officetracker.core.model.Workday
import com.officetracker.core.util.Dates
import com.officetracker.core.util.Format
import com.officetracker.ui.theme.Brand

// ---------- Visual vocabulary ----------

fun PlaceCategory.icon(): ImageVector = when (this) {
    PlaceCategory.OFFICE -> Icons.Default.Business
    PlaceCategory.FACTORY -> Icons.Default.Factory
    PlaceCategory.CLIENT -> Icons.Default.Handshake
    PlaceCategory.SUPPLIER -> Icons.Default.LocalShipping
    PlaceCategory.MEAL -> Icons.Default.Restaurant
    PlaceCategory.HOME -> Icons.Default.Home
    PlaceCategory.OTHER -> Icons.Default.Place
}

fun PlaceCategory.color(): Color = when (this) {
    PlaceCategory.OFFICE -> Color(0xFF0B6E99)
    PlaceCategory.FACTORY -> Color(0xFF6A4FB3)
    PlaceCategory.CLIENT -> Color(0xFF1E8E5A)
    PlaceCategory.SUPPLIER -> Color(0xFF8D6E00)
    PlaceCategory.MEAL -> Color(0xFFD9602B)
    PlaceCategory.HOME -> Color(0xFF5C6BC0)
    PlaceCategory.OTHER -> Color(0xFF607D8B)
}

fun WorkStatus.label(): String = when (this) {
    WorkStatus.NOT_STARTED -> "Not started"
    WorkStatus.ACTIVE -> "On duty"
    WorkStatus.PAUSED -> "Paused"
    WorkStatus.ENDED -> "Day ended"
}

fun WorkStatus.color(): Color = when (this) {
    WorkStatus.NOT_STARTED -> Brand.Muted
    WorkStatus.ACTIVE -> Brand.Success
    WorkStatus.PAUSED -> Brand.Warning
    WorkStatus.ENDED -> Brand.Primary
}

fun LiveState?.statusLabel(now: Long): String = when {
    this == null -> "Off duty"
    isStale(now) -> "No signal"
    status == WorkStatus.ACTIVE -> "On duty"
    status == WorkStatus.PAUSED -> "Paused"
    else -> "Off duty"
}

fun LiveState?.statusColor(now: Long): Color = when {
    this == null -> Brand.Muted
    isStale(now) -> Brand.Danger
    status == WorkStatus.ACTIVE -> Brand.Success
    status == WorkStatus.PAUSED -> Brand.Warning
    else -> Brand.Muted
}

// ---------- Stats ----------

@Composable
fun DayStats(day: Workday?, visits: Int, ratePerKm: Double, now: Long, modifier: Modifier = Modifier) {
    val distance = day?.distanceMeters ?: 0.0
    val features by rememberFeatures()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("On duty", Dates.duration(day?.activeMillis(now) ?: 0), Icons.Default.Schedule, Modifier.weight(1f))
            StatTile("Distance", Format.distance(distance), Icons.Default.Route, Modifier.weight(1f), tint = Brand.Route)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Visits", visits.toString(), Icons.Default.Place, Modifier.weight(1f), tint = Brand.Success)
            if (features.allowance) {
                StatTile("Allowance", Format.taka(Format.allowance(distance, ratePerKm)), Icons.Default.Payments, Modifier.weight(1f), tint = Brand.Warning)
            } else {
                StatTile("Pauses", (day?.pauseCount ?: 0).toString(), Icons.Default.Schedule, Modifier.weight(1f), tint = Brand.Warning)
            }
        }
        if (day != null && day.mockCount > 0 && features.fakeGpsAlerts) {
            Banner(
                text = "Fake GPS was detected ${day.mockCount} times on this day.",
                icon = Icons.Default.Warning, color = Brand.Danger,
            )
        }
    }
}

/** The signed-in company's feature switches (all on for the super admin). Live-updating. */
@Composable
fun rememberFeatures(): State<Features> =
    com.officetracker.OfficeTrackerApp.container.org.features.collectAsStateWithLifecycle()

// ---------- Map ----------

fun DayDetail.mapMarkers(showLive: Boolean): List<MapMarker> = buildList {
    val w = workday
    if (w?.startLatitude != null && w.startLongitude != null) {
        add(MapMarker("start", w.startLatitude, w.startLongitude, "Day started", w.startName?.let { "${Dates.time(w.startedAt)} · $it" } ?: Dates.time(w.startedAt), Brand.Success, "S"))
    }
    stays.sortedBy { it.arrivalAt }.forEachIndexed { i, s ->
        add(
            MapMarker(
                "stay:${s.id}", s.latitude, s.longitude, "${i + 1}. ${s.name}",
                "${Dates.time(s.arrivalAt)} – ${s.departureAt?.let { Dates.time(it) } ?: "now"}",
                s.category.color(), (i + 1).toString(),
            )
        )
    }
    if (w?.endLatitude != null && w.endLongitude != null && w.endedAt != null) {
        add(MapMarker("end", w.endLatitude, w.endLongitude, "Day ended", w.endName?.let { "${Dates.time(w.endedAt)} · $it" } ?: Dates.time(w.endedAt), Brand.Danger, "E"))
    }
    if (showLive && w?.status == WorkStatus.ACTIVE) {
        route.lastOrNull()?.let { add(MapMarker("live", it.latitude, it.longitude, "Now", Dates.time(it.time), Brand.Route, pulse = true)) }
    }
}

@Composable
fun DayMapCard(detail: DayDetail, height: Dp = 260.dp, showLive: Boolean = true, fitKey: Any? = detail.workday?.date) {
    var fullscreen by remember { mutableStateOf(false) }
    val markers = detail.mapMarkers(showLive)
    Box(
        Modifier.fillMaxWidth().height(height).clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        if (detail.route.isEmpty() && markers.isEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Route, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text("Route appears here once tracking starts", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            OsmMap(Modifier.fillMaxSize(), route = detail.route, markers = markers, fitKey = fitKey)
            FilledTonalIconButton(onClick = { fullscreen = true }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Default.Fullscreen, contentDescription = "Full screen map")
            }
        }
    }
    if (fullscreen) FullscreenMap(detail, showLive) { fullscreen = false }
}

@Composable
fun FullscreenMap(detail: DayDetail, showLive: Boolean, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                OsmMap(Modifier.fillMaxSize(), route = detail.route, markers = detail.mapMarkers(showLive), fitKey = "full")
                FilledTonalIconButton(onClick = onDismiss, modifier = Modifier.statusBarsPadding().padding(12.dp).align(Alignment.TopEnd)) {
                    Icon(Icons.Default.Close, contentDescription = "Close map")
                }
                Surface(
                    Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    shape = MaterialTheme.shapes.medium, tonalElevation = 3.dp, shadowElevation = 4.dp,
                ) {
                    val w = detail.workday
                    Text(
                        "${detail.stays.size} visits · ${Format.distance(w?.distanceMeters ?: 0.0)}",
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

// ---------- Timeline ----------

fun LazyListScope.timelineItems(entries: List<TimelineEntry>, now: Long, onStayClick: ((Stay) -> Unit)?) {
    items(entries, key = { e ->
        when (e) {
            is TimelineEntry.Visit -> "v:${e.stay.id}"
            is TimelineEntry.Travel -> "t:${e.fromTime}"
        }
    }) { e ->
        when (e) {
            is TimelineEntry.Visit -> VisitRow(e.index, e.stay, now, onStayClick)
            is TimelineEntry.Travel -> TravelRow(e)
        }
    }
}

/** Non-lazy timeline for use inside another scrolling list. */
@Composable
fun TimelineStatic(entries: List<TimelineEntry>, now: Long, onStayClick: ((Stay) -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.forEach { e ->
            when (e) {
                is TimelineEntry.Visit -> VisitRow(e.index, e.stay, now, onStayClick)
                is TimelineEntry.Travel -> TravelRow(e)
            }
        }
    }
}

@Composable
private fun VisitRow(index: Int, stay: Stay, now: Long, onClick: ((Stay) -> Unit)?) {
    val color = stay.category.color()
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .then(if (onClick != null) Modifier.clickable { onClick(stay) } else Modifier)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(color.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(stay.category.icon(), contentDescription = stay.category.label, tint = color, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("$index. ${stay.name}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val left = stay.departureAt?.let { Dates.time(it) } ?: "now"
            Text(
                "${Dates.time(stay.arrivalAt)} – $left · ${stay.category.label}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            stay.address?.takeIf { it.isNotBlank() && it != stay.name }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(Dates.duration(stay.durationMillis(now)), style = MaterialTheme.typography.titleSmall)
            if (stay.isOpen) StatusPill("Here", Brand.Success)
            else if (onClick != null) Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TravelRow(t: TimelineEntry.Travel) {
    Row(Modifier.fillMaxWidth().padding(start = 30.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(2.dp).height(28.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Spacer(Modifier.width(18.dp))
        Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(
            "Travel · ${Format.distance(t.distanceMeters)} · ${Dates.duration(t.toTime - t.fromTime)}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- Edit stay ----------

@Composable
fun EditStayDialog(stay: Stay, onSave: (String, PlaceCategory) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(stay.name) }
    var category by remember { mutableStateOf(stay.category) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Label this place") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(60) }, singleLine = true,
                    label = { Text("Place name") }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("Category", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                CategoryChips(category) { category = it }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Future visits to this spot will get the same label automatically.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, category); onDismiss() }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun CategoryChips(selected: PlaceCategory, onSelect: (PlaceCategory) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PlaceCategory.entries.forEach { c ->
            FilterChip(
                selected = c == selected,
                onClick = { onSelect(c) },
                label = { Text(c.label) },
                leadingIcon = { Icon(c.icon(), contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
        }
    }
}

@Composable
fun ScreenMessage(text: String?, onDismiss: () -> Unit) {
    if (text == null) return
    Banner(text = text, icon = Icons.Default.Info, color = MaterialTheme.colorScheme.primary, actionLabel = "OK", onAction = onDismiss)
}

