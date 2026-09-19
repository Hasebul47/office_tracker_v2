@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.officetracker.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.officetracker.AppContainer
import com.officetracker.core.model.LiveState
import com.officetracker.core.model.Role
import com.officetracker.core.model.UserProfile
import com.officetracker.core.model.WorkStatus
import com.officetracker.core.util.Dates
import com.officetracker.core.util.Format
import com.officetracker.core.util.Phone
import com.officetracker.report.TeamReportRow
import com.officetracker.ui.appViewModel
import com.officetracker.ui.components.Avatar
import com.officetracker.ui.components.EmptyState
import com.officetracker.ui.components.MapMarker
import com.officetracker.ui.components.OsmMap
import com.officetracker.ui.components.ScreenMessage
import com.officetracker.ui.components.SectionCard
import com.officetracker.ui.components.StatusPill
import com.officetracker.ui.components.rememberNow
import com.officetracker.ui.components.statusColor
import com.officetracker.ui.components.statusLabel
import com.officetracker.ui.history.MonthSwitcher
import com.officetracker.ui.theme.Brand
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.time.YearMonth

data class TeamMember(val profile: UserProfile, val live: LiveState?)

enum class TeamFilter(val label: String) { ALL("All"), ON_DUTY("On duty"), PAUSED("Paused"), ALERTS("Alerts"), OFF("Off duty") }

class TeamViewModel(private val c: AppContainer) : ViewModel() {
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set

    val members: StateFlow<List<TeamMember>> = combine(
        c.users.observeUsers().catch { message = it.message; emit(emptyList()) },
        c.org.observeLive().catch { emit(emptyList()) },
    ) { users, live ->
        loaded = true
        val byUid = live.associateBy { it.uid }
        users.map { TeamMember(it, byUid[it.uid]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createEmployee(name: String, phone: String, password: String, department: String, role: Role, onDone: () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            c.users.createEmployee(name, phone, password, department, role)
                .onSuccess {
                    message = "${it.name} can now sign in with ${Phone.pretty(it.phone)} and the password you set."
                    onDone()
                }
                .onFailure { message = it.message }
            busy = false
        }
    }

    fun exportTeam(month: YearMonth, onReady: (android.content.Intent) -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val (from, to) = Dates.monthRange(month)
                val rows = members.value.filter { !it.profile.disabled }.map { m ->
                    async { TeamReportRow(m.profile, c.cloudDays.fetchWorkdays(m.profile.uid, from, to).getOrDefault(emptyList())) }
                }.awaitAll()
                val file = c.reports.teamReport(month, rows, c.org.config.value.ratePerKm)
                onReady(c.reports.shareIntent(file, "Team report ${Dates.month(month)}"))
            } catch (e: Exception) {
                message = e.message ?: "Could not build the report."
            } finally {
                busy = false
            }
        }
    }
}

private fun TeamMember.matches(filter: TeamFilter, now: Long): Boolean = when (filter) {
    TeamFilter.ALL -> true
    TeamFilter.ON_DUTY -> live?.let { it.status == WorkStatus.ACTIVE && !it.isStale(now) } == true
    TeamFilter.PAUSED -> live?.status == WorkStatus.PAUSED
    TeamFilter.ALERTS -> hasAlert(now)
    TeamFilter.OFF -> live?.status?.isOnDuty != true
}

private fun TeamMember.hasAlert(now: Long): Boolean {
    val l = live ?: return false
    if (!l.status.isOnDuty) return false
    return l.isStale(now) || l.mockLocation || !l.gpsEnabled || (l.batteryPercent ?: 100) <= 15
}

@Composable
fun TeamScreen(onOpenEmployee: (String) -> Unit) {
    val vm = appViewModel(key = "team") { TeamViewModel(it) }
    val members by vm.members.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val now = rememberNow()
    var tab by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(TeamFilter.ALL) }
    var adding by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Team") },
                actions = {
                    IconButton(onClick = { reporting = true }) { Icon(Icons.Default.Summarize, contentDescription = "Monthly team report") }
                },
            )
        },
        floatingActionButton = {
            if (tab == 1) {
                ExtendedFloatingActionButton(
                    onClick = { adding = true },
                    icon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                    text = { Text("Add employee") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Live map") }, icon = { Icon(Icons.Default.Map, null) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("People") }, icon = { Icon(Icons.AutoMirrored.Filled.List, null) })
            }
            if (!vm.loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { ScreenMessage(vm.message) { vm.message = null } }
            SummaryRow(members, now)
            when (tab) {
                0 -> LiveMap(members, now, onOpenEmployee)
                else -> PeopleList(
                    members = members.filter { m ->
                        m.matches(filter, now) && (query.isBlank() || m.profile.name.contains(query, true) ||
                            m.profile.phone.contains(query.filter { it.isDigit() }.ifEmpty { "\u0000" }) ||
                            m.profile.department.contains(query, true))
                    },
                    now = now, query = query, onQuery = { query = it }, filter = filter, onFilter = { filter = it },
                    onOpen = onOpenEmployee,
                )
            }
        }
    }

    if (adding) {
        AddEmployeeDialog(busy = vm.busy, onDismiss = { adding = false }) { name, phone, pass, dept, role ->
            vm.createEmployee(name, phone, pass, dept, role) { adding = false }
        }
    }
    if (reporting) {
        TeamReportDialog(busy = vm.busy, onDismiss = { reporting = false }) { month ->
            vm.exportTeam(month) { intent -> reporting = false; context.startActivity(intent) }
        }
    }
}

@Composable
private fun SummaryRow(members: List<TeamMember>, now: Long) {
    val active = members.count { m -> m.live?.let { it.status == WorkStatus.ACTIVE && !it.isStale(now) } == true }
    val paused = members.count { it.live?.status == WorkStatus.PAUSED }
    val alerts = members.count { it.hasAlert(now) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusPill("$active on duty", Brand.Success)
        StatusPill("$paused paused", Brand.Warning)
        if (alerts > 0) StatusPill("$alerts alerts", Brand.Danger)
        Spacer(Modifier.weight(1f))
        Text("${members.count { !it.profile.disabled }} staff", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LiveMap(members: List<TeamMember>, now: Long, onOpen: (String) -> Unit) {
    val markers = members.mapNotNull { m ->
        val l = m.live ?: return@mapNotNull null
        if (l.latitude == null || l.longitude == null || !l.status.isOnDuty) return@mapNotNull null
        MapMarker(
            id = m.profile.uid, latitude = l.latitude, longitude = l.longitude,
            title = m.profile.name,
            snippet = "${l.statusLabel(now)} · ${Dates.ago(l.updatedAt, now)}" + (l.placeName?.let { " · $it" } ?: ""),
            color = l.statusColor(now), label = m.profile.initials, pulse = l.status == WorkStatus.ACTIVE && !l.isStale(now),
        )
    }
    Box(Modifier.fillMaxSize().padding(top = 8.dp)) {
        OsmMap(Modifier.fillMaxSize(), markers = markers, fitKey = markers.size, onMarkerClick = onOpen)
        if (markers.isEmpty()) {
            SectionCard(Modifier.align(Alignment.Center).padding(24.dp)) {
                Text("Nobody is on duty right now.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PeopleList(
    members: List<TeamMember>,
    now: Long,
    query: String,
    onQuery: (String) -> Unit,
    filter: TeamFilter,
    onFilter: (TeamFilter) -> Unit,
    onOpen: (String) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedTextField(
                value = query, onValueChange = onQuery, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search name, phone or department") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
            )
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TeamFilter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { onFilter(f) }, label = { Text(f.label) })
                }
            }
        }
        if (members.isEmpty()) item {
            EmptyState(Icons.Default.Groups, "No one here", "Try another filter, or add an employee.")
        }
        items(members, key = { it.profile.uid }) { m -> MemberCard(m, now) { onOpen(m.profile.uid) } }
    }
}

@Composable
private fun MemberCard(m: TeamMember, now: Long, onClick: () -> Unit) {
    val l = m.live
    val color = if (m.profile.disabled) Brand.Muted else l.statusColor(now)
    SectionCard(Modifier.clickable(onClick = onClick), padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(m.profile.initials, color)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.profile.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (m.profile.isAdmin) Text("  Admin", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    listOfNotNull(
                        m.profile.department.takeIf { it.isNotBlank() },
                        l?.takeIf { it.status.isOnDuty }?.placeName,
                        l?.let { Dates.ago(it.updatedAt, now) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (l != null && l.status.isOnDuty) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        l.batteryPercent?.let { b ->
                            val icon = when {
                                l.charging -> Icons.Default.BatteryChargingFull
                                b <= 15 -> Icons.Default.BatteryAlert
                                else -> Icons.Default.BatteryFull
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(icon, null, Modifier.size(14.dp), tint = if (b <= 15 && !l.charging) Brand.Danger else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("$b%", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Text(Format.distance(l.distanceMeters), style = MaterialTheme.typography.labelSmall)
                        if (!l.gpsEnabled) Icon(Icons.Default.GpsOff, "GPS off", Modifier.size(14.dp), tint = Brand.Danger)
                        if (l.mockLocation) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, null, Modifier.size(14.dp), tint = Brand.Danger)
                                Text(" Fake GPS", style = MaterialTheme.typography.labelSmall, color = Brand.Danger)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            StatusPill(if (m.profile.disabled) "Deactivated" else l.statusLabel(now), color)
        }
    }
}

@Composable
private fun AddEmployeeDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String, Role) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf(generatePassword()) }
    var department by remember { mutableStateOf("Field Operations") }
    var role by remember { mutableStateOf(Role.EMPLOYEE) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Add employee") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(50) }, label = { Text("Full name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    phone, { phone = it.filter { c -> c.isDigit() || c == '+' }.take(14) }, label = { Text("Mobile number") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone),
                    isError = phone.isNotEmpty() && !Phone.isValid(phone),
                )
                OutlinedTextField(
                    password, { password = it.take(32) }, label = { Text("Initial password") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { IconButton(onClick = { password = generatePassword() }) { Icon(Icons.Default.Casino, "Generate") } },
                    supportingText = { Text("Share it with the employee; they can change it in Profile.") },
                )
                OutlinedTextField(department, { department = it.take(40) }, label = { Text("Department") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = role == Role.EMPLOYEE, onClick = { role = Role.EMPLOYEE }, label = { Text("Employee") })
                    FilterChip(selected = role == Role.ADMIN, onClick = { role = Role.ADMIN }, label = { Text("Administrator") })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, phone, password, department, role) },
                enabled = !busy && name.isNotBlank() && Phone.isValid(phone) && password.length >= 6,
            ) { Text(if (busy) "Creating…" else "Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun TeamReportDialog(busy: Boolean, onDismiss: () -> Unit, onExport: (YearMonth) -> Unit) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Team report") },
        text = {
            Column {
                Text("Hours, distance and travel allowance for every employee, as a spreadsheet (CSV).", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                MonthSwitcher(month, onPrev = { month = month.minusMonths(1) }, onNext = { if (month.isBefore(YearMonth.now())) month = month.plusMonths(1) })
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onExport(month) }, enabled = !busy) { Text("Export") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

private fun generatePassword(): String {
    val alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val rnd = SecureRandom()
    return (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
}

