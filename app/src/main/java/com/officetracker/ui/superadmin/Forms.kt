package com.officetracker.ui.superadmin

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.officetracker.core.model.AccessState
import com.officetracker.core.model.Company
import com.officetracker.core.model.Subscription
import com.officetracker.core.util.Dates

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    number: Boolean = false,
    decimal: Boolean = false,
    phone: Boolean = false,
    singleLine: Boolean = true,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v ->
            onChange(
                when {
                    decimal -> v.filter { it.isDigit() || it == '.' }.take(10)
                    number -> v.filter { it.isDigit() }.take(7)
                    phone -> v.filter { it.isDigit() || it == '+' }.take(14)
                    else -> v.take(if (singleLine) 80 else 500)
                }
            )
        },
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                decimal -> KeyboardType.Decimal
                number -> KeyboardType.Number
                phone -> KeyboardType.Phone
                else -> KeyboardType.Text
            }
        ),
        modifier = modifier,
    )
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

fun Company.expiryText(): String =
    if (expiresAt >= Subscription.LIFETIME_EXPIRY) "Lifetime" else Dates.day(Dates.keyOf(expiresAt))

fun Company.statusLine(now: Long): String {
    val a = access(now)
    return when (a) {
        AccessState.TRIAL, AccessState.ACTIVE ->
            if (expiresAt >= Subscription.LIFETIME_EXPIRY) "Lifetime" else "${daysLeft(now)} days left"
        AccessState.GRACE -> "Expired ${Dates.shortDay(Dates.keyOf(expiresAt))} · in grace"
        AccessState.EXPIRED -> "Expired ${Dates.shortDay(Dates.keyOf(expiresAt))}"
        AccessState.SUSPENDED -> "Suspended"
    }
}
