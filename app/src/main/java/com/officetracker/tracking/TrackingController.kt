package com.officetracker.tracking

import android.content.Context
import com.officetracker.BuildConfig
import com.officetracker.core.model.AccessState
import com.officetracker.core.model.Attendance
import com.officetracker.core.model.Place
import com.officetracker.core.util.Format
import com.officetracker.core.util.Geo
import com.officetracker.core.model.LiveState
import com.officetracker.core.model.WorkStatus
import com.officetracker.core.util.Dates
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

    /** Blocks punching in when the company requires it to happen inside an office zone. */
    private suspend fun checkInsideZone() {
        val settings = org.config.value.attendance
        if (!org.features.value.attendance || !settings.enabled || !settings.requireZoneToPunch) return
        if (org.places.value.none { it.attendance }) return // no zones defined yet
        if (currentZone() != null) return
        val nearest = nearestZone()
        error(
            if (nearest != null) "You are ${Format.distance(nearest.second)} from ${nearest.first.name}. Punch in at the office."
            else "You must be at an office location to punch in."
        )
    }

    /** Blocks tracking when the company's subscription does not allow it. */
    private fun checkPlan() {
        val company = org.currentCompany ?: return // unknown offline: allow, the cloud re-checks on upload
        val access = company.access(System.currentTimeMillis())
        check(access.usable) {
            when (access) {
                AccessState.SUSPENDED -> "Your company's account is suspended. Contact your administrator."
                else -> "Your company's subscription has expired. Contact your administrator to renew."
            }
        }
    }

    /** Called when the subscription stops being usable: pause an active day and stop GPS. */
    suspend fun lockedByPlan() {
        val uid = auth.currentUid ?: return
        val open = workdays.openWorkday(uid) ?: return
        if (open.status == WorkStatus.ACTIVE) {
            workdays.pause(uid, System.currentTimeMillis())
        }
        TrackingService.stop(context)
    }

    /** Is the phone inside an attendance zone right now? Null when it cannot get a fix. */
    suspend fun currentZone(): Place? {
        val loc = locationClient.currentLocation(8_000) ?: return null
        return Attendance.zoneAt(org.places.value, loc.latitude, loc.longitude)
    }

    /** Nearest attendance zone and how far away it is, for the "you are too far" message. */
    suspend fun nearestZone(): Pair<Place, Double>? {
        val loc = locationClient.currentLocation(8_000) ?: return null
        return org.places.value.filter { it.attendance }
            .map { it to Geo.distanceMeters(loc.latitude, loc.longitude, it.latitude, it.longitude) }
            .minByOrNull { it.second }
    }

    /**
     * [manual] = the employee tapped Punch in / Start. Then the company's attendance rule can
     * require them to be inside an office zone; the scheduler starts the day regardless, and
     * attendance simply begins when they arrive.
     */
    suspend fun startDay(manual: Boolean = true): Result<Unit> = runCatching {
        val uid = uid()
        checkPlan()
        check(locationClient.hasForegroundPermission()) { "Location permission is required to start your day." }
        check(locationClient.isLocationEnabled()) { "Turn on Location (GPS) to start your day." }
        // After the permission / GPS checks, so a missing fix is not reported as "you are too far".
        if (manual) checkInsideZone()
        val now = System.currentTimeMillis()
        val open = workdays.openWorkday(uid)
        val staleOpen = open != null && open.date != Dates.keyOf(now)
        var createdDate: String? = null
        when {
            open?.status == WorkStatus.PAUSED && !staleOpen -> workdays.resume(uid, now)
            open == null || staleOpen || open.status != WorkStatus.ACTIVE -> {
                // Create the day and start GPS straight away; the start place is filled in once a
                // fix arrives (waiting first could outlast Android's background-start allowance).
                createdDate = workdays.startDay(uid, now, null).date
            }
        }
        if (!TrackingService.start(context)) {
            error("Android did not allow tracking to start in the background. Open the app to start it.")
        }
        publishStatus(uid, WorkStatus.ACTIVE, here = null)
        createdDate?.let { date ->
            currentNamedLocation(uid)?.let { here -> workdays.fillStart(uid, date, here) }
        }
    }

    /** Set by the container; lets the controller respect a locked work schedule. */
    var scheduleLock: () -> Boolean = { false }

    private fun checkNotLocked() {
        check(!scheduleLock()) { "Your company requires tracking during working hours. It stops automatically at the end time." }
    }

    suspend fun pause(manual: Boolean = true): Result<Unit> = runCatching {
        if (manual) checkNotLocked()
        val uid = uid()
        workdays.pause(uid, System.currentTimeMillis())
        TrackingService.stop(context)
        publishStatus(uid, WorkStatus.PAUSED, here = null)
    }

    suspend fun resume(manual: Boolean = true): Result<Unit> = runCatching {
        val uid = uid()
        checkPlan()
        check(locationClient.hasForegroundPermission()) { "Location permission is required." }
        if (manual) checkInsideZone()
        workdays.resume(uid, System.currentTimeMillis())
        TrackingService.start(context)
        publishStatus(uid, WorkStatus.ACTIVE, here = null)
    }

    suspend fun endDay(manual: Boolean = true): Result<Unit> = runCatching {
        if (manual) checkNotLocked()
        val uid = uid()
        val date = workdays.openWorkday(uid)?.date
        val here = currentNamedLocation(uid, timeoutMs = 6_000)
        workdays.endDay(uid, System.currentTimeMillis(), here)
        TrackingService.stop(context)
        publishStatus(uid, WorkStatus.ENDED, here)
        // No fresh fix: the last route point was used; give it a name too.
        if (here == null && date != null) {
            workdays.endPointNeedingName(uid, date)?.let { (lat, lng) ->
                workdays.fillEndName(uid, date, namer.label(uid, lat, lng).name)
            }
        }
    }

    /** Restarts the service if the app was killed while a workday was active. */
    suspend fun ensureServiceState() {
        val uid = auth.currentUid ?: return
        val open = workdays.openWorkday(uid)
        val planOk = org.currentCompany?.access(System.currentTimeMillis())?.usable ?: true
        if (open?.status == WorkStatus.ACTIVE && planOk && locationClient.hasForegroundPermission()) {
            TrackingService.start(context)
        }
    }

    private suspend fun currentNamedLocation(uid: String, timeoutMs: Long = 10_000): NamedLocation? {
        val loc = locationClient.currentLocation(timeoutMs) ?: return null
        val label = namer.label(uid, loc.latitude, loc.longitude)
        return NamedLocation(loc.latitude, loc.longitude, label.name)
    }

    private suspend fun publishStatus(uid: String, status: WorkStatus, here: NamedLocation?) {
        // Off duty: clear the attendance zone so the team list stops showing "At <office>".
        val offDuty = status != WorkStatus.ACTIVE
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
                inZone = if (offDuty) false else null,
                zoneName = null,
                distanceMeters = day?.distanceMeters ?: 0.0,
                appVersion = BuildConfig.VERSION_NAME,
                updatedAt = System.currentTimeMillis(),
                dayStartedAt = day?.startedAt,
            )
        )
    }
}
