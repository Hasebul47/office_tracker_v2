@file:OptIn(ExperimentalCoroutinesApi::class)

package com.officetracker.ui.today

import androidx.compose.foundation.layout.Arrangement
import com.officetracker.core.model.effectiveSchedule
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.officetracker.AppContainer
import com.officetracker.core.model.DayDetail
import com.officetracker.core.model.PlaceCategory
import com.officetracker.core.model.Stay
import com.officetracker.core.model.Timeline
import com.officetracker.core.model.UserProfile
import com.officetracker.core.model.WorkStatus
import com.officetracker.core.model.Workday
import com.officetracker.core.model.WorkSchedule
import com.officetracker.ui.components.summary
import com.officetracker.ui.components.autostartIntent
import com.officetracker.core.model.effectiveDistance
import com.officetracker.core.util.Dates
import com.officetracker.ui.appViewModel
import com.officetracker.ui.components.Banner
import com.officetracker.ui.components.ConfirmDialog
import com.officetracker.ui.components.DayMapCard
import com.officetracker.ui.components.DayStats
import com.officetracker.ui.components.EditStayDialog
import com.officetracker.ui.components.EmptyState
import com.officetracker.ui.components.LoadingButton
import com.officetracker.ui.components.ScreenMessage
import com.officetracker.ui.components.SectionCard
import com.officetracker.ui.components.SectionTitle
import com.officetracker.ui.components.StatusPill
import com.officetracker.ui.components.batteryOptimizationIntent
import com.officetracker.ui.components.color
import com.officetracker.ui.components.hasBackgroundLocation
import com.officetracker.ui.components.hasNotificationPermission
import com.officetracker.ui.components.hasPreciseLocation
import com.officetracker.ui.components.isIgnoringBatteryOptimizations
import com.officetracker.ui.components.label
import com.officetracker.ui.components.appSettingsIntent
import com.officetracker.ui.components.rememberBackgroundLocationAction
import com.officetracker.ui.components.rememberLocationReadyAction
import com.officetracker.ui.components.rememberFeatures
import com.officetracker.ui.components.rememberNow
import com.officetracker.ui.components.rememberResumeTick
import com.officetracker.ui.components.safeStart
import com.officetracker.ui.components.timelineItems
import com.officetracker.ui.theme.Brand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime

enum class DayAction { START, PAUSE, RESUME, END }

class TodayViewModel(private val c: AppContainer, private val uid: String) : ViewModel() {

    private val current = c.workdays.observeCurrent(uid, Dates.todayKey())

    /** null until the first database read completes. */
    val detail: StateFlow<DayDetail?> = current
        .map { it?.date ?: Dates.todayKey() }
        .distinctUntilChanged()
        .flatMapLatest { date -> c.workdays.observeDay(uid, date) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val config = c.org.config
    val pendingUploads: StateFlow<Int> = c.workdays.observePendingUploads(uid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    var busy by mutableStateOf<DayAction?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    fun act(action: DayAction) {
        if (busy != null) return
        busy = action
        viewModelScope.launch {
            val result = when (action) {
                DayAction.START -> c.tracking.startDay()
                DayAction.PAUSE -> c.tracking.pause()
                DayAction.RESUME -> c.tracking.resume()
                DayAction.END -> c.tracking.endDay()
            }
            result.onFailure { message = it.message ?: "Could not update your workday." }
            busy = null
        }
    }

    fun relabel(stay: Stay, name: String, category: PlaceCategory) {
        viewModelScope.launch { c.workdays.relabelStay(stay.id, name, category) }
    }

    fun shareDay(profile: UserProfile, detail: DayDetail) =
        c.reports.shareIntent(c.reports.dayReport(profile, detail, config.value.ratePerKm), "Workday report")
}

@Composable
fun TodayScreen(profile: UserProfile, onOpenHistory: () -> Unit) {
    val vm = appViewModel(key = "today-${profile.uid}") { TodayViewModel(it, profile.uid) }
    val detail by vm.detail.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val pending by vm.pendingUploads.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val now = rememberNow(15_000)
    val tick = rememberResumeTick()
    val features by rememberFeatures()

    var editing by remember { mutableStateOf<Stay?>(null) }
    var confirmEnd by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<DayAction?>(null) }
    val ensureLocation = rememberLocationReadyAction { pendingAction?.let { vm.act(it) } }
    val askBackground = rememberBackgroundLocationAction()

    val d = detail
    if (d == null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
        }
        return
    }
    val day = d.workday
    val status = day?.status ?: WorkStatus.NOT_STARTED
    val timeline = remember(d) { Timeline.build(d.stays, d.route) }

    // Re-evaluated on every resume (user may come back from Settings).
    val permissionState = remember(tick) {
        PermissionSnapshot(
            precise = context.hasPreciseLocation(),
            background = context.hasBackgroundLocation(),
            notifications = context.hasNotificationPermission(),
            batteryOk = context.isIgnoringBatteryOptimizations(),
            gpsOn = com.officetracker.OfficeTrackerApp.container.locationClient.isLocationEnabled(),
        )
    }

    val autoSchedule = profile.effectiveSchedule(config.schedule)?.takeIf { features.scheduler }
    var autostartHidden by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(greeting() + ", " + profile.name.substringBefore(' '), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Dates.day(Dates.todayKey()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SyncBadge(pending)
                IconButton(onClick = onOpenHistory) { Icon(Icons.Default.History, contentDescription = "History") }
            }
        }

        item { ScreenMessage(vm.message) { vm.message = null } }

        // Anything that will stop tracking from working properly.
        if (status == WorkStatus.ACTIVE && !permissionState.precise) item {
            Banner("Location permission was removed - tracking has stopped.", Icons.Default.Warning, Brand.Danger, actionLabel = "Fix") {
                pendingAction = DayAction.RESUME; ensureLocation()
            }
        }
        if (status.isOnDuty && !permissionState.gpsOn) item {
            Banner("Location (GPS) is off. Your route is not being recorded.", Icons.Default.GpsOff, Brand.Danger, actionLabel = "Turn on") {
                pendingAction = null; ensureLocation()
            }
        }
        if (status.isOnDuty && permissionState.precise && !permissionState.background) item {
            Banner("Allow location \"All the time\" so tracking survives restarts.", Icons.Default.LocationOn, Brand.Warning, actionLabel = "Allow") { askBackground() }
        }
        if (status.isOnDuty && !permissionState.batteryOk) item {
            Banner("Battery optimisation may stop tracking. Allow the app to run in the background.", Icons.Default.BatterySaver, Brand.Warning, actionLabel = "Allow") {
                context.safeStart(context.batteryOptimizationIntent())
            }
        }
        if (status.isOnDuty && !permissionState.notifications) item {
            Banner("Notifications are off, so you won't see the tracking status.", Icons.Default.NotificationsOff, Brand.Muted, actionLabel = "Settings") {
                context.safeStart(context.appSettingsIntent())
            }
        }
        if (day != null && status.isOnDuty && day.date != Dates.todayKey()) item {
            Banner("Your workday from ${Dates.shortDay(day.date)} is still open.", Icons.Default.Warning, Brand.Warning, actionLabel = "End it") { confirmEnd = true }
        }

        item {
            WorkdayCard(
                day = day, status = status, now = now, busy = vm.busy,
                locked = autoSchedule?.let { it.enforce && it.isWithinHours(now, Dates.zone) } == true,
                onStart = { pendingAction = DayAction.START; ensureLocation() },
                onPause = { vm.act(DayAction.PAUSE) },
                onResume = { pendingAction = DayAction.RESUME; ensureLocation() },
                onEnd = { confirmEnd = true },
            )
        }

        if (autoSchedule != null) {
            if (autoSchedule.autoStart && permissionState.precise && !permissionState.background) item {
                Banner(
                    "Allow location \"All the time\" so your workday can start automatically at ${WorkSchedule.formatMinute(autoSchedule.startMinute)}.",
                    Icons.Default.LocationOn, Brand.Warning, actionLabel = "Allow",
                ) { askBackground() }
            }
            if (!autostartHidden) {
                context.autostartIntent()?.let { intent ->
                    item {
                        Banner(
                            "${android.os.Build.MANUFACTURER} phones block automatic start. Turn ON \"Autostart\" for Office Tracker.",
                            Icons.Default.Warning, Brand.Warning, actionLabel = "Open",
                        ) { context.safeStart(intent); autostartHidden = true }
                    }
                }
            }
            item {
                Text(
                    "Schedule: ${autoSchedule.summary()}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            val schedule = profile.effectiveSchedule(config.schedule)?.takeIf { features.scheduler }
            DayStats(
                day, d.stays.size, config.ratePerKm, now,
                distanceMeters = d.effectiveDistance(),
                lateMinutes = day?.let { schedule?.lateMinutes(it.startedAt, Dates.zone) } ?: 0,
            )
        }
        item { DayMapCard(d) }
        item {
            SectionTitle("Visits") {
                if (day != null && features.reports) {
                    IconButton(onClick = { context.startActivity(vm.shareDay(profile, d)) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share report")
                    }
                }
            }
        }
        if (timeline.isEmpty()) {
            item {
                EmptyState(
                    Icons.Default.Place, "No visits yet",
                    if (status == WorkStatus.ACTIVE) "Stay at a place for ${config.minStayMinutes} minutes and it will appear here."
                    else "Start your day and the places you visit are recorded automatically.",
                )
            }
        } else {
            timelineItems(timeline, now, onStayClick = { editing = it })
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    // A work schedule starts tracking by itself, which Android only allows with "Allow all the time".
    if (autoSchedule != null && autoSchedule.autoStart && permissionState.precise && !permissionState.background) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Allow location all the time") },
            text = {
                Text(
                    "Your company starts tracking automatically at ${WorkSchedule.formatMinute(autoSchedule.startMinute)}. " +
                        "Android only allows that if Office Tracker may use location \"All the time\". " +
                        "On the next screen choose Permissions > Location > Allow all the time."
                )
            },
            confirmButton = { TextButton(onClick = { askBackground() }) { Text("Allow") } },
        )
    }

    editing?.let { stay ->
        EditStayDialog(stay, onSave = { name, cat -> vm.relabel(stay, name, cat) }, onDismiss = { editing = null })
    }
    if (confirmEnd) {
        ConfirmDialog(
            title = "End your workday?",
            message = "Tracking stops and today's report is finalised. You can start again later if needed.",
            confirmLabel = "End day",
            onConfirm = { vm.act(DayAction.END) },
            onDismiss = { confirmEnd = false },
        )
    }
}

private data class PermissionSnapshot(
    val precise: Boolean,
    val background: Boolean,
    val notifications: Boolean,
    val batteryOk: Boolean,
    val gpsOn: Boolean,
)

@Composable
private fun WorkdayCard(
    day: Workday?,
    status: WorkStatus,
    now: Long,
    busy: DayAction?,
    locked: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEnd: () -> Unit,
) {
    SectionCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(status.label(), status.color())
                Spacer(Modifier.weight(1f))
                if (day != null) {
                    Text(
                        "Started ${Dates.time(day.startedAt)}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                Dates.duration(day?.activeMillis(now) ?: 0),
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp),
            )
            Text(
                when (status) {
                    WorkStatus.NOT_STARTED -> "Tap Start when you begin work."
                    WorkStatus.ACTIVE -> day?.startName?.let { "Started at $it" } ?: "Recording your route"
                    WorkStatus.PAUSED -> day?.pausedAt?.let { "Paused since ${Dates.time(it)}" } ?: "Paused"
                    WorkStatus.ENDED -> day?.endedAt?.let { t -> "Ended at ${Dates.time(t)}" + (day?.endName?.let { n -> " · $n" }.orEmpty()) } ?: "Day ended"
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))
            when (status) {
                WorkStatus.NOT_STARTED, WorkStatus.ENDED -> LoadingButton(
                    text = if (status == WorkStatus.ENDED) "Start again" else "Start my day",
                    icon = Icons.Default.PlayArrow, loading = busy == DayAction.START, onClick = onStart,
                    modifier = Modifier.fillMaxWidth(), containerColor = Brand.Success,
                )
                WorkStatus.ACTIVE -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onPause, enabled = busy == null && !locked, modifier = Modifier.weight(1f).height(52.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pause")
                    }
                    LoadingButton(
                        text = "End day", icon = Icons.Default.Stop, loading = busy == DayAction.END, onClick = onEnd,
                        modifier = Modifier.weight(1f), containerColor = Brand.Danger, enabled = !locked,
                    )
                }
                WorkStatus.PAUSED -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LoadingButton(
                        text = "Resume", icon = Icons.Default.PlayArrow, loading = busy == DayAction.RESUME, onClick = onResume,
                        modifier = Modifier.weight(1f), containerColor = Brand.Success,
                    )
                    LoadingButton(
                        text = "End day", icon = Icons.Default.Stop, loading = busy == DayAction.END, onClick = onEnd,
                        modifier = Modifier.weight(1f), containerColor = Brand.Danger,
                    )
                }
            }
            if (locked && status == WorkStatus.ACTIVE) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tracking is required during working hours. It ends automatically at the scheduled time.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SyncBadge(pending: Int) {
    if (pending == 0) {
        Icon(Icons.Default.CloudDone, contentDescription = "All data uploaded", tint = Brand.Success, modifier = Modifier.padding(horizontal = 8.dp))
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            Icon(Icons.Default.CloudUpload, contentDescription = "Waiting to upload", tint = Brand.Warning, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (pending > 99) "99+" else pending.toString(), style = MaterialTheme.typography.labelMedium, color = Brand.Warning)
        }
    }
}

private fun greeting(): String {
    val h = LocalTime.now().hour
    return when {
        h < 12 -> "Good morning"
        h < 17 -> "Good afternoon"
        else -> "Good evening"
    }
}
