package com.officetracker.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.officetracker.AppContainer
import com.officetracker.R
import com.officetracker.ui.appViewModel
import com.officetracker.ui.components.Banner
import com.officetracker.ui.components.LoadingButton
import kotlinx.coroutines.launch

class AuthViewModel(private val c: AppContainer) : ViewModel() {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun signIn(phone: String, password: String) = launchAction { c.auth.signIn(phone, password) }

    fun setup(name: String, phone: String, password: String, confirm: String) {
        if (password != confirm) {
            error = "Passwords do not match."
            return
        }
        launchAction { c.auth.setupOrganization(name, phone, password) }
    }

    fun clearError() { error = null }

    private fun launchAction(block: suspend () -> Result<Unit>) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            block().onFailure { error = it.message ?: "Something went wrong." }
            busy = false
        }
    }
}

@Composable
fun AuthScreen(initialMessage: String?) {
    val vm = appViewModel { AuthViewModel(it) }
    var setupMode by rememberSaveable { mutableStateOf(false) }
    var phone by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var dismissedInitial by rememberSaveable { mutableStateOf(false) }

    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(primary.copy(alpha = 0.14f), MaterialTheme.colorScheme.background))
        )
    ) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground_art),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(84.dp).clip(RoundedCornerShape(22.dp)),
            )
            Spacer(Modifier.height(16.dp))
            Text("Office Tracker", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (setupMode) "Set up your organisation" else "Sign in with your work phone number",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))

            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val message = vm.error ?: initialMessage.takeUnless { dismissedInitial }
                if (message != null) {
                    Banner(message, Icons.Default.Warning, MaterialTheme.colorScheme.error, actionLabel = "OK", onAction = {
                        vm.clearError(); dismissedInitial = true
                    })
                }
                if (setupMode) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(50) }, label = { Text("Your name") },
                        leadingIcon = { Icon(Icons.Default.Badge, null) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                }
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it.filter { ch -> ch.isDigit() || ch == '+' }.take(14) },
                    label = { Text("Mobile number") }, placeholder = { Text("01XXXXXXXXX") },
                    leadingIcon = { Icon(Icons.Default.Phone, null) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it.take(64) }, label = { Text("Password") },
                    leadingIcon = { Icon(Icons.Default.Lock, null) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Show password")
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = if (setupMode) ImeAction.Next else ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (!setupMode) vm.signIn(phone, password) }),
                )
                if (setupMode) {
                    OutlinedTextField(
                        value = confirm, onValueChange = { confirm = it.take(64) }, label = { Text("Confirm password") },
                        leadingIcon = { Icon(Icons.Default.Lock, null) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    )
                }
                Spacer(Modifier.height(4.dp))
                LoadingButton(
                    text = if (setupMode) "Create administrator" else "Sign in",
                    loading = vm.busy,
                    icon = if (setupMode) Icons.Default.AdminPanelSettings else null,
                    onClick = { if (setupMode) vm.setup(name, phone, password, confirm) else vm.signIn(phone, password) },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = { setupMode = !setupMode; vm.clearError() },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(if (setupMode) "Back to sign in" else "First time? Set up organisation")
                }
                if (!setupMode) {
                    Text(
                        "Forgot your password? Ask your administrator.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                } else {
                    Text(
                        "This works only once, for the very first administrator. Everyone else is added from the Team tab.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
