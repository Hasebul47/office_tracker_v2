package com.officetracker.data.repo

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.officetracker.core.model.AppConfig
import com.officetracker.core.model.Billing
import com.officetracker.core.model.Company
import com.officetracker.core.model.Payment
import com.officetracker.core.model.Plan
import com.officetracker.core.model.PlatformConfig
import com.officetracker.core.model.Role
import com.officetracker.core.model.Subscription
import com.officetracker.core.model.UserProfile
import com.officetracker.core.util.Phone
import com.officetracker.data.remote.Mappers
import com.officetracker.data.remote.Paths
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

data class NewCompanyInput(
    val name: String,
    val contactName: String,
    val contactPhone: String,
    val email: String,
    val address: String,
    val adminName: String,
    val adminPhone: String,
    val adminPassword: String,
)

/**
 * Platform-level data. Everyone signed in listens to [platform] (maintenance mode, announcement,
 * minimum app version); only the super admin uses the management functions.
 */
class PlatformRepository(
    private val db: FirebaseFirestore,
    private val logins: LoginCreator,
) {
    private val _platform = MutableStateFlow(PlatformConfig())
    val platform: StateFlow<PlatformConfig> = _platform.asStateFlow()
    private var platformReg: ListenerRegistration? = null

    fun startListening() {
        if (platformReg != null) return
        platformReg = Paths.platformConfig(db).addSnapshotListener { doc, _ ->
            if (doc != null && (doc.exists() || !doc.metadata.isFromCache)) _platform.value = Mappers.platform(doc)
        }
    }

    fun stopListening() {
        platformReg?.remove(); platformReg = null
    }

    suspend fun savePlatform(config: PlatformConfig): Result<Unit> = runCatching {
        require(config.graceDays in 0..60) { "Grace period must be 0-60 days." }
        Paths.platformConfig(db).set(Mappers.platformMap(config), SetOptions.merge()).await()
        Unit
    }.mapError()

    // ---------- Plans ----------

    fun observePlans(): Flow<List<Plan>> = callbackFlow {
        val reg = db.collection(Paths.PLANS).addSnapshotListener { snap, error ->
            if (error != null) {
                close(error.toFriendly())
                return@addSnapshotListener
            }
            trySend(snap?.documents.orEmpty().map { Mappers.plan(it) }.sortedWith(compareBy({ it.sortOrder }, { it.price })))
        }
        awaitClose { reg.remove() }
    }

    suspend fun savePlan(plan: Plan): Result<Unit> = runCatching {
        require(plan.name.isNotBlank()) { "Enter a plan name." }
        require(plan.maxUsers >= 1) { "Allow at least 1 user." }
        require(plan.maxAdmins >= 1) { "Allow at least 1 administrator." }
        require(plan.billing == Billing.LIFETIME || plan.durationDays >= 1) { "Duration must be at least 1 day." }
        val col = db.collection(Paths.PLANS)
        val ref = if (plan.id.isBlank()) col.document() else col.document(plan.id)
        ref.set(Mappers.planMap(plan)).await()
        Unit
    }.mapError()

    suspend fun deletePlan(id: String): Result<Unit> = runCatching {
        db.collection(Paths.PLANS).document(id).delete().await()
        Unit
    }.mapError()

    // ---------- Companies ----------

    fun observeCompanies(): Flow<List<Company>> = callbackFlow {
        val reg = db.collection(Paths.COMPANIES).addSnapshotListener { snap, error ->
            if (error != null) {
                close(error.toFriendly())
                return@addSnapshotListener
            }
            trySend(snap?.documents.orEmpty().mapNotNull { Mappers.company(it) }.sortedBy { it.name.lowercase() })
        }
        awaitClose { reg.remove() }
    }

    fun observeCompany(cid: String): Flow<Company?> = callbackFlow {
        val reg = Paths.company(db, cid).addSnapshotListener { doc, error ->
            if (error != null) {
                close(error.toFriendly())
                return@addSnapshotListener
            }
            trySend(doc?.let { Mappers.company(it) })
        }
        awaitClose { reg.remove() }
    }

    /** Creates a company on [plan] together with its first administrator login. */
    suspend fun createCompany(input: NewCompanyInput, plan: Plan): Result<Company> = runCatching {
        require(input.name.isNotBlank()) { "Enter the company name." }
        require(input.adminName.isNotBlank()) { "Enter the administrator's name." }
        require(Phone.isValid(input.adminPhone)) { "Enter a valid mobile number for the administrator." }
        require(input.adminPassword.length >= 6) { "Administrator password must be at least 6 characters." }
        val now = System.currentTimeMillis()
        val grace = platform.value.graceDays
        val ref = db.collection(Paths.COMPANIES).document()
        val expires = expiryFor(plan, now, now)
        val company = Company(
            id = ref.id, name = input.name.trim(), contactName = input.contactName.trim(),
            contactPhone = Phone.normalize(input.contactPhone.ifBlank { input.adminPhone }),
            email = input.email.trim(), address = input.address.trim(), notes = "",
            planId = plan.id, planName = plan.name, billing = plan.billing, price = plan.price,
            suspended = false, suspendReason = null, startedAt = now,
            expiresAt = expires, accessUntil = Subscription.accessUntil(expires, grace),
            maxUsers = plan.maxUsers, maxAdmins = plan.maxAdmins, userCount = 1, adminCount = 1,
            features = plan.features, settings = AppConfig(), createdAt = now,
        )
        val adminPhone = Phone.normalize(input.adminPhone)
        val uid = logins.create(adminPhone, input.adminPassword)
        val admin = UserProfile(
            uid = uid, name = input.adminName.trim(), phone = adminPhone, role = Role.ADMIN,
            department = "Management", disabled = false, createdAt = now, companyId = ref.id,
        )
        db.batch()
            .set(ref, Mappers.newCompanyMap(company))
            .set(Paths.user(db, uid), Mappers.profileMap(admin))
            .commit().await()
        company
    }.mapError()

    suspend fun updateCompanyProfile(company: Company): Result<Unit> = runCatching {
        require(company.name.isNotBlank()) { "Company name cannot be empty." }
        Paths.company(db, company.id).update(Mappers.companyProfileMap(company)).await()
        Unit
    }.mapError()

    /** Saves any subscription / limit / feature change; access window is recomputed. */
    suspend fun updateSubscription(company: Company): Result<Unit> = runCatching {
        require(company.maxUsers >= 1) { "Allow at least 1 user." }
        require(company.maxAdmins >= 1) { "Allow at least 1 administrator." }
        val fixed = company.copy(accessUntil = Subscription.accessUntil(company.expiresAt, platform.value.graceDays))
        Paths.company(db, company.id).update(Mappers.subscriptionMap(fixed)).await()
        Unit
    }.mapError()

    /**
     * Moves a company to [plan]. With [renew] the plan's period is added to the current expiry
     * (renewal/upgrade); otherwise the new period starts today.
     */
    suspend fun assignPlan(company: Company, plan: Plan, renew: Boolean): Result<Unit> {
        val now = System.currentTimeMillis()
        // A lifetime expiry is not a period to add to: switching away from it starts today.
        val base = if (renew && company.expiresAt < Subscription.LIFETIME_EXPIRY) company.expiresAt else now
        val expires = expiryFor(plan, base, now)
        return updateSubscription(
            company.copy(
                planId = plan.id, planName = plan.name, billing = plan.billing, price = plan.price,
                maxUsers = plan.maxUsers, maxAdmins = plan.maxAdmins, features = plan.features,
                startedAt = if (renew) company.startedAt else now, expiresAt = expires,
                suspended = false, suspendReason = null,
            )
        )
    }

    suspend fun extend(company: Company, days: Int): Result<Unit> =
        updateSubscription(company.copy(expiresAt = Subscription.extend(company.expiresAt, days, System.currentTimeMillis())))

    suspend fun setSuspended(company: Company, suspended: Boolean, reason: String?): Result<Unit> =
        updateSubscription(company.copy(suspended = suspended, suspendReason = if (suspended) reason?.trim() else null))

    /** Records a payment and (optionally) extends the subscription in one atomic batch. */
    suspend fun recordPayment(company: Company, payment: Payment): Result<Unit> = runCatching {
        require(payment.amount >= 0) { "Amount cannot be negative." }
        val now = System.currentTimeMillis()
        val batch = db.batch().set(Paths.payments(db, company.id).document(), Mappers.paymentMap(payment.copy(createdAt = now)))
        if (payment.extendedDays > 0) {
            val expires = Subscription.extend(company.expiresAt, payment.extendedDays, now)
            val updated = company.copy(
                expiresAt = expires,
                accessUntil = Subscription.accessUntil(expires, platform.value.graceDays),
                suspended = false, suspendReason = null,
                billing = if (company.billing == Billing.TRIAL) Billing.CUSTOM else company.billing,
            )
            batch.update(Paths.company(db, company.id), Mappers.subscriptionMap(updated))
        }
        batch.commit().await()
        Unit
    }.mapError()

    /**
     * Deletes a company. Its people are deactivated first (their history stays in the cloud),
     * so nobody is left signed in to a company that no longer exists.
     */
    suspend fun deleteCompany(cid: String): Result<Unit> = runCatching {
        val users = db.collection(Paths.USERS).whereEqualTo("companyId", cid).get().await().documents
        users.chunked(400).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { batch.update(it.reference, "disabled", true) }
            batch.commit().await()
        }
        Paths.company(db, cid).delete().await()
        Unit
    }.mapError()

    private fun expiryFor(plan: Plan, from: Long, now: Long): Long = when (plan.billing) {
        Billing.LIFETIME -> Subscription.LIFETIME_EXPIRY
        Billing.TRIAL -> now + plan.durationDays.coerceAtLeast(1) * Subscription.DAY_MS
        else -> Subscription.extend(from, plan.durationDays, now)
    }
}
