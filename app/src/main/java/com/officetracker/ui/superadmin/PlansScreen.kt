@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.officetracker.ui.superadmin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.officetracker.core.model.Billing
import com.officetracker.core.model.Features
import com.officetracker.core.model.Plan
import com.officetracker.core.util.Format
import com.officetracker.ui.appViewModel
import com.officetracker.ui.components.ConfirmDialog
import com.officetracker.ui.components.EmptyState
import com.officetracker.ui.components.LoadingButton
import com.officetracker.ui.components.ScreenMessage
import com.officetracker.ui.components.SectionCard
import com.officetracker.ui.components.SectionTitle
import com.officetracker.ui.components.StatusPill
import com.officetracker.ui.theme.Brand

@Composable
fun PlansScreen() {
    val vm = appViewModel(key = "sa-plans") { PlansViewModel(it) }
    val plans by vm.plans.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Plan?>(null) }
    var deleting by remember { mutableStateOf<Plan?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Plans") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = Plan("", "", "", Billing.MONTHLY, 30, 0.0, 10, 1, Features.ALL, true, (plans?.size ?: 0)) },
                icon = { Icon(Icons.Default.Add, null) }, text = { Text("New plan") },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { ScreenMessage(vm.message) { vm.message = null } }
            item {
                Text(
                    "Plans are templates. Assigning one copies its limits and features to the company, which you can then customise. Editing a plan does not change companies already on it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val list = plans
            if (list == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            else if (list.isEmpty()) item { EmptyState(Icons.Default.Sell, "No plans", "Create trial, monthly and yearly packages.") }
            else items(list, key = { it.id }) { p ->
                SectionCard(Modifier.clickable { editing = p }, padding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.width(8.dp))
                                if (!p.active) StatusPill("Hidden", Brand.Muted)
                            }
                            Text(
                                "${if (p.price > 0) Format.taka(p.price) else "Free"} · ${p.billing.label} · " +
                                    if (p.billing == Billing.LIFETIME) "no expiry" else "${p.durationDays} days",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "${p.maxUsers} users · ${p.maxAdmins} admins · " +
                                    Features.LABELS.count { p.features.get(it.first) }.let { "$it/${Features.LABELS.size} features" },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (p.description.isNotBlank()) {
                                Text(p.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { deleting = p }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            }
        }
    }

    editing?.let { plan ->
        PlanEditor(plan, vm.busy, onDismiss = { editing = null }) { vm.save(it) { editing = null } }
    }
    deleting?.let { p ->
        ConfirmDialog(
            "Delete ${p.name}?", "Companies already on this plan keep their current terms. You can hide a plan instead of deleting it.",
            "Delete", onConfirm = { vm.delete(p) }, onDismiss = { deleting = null }, destructive = true,
        )
    }
}

@Composable
private fun PlanEditor(initial: Plan, busy: Boolean, onDismiss: () -> Unit, onSave: (Plan) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var description by remember { mutableStateOf(initial.description) }
    var billing by remember { mutableStateOf(initial.billing) }
    var days by remember { mutableStateOf(initial.durationDays.toString()) }
    var price by remember { mutableStateOf(if (initial.price == 0.0) "0" else initial.price.toString()) }
    var maxUsers by remember { mutableStateOf(initial.maxUsers.toString()) }
    var maxAdmins by remember { mutableStateOf(initial.maxAdmins.toString()) }
    var features by remember { mutableStateOf(initial.features) }
    var active by remember { mutableStateOf(initial.active) }
    var order by remember { mutableStateOf(initial.sortOrder.toString()) }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().imePadding()) {
                TopAppBar(
                    title = { Text(if (initial.id.isBlank()) "New plan" else "Edit plan") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") } },
                )
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Field("Plan name", name, { name = it })
                    Field("Short description", description, { description = it })
                    SectionTitle("Billing")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Billing.entries.forEach { b ->
                            FilterChip(selected = billing == b, onClick = {
                                billing = b
                                days = when (b) {
                                    Billing.MONTHLY -> "30"
                                    Billing.QUARTERLY -> "90"
                                    Billing.YEARLY -> "365"
                                    Billing.TRIAL -> "14"
                                    else -> days
                                }
                            }, label = { Text(b.label) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("Period (days)", days, { days = it }, Modifier.weight(1f), number = true)
                        Field("Price (৳)", price, { price = it }, Modifier.weight(1f), decimal = true)
                    }
                    SectionTitle("Limits")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("Max users", maxUsers, { maxUsers = it }, Modifier.weight(1f), number = true)
                        Field("Max admins", maxAdmins, { maxAdmins = it }, Modifier.weight(1f), number = true)
                    }
                    SectionTitle("Features")
                    Features.LABELS.forEach { (key, label) ->
                        SwitchRow(label, features.get(key), { features = features.with(key, it) })
                    }
                    SectionTitle("Visibility")
                    SwitchRow("Offered to new companies", active, { active = it })
                    Field("Sort order", order, { order = it }, number = true)
                    LoadingButton(
                        text = "Save plan", loading = busy, modifier = Modifier.fillMaxWidth(),
                        enabled = name.isNotBlank(),
                        onClick = {
                            onSave(
                                initial.copy(
                                    name = name.trim(), description = description.trim(), billing = billing,
                                    durationDays = days.toIntOrNull() ?: initial.durationDays,
                                    price = price.toDoubleOrNull() ?: 0.0,
                                    maxUsers = maxUsers.toIntOrNull() ?: initial.maxUsers,
                                    maxAdmins = maxAdmins.toIntOrNull() ?: initial.maxAdmins,
                                    features = features, active = active, sortOrder = order.toIntOrNull() ?: 0,
                                )
                            )
                        },
                    )
                }
            }
        }
    }
}
