@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.officetracker.ui.superadmin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.officetracker.core.model.AccessState
import com.officetracker.core.model.Plan
import com.officetracker.core.util.Format
import com.officetracker.core.util.Phone
import com.officetracker.data.repo.NewCompanyInput
import com.officetracker.ui.appViewModel
import com.officetracker.ui.components.EmptyState
import com.officetracker.ui.components.LoadingButton
import com.officetracker.ui.components.ScreenMessage
import com.officetracker.ui.components.SectionTitle
import com.officetracker.ui.components.rememberNow
import java.security.SecureRandom

@Composable
fun CompaniesScreen(onOpenCompany: (String) -> Unit) {
    val vm = appViewModel(key = "sa-overview") { PlatformOverviewViewModel(it) }
    val companies by vm.companies.collectAsStateWithLifecycle()
    val plans by vm.plans.collectAsStateWithLifecycle()
    val now = rememberNow(60_000)
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<AccessState?>(null) }
    var creating by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Companies") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, null) }, text = { Text("New company") },
            )
        },
    ) { padding ->
        val all = companies
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { ScreenMessage(vm.message) { vm.message = null } }
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search company, contact or phone") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                )
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
                    AccessState.entries.forEach { s ->
                        FilterChip(selected = filter == s, onClick = { filter = s }, label = { Text(s.label) })
                    }
                }
            }
            if (all == null) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            } else {
                val shown = all.filter { c ->
                    (filter == null || c.access(now) == filter) &&
                        (query.isBlank() || c.name.contains(query, true) || c.contactName.contains(query, true) ||
                            c.contactPhone.contains(query.filter { it.isDigit() }.ifEmpty { "\u0000" }))
                }
                if (shown.isEmpty()) item {
                    EmptyState(Icons.Default.Business, "No companies", if (all.isEmpty()) "Create your first customer." else "Try another search or filter.")
                }
                items(shown, key = { it.id }) { c -> CompanyRow(c, now) { onOpenCompany(c.id) } }
            }
        }
    }

    if (creating) {
        CreateCompanyDialog(
            plans = plans.filter { it.active },
            busy = vm.busy,
            error = vm.message,
            onDismiss = { creating = false },
        ) { input, plan ->
            vm.createCompany(input, plan) { created -> creating = false; onOpenCompany(created.id) }
        }
    }
}

@Composable
private fun CreateCompanyDialog(
    plans: List<Plan>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (NewCompanyInput, Plan) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var contactName by remember { mutableStateOf("") }
    var contactPhone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var adminName by remember { mutableStateOf("") }
    var adminPhone by remember { mutableStateOf("") }
    var adminPassword by remember { mutableStateOf(randomPassword()) }
    var plan by remember(plans) { mutableStateOf(plans.firstOrNull()) }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().imePadding()) {
                TopAppBar(
                    title = { Text("New company") },
                    navigationIcon = { IconButton(onClick = onDismiss, enabled = !busy) { Icon(Icons.Default.Close, "Close") } },
                )
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    error?.let { ScreenMessage(it) {} }
                    SectionTitle("Company")
                    Field("Company name", name, { name = it })
                    Field("Contact person", contactName, { contactName = it })
                    Field("Contact phone", contactPhone, { contactPhone = it }, phone = true)
                    Field("Email", email, { email = it })
                    Field("Address", address, { address = it })

                    SectionTitle("Plan")
                    if (plans.isEmpty()) {
                        Text("Create a plan in the Plans tab first.", color = MaterialTheme.colorScheme.error)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        plans.forEach { p ->
                            FilterChip(
                                selected = plan?.id == p.id, onClick = { plan = p },
                                label = { Text("${p.name} · ${if (p.price > 0) Format.taka(p.price) else "Free"}") },
                            )
                        }
                    }
                    plan?.let { p ->
                        Text(
                            "${p.billing.label} · ${p.durationDays} days · up to ${p.maxUsers} users, ${p.maxAdmins} admins",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    SectionTitle("Company administrator")
                    Field("Admin name", adminName, { adminName = it })
                    Field(
                        "Admin mobile number", adminPhone, { adminPhone = it }, phone = true,
                        supporting = if (adminPhone.isNotEmpty() && !Phone.isValid(adminPhone)) "Enter an 11-digit number (01XXXXXXXXX)" else "This is their login",
                    )
                    OutlinedTextField(
                        value = adminPassword, onValueChange = { adminPassword = it.take(32) }, singleLine = true,
                        label = { Text("Initial password") }, modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { IconButton(onClick = { adminPassword = randomPassword() }) { Icon(Icons.Default.Casino, "Generate") } },
                        supportingText = { Text("Share it with the admin; they can change it in Profile.") },
                    )
                    LoadingButton(
                        text = "Create company", loading = busy, modifier = Modifier.fillMaxWidth(),
                        enabled = name.isNotBlank() && adminName.isNotBlank() && Phone.isValid(adminPhone) &&
                            adminPassword.length >= 6 && plan != null,
                        onClick = {
                            val p = plan ?: return@LoadingButton
                            onCreate(
                                NewCompanyInput(name, contactName.ifBlank { adminName }, contactPhone, email, address, adminName, adminPhone, adminPassword),
                                p,
                            )
                        },
                    )
                }
            }
        }
    }
}

fun randomPassword(): String {
    val alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val rnd = SecureRandom()
    return (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
}
