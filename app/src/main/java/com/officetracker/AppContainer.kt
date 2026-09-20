package com.officetracker

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.officetracker.data.local.AppDatabase
import com.officetracker.data.local.SessionStore
import com.officetracker.data.repo.AuthRepository
import com.officetracker.data.repo.CloudDayRepository
import com.officetracker.data.repo.CompanyState
import com.officetracker.data.repo.LoginCreator
import com.officetracker.data.repo.OrgRepository
import com.officetracker.data.repo.PlatformRepository
import com.officetracker.data.repo.Session
import com.officetracker.data.repo.UserRepository
import com.officetracker.data.repo.WorkdayRepository
import com.officetracker.data.sync.CloudUploader
import com.officetracker.data.sync.SyncScheduler
import com.officetracker.report.ReportExporter
import com.officetracker.tracking.LocationClient
import com.officetracker.tracking.PlaceNamer
import com.officetracker.tracking.TrackingController
import com.officetracker.tracking.TrackingProcessor
import com.officetracker.tracking.ScheduleManager
import com.officetracker.tracking.TrackingService
import com.officetracker.tracking.WatchdogWorker
import com.officetracker.update.UpdateController
import com.officetracker.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Manual dependency injection: one place that wires the app together. */
class AppContainer(private val context: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** False when google-services.json is missing; the UI then shows setup instructions. */
    val firebaseReady: Boolean = FirebaseApp.getApps(context).isNotEmpty()

    val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val firebaseAuth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    val db: AppDatabase by lazy { AppDatabase.build(context) }
    val store = SessionStore(context)
    val sync = SyncScheduler(context)
    val locationClient = LocationClient(context)

    val auth: AuthRepository by lazy { AuthRepository(firebaseAuth, firestore, store, appScope) }
    val org: OrgRepository by lazy { OrgRepository(firestore) }
    private val logins by lazy { LoginCreator(context) }
    val users: UserRepository by lazy { UserRepository(firestore, logins) }
    val platform: PlatformRepository by lazy { PlatformRepository(firestore, logins) }
    val cloudDays: CloudDayRepository by lazy { CloudDayRepository(firestore) }
    val workdays: WorkdayRepository by lazy { WorkdayRepository(db, sync) }
    val uploader: CloudUploader by lazy { CloudUploader(db, firestore) }
    val namer: PlaceNamer by lazy { PlaceNamer(context, org, db.trackerDao()) }
    val tracking: TrackingController by lazy {
        TrackingController(context, auth, workdays, org, locationClient, namer)
    }
    val reports: ReportExporter by lazy { ReportExporter(context) }
    val updates: UpdateController by lazy { UpdateController(UpdateManager(context, store), appScope) }

    val scheduler: ScheduleManager by lazy { ScheduleManager(context, auth, org) }

    fun newProcessor(uid: String) = TrackingProcessor(context, uid, db, org, namer, sync, locationClient)

    /** Keeps shared listeners and background work in step with the signed-in user. */
    fun start() {
        if (!firebaseReady) return
        appScope.launch {
            var wasSignedIn = false
            auth.session.collect { session ->
                when (session) {
                    is Session.SignedIn -> {
                        wasSignedIn = true
                        platform.startListening()
                        val cid = session.profile.companyId
                        if (session.profile.isSuperAdmin || cid == null) {
                            org.stopListening()
                        } else {
                            org.startListening(cid)
                            WatchdogWorker.schedule(context)
                            sync.schedulePeriodic()
                            sync.requestSync(expedited = true)
                            tracking.ensureServiceState()
                        }
                    }
                    is Session.SignedOut -> {
                        if (wasSignedIn) TrackingService.stop(context)
                        wasSignedIn = false
                        org.stopListening()
                        platform.stopListening()
                        sync.cancelAll()
                        scheduler.cancel()
                    }
                    Session.Loading -> Unit
                }
            }
        }
        watchDeviceAndSchedule()
        // Subscription enforcement: re-evaluated on every company change and once a minute, so a
        // suspension or an expiry takes effect on the phone without a restart.
        appScope.launch {
            combine(org.company, ticker(60_000)) { state, now -> (state as? CompanyState.Loaded)?.company?.access(now) }
                .distinctUntilChanged()
                .collect { access ->
                    if (access != null && !access.usable) tracking.lockedByPlan()
                }
        }
    }

    /**
     * One device per account: if the company requires it and the account was signed in on
     * another phone (or an admin signed it out), pause tracking here and sign out.
     * Also re-arms the auto start/end alarms whenever the profile or company changes.
     */
    private fun watchDeviceAndSchedule() {
        appScope.launch {
            // Claim at most once per account per process: if the write is rejected (e.g. rules not
            // yet published) the rolled-back snapshot shows null again and would re-claim forever.
            var claimedFor: String? = null
            combine(auth.session, org.company) { s, c -> s to c }.collect { (session, companyState) ->
                val profile = (session as? Session.SignedIn)?.profile ?: return@collect
                if (profile.isSuperAdmin) return@collect
                val company = (companyState as? CompanyState.Loaded)?.company ?: return@collect
                scheduler.reschedule()
                if (auth.claimingDevice) return@collect
                val active = profile.activeDeviceId
                val mine = auth.deviceId()
                when {
                    active == SessionStore.CACHED_DEVICE -> Unit // offline start: wait for the real profile
                    active == null && claimedFor == profile.uid -> Unit
                    active == null -> { // accounts from before this feature
                        claimedFor = profile.uid
                        auth.claimDevice(profile.uid)
                    }
                    active.startsWith(UserRepository.REVOKED_PREFIX) -> kickOut("You were signed out by your administrator.")
                    company.features.singleDevice && active != mine ->
                        kickOut("Your account was signed in on another phone (${profile.activeDeviceName ?: "unknown"}). Only one phone can be used at a time.")
                }
            }
        }
    }

    private suspend fun kickOut(message: String) {
        val uid = auth.currentUid
        tracking.lockedByPlan()
        // Upload what this phone recorded before it loses access.
        if (uid != null) withTimeoutOrNull(10_000) { runCatching { uploader.uploadPending(uid) } }
        auth.signOutWithMessage(message)
    }

    private fun ticker(periodMs: Long) = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(periodMs)
        }
    }
}
