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
import com.officetracker.tracking.TrackingService
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
                    }
                    Session.Loading -> Unit
                }
            }
        }
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

    private fun ticker(periodMs: Long) = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(periodMs)
        }
    }
}
