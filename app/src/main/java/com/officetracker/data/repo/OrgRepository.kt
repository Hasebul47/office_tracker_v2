package com.officetracker.data.repo

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.officetracker.core.model.AppConfig
import com.officetracker.core.model.LiveState
import com.officetracker.core.model.Place
import com.officetracker.data.remote.Mappers
import com.officetracker.data.remote.Paths
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Organisation-wide shared data: known places, settings and live positions.
 * Places and settings are kept hot while someone is signed in, because the tracking
 * service needs them instantly (and offline, thanks to Firestore's local cache).
 */
class OrgRepository(private val db: FirebaseFirestore) {

    private val _places = MutableStateFlow<List<Place>>(emptyList())
    val places: StateFlow<List<Place>> = _places.asStateFlow()

    private val _config = MutableStateFlow(AppConfig())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    private var placesReg: ListenerRegistration? = null
    private var configReg: ListenerRegistration? = null

    fun startListening() {
        if (placesReg == null) {
            placesReg = db.collection(Paths.PLACES).addSnapshotListener { snap, _ ->
                if (snap != null) _places.value = snap.documents.mapNotNull { Mappers.place(it) }.sortedBy { it.name.lowercase() }
            }
        }
        if (configReg == null) {
            configReg = db.collection(Paths.CONFIG).document(Paths.CONFIG_APP).addSnapshotListener { doc, _ ->
                if (doc != null) _config.value = Mappers.config(doc)
            }
        }
    }

    fun stopListening() {
        placesReg?.remove(); placesReg = null
        configReg?.remove(); configReg = null
        _places.value = emptyList()
        _config.value = AppConfig()
    }

    suspend fun savePlace(place: Place): Result<Unit> = runCatching {
        require(place.name.isNotBlank()) { "Enter a name for the place." }
        val col = db.collection(Paths.PLACES)
        val ref = if (place.id.isBlank()) col.document() else col.document(place.id)
        ref.set(Mappers.placeMap(place)).await()
        Unit
    }.mapError()

    suspend fun deletePlace(id: String): Result<Unit> = runCatching {
        db.collection(Paths.PLACES).document(id).delete().await()
        Unit
    }.mapError()

    suspend fun saveConfig(config: AppConfig): Result<Unit> = runCatching {
        require(config.ratePerKm in 0.0..1000.0) { "Rate must be between 0 and 1000." }
        require(config.stayRadiusMeters in 30.0..500.0) { "Stay radius must be 30-500 m." }
        require(config.minStayMinutes in 1..60) { "Minimum stay must be 1-60 minutes." }
        db.collection(Paths.CONFIG).document(Paths.CONFIG_APP).set(Mappers.configMap(config), SetOptions.merge()).await()
        Unit
    }.mapError()

    // ---- Live positions ----

    /** Fire-and-forget: Firestore queues the write while offline. */
    fun publishLive(state: LiveState) {
        db.collection(Paths.LIVE).document(state.uid).set(Mappers.liveMap(state), SetOptions.merge())
    }

    fun observeLive(uid: String): Flow<LiveState?> = callbackFlow {
        val reg = db.collection(Paths.LIVE).document(uid).addSnapshotListener { doc, error ->
            if (error != null) {
                close(error.toFriendly())
                return@addSnapshotListener
            }
            trySend(doc?.takeIf { it.exists() }?.let { Mappers.live(it) })
        }
        awaitClose { reg.remove() }
    }

    fun observeLive(): Flow<List<LiveState>> = callbackFlow {
        val reg = db.collection(Paths.LIVE).addSnapshotListener { snap, error ->
            if (error != null) {
                close(error.toFriendly())
                return@addSnapshotListener
            }
            trySend(snap?.documents.orEmpty().map { Mappers.live(it) })
        }
        awaitClose { reg.remove() }
    }
}
