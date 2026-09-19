package com.officetracker.data.repo

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.officetracker.core.model.AppConfig
import com.officetracker.core.model.Company
import com.officetracker.core.model.Features
import com.officetracker.core.model.LiveState
import com.officetracker.core.model.Payment
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

sealed interface CompanyState {
    data object Loading : CompanyState
    data object Missing : CompanyState
    data class Loaded(val company: Company) : CompanyState
}

/**
 * The signed-in person's company (tenant). Kept hot while signed in: the company document
 * carries subscription, limits, feature switches and settings, so any change the super admin
 * makes reaches every phone within seconds.
 */
class OrgRepository(private val db: FirebaseFirestore) {

    private val _company = MutableStateFlow<CompanyState>(CompanyState.Loading)
    val company: StateFlow<CompanyState> = _company.asStateFlow()

    private val _places = MutableStateFlow<List<Place>>(emptyList())
    val places: StateFlow<List<Place>> = _places.asStateFlow()

    private val _config = MutableStateFlow(AppConfig())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    private val _features = MutableStateFlow(Features.ALL)
    val features: StateFlow<Features> = _features.asStateFlow()

    @Volatile var companyId: String? = null
        private set

    private var companyReg: ListenerRegistration? = null
    private var placesReg: ListenerRegistration? = null

    val currentCompany: Company? get() = (company.value as? CompanyState.Loaded)?.company

    fun startListening(cid: String) {
        if (companyId == cid && companyReg != null) return
        stopListening()
        companyId = cid
        _company.value = CompanyState.Loading
        companyReg = Paths.company(db, cid).addSnapshotListener { doc, error ->
            when {
                error != null && error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED -> _company.value = CompanyState.Missing
                error != null -> Unit // keep last known state while offline
                doc == null -> Unit
                !doc.exists() && doc.metadata.isFromCache -> Unit
                !doc.exists() -> _company.value = CompanyState.Missing
                else -> Mappers.company(doc)?.let { c ->
                    _company.value = CompanyState.Loaded(c)
                    _config.value = c.settings
                    _features.value = c.features
                }
            }
        }
        placesReg = Paths.places(db, cid).addSnapshotListener { snap, _ ->
            if (snap != null) _places.value = snap.documents.mapNotNull { Mappers.place(it) }.sortedBy { it.name.lowercase() }
        }
    }

    fun stopListening() {
        companyReg?.remove(); companyReg = null
        placesReg?.remove(); placesReg = null
        companyId = null
        _company.value = CompanyState.Loading
        _places.value = emptyList()
        _config.value = AppConfig()
        _features.value = Features.ALL
    }

    private fun cid(): String = companyId ?: error("No company selected.")

    suspend fun savePlace(place: Place): Result<Unit> = runCatching {
        require(place.name.isNotBlank()) { "Enter a name for the place." }
        val col = Paths.places(db, cid())
        val ref = if (place.id.isBlank()) col.document() else col.document(place.id)
        ref.set(Mappers.placeMap(place)).await()
        Unit
    }.mapError()

    suspend fun deletePlace(id: String): Result<Unit> = runCatching {
        Paths.places(db, cid()).document(id).delete().await()
        Unit
    }.mapError()

    suspend fun saveConfig(config: AppConfig): Result<Unit> = runCatching {
        require(config.ratePerKm in 0.0..1000.0) { "Rate must be between 0 and 1000." }
        require(config.stayRadiusMeters in 30.0..500.0) { "Stay radius must be 30-500 m." }
        require(config.minStayMinutes in 1..60) { "Minimum stay must be 1-60 minutes." }
        Paths.company(db, cid()).update(
            mapOf("settings" to Mappers.configMap(config), "updatedAt" to System.currentTimeMillis())
        ).await()
        Unit
    }.mapError()

    // ---- Live positions ----

    /** Fire-and-forget: Firestore queues the write while offline. */
    fun publishLive(state: LiveState) {
        val cid = companyId ?: return
        Paths.live(db, cid).document(state.uid).set(Mappers.liveMap(state), SetOptions.merge())
    }

    fun observeLive(cid: String = cid()): Flow<List<LiveState>> = callbackFlow {
        val reg = Paths.live(db, cid).addSnapshotListener { snap, error ->
            if (error != null) {
                close(error.toFriendly())
                return@addSnapshotListener
            }
            trySend(snap?.documents.orEmpty().map { Mappers.live(it) })
        }
        awaitClose { reg.remove() }
    }

    fun observeLive(cid: String, uid: String): Flow<LiveState?> = callbackFlow {
        val reg = Paths.live(db, cid).document(uid).addSnapshotListener { doc, error ->
            if (error != null) {
                close(error.toFriendly())
                return@addSnapshotListener
            }
            trySend(doc?.takeIf { it.exists() }?.let { Mappers.live(it) })
        }
        awaitClose { reg.remove() }
    }

    fun observePayments(cid: String = cid()): Flow<List<Payment>> = callbackFlow {
        val reg = Paths.payments(db, cid).orderBy("createdAt", Query.Direction.DESCENDING).limit(50)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    close(error.toFriendly())
                    return@addSnapshotListener
                }
                trySend(snap?.documents.orEmpty().map { Mappers.payment(it) })
            }
        awaitClose { reg.remove() }
    }
}
