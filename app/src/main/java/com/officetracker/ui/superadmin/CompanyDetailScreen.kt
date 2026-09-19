@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.officetracker.ui.superadmin

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.officetracker.core.model.Billing
import com.officetracker.core.model.Company
import com.officetracker.core.model.Features
import com.officetracker.core.model.Payment
import com.officetracker.core.model.Plan
import com.officetracker.core.model.Role
import com.officetracker.core.model.Subscription
import com.officetracker.core.model.UserProfile
import com.officetracker.core.util.Dates
import com.officetracker.core.util.Format
import com.officetracker.core.util.Phone
import com.officetracker.ui.appViewModel
import com.officetracker.ui.components.Avatar
import com.officetracker.ui.components.ConfirmDialog
import com.officetracker.ui.components.InfoRow
import com.officetracker.ui.components.LoadingButton
import com.officetracker.ui.components.ScreenMessage
import com.officetracker.ui.components.SectionCard
import com.officetracker.ui.components.SectionTitle
import com.officetracker.ui.components.StatusPill
import com.officetracker.ui.components.rememberNow
import com.officetracker.ui.tenant.color
import com.officetracker.ui.theme.Brand
import java.time.Instant
import java.time.ZoneOffset

@Composable
fun CompanyDetailScreen(companyId: String, onBack: () -> Unit, onOpenPerson: (String) -> Unit) {
    val vm = appViewModel(key = "sa-company-$companyId") { CompanyDetailViewModel(it, companyId) }
    val company by vm.company.collectAsStateWithLifecycle()
    val people by vm.people.collectAsStateWithLifecycle()
    val payments by vm.payments.collectAsStateWithLifecycle()
    val plans by vm.plans.collectAsStateWithLifecycle()
    val now = rememberNow(60_000)

    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<DetailDialog?>(null) }

    val co = company
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(co?.name ?: "Company") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (co != null) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Edit company details") }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                                    onClick = { menu = false; dialog = DetailDialog.EditProfile })
                                DropdownMenuItem(text = { Text("Recount users") }, leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                    onClick = { menu = false; vm.recount() })
                                DropdownMenuItem(text = { Text("Import v1 employees") }, leadingIcon = { Icon(Icons.Default.Upload, null) },
                                    onClick = { menu = false; dialog = DetailDialog.Import })
                                DropdownMenuItem(
                                    text = { Text("Delete company", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; dialog = DetailDialog.Delete },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (co == null) {
            Box(Modifier.fillMaxSize().padding(padding)) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            return@Scaffold
        }
        val access = co.access(now)
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ScreenMessage(vm.message) { vm.message = null } }
            if (vm.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }

            // ---- Subscription ----
            item {
                SectionCard {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(co.planName.ifBlank { "No plan" }, style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "${co.billing.label}${if (co.price > 0) " · " + Format.taka(co.price) else ""} · ${co.statusLine(now)}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            StatusPill(access.label, access.color())
                        }
                        Spacer(Modifier.height(8.dp))
                        InfoRow("Started", if (co.startedAt > 0) Dates.day(Dates.keyOf(co.startedAt)) else "—")
                        InfoRow("Expires", co.expiryText())
                        if (co.expiresAt < Subscription.LIFETIME_EXPIRY) InfoRow("Locks after grace", Dates.day(Dates.keyOf(co.accessUntil)))
                        InfoRow("Seats used", "${co.userCount} / ${co.maxUsers} users · ${co.adminCount} / ${co.maxAdmins} admins")
                        co.suspendReason?.takeIf { co.suspended && it.isNotBlank() }?.let { InfoRow("Suspended because", it) }
                        Spacer(Modifier.height(10.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilledTonalButton(onClick = { dialog = DetailDialog.ChangePlan }) {
                                Icon(Icons.Default.Sell, null); Spacer(Modifier.width(6.dp)); Text("Change plan")
                            }
                            FilledTonalButton(onClick = { dialog = DetailDialog.Payment }) {
                                Icon(Icons.Default.Payments, null); Spacer(Modifier.width(6.dp)); Text("Record payment")
                            }
                            OutlinedButton(onClick = { dialog = DetailDialog.Expiry }) {
                                Icon(Icons.Default.CalendarMonth, null); Spacer(Modifier.width(6.dp)); Text("Set expiry")
                            }
                            if (co.suspended) {
                                OutlinedButton(onClick = { vm.setSuspended(false, null) }) {
                                    Icon(Icons.Default.CheckCircle, null, tint = Brand.Success); Spacer(Modifier.width(6.dp)); Text("Re-activate")
                                }
                            } else {
                                OutlinedButton(onClick = { dialog = DetailDialog.Suspend }) {
                                    Icon(Icons.Default.Block, null, tint = Brand.Danger); Spacer(Modifier.width(6.dp)); Text("Suspend")
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Quick extend", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(7, 30, 90, 365).forEach { d ->
                                FilterChip(selected = false, onClick = { vm.extend(d) }, label = { Text("+$d days") })
                            }
                        }
                    }
                }
            }

            // ---- Limits & price override ----
            item { SectionTitle("Limits & price (custom)") }
            item { LimitsCard(co, vm.busy) { vm.saveSubscription(it) } }

            // ---- Features ----
            item { SectionTitle("Features") }
            item {
                SectionCard(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                    Column {
                        Features.LABELS.forEach { (key, label) ->
                            SwitchRow(label, co.features.get(key), onChange = { on ->
                                vm.saveSubscription(co.copy(features = co.features.with(key, on)))
                            }, enabled = !vm.busy)
                        }
                        Text(
                            "Switches apply to this company's phones within seconds.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                }
            }

            // ---- People ----
            item {
                SectionTitle("People (${people.size})") {
                    TextButton(onClick = { dialog = DetailDialog.AddPerson }) {
                        Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(4.dp)); Text("Add")
                    }
                }
            }
            items(people, key = { "u-" + it.uid }) { p -> PersonRow(p) { onOpenPerson(p.uid) } }

            // ---- Company info ----
            item { SectionTitle("Contact") }
            item {
                SectionCard {
                    Column {
                        InfoRow("Contact", co.contactName.ifBlank { "—" })
                        InfoRow("Phone", co.contactPhone.ifBlank { "—" })
                        InfoRow("Email", co.email.ifBlank { "—" })
                        InfoRow("Address", co.address.ifBlank { "—" })
                        if (co.notes.isNotBlank()) InfoRow("Notes", co.notes)
                        InfoRow("Customer since", if (co.createdAt > 0) Dates.day(Dates.keyOf(co.createdAt)) else "—")
                    }
                }
            }

            // ---- Payments ----
            item { SectionTitle("Payments (${payments.size})") }
            if (payments.isEmpty()) item {
                Text("No payments recorded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(payments, key = { "p-" + it.id }) { p ->
                SectionCard(padding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(Format.taka(p.amount), style = MaterialTheme.typography.titleSmall)
                            Text(
                                listOf(Dates.day(Dates.keyOf(p.createdAt)), p.method, p.reference, p.note).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (p.extendedDays > 0) Text("+${p.extendedDays} d", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    // ---- Dialogs ----
    if (co != null) when (dialog) {
        DetailDialog.ChangePlan -> ChangePlanDialog(co, plans.filter { it.active || it.id == co.planId }, onDismiss = { dialog = null }) { plan, renew ->
            vm.assignPlan(plan, renew) { dialog = null }
        }
        DetailDialog.Payment -> PaymentDialog(co, plans.firstOrNull { it.id == co.planId }, onDismiss = { dialog = null }) { payment ->
            vm.recordPayment(payment) { dialog = null }
        }
        DetailDialog.Expiry -> ExpiryDialog(co, onDismiss = { dialog = null }) { millis -> vm.setExpiry(millis); dialog = null }
        DetailDialog.Suspend -> SuspendDialog(co, onDismiss = { dialog = null }) { reason -> vm.setSuspended(true, reason); dialog = null }
        DetailDialog.EditProfile -> EditCompanyDialog(co, onDismiss = { dialog = null }) { updated -> vm.saveProfile(updated) { dialog = null } }
        DetailDialog.AddPerson -> AddPersonDialog(co, vm.busy, onDismiss = { dialog = null }) { name, phone, pass, role ->
            vm.addPerson(name, phone, pass, role) { dialog = null }
        }
        DetailDialog.Import -> ConfirmDialog(
            "Import v1 employees into ${co.name}?",
            "Every v1 account (old users/{phone} records with plain-text passwords) gets a secure login in this company with its existing password. The old records are deleted.",
            "Import", onConfirm = { vm.importLegacy() }, onDismiss = { dialog = null },
        )
        DetailDialog.Delete -> DeleteCompanyDialog(co, onDismiss = { dialog = null }) { vm.delete(onBack); dialog = null }
        null -> Unit
    }
    vm.importReport?.let { report ->
        AlertDialog(
            onDismissRequest = { vm.importReport = null },
            title = { Text("Imported ${report.imported} people") },
            text = { Text(if (report.skipped.isEmpty()) "All old accounts were migrated." else "Not imported:\n" + report.skipped.joinToString("\n") { "• $it" }) },
            confirmButton = { TextButton(onClick = { vm.importReport = null }) { Text("OK") } },
        )
    }
}

private enum class DetailDialog { ChangePlan, Payment, Expiry, Suspend, EditProfile, AddPerson, Import, Delete }

@Composable
private fun PersonRow(p: UserProfile, onClick: () -> Unit) {
    SectionCard(Modifier.clickable(onClick = onClick), padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(p.initials, if (p.disabled) Brand.Muted else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleSmall)
                Text("${Phone.pretty(p.phone)} · ${p.department}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusPill(if (p.disabled) "Disabled" else p.role.label, if (p.disabled) Brand.Muted else if (p.isAdmin) Brand.Primary else Brand.Success)
        }
    }
}

@Composable
private fun LimitsCard(co: Company, busy: Boolean, onSave: (Company) -> Unit) {
    var maxUsers by remember(co) { mutableStateOf(co.maxUsers.toString()) }
    var maxAdmins by remember(co) { mutableStateOf(co.maxAdmins.toString()) }
    var price by remember(co) { mutableStateOf(if (co.price == 0.0) "0" else co.price.toString()) }
    var billing by remember(co) { mutableStateOf(co.billing) }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Max users", maxUsers, { maxUsers = it }, Modifier.weight(1f), number = true)
                Field("Max admins", maxAdmins, { maxAdmins = it }, Modifier.weight(1f), number = true)
            }
            Field("Price (৳ per billing period)", price, { price = it }, decimal = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Billing.entries.forEach { b -> FilterChip(selected = billing == b, onClick = { billing = b }, label = { Text(b.label) }) }
            }
            LoadingButton(
                text = "Save limits", loading = busy, modifier = Modifier.fillMaxWidth(),
                onClick = {
                    onSave(
                        co.copy(
                            maxUsers = maxUsers.toIntOrNull() ?: co.maxUsers,
                            maxAdmins = maxAdmins.toIntOrNull() ?: co.maxAdmins,
                            price = price.toDoubleOrNull() ?: co.price,
                            billing = billing,
                            expiresAt = when {
                                billing == Billing.LIFETIME -> Subscription.LIFETIME_EXPIRY
                                // Leaving lifetime: give a normal period from today instead of year 2100.
                                co.expiresAt >= Subscription.LIFETIME_EXPIRY -> Subscription.extend(0, 30, System.currentTimeMillis())
                                else -> co.expiresAt
                            },
                        )
                    )
                },
            )
            Text(
                "Overrides the plan for this company only. Lowering max users below current usage blocks new sign-ups, it does not remove anyone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChangePlanDialog(co: Company, plans: List<Plan>, onDismiss: () -> Unit, onApply: (Plan, Boolean) -> Unit) {
    var selected by remember { mutableStateOf(plans.firstOrNull { it.id == co.planId } ?: plans.firstOrNull()) }
    var renew by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change plan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                plans.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = p }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(selected = selected?.id == p.id, onClick = { selected = p })
                        Column {
                            Text(p.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${p.billing.label} · ${if (p.price > 0) Format.taka(p.price) else "Free"} · ${p.maxUsers} users",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider()
                SwitchRow(
                    "Add to current period", renew, { renew = it },
                    subtitle = if (renew) "Days are added after the current expiry (renewal)" else "New period starts today",
                )
                Text(
                    "Limits and features are copied from the plan; you can still customise them afterwards.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(enabled = selected != null, onClick = { selected?.let { onApply(it, renew) } }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PaymentDialog(co: Company, plan: Plan?, onDismiss: () -> Unit, onSave: (Payment) -> Unit) {
    var amount by remember { mutableStateOf(if (co.price > 0) co.price.toLong().toString() else "") }
    var method by remember { mutableStateOf("bKash") }
    var reference by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var days by remember { mutableStateOf((plan?.durationDays ?: 30).toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record payment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Amount (৳)", amount, { amount = it }, decimal = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("bKash", "Nagad", "Rocket", "Bank", "Cash", "Card").forEach { m ->
                        FilterChip(selected = method == m, onClick = { method = m }, label = { Text(m) })
                    }
                }
                Field("Transaction ID / reference", reference, { reference = it })
                Field("Extend subscription by (days)", days, { days = it }, number = true, supporting = "0 = record only")
                Field("Note", note, { note = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    Payment(
                        id = "", amount = amount.toDoubleOrNull() ?: 0.0, method = method, reference = reference.trim(),
                        note = note.trim(), extendedDays = days.toIntOrNull() ?: 0, createdAt = System.currentTimeMillis(),
                    )
                )
            }, enabled = amount.toDoubleOrNull() != null) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ExpiryDialog(co: Company, onDismiss: () -> Unit, onSet: (Long) -> Unit) {
    val initial = if (co.expiresAt in 1 until Subscription.LIFETIME_EXPIRY) co.expiresAt else System.currentTimeMillis()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = Dates.parse(Dates.keyOf(initial)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { utc ->
                    // End of the chosen local day.
                    val date = Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate()
                    onSet(date.plusDays(1).atStartOfDay(Dates.zone).toInstant().toEpochMilli() - 1)
                }
            }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state, title = { Text("Subscription ends on", Modifier.padding(start = 24.dp, top = 16.dp)) })
    }
}

@Composable
private fun SuspendDialog(co: Company, onDismiss: () -> Unit, onSuspend: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Suspend ${co.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Every phone in this company locks immediately and tracking stops. Data is kept. You can re-activate at any time.")
                Field("Reason shown to the company (optional)", reason, { reason = it })
            }
        },
        confirmButton = { TextButton(onClick = { onSuspend(reason) }) { Text("Suspend", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditCompanyDialog(co: Company, onDismiss: () -> Unit, onSave: (Company) -> Unit) {
    var name by remember { mutableStateOf(co.name) }
    var contact by remember { mutableStateOf(co.contactName) }
    var phone by remember { mutableStateOf(co.contactPhone) }
    var email by remember { mutableStateOf(co.email) }
    var address by remember { mutableStateOf(co.address) }
    var notes by remember { mutableStateOf(co.notes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Company details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Field("Company name", name, { name = it })
                Field("Contact person", contact, { contact = it })
                Field("Contact phone", phone, { phone = it }, phone = true)
                Field("Email", email, { email = it })
                Field("Address", address, { address = it })
                Field("Internal notes", notes, { notes = it }, singleLine = false)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(co.copy(name = name.trim(), contactName = contact.trim(), contactPhone = phone.trim(), email = email.trim(), address = address.trim(), notes = notes.trim()))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddPersonDialog(co: Company, busy: Boolean, onDismiss: () -> Unit, onAdd: (String, String, String, Role) -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf(randomPassword()) }
    var role by remember { mutableStateOf(if (co.adminCount == 0) Role.ADMIN else Role.EMPLOYEE) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Add person to ${co.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Full name", name, { name = it })
                Field("Mobile number", phone, { phone = it }, phone = true)
                OutlinedTextField(
                    value = password, onValueChange = { password = it.take(32) }, singleLine = true,
                    label = { Text("Initial password") }, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { IconButton(onClick = { password = randomPassword() }) { Icon(Icons.Default.Casino, "Generate") } },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = role == Role.ADMIN, onClick = { role = Role.ADMIN }, label = { Text("Administrator") })
                    FilterChip(selected = role == Role.EMPLOYEE, onClick = { role = Role.EMPLOYEE }, label = { Text("Employee") })
                }
                if (!co.canAddUser) {
                    Text(
                        "This company is at its limit (${co.maxUsers}). Adding anyway gives a bonus seat.",
                        style = MaterialTheme.typography.bodySmall, color = Brand.Warning,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank() && Phone.isValid(phone) && password.length >= 6,
                onClick = { onAdd(name, phone, password, role) },
            ) { Text(if (busy) "Adding…" else "Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteCompanyDialog(co: Company, onDismiss: () -> Unit, onDelete: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${co.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("All ${co.userCount} people are deactivated and the company is removed. Tracking history stays in the database. This cannot be undone. Prefer Suspend if unsure.")
                Field("Type the company name to confirm", typed, { typed = it })
            }
        },
        confirmButton = {
            TextButton(enabled = typed.trim() == co.name.trim(), onClick = onDelete) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
