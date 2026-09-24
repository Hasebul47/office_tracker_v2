@file:OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.officetracker.ui.admin

import android.content.Intent
import com.officetracker.core.model.effectiveSchedule
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.officetracker.AppContainer
import com.officetracker.core.model.LiveState
import com.officetracker.core.model.Role
import com.officetracker.core.model.UserProfile
import com.officetracker.core.model.WorkSchedule
import com.officetracker.ui.components.ScheduleEditor
import com.officetracker.ui.components.summary
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material.icons.filled.PhonelinkErase
import androidx.compose.material.icons.filled.Schedule
import com.officetracker.core.util.Dates
import com.officetracker.core.util.Phone
import com.officetracker.ui.appViewModel
import com.officetracker.ui.components.Avatar
import com.officetracker.ui.components.ConfirmDialog
import com.officetracker.ui.components.ScreenMessage
import com.officetracker.ui.components.SectionCard
import com.officetracker.ui.components.StatusPill
import com.officetracker.ui.components.rememberNow
import com.officetracker.ui.components.statusColor
import com.officetracker.ui.components.statusLabel
import com.officetracker.ui.day.DayViewModel
import com.officetracker.ui.day.dayContent
import com.officetracker.ui.history.MonthSwitcher
import com.officetracker.ui.theme.Brand
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth

class EmployeeViewModel(private val c: AppContainer, private val uid: String) : ViewModel() {
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set

    val profile: StateFlow<UserProfile?> = c.users.observeUser(uid)
        .catch { message = it.message; emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val live: StateFlow<LiveState?> = profile
        .map { it?.companyId }
        .distinctUntilChanged()
        .flatMapLatest { cid -> if (cid == null) flowOf(null) else c.org.observeLive(cid, uid).catch { emit(null) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(name: String, department: String, role: Role, onDone: () -> Unit) = launchBusy {
        val before = profile.value ?: return@launchBusy
        c.users.updateUser(before, name, department, role).onSuccess { onDone() }.onFailure { message = it.message }
    }

    fun setSchedule(schedule: WorkSchedule?, onDone: () -> Unit) = launchBusy {
        c.users.setSchedule(uid, schedule)
            .onSuccess { message = if (schedule == null) "Now follows the company schedule." else "Personal schedule saved. Their phone updates within seconds."; onDone() }
            .onFailure { message = it.message }
    }

    /** Administrator approval of a day's overtime (stored in the cloud, phones never overwrite it). */
    fun approveOt(date: String, approved: Boolean, onDone: () -> Unit) = launchBusy {
        c.cloudDays.setOtApproved(uid, date, approved)
            .onSuccess { message = if (approved) "Overtime approved." else "Approval removed."; onDone() }
            .onFailure { message = it.message }
    }

    val attendanceSettings = c.org.config

    fun revokeDevice() = launchBusy {
        c.users.revokeDevice(uid)
            .onSuccess { message = "Signed out from their phone. They can sign in again on any phone." }
            .onFailure { message = it.message }
    }

    val companySchedule = c.org.config

    fun setDisabled(disabled: Boolean) = launchBusy {
        c.users.setDisabled(uid, disabled)
            .onSuccess { message = if (disabled) "Account deactivated. They are signed out on their next sync." else "Account re-activated." }
            .onFailure { message = it.message }
    }

    fun delete(onDone: () -> Unit) = launchBusy {
        val user = profile.value ?: return@launchBusy
        c.users.deleteUser(user).onSuccess { onDone() }.onFailure { message = it.message }
    }

    fun exportMonth(profile: UserProfile, month: YearMonth, onReady: (Intent) -> Unit) = launchBusy {
        val (from, to) = Dates.monthRange(month)
        c.cloudDays.fetchWorkdays(uid, from, to)
            .onSuccess { days ->
                val visits = days.associate { it.date to c.cloudDays.fetchStayCount(uid, it.date) }
                val file = c.reports.monthReport(
                    profile, month, days, visits, c.org.config.value.ratePerKm,
                    profile.effectiveSchedule(c.org.config.value.schedule),
                    c.org.config.value.attendance.takeIf { c.org.features.value.attendance },
                )
                onReady(c.reports.shareIntent(file, "${profile.name} · ${Dates.month(month)}"))
            }
            .onFailure { message = it.message }
    }

    private fun launchBusy(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { block() } finally { busy = false }
        }
    }
}

@Composable
fun EmployeeScreen(viewer: UserProfile, uid: String, onBack: () -> Unit) {
    val vm = appViewModel(key = "employee-$uid") { EmployeeViewModel(it, uid) }
    val dayVm = appViewModel(key = "day-$uid") { DayViewModel(it, uid, Dates.todayKey(), isSelf = uid == viewer.uid) }
    val profile by vm.profile.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val day by dayVm.state.collectAsStateWithLifecycle()
    val features by com.officetracker.OfficeTrackerApp.container.org.features.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val now = rememberNow()

    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDisable by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    var editingSchedule by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf(false) }
    val companySchedule by vm.companySchedule.collectAsStateWithLifecycle()
    val attendance by vm.attendanceSettings.collectAsStateWithLifecycle()
    val daySchedule by dayVm.schedule.collectAsStateWithLifecycle()

    val p = profile
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(p?.name ?: "Employee") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (p != null) {
                        if (viewer.isSuperAdmin || features.reports) {
                            IconButton(onClick = { reporting = true }) { Icon(Icons.Default.Summarize, "Monthly report") }
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Edit details") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; editing = true })
                                if (viewer.isSuperAdmin || features.scheduler) {
                                    DropdownMenuItem(text = { Text("Work schedule") }, leadingIcon = { Icon(Icons.Default.Schedule, null) }, onClick = { menu = false; editingSchedule = true })
                                }
                                if (p.uid != viewer.uid && p.activeDeviceId != null) {
                                    DropdownMenuItem(text = { Text("Sign out from phone") }, leadingIcon = { Icon(Icons.Default.PhonelinkErase, null) }, onClick = { menu = false; confirmRevoke = true })
                                }
                                if (p.uid != viewer.uid) {
                                    DropdownMenuItem(
                                        text = { Text(if (p.disabled) "Re-activate" else "Deactivate") },
                                        leadingIcon = { Icon(if (p.disabled) Icons.Default.CheckCircle else Icons.Default.Block, null) },
                                        onClick = { menu = false; if (p.disabled) vm.setDisabled(false) else confirmDisable = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = { menu = false; confirmDelete = true },
                                    )
                                }
                            }
                        }
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
            item { ScreenMessage(vm.message) { vm.message = null } }
            if (vm.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (p != null) item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(p.initials, live.statusColor(now), size = 52.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.department, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${Phone.pretty(p.phone)} · ${if (p.isAdmin) "Administrator" else "Employee"}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            p.activeDeviceName?.takeIf { p.activeDeviceId?.startsWith("revoked") == false }?.let { device ->
                                Text(
                                    "Phone: $device" + (p.activeSince?.let { " · since ${Dates.shortDay(Dates.keyOf(it))}" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "Schedule: " + ((p.schedule ?: companySchedule.schedule).summary()) + if (p.schedule != null) " (personal)" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            live?.let { l ->
                                Text(
                                    "Last update ${Dates.ago(l.updatedAt, now)}" + (l.batteryPercent?.let { " · battery $it%" } ?: "") +
                                        (l.appVersion?.let { " · v$it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            StatusPill(if (p.disabled) "Deactivated" else live.statusLabel(now), if (p.disabled) Brand.Muted else live.statusColor(now))
                            IconButton(onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${p.phone}"))) }
                            }) { Icon(Icons.Default.Phone, "Call") }
                        }
                    }
                }
            }
            dayContent(state = day, onShift = dayVm::shift, onPick = dayVm::setDate, onRelabel = null, schedule = daySchedule)
            val dayWorkday = day.detail?.workday
            if (features.overtime && attendance.attendance.otEnabled && dayWorkday != null && dayWorkday.otMillis > 0) {
                item {
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Overtime ${Dates.duration(dayWorkday.otMillis)}", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (dayWorkday.otApproved) "Approved for payment"
                                    else if (attendance.attendance.otRequiresApproval) "Waiting for your approval"
                                    else "Counted automatically",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                enabled = !vm.busy,
                                onClick = { vm.approveOt(day.date, !dayWorkday.otApproved) { dayVm.refresh() } },
                            ) { Text(if (dayWorkday.otApproved) "Remove" else "Approve") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (editing && p != null) {
        EditEmployeeDialog(p, vm.busy, onDismiss = { editing = false }) { name, dept, role ->
            vm.update(name, dept, role) { editing = false }
        }
    }
    if (confirmDisable && p != null) {
        ConfirmDialog(
            "Deactivate ${p.name}?", "They will be signed out and cannot record workdays until re-activated. History is kept.",
            "Deactivate", onConfirm = { vm.setDisabled(true) }, onDismiss = { confirmDisable = false }, destructive = true,
        )
    }
    if (confirmDelete && p != null) {
        ConfirmDialog(
            "Delete ${p.name}?",
            "The profile is removed and they can no longer sign in. Their tracked history stays in the cloud for your records. To reuse the phone number later, also delete the login in Firebase Console > Authentication.",
            "Delete", onConfirm = { vm.delete(onBack) }, onDismiss = { confirmDelete = false }, destructive = true,
        )
    }
    if (editingSchedule && p != null) {
        var personal by remember { mutableStateOf(p.schedule != null) }
        var draft by remember { mutableStateOf(p.schedule ?: companySchedule.schedule.copy(enabled = true)) }
        AlertDialog(
            onDismissRequest = { editingSchedule = false },
            title = { Text("Work schedule · ${p.name}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Personal schedule", modifier = Modifier.weight(1f))
                        Switch(checked = personal, onCheckedChange = { personal = it })
                    }
                    if (personal) {
                        ScheduleEditor(draft) { draft = it }
                    } else {
                        Text(
                            "Follows the company schedule: ${companySchedule.schedule.summary()}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !vm.busy && (!personal || !draft.enabled || draft.endMinute > draft.startMinute),
                    onClick = { vm.setSchedule(if (personal) draft else null) { editingSchedule = false } },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingSchedule = false }) { Text("Cancel") } },
        )
    }
    if (confirmRevoke && p != null) {
        ConfirmDialog(
            "Sign ${p.name} out?",
            "Their phone (${p.activeDeviceName ?: "current phone"}) is signed out within seconds and any active workday is paused. They can sign in again.",
            "Sign out", onConfirm = { vm.revokeDevice() }, onDismiss = { confirmRevoke = false },
        )
    }
    if (reporting && p != null) {
        var month by remember { mutableStateOf(YearMonth.now()) }
        AlertDialog(
            onDismissRequest = { reporting = false },
            title = { Text("Monthly report") },
            text = {
                Column {
                    MonthSwitcher(month, onPrev = { month = month.minusMonths(1) }, onNext = { if (month.isBefore(YearMonth.now())) month = month.plusMonths(1) })
                    if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = !vm.busy, onClick = {
                    vm.exportMonth(p, month) { intent -> reporting = false; context.startActivity(intent) }
                }) { Text("Export") }
            },
            dismissButton = { TextButton(onClick = { reporting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EditEmployeeDialog(p: UserProfile, busy: Boolean, onDismiss: () -> Unit, onSave: (String, String, Role) -> Unit) {
    var name by remember { mutableStateOf(p.name) }
    var department by remember { mutableStateOf(p.department) }
    var role by remember { mutableStateOf(p.role) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit employee") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(50) }, label = { Text("Full name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(department, { department = it.take(40) }, label = { Text("Department") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = role == Role.EMPLOYEE, onClick = { role = Role.EMPLOYEE }, label = { Text("Employee") })
                    FilterChip(selected = role == Role.ADMIN, onClick = { role = Role.ADMIN }, label = { Text("Administrator") })
                }
                Text("Phone number can't be changed - it is the login.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, department, role) }, enabled = !busy && name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
