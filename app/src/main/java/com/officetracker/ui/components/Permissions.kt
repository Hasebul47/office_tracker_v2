package com.officetracker.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority

fun Context.has(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

fun Context.hasPreciseLocation(): Boolean = has(Manifest.permission.ACCESS_FINE_LOCATION)

fun Context.hasBackgroundLocation(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

fun Context.hasNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || has(Manifest.permission.POST_NOTIFICATIONS)

fun Context.isIgnoringBatteryOptimizations(): Boolean =
    (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(packageName) ?: true

@SuppressLint("BatteryLife")
fun Context.batteryOptimizationIntent(): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))

fun Context.exactAlarmSettingsIntent(): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
    } else {
        appSettingsIntent()
    }

/**
 * Phone makers that block background start-up unless the user enables "Autostart"
 * (without it, schedule alarms and the watchdog never run while the app is closed).
 */
fun Context.autostartIntent(): Intent? {
    val maker = Build.MANUFACTURER.lowercase()
    val candidates = when {
        maker in listOf("xiaomi", "redmi", "poco") -> listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        )
        maker in listOf("oppo", "realme", "oneplus") -> listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        )
        maker == "vivo" -> listOf(
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        )
        maker in listOf("huawei", "honor") -> listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        )
        else -> return null
    }
    return candidates
        .map { (pkg, cls) -> Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        .firstOrNull { it.resolveActivity(packageManager) != null }
        ?: appSettingsIntent()
}

fun Context.appSettingsIntent(): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

fun Context.safeStart(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: Exception) {
        runCatching { startActivity(appSettingsIntent()) }
    }
}

/** A counter that changes every time the screen resumes, so permission checks refresh. */
@Composable
fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    return tick
}

/**
 * Returns an action that makes sure precise location permission (and notification permission on
 * Android 13+) is granted and that device Location is switched on, then calls [onReady].
 */
@Composable
fun rememberLocationReadyAction(onReady: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val ready by rememberUpdatedState(onReady)

    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) ready()
        else Toast.makeText(context, "Location must be on to track your workday.", Toast.LENGTH_LONG).show()
    }

    val checkSettings: () -> Unit = remember(context) {
        {
            val request = LocationSettingsRequest.Builder()
                .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000).build())
                .build()
            LocationServices.getSettingsClient(context).checkLocationSettings(request)
                .addOnSuccessListener { ready() }
                .addOnFailureListener { e ->
                    if (e is ResolvableApiException) {
                        runCatching { settingsLauncher.launch(IntentSenderRequest.Builder(e.resolution).build()) }
                            .onFailure { ready() }
                    } else {
                        ready()
                    }
                }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        when {
            grants[Manifest.permission.ACCESS_FINE_LOCATION] == true -> checkSettings()
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true ->
                Toast.makeText(context, "Please allow \"Precise\" location - approximate location cannot record visits.", Toast.LENGTH_LONG).show()
            else -> {
                Toast.makeText(context, "Location permission is required. Enable it in app settings.", Toast.LENGTH_LONG).show()
                context.safeStart(context.appSettingsIntent())
            }
        }
    }

    return remember(context) {
        {
            if (context.hasPreciseLocation() && context.hasNotificationPermission()) {
                checkSettings()
            } else {
                val perms = buildList {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                }
                permissionLauncher.launch(perms.toTypedArray())
            }
        }
    }
}

@Composable
fun rememberBackgroundLocationAction(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) context.safeStart(context.appSettingsIntent())
    }
    return remember(context) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) launcher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
}
