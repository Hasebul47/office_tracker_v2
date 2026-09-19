package com.officetracker.ui.tenant

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.officetracker.BuildConfig
import com.officetracker.OfficeTrackerApp
import com.officetracker.core.model.AccessState
import com.officetracker.core.model.Company
import com.officetracker.core.model.PlatformConfig
import com.officetracker.core.model.UserProfile
import com.officetracker.core.util.Dates
import com.officetracker.ui.components.InfoRow
import com.officetracker.ui.components.LoadingButton
import com.officetracker.ui.components.SectionCard
import com.officetracker.ui.theme.Brand

sealed interface BlockedReason {
    data class Maintenance(val message: String) : BlockedReason
    data object UpdateRequired : BlockedReason
    data object NoCompany : BlockedReason
    data class Locked(val company: Company, val access: AccessState) : BlockedReason
}

/** Full-screen stop shown whenever the platform or the company's plan does not allow use. */
@Composable
fun BlockedScreen(reason: BlockedReason, profile: UserProfile, platform: PlatformConfig) {
    val context = LocalContext.current
    val updates = OfficeTrackerApp.container.updates
    val updateState by updates.state.collectAsStateWithLifecycle()

    val (icon: ImageVector, title: String, message: String) = when (reason) {
        is BlockedReason.Maintenance -> Triple(Icons.Default.Build, "Under maintenance", reason.message)
        BlockedReason.UpdateRequired -> Triple(
            Icons.Default.SystemUpdate, "Update required",
            "This version (${BuildConfig.VERSION_NAME}) is no longer supported. Install the latest version to continue.",
        )
        BlockedReason.NoCompany -> Triple(
            Icons.Default.Business, "No company",
            "Your account is not linked to an active company. Contact your administrator.",
        )
        is BlockedReason.Locked -> when (reason.access) {
            AccessState.SUSPENDED -> Triple(
                Icons.Default.Block, "Account suspended",
                (reason.company.suspendReason?.takeIf { it.isNotBlank() }?.let { "$it\n\n" } ?: "") +
                    if (profile.isAdmin) "Your company's account has been suspended. Contact support to restore access."
                    else "Your company's account is suspended. Please contact your administrator.",
            )
            else -> Triple(
                Icons.Default.EventBusy, "Subscription expired",
                if (profile.isAdmin) "Your ${reason.company.planName} plan ended on ${Dates.day(Dates.keyOf(reason.company.expiresAt))}. Renew to continue tracking. Your data is safe."
                else "Your company's subscription has ended. Please ask your administrator to renew it.",
            )
        }
    }

    Box(Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(72.dp).background(Brand.Warning.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Brand.Warning, modifier = Modifier.size(36.dp))
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)

            if (reason is BlockedReason.Locked && profile.isAdmin) {
                SectionCard {
                    Column {
                        InfoRow("Company", reason.company.name)
                        InfoRow("Plan", reason.company.planName)
                        InfoRow("Expired", Dates.day(Dates.keyOf(reason.company.expiresAt)))
                        InfoRow("Users", "${reason.company.userCount} / ${reason.company.maxUsers}")
                    }
                }
            }
            if (reason is BlockedReason.UpdateRequired) {
                LoadingButton(
                    text = "Check for update", loading = updateState.checking, icon = Icons.Default.SystemUpdate,
                    onClick = { updates.check(force = true) }, modifier = Modifier.fillMaxWidth(),
                )
                updateState.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (reason !is BlockedReason.Maintenance && (profile.isAdmin || reason is BlockedReason.UpdateRequired)) {
                SupportContacts(platform)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { OfficeTrackerApp.container.auth.signOut() }) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Sign out")
            }
        }
    }
}

@Composable
fun SupportContacts(platform: PlatformConfig) {
    val context = LocalContext.current
    if (platform.supportPhone.isBlank() && platform.supportEmail.isBlank()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (platform.supportPhone.isNotBlank()) {
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${platform.supportPhone}"))) }
            }) {
                Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Call support")
            }
        }
        if (platform.supportEmail.isNotBlank()) {
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${platform.supportEmail}"))) }
            }) {
                Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Email")
            }
        }
    }
}
