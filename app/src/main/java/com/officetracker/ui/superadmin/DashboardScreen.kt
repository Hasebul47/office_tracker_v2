package com.officetracker.ui.superadmin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.officetracker.core.model.Company
import com.officetracker.core.model.UserProfile
import com.officetracker.core.util.Format
import com.officetracker.ui.appViewModel
import com.officetracker.ui.components.Avatar
import com.officetracker.ui.components.EmptyState
import com.officetracker.ui.components.ScreenMessage
import com.officetracker.ui.components.SectionCard
import com.officetracker.ui.components.SectionTitle
import com.officetracker.ui.components.StatTile
import com.officetracker.ui.components.StatusPill
import com.officetracker.ui.components.rememberNow
import com.officetracker.ui.tenant.color
import com.officetracker.ui.theme.Brand

@Composable
fun DashboardScreen(profile: UserProfile, onOpenCompany: (String) -> Unit, onOpenCompanies: () -> Unit) {
    val vm = appViewModel(key = "sa-overview") { PlatformOverviewViewModel(it) }
    val companies by vm.companies.collectAsStateWithLifecycle()
    val now = rememberNow(60_000)
    val list = companies
    if (list == null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
        }
        return
    }
    val stats = remember(list, now / 3_600_000) { DashboardStats.from(list, now) }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                Text("Hello, ${profile.name.substringBefore(' ')}", style = MaterialTheme.typography.headlineSmall)
                Text("Platform overview", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { ScreenMessage(vm.message) { vm.message = null } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Monthly revenue", Format.taka(stats.mrr), Icons.Default.Payments, Modifier.weight(1f), tint = Brand.Success)
                StatTile("Yearly run-rate", Format.taka(stats.mrr * 12), Icons.Default.TrendingUp, Modifier.weight(1f), tint = Brand.Success)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Companies", stats.companies.toString(), Icons.Default.Business, Modifier.weight(1f))
                StatTile("Users", stats.users.toString(), Icons.Default.Groups, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Paying", stats.active.toString(), Icons.Default.CheckCircle, Modifier.weight(1f), tint = Brand.Success)
                StatTile("On trial", stats.trial.toString(), Icons.Default.HourglassTop, Modifier.weight(1f), tint = Brand.Primary)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Payment due", stats.grace.toString(), Icons.Default.EventBusy, Modifier.weight(1f), tint = Brand.Warning)
                StatTile("Expired / suspended", (stats.expired + stats.suspended).toString(), Icons.Default.Block, Modifier.weight(1f), tint = Brand.Danger)
            }
        }

        item { SectionTitle("Needs attention") }
        if (stats.needsAttention.isEmpty()) {
            item { Text("No overdue companies.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(stats.needsAttention, key = { "a-" + it.id }) { CompanyRow(it, now) { onOpenCompany(it.id) } }
        }

        item { SectionTitle("Ending within 7 days") }
        if (stats.expiringSoon.isEmpty()) {
            item { Text("Nothing ends this week.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(stats.expiringSoon, key = { "e-" + it.id }) { CompanyRow(it, now) { onOpenCompany(it.id) } }
        }

        item {
            SectionTitle("Companies by plan") {
                TextButton(onClick = onOpenCompanies) { Text("All companies") }
            }
        }
        if (stats.byPlan.isEmpty()) {
            item { EmptyState(Icons.Default.Business, "No companies yet", "Create your first customer from the Companies tab.") }
        }
        items(stats.byPlan, key = { "p-" + it.first }) { (plan, count) ->
            Column(Modifier.fillMaxWidth()) {
                Row {
                    Text(plan, modifier = Modifier.weight(1f))
                    Text(count.toString(), style = MaterialTheme.typography.labelLarge)
                }
                LinearProgressIndicator(
                    progress = { count.toFloat() / stats.companies.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
fun CompanyRow(company: Company, now: Long, onClick: () -> Unit) {
    val access = company.access(now)
    SectionCard(Modifier.clickable(onClick = onClick), padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(company.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }, access.color())
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(company.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${company.planName.ifBlank { "No plan" }} · ${company.userCount}/${company.maxUsers} users · ${company.statusLine(now)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusPill(access.label, access.color())
        }
    }
}
