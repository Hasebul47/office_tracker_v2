package com.officetracker.tracking

import android.content.Context
import com.officetracker.BuildConfig
import com.officetracker.core.model.LiveState
import com.officetracker.core.model.WorkStatus
import com.officetracker.core.model.Workday
import com.officetracker.data.repo.AuthRepository
import com.officetracker.data.repo.NamedLocation
import com.officetracker.data.repo.OrgRepository
import com.officetracker.data.repo.WorkdayRepository

/** Single entry point for workday actions, used by the UI, the notification and boot receiver. */
class TrackingController(
    private val context: Context,
    private val auth: AuthRepository,
    private val workdays: WorkdayRepository,
    private val org: OrgRepository,
    private val locationClient: LocationClient,
    private val namer: PlaceNamer,
) {
    private fun uid(): String = auth.currentUid ?: error("Not signed in.")

    suspend fun startDay(): Result<Unit> = runCatching {
        val uid = uid()
        check(locationClient.hasForegroundPermission()) { "Location permission is required to start your day." }
        check(locationClient.isLocationEnabled()) { "Turn on Location (GPS) to start your day." }
        val open = workdays.openWorkday(uid)
        if (open?.status == WorkStatus.PAUSED) {
            workdays.resume(uid, System.currentTimeMillis())
        } else if (open == null || open.status != WorkStatus.ACTIVE) {
            val here = currentNamedLocation(uid)
            workdays.startDay(uid, System.currentTimeMillis(), here)
        }
        TrackingService.start(context)
        publishStatus(uid, WorkStatus.ACTIVE, here = null)
    }

    suspend fun pause(): Result<Unit> = runCatching {
        val uid = uid()
        workdays.pause(uid, System.currentTimeMillis())
        TrackingService.stop(context)
        publishStatus(uid, WorkStatus.PAUSED, here = null)
    }

    suspend fun resume(): Result<Unit> = runCatching {
        val uid = uid()
        check(locationClient.hasForegroundPermission()) { "Location permission is required." }
        workdays.resume(uid, System.currentTimeMillis())
        TrackingService.start(context)
        publishStatus(uid, WorkStatus.ACTIVE, here = null)
    }

    suspend fun endDay(): Result<Unit> = runCatching {
        val uid = uid()
        val here = currentNamedLocation(uid, timeoutMs = 6_000)
        workdays.endDay(uid, System.currentTimeMillis(), here)
        TrackingService.stop(context)
        publishStatus(uid, WorkStatus.ENDED, here)
    }

    /** Restarts the service if the app was killed while a workday was active. */
    suspend fun ensureServiceState() {
        val uid = auth.currentUid ?: return
        val open = workdays.openWorkday(uid)
        if (open?.status == WorkStatus.ACTIVE && locationClient.hasForegroundPermission()) {
            TrackingService.start(context)
        }
    }

    private suspend fun currentNamedLocation(uid: String, timeoutMs: Long = 10_000): NamedLocation? {
        val loc = locationClient.currentLocation(timeoutMs) ?: return null
        val label = namer.label(uid, loc.latitude, loc.longitude)
        return NamedLocation(loc.latitude, loc.longitude, label.name)
    }

    private suspend fun publishStatus(uid: String, status: WorkStatus, here: NamedLocation?) {
        val day: Workday? = workdays.openWorkday(uid)
        val health = DeviceStatus.read(context)
        org.publishLive(
            LiveState(
                uid = uid,
                status = status,
                latitude = here?.latitude ?: day?.startLatitude,
                longitude = here?.longitude ?: day?.startLongitude,
                accuracy = null,
                placeName = here?.name,
                batteryPercent = health.batteryPercent,
                charging = health.charging,
                gpsEnabled = locationClient.isLocationEnabled(),
                mockLocation = false,
                distanceMeters = day?.distanceMeters ?: 0.0,
                appVersion = BuildConfig.VERSION_NAME,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }
}
