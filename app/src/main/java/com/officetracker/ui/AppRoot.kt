package com.officetracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.officetracker.OfficeTrackerApp
import com.officetracker.data.repo.Session
import com.officetracker.ui.auth.AuthScreen
import com.officetracker.ui.update.UpdatePrompt

@Composable
fun AppRoot() {
    val container = OfficeTrackerApp.container
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (!container.firebaseReady) {
            FirebaseMissing()
            return@Surface
        }
        val session by container.auth.session.collectAsStateWithLifecycle()
        when (val s = session) {
            Session.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is Session.SignedOut -> AuthScreen(initialMessage = s.message)
            is Session.SignedIn -> MainShell(profile = s.profile)
        }
        UpdatePrompt()
    }
}

@Composable
private fun FirebaseMissing() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Text("Firebase is not configured", style = MaterialTheme.typography.titleLarge)
        Text(
            "This build has no google-services.json. Add your Firebase project's file to the app/ folder and rebuild. See README.md.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
