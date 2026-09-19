@file:OptIn(ExperimentalMaterial3Api::class)

package com.officetracker.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.officetracker.AppContainer
import com.officetracker.core.model.Place
import com.officetracker.core.model.PlaceCategory
import com.officetracker.ui.appViewModel
import com.officetracker.ui.components.CategoryChips
import com.officetracker.ui.components.ConfirmDialog
import com.officetracker.ui.components.EmptyState
import com.officetracker.ui.components.LoadingButton
import com.officetracker.ui.components.MapCircle
import com.officetracker.ui.components.MapMarker
import com.officetracker.ui.components.OsmMap
import com.officetracker.ui.components.PlacePickerMap
import com.officetracker.ui.components.ScreenMessage
import com.officetracker.ui.components.SectionCard
import com.officetracker.ui.components.color
import com.officetracker.ui.components.icon
import com.officetracker.ui.components.rememberLocationReadyAction
import kotlinx.coroutines.launch

class PlacesViewModel(private val c: AppContainer) : ViewModel() {
    val places = c.org.places
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    fun save(place: Place, onDone: () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            c.org.savePlace(place).onSuccess { onDone() }.onFailure { message = it.message }
            busy = false
        }
    }

    fun delete(place: Place) {
        viewModelScope.launch { c.org.deletePlace(place.id).onFailure { message = it.message } }
    }

    suspend fun here(): Pair<Double, Double>? = c.locationClient.currentLocation(8_000)?.let { it.latitude to it.longitude }

    suspend fun address(lat: Double, lng: Double): String? = c.namer.reverseGeocode(lat, lng)?.second
}

@Composable
fun PlacesScreen() {
    val vm = appViewModel(key = "places") { PlacesViewModel(it) }
    val places by vm.places.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Place?>(null) }
    var deleting by remember { mutableStateOf<Place?>(null) }
    var tab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Places") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = Place("", "", PlaceCategory.OFFICE, Double.NaN, Double.NaN, 100.0) },
                icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add place") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("List") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Map") }, icon = null)
            }
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { ScreenMessage(vm.message) { vm.message = null } }
            if (tab == 1) {
                OsmMap(
                    Modifier.fillMaxSize(),
                    markers = places.map { MapMarker(it.id, it.latitude, it.longitude, it.name, it.category.label, it.category.color()) },
                    circles = places.map { MapCircle(it.latitude, it.longitude, it.radiusMeters, it.category.color()) },
                    fitKey = places.size,
                    onMarkerClick = { id -> editing = places.firstOrNull { it.id == id } },
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(
                            "Visits inside these areas are named automatically on every employee's phone.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (places.isEmpty()) item {
                        EmptyState(Icons.Default.Place, "No places yet", "Add your offices, factories and regular client sites.")
                    }
                    items(places, key = { it.id }) { p ->
                        SectionCard(Modifier.clickable { editing = p }, padding = PaddingValues(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(40.dp).background(p.category.color().copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(p.category.icon(), null, tint = p.category.color())
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${p.category.label} · ${p.radiusMeters.toInt()} m" + (p.address?.let { " · $it" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(onClick = { deleting = p }) { Icon(Icons.Default.Delete, "Delete") }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { place ->
        PlaceEditor(place, vm, onDismiss = { editing = null })
    }
    deleting?.let { p ->
        ConfirmDialog(
            "Delete ${p.name}?", "Past visits keep their names; new visits here will be named from the map.",
            "Delete", onConfirm = { vm.delete(p) }, onDismiss = { deleting = null }, destructive = true,
        )
    }
}

@Composable
private fun PlaceEditor(initial: Place, vm: PlacesViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var category by remember { mutableStateOf(initial.category) }
    var radius by remember { mutableDoubleStateOf(initial.radiusMeters) }
    var lat by remember { mutableDoubleStateOf(initial.latitude) }
    var lng by remember { mutableDoubleStateOf(initial.longitude) }
    var start by remember { mutableStateOf(if (initial.latitude.isNaN()) null else initial.latitude to initial.longitude) }
    var mapKey by remember { mutableStateOf(0) }
    var locating by remember { mutableStateOf(false) }

    val useMyLocation = rememberLocationReadyAction {
        locating = true
        scope.launch {
            vm.here()?.let { start = it; mapKey++ }
            locating = false
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().imePadding()) {
                TopAppBar(
                    title = { Text(if (initial.id.isBlank()) "New place" else "Edit place") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") } },
                )
                Box(Modifier.fillMaxWidth().height(300.dp)) {
                    // Re-created when "use my location" moves the starting point.
                    androidx.compose.runtime.key(mapKey) {
                        PlacePickerMap(
                            initialLatitude = start?.first, initialLongitude = start?.second, radiusMeters = radius,
                            onCenterChanged = { a, b -> lat = a; lng = b },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    FilledTonalButton(
                        onClick = useMyLocation, enabled = !locating,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                    ) {
                        Icon(Icons.Default.MyLocation, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (locating) "Locating…" else "My location")
                    }
                }
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Map, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Text(
                            "  Move the map so the pin sits on the entrance.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    CategoryChips(category) { category = it }
                    Text("Area radius: ${radius.toInt()} m", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = radius.toFloat(), onValueChange = { radius = it.toDouble() },
                        valueRange = 30f..500f, steps = 46,
                    )
                    LoadingButton(
                        text = "Save place", loading = vm.busy, modifier = Modifier.fillMaxWidth(),
                        enabled = name.isNotBlank() && !lat.isNaN(),
                        onClick = {
                            scope.launch {
                                val address = vm.address(lat, lng)
                                vm.save(initial.copy(name = name.trim(), category = category, radiusMeters = radius, latitude = lat, longitude = lng, address = address), onDismiss)
                            }
                        },
                    )
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Cancel") }
                }
            }
        }
    }
}
