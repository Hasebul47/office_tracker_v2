package com.officetracker.data.repo

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.officetracker.BuildConfig
import com.officetracker.core.model.Role
import com.officetracker.core.model.UserProfile
import com.officetracker.core.model.WorkSchedule
import com.officetracker.core.util.Phone
import com.officetracker.data.remote.Mappers
import com.officetracker.data.remote.Paths
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

data class LegacyImportReport(val imported: Int, val skipped: List<String>)

/**
 * Creates Firebase logins through a secondary FirebaseApp, so the admin or super admin who is
 * creating the account stays signed in.
 */
class LoginCreator(private val context: Context) {

    suspend fun create(phone: String, password: String): String {
        val secondaryAuth = FirebaseAuth.getInstance(secondaryApp())
        val email = Phone.toAuthEmail(phone, BuildConfig.AUTH_EMAIL_DOMAIN)
        return try {
            val uid = try {
                secondaryAuth.createUserWithEmailAndPassword(email, password).await().user?.uid
            } catch (e: FirebaseAuthUserCollisionException) {
                // A login already exists (e.g. the profile was deleted earlier). Re-link it if the
                // same password was supplied; otherwise explain what to do.
                runCatching { secondaryAuth.signInWithEmailAndPassword(email, password).await().user?.uid }
                    .getOrNull()
                    ?: error(
                        "A login for $phone already exists with a different password. " +
                            "Delete it in Firebase Console > Authentication, then try again."
                    )
            }
            uid ?: error("Could not create the login.")
        } finally {
            secondaryAuth.signOut()
        }
    }

    private fun secondaryApp(): FirebaseApp =
        FirebaseApp.getApps(context).firstOrNull { it.name == SECONDARY_APP }
            ?: FirebaseApp.initializeApp(context, FirebaseApp.getInstance().options, SECONDARY_APP)

    private companion object {
        const val SECONDARY_APP = "account-admin"
    }
}

/** People inside a company. Every add/remove also moves the company's user counters (see rules). */
class UserRepository(
    private val db: FirebaseFirestore,
    private val logins: LoginCreator,
) {
    fun observeUsers(companyId: String): Flow<List<UserProfile>> = callbackFlow {
        val reg = db.collection(Paths.USERS).whereEqualTo("companyId", companyId).addSnapshotListener { snap, error ->
            if (error != null) {
                close(error.toFriendly())
                return@addSnapshotListener
            }
            val users = snap?.documents.orEmpty()
                .filter { Mappers.isV2Profile(it) }
                .map { Mappers.profile(it) }
                .sortedWith(compareBy({ it.disabled }, { it.role != Role.ADMIN }, { it.name.lowercase() }))
            trySend(users)
        }
        awaitClose { reg.remove() }
    }

    fun observeUser(uid: String): Flow<UserProfile?> = callbackFlow {
        val reg = Paths.user(db, uid).addSnapshotListener { doc, error ->
            if (error != null) {
                close(error.toFriendly())
                return@addSnapshotListener
            }
            trySend(doc?.takeIf { it.exists() }?.let { Mappers.profile(it) })
        }
        awaitClose { reg.remove() }
    }

    /**
     * Adds a person to [companyId]. The login is created first, then the profile and the
     * company counter are written in one batch so limits cannot be bypassed.
     */
    suspend fun createUser(
        companyId: String,
        name: String,
        phone: String,
        password: String,
        department: String,
        role: Role,
        limits: Pair<Int, Int>? = null,
        counts: Pair<Int, Int>? = null,
    ): Result<UserProfile> = runCatching {
        require(role != Role.SUPER_ADMIN) { "Not allowed." }
        require(name.isNotBlank()) { "Enter the person's name." }
        require(Phone.isValid(phone)) { "Enter a valid 11-digit mobile number." }
        require(password.length >= 6) { "Password must be at least 6 characters." }
        if (limits != null && counts != null) {
            val (maxUsers, maxAdmins) = limits
            val (users, admins) = counts
            check(users < maxUsers) { "Your plan allows $maxUsers users. Upgrade the plan to add more." }
            if (role == Role.ADMIN) check(admins < maxAdmins) { "Your plan allows $maxAdmins administrators." }
        }
        val cleanPhone = Phone.normalize(phone)
        val uid = logins.create(cleanPhone, password)
        val profile = UserProfile(
            uid = uid, name = name.trim(), phone = cleanPhone, role = role,
            department = department.trim().ifEmpty { if (role == Role.ADMIN) "Management" else "Field Operations" },
            disabled = false, createdAt = System.currentTimeMillis(), companyId = companyId,
        )
        db.batch()
            .set(Paths.user(db, uid), Mappers.profileMap(profile))
            .update(Paths.company(db, companyId), counterUpdate(userDelta = 1, adminDelta = if (role == Role.ADMIN) 1 else 0))
            .commit().await()
        profile
    }.mapError()

    suspend fun updateUser(before: UserProfile, name: String, department: String, role: Role): Result<Unit> = runCatching {
        require(name.isNotBlank()) { "Name cannot be empty." }
        require(role != Role.SUPER_ADMIN) { "Not allowed." }
        val cid = before.companyId ?: error("User has no company.")
        val adminDelta = when {
            before.role != Role.ADMIN && role == Role.ADMIN -> 1
            before.role == Role.ADMIN && role != Role.ADMIN -> -1
            else -> 0
        }
        val batch = db.batch().update(
            Paths.user(db, before.uid),
            mapOf("name" to name.trim(), "department" to department.trim(), "role" to role.name),
        )
        if (adminDelta != 0) batch.update(Paths.company(db, cid), counterUpdate(userDelta = 0, adminDelta = adminDelta))
        batch.commit().await()
        Unit
    }.mapError()

    /** Signs the person out of whatever phone they are using (they can sign in again). */
    suspend fun revokeDevice(uid: String): Result<Unit> = runCatching {
        Paths.user(db, uid).update("activeDeviceId", "$REVOKED_PREFIX${System.currentTimeMillis()}").await()
        Unit
    }.mapError()

    /** Admin-set personal schedule; null returns the person to the company schedule. */
    suspend fun setSchedule(uid: String, schedule: WorkSchedule?): Result<Unit> = runCatching {
        Paths.user(db, uid).update("schedule", schedule?.toMap() ?: FieldValue.delete()).await()
        Unit
    }.mapError()

    suspend fun setDisabled(uid: String, disabled: Boolean): Result<Unit> = runCatching {
        Paths.user(db, uid).update("disabled", disabled).await()
        Unit
    }.mapError()

    /**
     * Removes the profile and live position and frees the seat. Tracking history under
     * users/{uid}/days stays in the cloud. The Firebase login can only be removed in the console.
     */
    suspend fun deleteUser(user: UserProfile): Result<Unit> = runCatching {
        val cid = user.companyId ?: error("User has no company.")
        db.batch()
            .delete(Paths.user(db, user.uid))
            .delete(Paths.live(db, cid).document(user.uid))
            .update(Paths.company(db, cid), counterUpdate(userDelta = -1, adminDelta = if (user.role == Role.ADMIN) -1 else 0))
            .commit().await()
        Unit
    }.mapError()

    /**
     * Super admin: moves v1 accounts (users/{phone} with plain-text passwords) into [companyId]
     * with real logins, deleting the old records. Counters are recounted afterwards.
     */
    suspend fun importLegacyUsers(companyId: String): Result<LegacyImportReport> = runCatching {
        val all = db.collection(Paths.USERS).get().await().documents
        val legacy = all.filter { it.contains("password") }
        val existingPhones = all.filter { Mappers.isV2Profile(it) }.map { Phone.normalize(it.getString("phone").orEmpty()) }.toSet()
        var imported = 0
        val skipped = mutableListOf<String>()
        for (doc in legacy) {
            val phone = Phone.normalize(doc.getString("phoneNumber") ?: doc.id)
            val password = doc.getString("password").orEmpty()
            val name = doc.getString("displayName").orEmpty().ifBlank { phone }
            when {
                !Phone.isValid(phone) -> { skipped += "$name: invalid phone"; continue }
                phone in existingPhones -> {
                    runCatching { doc.reference.delete().await() }
                    skipped += "$name ($phone): already has an account - old record removed"
                    continue
                }
                password.length < 6 -> { skipped += "$name ($phone): password shorter than 6 characters - add manually"; continue }
            }
            try {
                val uid = logins.create(phone, password)
                val legacyRole = Role.from(doc.getString("role"))
                val profile = UserProfile(
                    uid = uid, name = name, phone = phone,
                    role = if (legacyRole == Role.ADMIN) Role.ADMIN else Role.EMPLOYEE,
                    department = doc.getString("department").orEmpty().ifBlank { "Field Operations" },
                    disabled = doc.getBoolean("isDisabled") ?: false,
                    createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis(),
                    companyId = companyId,
                )
                db.batch()
                    .set(Paths.user(db, uid), Mappers.profileMap(profile))
                    .delete(doc.reference)
                    .commit().await()
                imported++
            } catch (e: Exception) {
                skipped += "$name ($phone): ${e.toFriendly().message}"
            }
        }
        recount(companyId)
        LegacyImportReport(imported, skipped)
    }.mapError()

    /** Super admin: recalculates a company's user counters from the actual profiles. */
    suspend fun recount(companyId: String) {
        val users = db.collection(Paths.USERS).whereEqualTo("companyId", companyId).get().await().documents
            .filter { Mappers.isV2Profile(it) }
        Paths.company(db, companyId).update(
            mapOf(
                "userCount" to users.size,
                "adminCount" to users.count { it.getString("role") == Role.ADMIN.name },
                "updatedAt" to System.currentTimeMillis(),
            )
        ).await()
    }

    companion object {
        const val REVOKED_PREFIX = "revoked:"
    }

    private fun counterUpdate(userDelta: Int, adminDelta: Int): Map<String, Any> = buildMap {
        if (userDelta != 0) put("userCount", FieldValue.increment(userDelta.toLong()))
        if (adminDelta != 0) put("adminCount", FieldValue.increment(adminDelta.toLong()))
        put("updatedAt", System.currentTimeMillis())
    }
}
