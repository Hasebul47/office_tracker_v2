@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)

package com.officetracker.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Payments
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.officetracker.AppContainer
import com.officetracker.core.model.UserProfile
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

/** Own history: local database first, cloud copy for days recorded on another phone. */
class HistoryViewModel(private val c: AppContainer, private val uid: String) : ViewModel() {
    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    val state: StateFlow<MonthState> = _month.flatMapLatest { ym ->
        val (from, to) = Dates.monthRange(ym)
        val cloud = flow<List<Workday>?> {
            emit(null)
            emit(c.cloudDays.fetchWorkdays(uid, from, to).getOrDefault(emptyList()))
        }
        combine(c.workdays.observeWorkdays(uid, from, to), c.workdays.observeStayCounts(uid, from, to), cloud) { local, visits, remote ->
            val merged = (remote.orEmpty().associateBy { it.date } + local.associateBy { it.date }).values.sortedByDescending { it.date }
            MonthState(ym, merged, visits, loading = remote == null && local.isEmpty())
        }.onStart { emit(MonthState(ym)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MonthState(YearMonth.now()))

    fun shift(months: Long) {
        val next = _month.value.plusMonths(months)
        if (!next.isAfter(YearMonth.now())) _month.value = next
    }

    fun export(profile: UserProfile, s: MonthState) = c.reports.shareIntent(
        c.reports.monthReport(profile, s.month, s.days, s.visits, c.org.config.value.ratePerKm),
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My history") },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { context.startActivity(vm.export(profile, state)) }, enabled = state.days.isNotEmpty()) {
                        Icon(Icons.Default.Share, contentDescription = "Export month")
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
            item {
                MonthSwitcher(state.month, onPrev = { vm.shift(-1) }, onNext = { vm.shift(1) })
            }
            item { MonthTotals(state.days, config.ratePerKm, now) }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!state.loading && state.days.isEmpty()) item {
                EmptyState(Icons.Default.CalendarMonth, "No workdays", "Nothing was recorded in ${Dates.month(state.month)}.")
            }
            items(state.days, key = { it.date }) { d ->
                WorkdayRow(d, state.visits[d.date], config.ratePerKm, now) { onOpenDay(d.date) }
            }
        }
    }
}

@Composable
fun MonthSwitcher(month: YearMonth, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) { Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month") }
        Text(Dates.month(month), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = onNext, enabled = month.isBefore(YearMonth.now())) {
            Icon(Icons.Default.ChevronRight, contentDescription = "Next month")
        }
    }
}

@Composable
fun MonthTotals(days: List<Workday>, ratePerKm: Double, now: Long) {
    val meters = days.sumOf { it.distanceMeters }
    val hours = days.sumOf { it.activeMillis(now) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Days worked", days.size.toString(), Icons.Default.CalendarMonth, Modifier.weight(1f))
            StatTile("Time on duty", Dates.duration(hours), Icons.Default.Schedule, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Distance", Format.distance(meters), Icons.Default.Route, Modifier.weight(1f), tint = Brand.Route)
            StatTile("Allowance", Format.taka(Format.allowance(meters, ratePerKm)), Icons.Default.Payments, Modifier.weight(1f), tint = Brand.Warning)
        }
    }
}

@Composable
fun WorkdayRow(d: Workday, visits: Int?, ratePerKm: Double, now: Long, onClick: () -> Unit) {
    SectionCard(Modifier.clickable(onClick = onClick), padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Dates.friendlyDay(d.date), style = MaterialTheme.typography.titleSmall)
                Text(
                    "${Dates.time(d.startedAt)} – ${d.endedAt?.let { Dates.time(it) } ?: "…"} · ${Dates.duration(d.activeMillis(now))}" +
                        (visits?.let { " · $it visits" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(Format.distance(d.distanceMeters), style = MaterialTheme.typography.titleSmall)
                if (d.status.isOnDuty) StatusPill(d.status.label(), d.status.color())
                else Text(Format.taka(Format.allowance(d.distanceMeters, ratePerKm)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Small helper so screens can read the organisation settings. */
object OfficeConfig {
    @Composable
    fun collect() = com.officetracker.OfficeTrackerApp.container.org.config.collectAsStateWithLifecycle()
}
