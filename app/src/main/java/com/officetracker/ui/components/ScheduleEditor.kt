@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.officetracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.officetracker.core.model.WorkSchedule

private val WEEK = listOf(6 to "Sat", 7 to "Sun", 1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri")

/**
 * Edits a [WorkSchedule]. Changes are reported through [onChange]; the caller saves them.
 * Once saved, every affected phone re-arms its alarms within seconds.
 */
@Composable
fun ScheduleEditor(schedule: WorkSchedule, onChange: (WorkSchedule) -> Unit) {
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = start, false = end
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleRow("Use a work schedule", schedule.enabled, "Automatic start/end and late marking") {
            onChange(schedule.copy(enabled = it))
        }
        if (schedule.enabled) {
            Text("Working days", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WEEK.forEach { (day, label) ->
                    FilterChip(
                        selected = day in schedule.days,
                        onClick = {
                            val days = if (day in schedule.days) schedule.days - day else schedule.days + day
                            onChange(schedule.copy(days = days))
                        },
                        label = { Text(label) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Schedule, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Start ${WorkSchedule.formatMinute(schedule.startMinute)}")
                }
                OutlinedButton(onClick = { picking = false }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Schedule, null)
                    Spacer(Modifier.width(6.dp))
                    Text("End ${WorkSchedule.formatMinute(schedule.endMinute)}")
                }
            }
            if (schedule.endMinute <= schedule.startMinute) {
                Text("End time must be after start time.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            ToggleRow("Start tracking automatically", schedule.autoStart, "At the start time on working days") {
                onChange(schedule.copy(autoStart = it))
            }
            ToggleRow("End the day automatically", schedule.autoEnd, "At the end time, even if someone forgets") {
                onChange(schedule.copy(autoEnd = it))
            }
            ToggleRow("Lock tracking during working hours", schedule.enforce, "Staff can't pause or end their day between start and end") {
                onChange(schedule.copy(enforce = it))
            }
            OutlinedTextField(
                value = schedule.lateAfterMinutes.toString(),
                onValueChange = { v -> v.filter { it.isDigit() }.take(3).toIntOrNull()?.let { onChange(schedule.copy(lateAfterMinutes = it.coerceIn(0, 240))) } },
                label = { Text("Late after (minutes)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    picking?.let { isStart ->
        val initial = if (isStart) schedule.startMinute else schedule.endMinute
        val state = rememberTimePickerState(initialHour = initial / 60, initialMinute = initial % 60, is24Hour = false)
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(if (isStart) "Work starts at" else "Work ends at") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val minute = state.hour * 60 + state.minute
                    onChange(if (isStart) schedule.copy(startMinute = minute) else schedule.copy(endMinute = minute))
                    picking = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, subtitle: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** One-line description, e.g. "Sat, Sun, Mon · 9:00 AM – 6:00 PM · auto start & end". */
fun WorkSchedule.summary(): String {
    if (!enabled) return "No schedule"
    val lock = if (enforce) " · locked" else ""
    val auto = when {
        autoStart && autoEnd -> " · auto start & end"
        autoStart -> " · auto start"
        autoEnd -> " · auto end"
        else -> ""
    }
    return "${daysLabel()} · ${WorkSchedule.formatMinute(startMinute)} – ${WorkSchedule.formatMinute(endMinute)}$auto$lock"
}
