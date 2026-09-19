@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)

package com.officetracker.ui.day

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.officetracker.AppContainer
import com.officetracker.core.model.DayDetail
import com.officetracker.core.model.PlaceCategory
import com.officetracker.core.model.Stay
import com.officetracker.core.model.Timeline
import com.officetracker.core.model.UserProfile
import com.officetracker.core.util.Dates
import com.officetracker.ui.appViewModel
import com.officetracker.ui.components.DayMapCard
import com.officetracker.ui.components.DayStats
import com.officetracker.ui.components.EditStayDialog
import com.officetracker.ui.components.EmptyState
import com.officetracker.ui.components.ScreenMessage
import com.officetracker.ui.components.SectionTitle
import com.officetracker.ui.components.StatusPill
import com.officetracker.ui.components.color
import com.officetracker.ui.components.label
import com.officetracker.ui.components.rememberNow
import com.officetracker.ui.components.TimelineStatic
import com.officetracker.ui.history.OfficeConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset

data class DayState(
    val date: String,
    val detail: DayDetail? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val local: Boolean = false,
)

/**
 * One day of one employee. The employee's own days come live from the phone's database;
 * anything else (administrator view, or a day recorded on another phone) comes from the cloud.
 */
class DayViewModel(
    private val c: AppContainer,
    private val uid: String,
    initialDate: String,
    private val isSelf: Boolean,
) : ViewModel() {
    private val date = MutableStateFlow(initialDate.ifBlank { Dates.todayKey() })
    private val reload = MutableStateFlow(0)

    val state: StateFlow<DayState> = combine(date, reload) { d, _ -> d }
        .flatMapLatest { d ->
            flow {
                emit(DayState(d))
                if (isSelf && c.workdays.hasLocalDay(uid, d)) {
                    emitAll(c.workdays.observeDay(uid, d).map { DayState(d, it, loading = false, local = true) })
                } else {
                    val result = c.cloudDays.fetchDay(uid, d)
                    emit(
                        DayState(
                            d, result.getOrNull(), loading = false,
                            error = result.exceptionOrNull()?.message,
                        )
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayState(initialDate))

    fun setDate(key: String) {
        if (key <= Dates.todayKey()) date.value = key
    }

    fun shift(days: Long) = setDate(Dates.key(Dates.parse(date.value).plusDays(days)))

    fun refresh() { reload.value++ }

    fun relabel(stay: Stay, name: String, category: PlaceCategory) {
        viewModelScope.launch { c.workdays.relabelStay(stay.id, name, category) }
    }

    fun share(profile: UserProfile, detail: DayDetail) =
        c.reports.shareIntent(c.reports.dayReport(profile, detail, c.org.config.value.ratePerKm), "Workday report")
}

@Composable
fun DayDetailScreen(viewer: UserProfile, uid: String, initialDate: String, onBack: () -> Unit) {
    val isSelf = uid == viewer.uid
    val vm = appViewModel(key = "day-$uid") { DayViewModel(it, uid, initialDate, isSelf) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Dates.friendlyDay(state.date)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (!state.local) IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, "Refresh") }
                    val d = state.detail
                    if (d?.workday != null) {
                        IconButton(onClick = { context.startActivity(vm.share(viewer, d)) }) { Icon(Icons.Default.Share, "Share report") }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            dayContent(
                state = state,
                onShift = vm::shift,
                onPick = vm::setDate,
                onRelabel = if (state.local) vm::relabel else null,
            )
        }
    }
}

/** Date switcher + stats + map + timeline, shared with the administrator's employee screen. */
fun LazyListScope.dayContent(
    state: DayState,
    onShift: (Long) -> Unit,
    onPick: (String) -> Unit,
    onRelabel: ((Stay, String, PlaceCategory) -> Unit)?,
) {
    item { DateSwitcher(state.date, onShift, onPick) }
    if (state.loading) {
        item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        return
    }
    state.error?.let { msg -> item { ScreenMessage(msg) {} } }
    val detail = state.detail
    if (detail?.workday == null) {
        item { EmptyState(Icons.Default.EventBusy, "No workday", "Nothing was recorded on ${Dates.day(state.date)}.") }
        return
    }
    item { DayBody(detail, onRelabel) }
}

@Composable
private fun DayBody(detail: DayDetail, onRelabel: ((Stay, String, PlaceCategory) -> Unit)?) {
    val config by OfficeConfig.collect()
    val now = rememberNow()
    var editing by remember { mutableStateOf<Stay?>(null) }
    val timeline = remember(detail) { Timeline.build(detail.stays, detail.route) }
    val day = detail.workday ?: return
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(day.status.label(), day.status.color())
            Text(
                "  ${Dates.time(day.startedAt)} – ${day.endedAt?.let { Dates.time(it) } ?: "now"}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DayStats(day, detail.stays.size, config.ratePerKm, now)
        DayMapCard(detail, fitKey = day.date)
        SectionTitle("Visits")
        if (timeline.isEmpty()) {
            EmptyState(Icons.Default.CalendarMonth, "No visits", "No stop longer than ${config.minStayMinutes} minutes was recorded.")
        } else {
            // A nested lazy list is not allowed; the day's timeline is short enough to lay out directly.
            TimelineStatic(timeline, now, onStayClick = if (onRelabel != null) ({ editing = it }) else null)
        }
    }
    editing?.let { stay ->
        EditStayDialog(stay, onSave = { n, cat -> onRelabel?.invoke(stay, n, cat) }, onDismiss = { editing = null })
    }
}

@Composable
fun DateSwitcher(date: String, onShift: (Long) -> Unit, onPick: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onShift(-1) }) { Icon(Icons.Default.ChevronLeft, "Previous day") }
        TextButton(onClick = { picking = true }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.CalendarMonth, contentDescription = null)
            Text("  " + Dates.day(date), textAlign = TextAlign.Center)
        }
        IconButton(onClick = { onShift(1) }, enabled = date < Dates.todayKey()) { Icon(Icons.Default.ChevronRight, "Next day") }
    }
    if (picking) {
        val todayUtc = Dates.today().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = Dates.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayUtc
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onPick(Dates.key(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()))
                    }
                    picking = false
                }) { Text("Show") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}
