package com.officetracker.tracking

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.officetracker.core.model.Place
import com.officetracker.core.model.PlaceCategory
import com.officetracker.core.util.Geo
import com.officetracker.data.local.TrackerDao
import com.officetracker.data.repo.OrgRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

data class PlaceLabel(
    val name: String,
    val address: String?,
    val category: PlaceCategory,
    val placeId: String?,
    val manual: Boolean,
)

/** Names a coordinate: admin-defined place > employee's own earlier label > reverse geocoding. */
class PlaceNamer(
    private val context: Context,
    private val org: OrgRepository,
    private val dao: TrackerDao,
) {
    fun matchKnownPlace(lat: Double, lng: Double): Place? =
        if (!org.features.value.places) null else org.places.value
            .map { it to Geo.distanceMeters(lat, lng, it.latitude, it.longitude) }
            .filter { (place, d) -> d <= place.radiusMeters }
            .minByOrNull { it.second }
            ?.first

    suspend fun learnedLabel(uid: String, lat: Double, lng: Double, withinMeters: Double = 80.0): PlaceLabel? {
        val dLat = Geo.metersToLatDegrees(withinMeters)
        val dLng = Geo.metersToLonDegrees(withinMeters, lat)
        return dao.labelledStaysNear(uid, lat - dLat, lat + dLat, lng - dLng, lng + dLng)
            .firstOrNull { Geo.distanceMeters(lat, lng, it.latitude, it.longitude) <= withinMeters }
            ?.let { PlaceLabel(it.name, it.address, PlaceCategory.from(it.category), null, manual = true) }
    }

    /** Label available instantly, without network. */
    suspend fun quickLabel(uid: String?, lat: Double, lng: Double): PlaceLabel? {
        matchKnownPlace(lat, lng)?.let { return PlaceLabel(it.name, it.address, it.category, it.id, manual = false) }
        if (uid != null) learnedLabel(uid, lat, lng)?.let { return it }
        return null
    }

    suspend fun label(uid: String?, lat: Double, lng: Double): PlaceLabel {
        quickLabel(uid, lat, lng)?.let { return it }
        val (name, address) = reverseGeocode(lat, lng) ?: (null to null)
        return PlaceLabel(name ?: UNNAMED, address, PlaceCategory.OTHER, null, manual = false)
    }

    suspend fun reverseGeocode(lat: Double, lng: Double): Pair<String?, String?>? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        val address = withTimeoutOrNull(5_000) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine<Address?> { cont ->
                    geocoder.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                }
            } else {
                // The legacy call blocks; run it outside structured concurrency so the timeout
                // can abandon it instead of waiting for it.
                CoroutineScope(Dispatchers.IO).async {
                    @Suppress("DEPRECATION")
                    runCatching { geocoder.getFromLocation(lat, lng, 1)?.firstOrNull() }.getOrNull()
                }.await()
            }
        } ?: return null
        val name = listOfNotNull(
            address.featureName?.takeIf { f -> f.any { it.isLetter() } && f != address.getAddressLine(0) },
            address.thoroughfare,
            address.subLocality,
            address.locality,
        ).firstOrNull()
        return name to address.getAddressLine(0)
    }

    companion object {
        const val UNNAMED = "Unnamed place"
    }
}
