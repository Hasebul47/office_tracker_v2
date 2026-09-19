package com.officetracker.data.repo

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.firestore.FirebaseFirestore
import com.officetracker.BuildConfig
import com.officetracker.core.model.Role
import com.officetracker.core.model.UserProfile
import com.officetracker.core.util.Phone
import com.officetracker.data.remote.Mappers
import com.officetracker.data.remote.Paths
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

data class LegacyImportReport(val imported: Int, val skipped: List<String>)

/** Administrator operations on employee accounts. */
class UserRepository(
    private val context: Context,
    private val db: FirebaseFirestore,
) {
    fun observeUsers(): Flow<List<UserProfile>> = callbackFlow {
        val reg = db.collection(Paths.USERS).addSnapshotListener { snap, error ->
            if (error != null) {
                close(error.toFriendly())
                return@addSnapshotListener
            }
            val users = snap?.documents.orEmpty()
                .filter { Mappers.isV2Profile(it) }
                .map { Mappers.profile(it) }
                .sortedWith(compareBy({ it.disabled }, { it.name.lowercase() }))
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
     * Creates the Firebase login through a secondary FirebaseApp so the administrator stays
     * signed in, then writes the profile document with the administrator's own credentials.
     */
    suspend fun createEmployee(
        name: String,
        phone: String,
        password: String,
        department: String,
        role: Role,
    ): Result<UserProfile> = runCatching {
        require(name.isNotBlank()) { "Enter the employee's name." }
        require(Phone.isValid(phone)) { "Enter a valid 11-digit mobile number." }
        require(password.length >= 6) { "Password must be at least 6 characters." }
        val cleanPhone = Phone.normalize(phone)
        val uid = createLogin(cleanPhone, password)
        val profile = UserProfile(
            uid = uid, name = name.trim(), phone = cleanPhone, role = role,
            department = department.trim().ifEmpty { "Field Operations" },
            disabled = false, createdAt = System.currentTimeMillis(),
        )
        Paths.user(db, uid).set(Mappers.profileMap(profile)).await()
        profile
    }.mapError()

    private suspend fun createLogin(phone: String, password: String): String {
        val secondaryAuth = FirebaseAuth.getInstance(secondaryApp())
        val email = Phone.toAuthEmail(phone, BuildConfig.AUTH_EMAIL_DOMAIN)
        return try {
            val uid = try {
                secondaryAuth.createUserWithEmailAndPassword(email, password).await().user?.uid
            } catch (e: FirebaseAuthUserCollisionException) {
                // A login already exists (e.g. the profile was deleted earlier). Re-link it if the
                // administrator supplied the same password; otherwise explain what to do.
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

    suspend fun updateEmployee(uid: String, name: String, department: String, role: Role): Result<Unit> = runCatching {
        require(name.isNotBlank()) { "Name cannot be empty." }
        Paths.user(db, uid).update(
            mapOf("name" to name.trim(), "department" to department.trim(), "role" to role.name)
        ).await()
        Unit
    }.mapError()

    suspend fun setDisabled(uid: String, disabled: Boolean): Result<Unit> = runCatching {
        Paths.user(db, uid).update("disabled", disabled).await()
        Unit
    }.mapError()

    /**
     * Removes the profile and live position. Tracking history under users/{uid}/days stays in
     * the cloud for records. The Firebase login itself can only be removed in the console.
     */
    suspend fun deleteEmployee(uid: String): Result<Unit> = runCatching {
        db.batch()
            .delete(Paths.user(db, uid))
            .delete(db.collection(Paths.LIVE).document(uid))
            .commit().await()
        Unit
    }.mapError()

    /**
     * One-time migration from v1, which stored employees at users/{phone} with plain-text
     * passwords. Each is given a real Firebase login with the same password, and the old
     * document (including its password) is deleted.
     */
    suspend fun importLegacyUsers(): Result<LegacyImportReport> = runCatching {
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
                    // Already migrated (e.g. the administrator created during setup): just drop the old record.
                    runCatching { doc.reference.delete().await() }
                    skipped += "$name ($phone): already has a v2 account - old record removed"
                    continue
                }
                password.length < 6 -> { skipped += "$name ($phone): password shorter than 6 characters - add manually"; continue }
            }
            try {
                val uid = createLogin(phone, password)
                val profile = UserProfile(
                    uid = uid, name = name, phone = phone,
                    role = Role.from(doc.getString("role")),
                    department = doc.getString("department").orEmpty().ifBlank { "Field Operations" },
                    disabled = doc.getBoolean("isDisabled") ?: false,
                    createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis(),
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
        LegacyImportReport(imported, skipped)
    }.mapError()

    private companion object {
        const val SECONDARY_APP = "employee-admin"
    }
}
