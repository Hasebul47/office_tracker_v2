package com.officetracker.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.officetracker.BuildConfig
import com.officetracker.OfficeTrackerApp
import com.officetracker.ui.components.safeStart

@Composable
fun UpdatePrompt() {
    val updates = OfficeTrackerApp.container.updates
    val state by updates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Checked when the app opens and every time it comes back to the foreground (at most every 30 min).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updates.check(force = false) }

    val info = state.available
    if (!state.showDialog || info == null) return

    AlertDialog(
        onDismissRequest = { if (state.progress == null) updates.dismiss(skipVersion = false) },
        icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
        title = { Text("Update available: v${info.version}") },
        text = {
            Column {
                Text("You have v${BuildConfig.VERSION_NAME}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (info.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                        Text(info.notes, style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.progress?.let { p ->
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text("Downloading ${(p * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                }
                state.message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            when {
                state.downloaded != null -> TextButton(onClick = {
                    if (!updates.install()) context.safeStart(updates.installPermissionIntent())
                }) { Text("Install") }
                state.progress != null -> TextButton(onClick = {}, enabled = false) { Text("Downloading…") }
                else -> TextButton(onClick = { updates.download() }) { Text("Download") }
            }
        },
        dismissButton = {
            if (state.progress == null) {
                // "Later" only hides it; it comes back next time the app is opened.
                TextButton(onClick = { updates.dismiss(skipVersion = false) }) { Text("Later") }
            }
        },
    )
}
