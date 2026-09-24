package com.officetracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MoreTime
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.officetracker.core.model.Attendance
import com.officetracker.core.model.AttendanceSettings
import com.officetracker.core.model.LiveState
import com.officetracker.core.model.Workday
import com.officetracker.core.util.Dates
import com.officetracker.core.util.Format
import com.officetracker.ui.theme.Brand

/** Punch card: where the person is, when they checked in / out, hours inside and overtime. */
@Composable
fun AttendanceCard(
    day: Workday?,
    settings: AttendanceSettings,
    showOt: Boolean,
    inZone: Boolean?,
    zoneName: String?,
    modifier: Modifier = Modifier,
) {
    SectionCard(modifier) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (inZone == true) Icons.Default.Business else Icons.Default.LocationOff,
                    contentDescription = null,
                    tint = if (inZone == true) Brand.Success else Brand.Muted,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Attendance", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            inZone == true -> "At ${zoneName ?: "the office"}"
                            inZone == false -> "Outside the office area"
                            else -> "Location not known yet"
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (inZone != null) StatusPill(if (inZone) "Inside" else "Outside", if (inZone) Brand.Success else Brand.Muted)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PunchTime("Punch in", day?.checkInAt, day?.checkInPlace, Icons.Default.Login, Brand.Success, Modifier.weight(1f))
                PunchTime("Punch out", day?.checkOutAt, day?.checkOutPlace, Icons.Default.Logout, Brand.Danger, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            InfoRow("Time at office", Dates.duration(day?.insideMillis ?: 0))
            if (showOt && settings.otEnabled) {
                val ot = day?.otMillis ?: 0
                InfoRow("Overtime", if (ot > 0) Dates.duration(ot) else "—")
                val payable = Attendance.payableOtMillis(day, settings)
                if (payable > 0 && settings.otRatePerHour > 0) {
                    InfoRow("OT amount", Format.taka(Attendance.otAmount(day, settings)))
                }
                if (ot > 0 && payable == 0L) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MoreTime, contentDescription = null, tint = Brand.Muted, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (settings.otRequiresApproval && day?.otApproved != true) "Your administrator approves overtime before it is paid"
                            else "Below the ${settings.otMinMinutes}-minute minimum",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PunchTime(
    label: String,
    time: Long?,
    place: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(time?.let { Dates.time(it) } ?: "—", style = MaterialTheme.typography.titleMedium)
        place?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** Live attendance state of a team member, for the admin's list. */
fun LiveState?.zoneLabel(): String? = when {
    this == null || inZone == null -> null
    inZone -> "At ${zoneName ?: "office"}"
    else -> "Outside office"
}
