@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)

package com.officetracker.ui.history

import androidx.compose.foundation.background
import com.officetracker.core.model.effectiveSchedule
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.officetracker.AppContainer
import com.officetracker.core.model.UserProfile
import com.officetracker.core.model.WorkSchedule
import com.officetracker.core.model.Workday
import com.officetracker.core.util.Dates
import com.officetracker.core.util.Format
import com.officetracker.ui.appViewModel
import com.officetracker.ui.components.EmptyState
import com.officetracker.ui.components.SectionCard
import com.officetracker.ui.components.StatTile
import com.officetracker.ui.components.StatusPill
import com.officetracker.ui.components.color
import com.officetracker.ui.components.label
import com.officetracker.ui.components.rememberFeatures
import com.officetracker.ui.components.rememberNow
import com.officetracker.ui.theme.Brand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth

data class MonthState(
    val month: YearMonth,
    val days: List<Workday> = emptyList(),
    val visits: Map<String, Int> = emptyMap(),
    val loading: Boolean = true,
)

/**
 * Own history. Days on this phone and days in the cloud (e.g. recorded on a previous phone)
 * are merged, keeping whichever copy was updated last.
 */
class HistoryViewModel(private val c: AppContainer, private val uid: String) : ViewModel() {
    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    val state: StateFlow<MonthState> = _month.flatMapLatest { ym ->
        val (from, to) = Dates.monthRange(ym)
        val cloud = flow<List<Workday>?> {
            emit(null)
            c.workdays.repair(uid, from, to)
            emit(c.cloudDays.fetchWorkdays(uid, from, to).getOrDefault(emptyList()))
        }
        combine(c.workdays.observeWorkdays(uid, from, to), c.workdays.observeStayCounts(uid, from, to), cloud) { local, visits, remote ->
            val merged = (local + remote.orEmpty()) // local first: wins ties
                .groupBy { it.date }
                .map { (_, copies) -> copies.maxBy { it.updatedAt } }
                .sortedByDescending { it.date }
            MonthState(ym, merged, visits, loading = remote == null && local.isEmpty())
        }.onStart { emit(MonthState(ym)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MonthState(YearMonth.now()))

    fun shift(months: Long) {
        val next = _month.value.plusMonths(months)
        if (!next.isAfter(YearMonth.now())) _month.value = next
    }

    fun schedule(profile: UserProfile): WorkSchedule? = profile.effectiveSchedule(c.org.config.value.schedule)

    fun export(profile: UserProfile, s: MonthState) = c.reports.shareIntent(
        c.reports.monthReport(profile, s.month, s.days, s.visits, c.org.config.value.ratePerKm, schedule(profile)),
        "Monthly report ${Dates.month(s.month)}",
    )
}

@Composable
fun HistoryScreen(profile: UserProfile, onOpenDay: (String) -> Unit, onBack: (() -> Unit)?) {
    val vm = appViewModel(key = "history-${profile.uid}") { HistoryViewModel(it, profile.uid) }
    val state by vm.state.collectAsStateWithLifecycle()
    val config by OfficeConfig.collect()
    val context = LocalContext.current
    val now = rememberNow()
    val features by rememberFeatures()
    val schedule = vm.schedule(profile)?.takeIf { features.scheduler }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My history") },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (features.reports) {
                        IconButton(onClick = { context.startActivity(vm.export(profile, state)) }, enabled = state.days.isNotEmpty()) {
                            Icon(Icons.Default.Share, contentDescription = "Export month")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { MonthSwitcher(state.month, onPrev = { vm.shift(-1) }, onNext = { vm.shift(1) }) }
            item { MonthCalendar(state.month, state.days, schedule, onOpenDay) }
            item { MonthTotals(state.days, config.ratePerKm, now, schedule) }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!state.loading && state.days.isEmpty()) item {
                EmptyState(Icons.Default.CalendarMonth, "No workdays", "Nothing was recorded in ${Dates.month(state.month)}.")
            }
            items(state.days, key = { it.date }) { d ->
                WorkdayRow(d, state.visits[d.date], config.ratePerKm, now, schedule) { onOpenDay(d.date) }
            }
        }
    }
}

@Composable
fun MonthSwitcher(month: YearMonth, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) { Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month") }
        Text(Dates.month(month), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        IconButton(onClick = onNext, enabled = month.isBefore(YearMonth.now())) {
            Icon(Icons.Default.ChevronRight, contentDescription = "Next month")
        }
    }
}

/** Month grid (Saturday first): green = worked, amber = late, red outline = missed working day. */
@Composable
fun MonthCalendar(month: YearMonth, days: List<Workday>, schedule: WorkSchedule?, onOpenDay: (String) -> Unit) {
    val byDate = days.associateBy { it.date }
    val first = month.atDay(1)
    val lead = (first.dayOfWeek.value + 1) % 7 // Saturday = 0
    val today = Dates.today()
    SectionCard(padding = PaddingValues(10.dp)) {
        Column {
            Row(Modifier.fillMaxWidth()) {
                listOf("Sa", "Su", "Mo", "Tu", "We", "Th", "Fr").forEach {
                    Text(
                        it, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            val cells = lead + month.lengthOfMonth()
            val rows = (cells + 6) / 7
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth()) {
                    for (col in 0 until 7) {
                        val dayNum = r * 7 + col - lead + 1
                        Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                            if (dayNum in 1..month.lengthOfMonth()) {
                                val date = month.atDay(dayNum)
                                val key = Dates.key(date)
                                val w = byDate[key]
                                val late = w != null && (schedule?.lateMinutes(w.startedAt, Dates.zone) ?: 0) > 0
                                val missed = w == null && schedule != null && date.isBefore(today) && schedule.isWorkDay(date)
                                val fill = when {
                                    w == null -> Color.Transparent
                                    late -> Brand.Warning.copy(alpha = 0.25f)
                                    else -> Brand.Success.copy(alpha = 0.25f)
                                }
                                Box(
                                    Modifier.fillMaxSize().clip(CircleShape).background(fill)
                                        .then(if (missed) Modifier.border(1.dp, Brand.Danger.copy(alpha = 0.6f), CircleShape) else Modifier)
                                        .then(if (date == today) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                                        .then(if (w != null) Modifier.clickable { onOpenDay(key) } else Modifier),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        dayNum.toString(),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (w != null) FontWeight.Bold else FontWeight.Normal,
                                        color = if (date.isAfter(today)) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LegendDot(Brand.Success, "Worked")
                if (schedule != null) {
                    LegendDot(Brand.Warning, "Late")
                    LegendDot(Brand.Danger, "Missed", outline = true)
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String, outline: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(10.dp).clip(CircleShape)
                .then(if (outline) Modifier.border(1.dp, color, CircleShape) else Modifier.background(color.copy(alpha = 0.5f)))
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun MonthTotals(days: List<Workday>, ratePerKm: Double, now: Long, schedule: WorkSchedule? = null) {
    val features by rememberFeatures()
    val meters = days.sumOf { it.distanceMeters }
    val hours = days.sumOf { it.activeMillis(now) }
    val lateDays = if (schedule == null) 0 else days.count { schedule.lateMinutes(it.startedAt, Dates.zone) > 0 }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                "Days worked", days.size.toString() + if (lateDays > 0) " ($lateDays late)" else "",
                Icons.Default.CalendarMonth, Modifier.weight(1f),
            )
            StatTile("Time on duty", Dates.duration(hours), Icons.Default.Schedule, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Distance", Format.distance(meters), Icons.Default.Route, Modifier.weight(1f), tint = Brand.Route)
            if (features.allowance) {
                StatTile("Allowance", Format.taka(Format.allowance(meters, ratePerKm)), Icons.Default.Payments, Modifier.weight(1f), tint = Brand.Warning)
            } else {
                StatTile("Avg / day", Format.distance(if (days.isEmpty()) 0.0 else meters / days.size), Icons.Default.Route, Modifier.weight(1f), tint = Brand.Warning)
            }
        }
    }
}

@Composable
fun WorkdayRow(d: Workday, visits: Int?, ratePerKm: Double, now: Long, schedule: WorkSchedule? = null, onClick: () -> Unit) {
    val features by rememberFeatures()
    val late = schedule?.lateMinutes(d.startedAt, Dates.zone) ?: 0
    SectionCard(Modifier.clickable(onClick = onClick), padding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Dates.friendlyDay(d.date), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (late > 0) StatusPill("Late ${Dates.duration(late * 60_000L)}", Brand.Warning, Modifier.padding(end = 6.dp))
                if (d.status.isOnDuty) StatusPill(d.status.label(), d.status.color())
            }
            PlaceLine(Brand.Success, Dates.time(d.startedAt), d.startName ?: "Start")
            val ended = d.endedAt
            if (ended != null) PlaceLine(Brand.Danger, Dates.time(ended), d.endName ?: "End")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Metric(Icons.Default.Schedule, Dates.duration(d.activeMillis(now)))
                Metric(Icons.Default.Route, Format.distance(d.distanceMeters))
                Metric(Icons.Default.Place, "${visits ?: 0} visits")
                if (features.allowance) Metric(Icons.Default.Payments, Format.taka(Format.allowance(d.distanceMeters, ratePerKm)))
            }
        }
    }
}

@Composable
private fun PlaceLine(color: Color, time: String, place: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(time, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(
            "  $place", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Metric(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

/** Small helper so screens can read the organisation settings. */
object OfficeConfig {
    @Composable
    fun collect() = com.officetracker.OfficeTrackerApp.container.org.config.collectAsStateWithLifecycle()
}
