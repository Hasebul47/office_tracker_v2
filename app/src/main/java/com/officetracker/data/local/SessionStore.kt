package com.officetracker.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.officetracker.core.model.Role
import com.officetracker.core.model.UserProfile
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "session")

/** Small key-value store: the last known profile (for offline start-up) and updater state. */
class SessionStore(private val context: Context) {

    private object Keys {
        val uid = stringPreferencesKey("uid")
        val name = stringPreferencesKey("name")
        val phone = stringPreferencesKey("phone")
        val role = stringPreferencesKey("role")
        val department = stringPreferencesKey("department")
        val companyId = stringPreferencesKey("company_id")
        val activeDevice = stringPreferencesKey("active_device")
        val lastUpdateCheck = longPreferencesKey("last_update_check")
        val skippedVersion = stringPreferencesKey("skipped_version")
        val backgroundPrompted = booleanPreferencesKey("background_prompted")
        val deviceId = stringPreferencesKey("device_id")
    }

    suspend fun saveProfile(p: UserProfile) {
        context.dataStore.edit {
            it[Keys.uid] = p.uid
            it[Keys.name] = p.name
            it[Keys.phone] = p.phone
            it[Keys.role] = p.role.name
            it[Keys.department] = p.department
            if (p.companyId != null) it[Keys.companyId] = p.companyId else it.remove(Keys.companyId)
            if (p.activeDeviceId != null) it[Keys.activeDevice] = p.activeDeviceId else it.remove(Keys.activeDevice)
        }
    }

    suspend fun cachedProfile(uid: String): UserProfile? {
        val prefs = context.dataStore.data.first()
        if (prefs[Keys.uid] != uid) return null
        return UserProfile(
            uid = uid,
            name = prefs[Keys.name].orEmpty(),
            phone = prefs[Keys.phone].orEmpty(),
            role = Role.from(prefs[Keys.role]),
            department = prefs[Keys.department].orEmpty(),
            disabled = false,
            createdAt = 0L,
            companyId = prefs[Keys.companyId],
            // Cached profiles never trigger a device claim or a sign-out on their own.
            activeDeviceId = prefs[Keys.activeDevice] ?: CACHED_DEVICE,
        )
    }

    companion object {
        const val CACHED_DEVICE = "cached"
    }

    suspend fun clearProfile() {
        context.dataStore.edit {
            it.remove(Keys.uid); it.remove(Keys.name); it.remove(Keys.phone)
            it.remove(Keys.role); it.remove(Keys.department); it.remove(Keys.companyId)
        }
    }

    /** Random id for this installation, used for one-device-per-account. */
    suspend fun deviceId(): String {
        context.dataStore.data.first()[Keys.deviceId]?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        context.dataStore.edit { it[Keys.deviceId] = id }
        return id
    }

    suspend fun lastUpdateCheck(): Long = context.dataStore.data.first()[Keys.lastUpdateCheck] ?: 0L
    suspend fun setLastUpdateCheck(time: Long) { context.dataStore.edit { it[Keys.lastUpdateCheck] = time } }
    suspend fun skippedVersion(): String? = context.dataStore.data.first()[Keys.skippedVersion]
    suspend fun setSkippedVersion(v: String) { context.dataStore.edit { it[Keys.skippedVersion] = v } }
    suspend fun backgroundPrompted(): Boolean = context.dataStore.data.first()[Keys.backgroundPrompted] ?: false
    suspend fun setBackgroundPrompted() { context.dataStore.edit { it[Keys.backgroundPrompted] = true } }
}
