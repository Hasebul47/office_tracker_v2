@file:OptIn(ExperimentalMaterial3Api::class)

package com.officetracker.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.RestartAlt
import com.officetracker.ui.components.autostartIntent
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import com.officetracker.core.model.AttendanceSettings
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.officetracker.AppContainer
import com.officetracker.BuildConfig
import com.officetracker.core.model.AppConfig
import com.officetracker.core.model.Company
import com.officetracker.core.model.WorkSchedule
import com.officetracker.core.util.Dates
import com.officetracker.OfficeTrackerApp
import com.officetracker.ui.components.ScheduleEditor
import com.officetracker.ui.components.exactAlarmSettingsIntent
import com.officetracker.ui.components.summary
import com.officetracker.core.model.UserProfile
import com.officetracker.core.util.Phone
import com.officetracker.ui.appViewModel
import com.officetracker.ui.components.Avatar
import com.officetracker.ui.components.ConfirmDialog
import com.officetracker.ui.components.InfoRow
import com.officetracker.ui.components.LoadingButton
import com.officetracker.ui.components.ScreenMessage
import com.officetracker.ui.components.SectionCard
import com.officetracker.ui.components.SectionTitle
import com.officetracker.ui.components.appSettingsIntent
import com.officetracker.ui.components.batteryOptimizationIntent
import com.officetracker.ui.components.hasBackgroundLocation
import com.officetracker.ui.components.hasNotificationPermission
import com.officetracker.ui.components.isIgnoringBatteryOptimizations
import com.officetracker.ui.components.rememberBackgroundLocationAction
import com.officetracker.ui.components.rememberResumeTick
import com.officetracker.ui.components.safeStart
import com.officetracker.ui.theme.Brand
import kotlinx.coroutines.launch

class ProfileViewModel(private val c: AppContainer, private val uid: String) : ViewModel() {
    val config = c.org.config
    val updates = c.updates
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    fun changePassword(current: String, new: String, confirm: String, onDone: () -> Unit) {
        if (new != confirm) { message = "New passwords do not match."; return }
        launchBusy {
            c.auth.changePassword(current, new)
                .onSuccess { message = "Password changed."; onDone() }
                .onFailure { message = it.message }
        }
    }

    fun saveConfig(config: AppConfig) = launchBusy {
        c.org.saveConfig(config).onSuccess { message = "Settings saved." }.onFailure { message = it.message }
    }

    /** Sign-out is blocked while on duty, otherwise the route would silently stop. */
    fun signOut(onDuty: () -> Unit) {
        viewModelScope.launch {
            val open = c.workdays.openWorkday(uid)
            if (open != null) onDuty() else c.auth.signOut()
        }
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
fun ProfileScreen(
    profile: UserProfile,
    company: Company,
    onOpenMyHistory: () -> Unit,
    onOpenSubscription: () -> Unit,
) {
    val vm = appViewModel(key = "profile-${profile.uid}") { ProfileViewModel(it, profile.uid) }
    val config by vm.config.collectAsStateWithLifecycle()
    val update by vm.updates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val tick = rememberResumeTick()
    val askBackground = rememberBackgroundLocationAction()

    var changingPassword by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    val background = remember(tick) { context.hasBackgroundLocation() }
    val battery = remember(tick) { context.isIgnoringBatteryOptimizations() }
    val notifications = remember(tick) { context.hasNotificationPermission() }
    val exactAlarms = remember(tick) { OfficeTrackerApp.container.scheduler.canScheduleExact() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Profile") })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenMessage(vm.message ?: update.message) { vm.message = null; vm.updates.clearMessage() }

            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(profile.initials, size = 56.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(profile.name, style = MaterialTheme.typography.titleLarge)
                        Text(Phone.pretty(profile.phone), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${if (profile.isAdmin) "Administrator" else "Employee"} · ${profile.department}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SettingRow(Icons.Default.Business, company.name, if (profile.isAdmin) "${company.planName} · ${company.access(System.currentTimeMillis()).label} · ${company.userCount}/${company.maxUsers} users" else "Your company") {
                if (profile.isAdmin) onOpenSubscription()
            }
            if (profile.isAdmin) {
                SettingRow(Icons.Default.History, "My workdays", "Your own tracked days and monthly report", onClick = onOpenMyHistory)
            }

            SectionTitle("Tracking reliability")
            SectionCard(padding = PaddingValues(vertical = 4.dp)) {
                Column {
                    CheckRow(Icons.Default.LocationOn, "Location \"All the time\"", "Keeps tracking after restarts", background) { askBackground() }
                    HorizontalDivider()
                    CheckRow(Icons.Default.BatterySaver, "Unrestricted battery", "Stops the phone killing the tracker", battery) {
                        context.safeStart(context.batteryOptimizationIntent())
                    }
                    HorizontalDivider()
                    CheckRow(Icons.Default.Notifications, "Notifications", "Shows tracking status", notifications) {
                        context.safeStart(context.appSettingsIntent())
                    }
                    val autostart = remember { context.autostartIntent() }
                    if (autostart != null) {
                        HorizontalDivider()
                        CheckRow(Icons.Default.RestartAlt, "Autostart (${android.os.Build.MANUFACTURER})", "Turn ON so tracking and the schedule work when the app is closed", false) {
                            context.safeStart(autostart)
                        }
                    }
                    if (company.features.scheduler) {
                        HorizontalDivider()
                        CheckRow(Icons.Default.Alarm, "Exact alarms", "Starts and ends your day on time", exactAlarms) {
                            context.safeStart(context.exactAlarmSettingsIntent())
                        }
                    }
                }
            }

            if (company.features.scheduler) {
                val mySchedule = profile.schedule ?: if (!profile.isAdmin) config.schedule else null
                SectionTitle("My work schedule")
                SectionCard {
                    Column {
                        Text(mySchedule?.summary() ?: "No schedule", style = MaterialTheme.typography.bodyMedium)
                        val (nextStart, nextEnd) = remember(tick, mySchedule) { OfficeTrackerApp.container.scheduler.upcoming() }
                        if (nextStart != null) InfoRow("Next automatic start", Dates.friendlyDay(Dates.keyOf(nextStart)) + " " + Dates.time(nextStart))
                        if (nextEnd != null) InfoRow("Next automatic end", Dates.friendlyDay(Dates.keyOf(nextEnd)) + " " + Dates.time(nextEnd))
                        if (profile.schedule != null) {
                            Text("Set personally by your administrator.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            SectionTitle("Account")
            SettingRow(Icons.Default.Key, "Change password", null) { changingPassword = true }

            if (profile.isAdmin) {
                SectionTitle("Organisation settings")
                OrgSettingsCard(config, vm.busy, showAllowance = company.features.allowance, onSave = vm::saveConfig)
                if (company.features.attendance) {
                    SectionTitle("Attendance & overtime")
                    AttendanceSettingsCard(config, vm.busy, showOt = company.features.overtime) {
                        vm.saveConfig(config.copy(attendance = it))
                    }
                }
                if (company.features.scheduler) {
                    SectionTitle("Company work schedule")
                    CompanyScheduleCard(config, vm.busy) { vm.saveConfig(config.copy(schedule = it)) }
                }
            }

            SectionTitle("App")
            SectionCard {
                Column {
                    InfoRow("Version", BuildConfig.VERSION_NAME)
                    Spacer(Modifier.height(8.dp))
                    LoadingButton(
                        text = if (update.available != null) "Update to v${update.available?.version}" else "Check for updates",
                        loading = update.checking, icon = Icons.Default.SystemUpdate,
                        onClick = { if (update.available != null) vm.updates.reopen() else vm.updates.check(force = true) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            TextButton(onClick = { vm.signOut(onDuty = { confirmSignOut = true }) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text("Sign out", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (changingPassword) {
        ChangePasswordDialog(busy = vm.busy, onDismiss = { changingPassword = false }) { cur, new, confirm ->
            vm.changePassword(cur, new, confirm) { changingPassword = false }
        }
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("You are still on duty") },
            text = { Text("End your workday on the Today tab before signing out, so your route and allowance are saved correctly.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false }) { Text("OK") } },
        )
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    SectionCard(Modifier.clickable(onClick = onClick), padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CheckRow(icon: ImageVector, title: String, subtitle: String, ok: Boolean, onFix: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !ok, onClick = onFix).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (ok) Icon(Icons.Default.CheckCircle, contentDescription = "OK", tint = Brand.Success, modifier = Modifier.size(22.dp))
        else TextButton(onClick = onFix) { Text("Fix", color = Color(0xFFD98A00)) }
    }
}

@Composable
private fun AttendanceSettingsCard(
    config: AppConfig,
    busy: Boolean,
    showOt: Boolean,
    onSave: (AttendanceSettings) -> Unit,
) {
    var draft by remember(config.attendance) { mutableStateOf(config.attendance) }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Mark your offices and sites as attendance zones in the Places tab. Time inside them counts as attendance.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingSwitch("Attendance punching", draft.enabled) { draft = draft.copy(enabled = it) }
            if (draft.enabled) {
                SettingSwitch("Must be inside a zone to punch in", draft.requireZoneToPunch) {
                    draft = draft.copy(requireZoneToPunch = it)
                }
                if (showOt) {
                    SettingSwitch("Count overtime after the end time", draft.otEnabled) { draft = draft.copy(otEnabled = it) }
                    if (draft.otEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumberField("OT starts after (min)", draft.otGraceMinutes.toString(), Modifier.weight(1f)) { v ->
                                draft = draft.copy(otGraceMinutes = v.toIntOrNull() ?: draft.otGraceMinutes)
                            }
                            NumberField("Minimum OT (min)", draft.otMinMinutes.toString(), Modifier.weight(1f)) { v ->
                                draft = draft.copy(otMinMinutes = v.toIntOrNull() ?: draft.otMinMinutes)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumberField("Max OT per day (h)", draft.otMaxHours.toString(), Modifier.weight(1f)) { v ->
                                draft = draft.copy(otMaxHours = v.toIntOrNull() ?: draft.otMaxHours)
                            }
                            NumberField("OT rate per hour (৳)", draft.otRatePerHour.toString(), Modifier.weight(1f)) { v ->
                                draft = draft.copy(otRatePerHour = v.toDoubleOrNull() ?: draft.otRatePerHour)
                            }
                        }
                        SettingSwitch("Overtime needs my approval", draft.otRequiresApproval) {
                            draft = draft.copy(otRequiresApproval = it)
                        }
                    }
                }
            }
            LoadingButton(
                text = "Save attendance settings", loading = busy, modifier = Modifier.fillMaxWidth(),
                enabled = draft != config.attendance,
                onClick = { onSave(draft) },
            )
        }
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun CompanyScheduleCard(config: AppConfig, busy: Boolean, onSave: (WorkSchedule) -> Unit) {
    var draft by remember(config.schedule) { mutableStateOf(config.schedule) }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Applies to all employees unless you set a personal schedule on their page. Phones pick up changes within seconds.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ScheduleEditor(draft) { draft = it }
            LoadingButton(
                text = "Save schedule", loading = busy, modifier = Modifier.fillMaxWidth(),
                enabled = draft != config.schedule && (!draft.enabled || draft.endMinute > draft.startMinute),
                onClick = { onSave(draft) },
            )
        }
    }
}

@Composable
private fun OrgSettingsCard(config: AppConfig, busy: Boolean, showAllowance: Boolean, onSave: (AppConfig) -> Unit) {
    var rate by remember(config) { mutableStateOf(config.ratePerKm.toString()) }
    var radius by remember(config) { mutableStateOf(config.stayRadiusMeters.toInt().toString()) }
    var minutes by remember(config) { mutableStateOf(config.minStayMinutes.toString()) }
    var accuracy by remember(config) { mutableStateOf(config.maxAccuracyMeters.toInt().toString()) }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Applies to every employee's phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (showAllowance) NumberField("Travel allowance per km (৳)", rate) { rate = it }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField("Visit radius (m)", radius, Modifier.weight(1f)) { radius = it }
                NumberField("Min. visit (min)", minutes, Modifier.weight(1f)) { minutes = it }
            }
            NumberField("Ignore GPS fixes worse than (m)", accuracy) { accuracy = it }
            LoadingButton(
                text = "Save settings", loading = busy, modifier = Modifier.fillMaxWidth(),
                onClick = {
                    onSave(
                        // copy(): keep fields edited elsewhere, such as the work schedule.
                        config.copy(
                            ratePerKm = rate.toDoubleOrNull() ?: config.ratePerKm,
                            stayRadiusMeters = radius.toDoubleOrNull() ?: config.stayRadiusMeters,
                            minStayMinutes = minutes.toIntOrNull() ?: config.minStayMinutes,
                            maxAccuracyMeters = (accuracy.toDoubleOrNull() ?: config.maxAccuracyMeters).coerceIn(20.0, 200.0),
                        )
                    )
                },
            )
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' }.take(7)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
private fun ChangePasswordDialog(busy: Boolean, onDismiss: () -> Unit, onSubmit: (String, String, String) -> Unit) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple("Current password", current) { v: String -> current = v },
                    Triple("New password (min. 6)", new) { v: String -> new = v },
                    Triple("Confirm new password", confirm) { v: String -> confirm = v },
                ).forEach { (label, value, set) ->
                    OutlinedTextField(
                        value = value, onValueChange = { set(it.take(64)) }, label = { Text(label) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(current, new, confirm) }, enabled = !busy && current.isNotEmpty() && new.length >= 6) {
                Text(if (busy) "Saving…" else "Change")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
