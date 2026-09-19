@file:OptIn(ExperimentalMaterial3Api::class)

package com.officetracker.ui.tenant

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Groups
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.officetracker.AppContainer
import com.officetracker.core.model.AccessState
import com.officetracker.core.model.Company
import com.officetracker.core.model.Features
import com.officetracker.core.model.Payment
import com.officetracker.core.model.PlatformConfig
import com.officetracker.core.model.Subscription
import com.officetracker.core.util.Dates
import com.officetracker.core.util.Format
import com.officetracker.ui.appViewModel
import com.officetracker.ui.components.InfoRow
import com.officetracker.ui.components.SectionCard
import com.officetracker.ui.components.SectionTitle
import com.officetracker.ui.components.StatTile
import com.officetracker.ui.components.StatusPill
import com.officetracker.ui.components.rememberNow
import com.officetracker.ui.theme.Brand
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn

fun AccessState.color(): Color = when (this) {
    AccessState.TRIAL -> Brand.Primary
    AccessState.ACTIVE -> Brand.Success
    AccessState.GRACE -> Brand.Warning
    AccessState.EXPIRED, AccessState.SUSPENDED -> Brand.Danger
}

class SubscriptionViewModel(c: AppContainer) : ViewModel() {
    val payments: StateFlow<List<Payment>> = c.org.observePayments()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** Company administrator's view of their plan, limits and payments (read-only). */
@Composable
fun SubscriptionScreen(company: Company, platform: PlatformConfig, onBack: () -> Unit) {
    val vm = appViewModel(key = "subscription-${company.id}") { SubscriptionViewModel(it) }
    val payments by vm.payments.collectAsStateWithLifecycle()
    val now = rememberNow(60_000)
    val access = company.access(now)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Subscription") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(company.planName.ifBlank { "No plan" }, style = MaterialTheme.typography.titleLarge)
                                Text(
                                    company.billing.label + if (company.price > 0) " · ${Format.taka(company.price)}" else "",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            StatusPill(access.label, access.color())
                        }
                        Spacer(Modifier.height(12.dp))
                        InfoRow("Company", company.name)
                        InfoRow("Started", if (company.startedAt > 0) Dates.day(Dates.keyOf(company.startedAt)) else "—")
                        InfoRow(
                            if (access == AccessState.GRACE) "Expired" else "Renews / ends",
                            if (company.expiresAt >= Subscription.LIFETIME_EXPIRY) "Never (lifetime)" else Dates.day(Dates.keyOf(company.expiresAt)),
                        )
                        if (company.expiresAt < Subscription.LIFETIME_EXPIRY) {
                            InfoRow("Days left", company.daysLeft(now).toString())
                        }
                        if (access == AccessState.GRACE) {
                            InfoRow("Access until", Dates.day(Dates.keyOf(company.accessUntil)))
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Users", "${company.userCount} / ${company.maxUsers}", Icons.Default.Groups, Modifier.weight(1f))
                    StatTile("Admins", "${company.adminCount} / ${company.maxAdmins}", Icons.Default.AdminPanelSettings, Modifier.weight(1f))
                }
            }
            item {
                LinearProgressIndicator(
                    progress = { (company.userCount.toFloat() / company.maxUsers.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { SectionTitle("Included features") }
            item {
                SectionCard {
                    Column {
                        Features.LABELS.forEach { (key, label) ->
                            val on = company.features.get(key)
                            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (on) Icons.Default.CheckCircle else Icons.Default.Cancel, contentDescription = null,
                                    tint = if (on) Brand.Success else Brand.Muted, modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(label, color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            item {
                Column {
                    SectionTitle("Renew or upgrade")
                    Text(
                        "Contact us to renew, change plan or add users. Changes apply to all your phones instantly.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    SupportContacts(platform)
                }
            }
            if (payments.isNotEmpty()) {
                item { SectionTitle("Payments") }
                items(payments, key = { it.id }) { p ->
                    SectionCard(padding = PaddingValues(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Event, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(Format.taka(p.amount), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    listOf(Dates.day(Dates.keyOf(p.createdAt)), p.method, p.reference).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (p.extendedDays > 0) Text("+${p.extendedDays} days", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}
