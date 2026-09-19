@file:OptIn(ExperimentalMaterial3Api::class)

package com.officetracker.ui.superadmin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.officetracker.BuildConfig
import com.officetracker.OfficeTrackerApp
import com.officetracker.core.model.PlatformConfig
import com.officetracker.core.model.UserProfile
import com.officetracker.core.util.Phone
import com.officetracker.ui.appViewModel
import com.officetracker.ui.components.Avatar
import com.officetracker.ui.components.InfoRow
import com.officetracker.ui.components.LoadingButton
import com.officetracker.ui.components.ScreenMessage
import com.officetracker.ui.components.SectionCard
import com.officetracker.ui.components.SectionTitle

@Composable
fun PlatformSettingsScreen(profile: UserProfile) {
    val vm = appViewModel(key = "sa-settings") { PlatformSettingsViewModel(it) }
    val config by vm.config.collectAsStateWithLifecycle()
    val updates = OfficeTrackerApp.container.updates
    val update by updates.state.collectAsStateWithLifecycle()

    var appName by remember(config) { mutableStateOf(config.appName) }
    var grace by remember(config) { mutableStateOf(config.graceDays.toString()) }
    var supportPhone by remember(config) { mutableStateOf(config.supportPhone) }
    var supportEmail by remember(config) { mutableStateOf(config.supportEmail) }
    var announcement by remember(config) { mutableStateOf(config.announcement) }
    var maintenance by remember(config) { mutableStateOf(config.maintenanceMode) }
    var maintenanceMessage by remember(config) { mutableStateOf(config.maintenanceMessage) }
    var minVersion by remember(config) { mutableStateOf(config.minVersionCode.toString()) }
    var changingPassword by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Platform") })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScreenMessage(vm.message ?: update.message) { vm.message = null; updates.clearMessage() }

            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(profile.initials)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(profile.name, style = MaterialTheme.typography.titleMedium)
                        Text("Super admin · ${Phone.pretty(profile.phone)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            SectionTitle("Live switches")
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SwitchRow(
                        "Maintenance mode", maintenance, { maintenance = it },
                        subtitle = "Locks every company's app (not yours) until turned off",
                    )
                    if (maintenance) Field("Maintenance message", maintenanceMessage, { maintenanceMessage = it }, singleLine = false)
                    Field(
                        "Announcement banner", announcement, { announcement = it }, singleLine = false,
                        supporting = "Shown at the bottom of every company app. Leave empty to hide.",
                    )
                    Field(
                        "Minimum app version code", minVersion, { minVersion = it }, number = true,
                        supporting = "Phones below this must update. This build is ${BuildConfig.VERSION_CODE} (v${BuildConfig.VERSION_NAME}).",
                    )
                }
            }

            SectionTitle("Billing & support")
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Product name", appName, { appName = it })
                    Field(
                        "Grace period after expiry (days)", grace, { grace = it }, number = true,
                        supporting = "Companies keep working this long after their plan ends. Applies on the next subscription change.",
                    )
                    Field("Support phone", supportPhone, { supportPhone = it }, phone = true)
                    Field("Support email", supportEmail, { supportEmail = it })
                }
            }
            LoadingButton(
                text = "Save platform settings", loading = vm.busy, modifier = Modifier.fillMaxWidth(),
                onClick = {
                    vm.save(
                        PlatformConfig(
                            appName = appName.trim().ifEmpty { "Office Tracker" },
                            graceDays = grace.toIntOrNull() ?: config.graceDays,
                            supportPhone = supportPhone.trim(),
                            supportEmail = supportEmail.trim(),
                            announcement = announcement.trim(),
                            maintenanceMode = maintenance,
                            maintenanceMessage = maintenanceMessage.trim().ifEmpty { config.maintenanceMessage },
                            minVersionCode = minVersion.toIntOrNull() ?: 0,
                        )
                    )
                },
            )

            SectionTitle("Account")
            SectionCard {
                Column {
                    InfoRow("App version", BuildConfig.VERSION_NAME)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { changingPassword = true }) {
                            Icon(Icons.Default.Key, null); Spacer(Modifier.width(6.dp)); Text("Password")
                        }
                        OutlinedButton(onClick = { if (update.available != null) updates.reopen() else updates.check(force = true) }) {
                            Icon(Icons.Default.SystemUpdate, null); Spacer(Modifier.width(6.dp)); Text("Updates")
                        }
                    }
                }
            }
            TextButton(onClick = vm::signOut, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.Logout, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text("Sign out", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (changingPassword) {
        var current by remember { mutableStateOf("") }
        var new by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { changingPassword = false },
            title = { Text("Change password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(current, { current = it }, label = { Text("Current password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    OutlinedTextField(new, { new = it }, label = { Text("New password (min. 8)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
            },
            confirmButton = {
                TextButton(enabled = current.isNotEmpty() && new.length >= 8 && !vm.busy, onClick = {
                    vm.changePassword(current, new) { changingPassword = false }
                }) { Text("Change") }
            },
            dismissButton = { TextButton(onClick = { changingPassword = false }) { Text("Cancel") } },
        )
    }
}
