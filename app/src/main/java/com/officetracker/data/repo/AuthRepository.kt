package com.officetracker.data.repo

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.officetracker.BuildConfig
import com.officetracker.core.model.Role
import com.officetracker.core.model.UserProfile
import com.officetracker.core.util.Phone
import com.officetracker.data.local.SessionStore
import com.officetracker.data.remote.Mappers
import com.officetracker.data.remote.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

sealed interface Session {
    data object Loading : Session
    data class SignedOut(val message: String? = null) : Session
    data class SignedIn(val profile: UserProfile) : Session
}

/**
 * Firebase Authentication (email/password provider, keyed by phone number) + the users/{uid}
 * profile document. Passwords are never stored in Firestore or on the device.
 */
class AuthRepository(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
    private val store: SessionStore,
    private val scope: CoroutineScope,
) {
    private val _session = MutableStateFlow<Session>(Session.Loading)
    val session: StateFlow<Session> = _session.asStateFlow()

    val currentUid: String? get() = auth.currentUser?.uid
    val currentProfile: UserProfile? get() = (session.value as? Session.SignedIn)?.profile

    private var profileListener: ListenerRegistration? = null
    private var pendingSignOutMessage: String? = null

    /** True while the first administrator's profile is being written during setup. */
    @Volatile private var settingUp = false

    init {
        auth.addAuthStateListener { fa ->
            val user = fa.currentUser
            profileListener?.remove()
            profileListener = null
            if (user == null) {
                _session.value = Session.SignedOut(pendingSignOutMessage)
                pendingSignOutMessage = null
            } else {
                listenToProfile(user.uid)
            }
        }
    }

    private fun listenToProfile(uid: String) {
        if (_session.value !is Session.SignedIn) _session.value = Session.Loading
        profileListener = Paths.user(db, uid).addSnapshotListener { doc, error ->
            when {
                error != null -> {
                    if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        forceSignOut("Your account is not active. Contact your administrator.")
                    } else {
                        useCachedProfile(uid)
                    }
                }
                doc == null -> useCachedProfile(uid)
                !doc.exists() && doc.metadata.isFromCache -> useCachedProfile(uid)
                !doc.exists() && settingUp -> _session.value = Session.Loading
                !doc.exists() -> forceSignOut("No employee profile exists for this login. Ask your administrator to add you.")
                else -> {
                    val profile = Mappers.profile(doc)
                    if (profile.disabled) {
                        forceSignOut("This account has been deactivated. Contact your administrator.")
                    } else {
                        _session.value = Session.SignedIn(profile)
                        scope.launch { store.saveProfile(profile) }
                    }
                }
            }
        }
    }

    private fun useCachedProfile(uid: String) {
        scope.launch {
            val cached = store.cachedProfile(uid)
            if (cached != null) {
                _session.value = Session.SignedIn(cached)
            } else if (_session.value !is Session.SignedIn) {
                _session.value = Session.Loading
            }
        }
    }

    private fun forceSignOut(message: String) {
        pendingSignOutMessage = message
        scope.launch { store.clearProfile() }
        auth.signOut()
    }

    suspend fun signIn(phone: String, password: String): Result<Unit> = runCatching {
        require(Phone.isValid(phone)) { "Enter a valid 11-digit mobile number." }
        require(password.isNotEmpty()) { "Enter your password." }
        val email = Phone.toAuthEmail(phone, BuildConfig.AUTH_EMAIL_DOMAIN)
        val result = auth.signInWithEmailAndPassword(email, password).await()
        val uid = result.user?.uid ?: error("Sign-in failed.")
        // Best effort: rules only allow these two fields to be changed by the user.
        Paths.user(db, uid).update(
            mapOf("lastLoginAt" to System.currentTimeMillis(), "appVersion" to BuildConfig.VERSION_NAME)
        )
        Unit
    }.mapError()

    /** First run only: creates the organisation's administrator. Rules reject it once done. */
    suspend fun setupOrganization(name: String, phone: String, password: String): Result<Unit> = runCatching {
        require(name.isNotBlank()) { "Enter your name." }
        require(Phone.isValid(phone)) { "Enter a valid 11-digit mobile number." }
        require(password.length >= 8) { "Use at least 8 characters for the admin password." }
        val email = Phone.toAuthEmail(phone, BuildConfig.AUTH_EMAIL_DOMAIN)
        settingUp = true
        val user = try {
            auth.createUserWithEmailAndPassword(email, password).await().user ?: error("Could not create account.")
        } catch (e: Exception) {
            settingUp = false
            throw e
        }
        val profile = UserProfile(
            uid = user.uid, name = name.trim(), phone = Phone.normalize(phone), role = Role.ADMIN,
            department = "Management", disabled = false, createdAt = System.currentTimeMillis(),
        )
        try {
            db.batch()
                .set(Paths.user(db, user.uid), Mappers.profileMap(profile))
                .set(
                    db.collection(Paths.CONFIG).document(Paths.CONFIG_SETUP),
                    mapOf("adminUid" to user.uid, "createdAt" to System.currentTimeMillis()),
                )
                .commit().await()
        } catch (e: FirebaseFirestoreException) {
            settingUp = false
            runCatching { user.delete().await() }
            auth.signOut()
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                error("This organisation is already set up. Sign in, or ask your administrator for an account.")
            }
            throw e
        } finally {
            settingUp = false
        }
        Unit
    }.mapError()

    suspend fun changePassword(current: String, newPassword: String): Result<Unit> = runCatching {
        require(newPassword.length >= 6) { "New password must be at least 6 characters." }
        val user = auth.currentUser ?: error("Not signed in.")
        val email = user.email ?: error("Account has no login id.")
        user.reauthenticate(EmailAuthProvider.getCredential(email, current)).await()
        user.updatePassword(newPassword).await()
        Unit
    }.mapError()

    fun signOut() {
        scope.launch { store.clearProfile() }
        auth.signOut()
    }
}

/** Turns Firebase exceptions into messages an employee can act on. */
fun <T> Result<T>.mapError(): Result<T> = recoverCatching { throw it.toFriendly() }

fun Throwable.toFriendly(): Throwable = when (this) {
    is IllegalArgumentException, is IllegalStateException -> this
    is FirebaseAuthWeakPasswordException -> IllegalStateException("Password is too weak. Use at least 6 characters.")
    // Must come after the weak-password case, which is a subclass.
    is FirebaseAuthInvalidCredentialsException -> IllegalStateException("Wrong phone number or password.")
    is FirebaseAuthInvalidUserException -> IllegalStateException("No login exists for this phone number, or it was disabled.")
    is FirebaseAuthUserCollisionException -> IllegalStateException("A login already exists for this phone number.")
    is FirebaseTooManyRequestsException -> IllegalStateException("Too many attempts. Wait a few minutes and try again.")
    is FirebaseNetworkException -> IllegalStateException("No internet connection. Check your network and try again.")
    is FirebaseFirestoreException -> when (code) {
        FirebaseFirestoreException.Code.PERMISSION_DENIED -> IllegalStateException("You don't have permission to do that.")
        FirebaseFirestoreException.Code.UNAVAILABLE -> IllegalStateException("Cloud is unreachable. Check your internet connection.")
        else -> IllegalStateException(message ?: "Cloud error.")
    }
    else -> IllegalStateException(message ?: "Something went wrong.")
}
