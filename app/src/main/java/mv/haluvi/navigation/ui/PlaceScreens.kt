package mv.haluvi.navigation.ui

import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.Fmt
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.data.Categories
import mv.haluvi.navigation.data.SavedLocation

enum class PlacePickMode { DESTINATION, CURRENT, MANAGE }

/** Shared state for list screens, kept across rebuilds and category picker round-trips. */
class PlaceListState(var query: String = "", var category: String = "all", var limit: Int = 60)

/** Adds a nearest-first (or alphabetical without GPS) list of saved places with a Load more button. */
fun addPlaceList(host: MainActivity, ui: UiBuilder, mode: PlacePickMode, state: PlaceListState, islandOnly: String? = null) {
    val g = AppGraph
    val f = g.location.fix
    val rows: List<Triple<SavedLocation, Double?, Double?>> = if (f != null) {
        g.store.nearest(f.lat, f.lon, state.query, state.category, islandOnly).map { Triple(it.loc, it.distance, it.bearing) }
    } else {
        g.store.filtered(state.query, state.category).filter { islandOnly == null || it.islandKey == islandOnly }.map { Triple(it, null, null) }
    }
    val show = rows.take(state.limit)
    ui.info("Showing ${show.size} of ${rows.size} places. Category: ${Categories.name(state.category)}." + if (f == null) " Waiting for GPS, so the list is alphabetical." else " Nearest first.")
    show.forEach { (loc, dist, bearing) ->
        val parts = arrayListOf(loc.name)
        if (dist != null && bearing != null) parts.add("${Fmt.meters(dist)}, ${Geo.directionName(bearing)}")
        if (loc.categoryId != "none") parts.add(loc.categoryName)
        if (loc.islandName.isNotBlank()) parts.add(loc.islandName)
        if (loc.isDownloadedMapPlace) parts.add("map place")
        ui.button(parts.joinToString(", ")) { onPlacePicked(host, mode, loc) }
    }
    if (rows.size > show.size) ui.button("Load more locations") { state.limit += 60; host.rebuild() }
    if (rows.isEmpty()) ui.info("No locations matched. You can save an extra location, download island map data, or search online.")
}

fun onPlacePicked(host: MainActivity, mode: PlacePickMode, loc: SavedLocation) {
    val g = AppGraph
    when (mode) {
        PlacePickMode.DESTINATION -> {
            g.nav.placeDestination = loc
            host.say("Destination: ${loc.name}")
            host.showTabs("Places")
        }
        PlacePickMode.CURRENT -> {
            g.settings.manualCurrentPlaceId = loc.id
            host.say("Current place: ${loc.name}")
            host.showTabs("Places")
        }
        PlacePickMode.MANAGE -> host.show { LocationOptionsScreen(loc.id) }
    }
}

/** Search box + category filter + list. */
class PlaceSearchScreen(private val mode: PlacePickMode, private val state: PlaceListState = PlaceListState()) : Screen() {
    override fun create(): View {
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title(when (mode) {
            PlacePickMode.DESTINATION -> "Search saved places for destination"
            PlacePickMode.CURRENT -> "Search saved places for current place"
            PlacePickMode.MANAGE -> "Search locations"
        })
        val edit = ui.edit("Place name, category or island", state.query) { state.query = it.trim(); state.limit = 60; host.rebuild() }
        ui.row(
            "Search" to { state.query = edit.text.toString().trim(); state.limit = 60; host.rebuild() },
            "Clear" to { state.query = ""; host.rebuild() }
        )
        ui.button("Category filter: ${Categories.name(state.category)}. Tap to change") { host.show(categoryFilterPicker(host, state)) }
        addPlaceList(host, ui, mode, state)
        ui.button("Back") { host.back() }
        return scroll
    }
}

fun categoryFilterPicker(host: MainActivity, state: PlaceListState): () -> Screen = {
    ChoiceScreen("Choose category filter", "The list shows only places in this category.",
        (listOf("all" to "All categories") + Categories.all.map { it.id to it.name }).map { (id, name) ->
            (name + if (state.category == id) " - selected" else "") to { state.category = id; state.limit = 60; host.back() }
        }, "Cancel")
}

class ChooseDestinationScreen : Screen() {
    private val state = PlaceListState(limit = 40)
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Choose destination")
        ui.button("Search saved places") { host.show { PlaceSearchScreen(PlacePickMode.DESTINATION) } }
        ui.button("Online search") { host.show { OnlineSearchScreen(OnlineSearchScreen.Mode.DESTINATION) } }
        val island = g.nav.currentIsland()
        if (island != null) {
            ui.heading("Saved places on ${island.fullName}")
            addPlaceList(host, ui, PlacePickMode.DESTINATION, state, island.key)
            ui.button("Show saved places on all islands") { host.show { PlaceSearchScreen(PlacePickMode.DESTINATION) } }
        } else {
            ui.heading("All saved places")
            addPlaceList(host, ui, PlacePickMode.DESTINATION, state)
        }
        ui.button("Back to Places tab") { host.showTabs("Places") }
        return scroll
    }
}

class ChooseCurrentPlaceScreen : Screen() {
    private val state = PlaceListState(limit = 40)
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Choose current place")
        ui.info("GPS is used automatically by default. The current place is used for the distance preview before you start.")
        ui.button("Use automatic GPS current place" + if (g.settings.manualCurrentPlaceId.isBlank()) " - selected" else "") {
            g.settings.manualCurrentPlaceId = ""
            host.say("Current place set to automatic GPS")
            host.showTabs("Places")
        }
        ui.button("Search saved places") { host.show { PlaceSearchScreen(PlacePickMode.CURRENT) } }
        ui.heading("Nearest saved places")
        addPlaceList(host, ui, PlacePickMode.CURRENT, state)
        ui.button("Back") { host.back() }
        return scroll
    }
}

class LocationManagementScreen(private val state: PlaceListState = PlaceListState()) : Screen() {
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Location Management")
        ui.button("Save extra location here") { val d = LocationDraft(); host.show { SaveLocationScreen(d) } }
        ui.button("Download or update island map data") { host.show { MapDataScreen() } }
        ui.button("Online search for new places") { host.show { OnlineSearchScreen(OnlineSearchScreen.Mode.MANAGE) } }
        ui.button("Search locations") { host.show { PlaceSearchScreen(PlacePickMode.MANAGE) } }
        ui.row(
            "Import locations" to { importLocations() },
            "Export locations" to { exportLocations() }
        )
        ui.button("Category filter: ${Categories.name(state.category)}. Tap to change") { host.show(categoryFilterPicker(host, state)) }
        val manual = g.store.items.count { !it.isDownloadedMapPlace }
        val downloaded = g.store.items.size - manual
        ui.info("$manual saved extra locations and $downloaded downloaded map places.")
        addPlaceList(host, ui, PlacePickMode.MANAGE, state)
        ui.button("Back to Home") { host.showTabs("Home") }
        return scroll
    }

    private fun importLocations() {
        host.pickFile(arrayOf("text/plain", "application/json", "application/octet-stream", "*/*")) { uri ->
            if (uri == null) return@pickFile
            host.scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = host.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                        AppGraph.store.import(text)
                    }
                }
                result.onSuccess { (added, skipped) ->
                    val msg = "$added locations imported." + if (skipped > 0) " $skipped repeated locations skipped." else ""
                    AppGraph.speaker.speak(msg, force = true); host.say(msg); host.rebuild()
                }.onFailure { host.say("This file could not be imported.") }
            }
        }
    }

    private fun exportLocations() {
        host.createFile("haluvi-locations.json", "application/json") { uri ->
            if (uri == null) return@createFile
            host.scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { host.contentResolver.openOutputStream(uri)?.use { it.write(AppGraph.store.exportJson().toByteArray()) } }.isSuccess
                }
                host.say(if (ok) "Locations exported." else "Export failed.")
            }
        }
    }
}

/** Editable values kept while the user visits the category picker. */
class LocationDraft(var name: String = "", var category: String = "none", var updateGps: Boolean = false, var loaded: Boolean = false)

fun categoryPicker(host: MainActivity, draft: LocationDraft): () -> Screen = {
    ChoiceScreen("Choose category", null, Categories.all.map { c ->
        (c.name + if (draft.category == c.id) " - selected" else "") to { draft.category = c.id; host.back() }
    }, "Cancel")
}

class SaveLocationScreen(private val draft: LocationDraft) : Screen() {
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Save extra location")
        ui.info("Saves your current GPS position. Use it for places that are not on the map, such as home, a friend's house, a door or a meeting point.")
        val f = g.location.fix
        ui.info(if (f == null) "GPS: waiting for signal." else "GPS accuracy: ${Fmt.meters(f.accuracy)}." + if (f.accuracy > 30) " Wait a little for a better fix if you can." else "")
        val edit = ui.edit("Location name, for example Home", draft.name)
        ui.button("Category: ${Categories.name(draft.category)}. Tap to change") { draft.name = edit.text.toString(); host.show(categoryPicker(host, draft)) }
        ui.button("Save extra location", color = UiColors.BUTTON_ON) {
            val name = edit.text.toString().trim()
            val fix = g.location.freshFix(15_000)
            when {
                name.isEmpty() -> g.speaker.speak("Please enter a location name.", force = true)
                fix == null -> g.speaker.speak("GPS is not ready. Please wait a few seconds in an open place and try again.", force = true)
                fix.accuracy > 150 -> g.speaker.speak("GPS signal is very weak. Please wait a few more seconds and try again.", force = true)
                else -> {
                    val loc = SavedLocation(SavedLocation.newId(), name, fix.lat, fix.lon, fix.accuracy, source = SavedLocation.SOURCE_MANUAL, categoryId = draft.category)
                    val dup = g.store.findDuplicate(name, fix.lat, fix.lon)
                    if (dup != null) {
                        g.speaker.speak("A location named ${dup.name} is already saved here.", force = true)
                    } else {
                        g.store.add(loc)
                        val msg = "$name saved." + if (loc.islandName.isNotBlank()) " Island: ${loc.islandName}." else ""
                        g.speaker.speak(msg, force = true)
                        host.back()
                    }
                }
            }
        }
        ui.button("Cancel") { host.back() }
        return scroll
    }
}

class LocationOptionsScreen(private val id: String) : Screen() {
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        val loc = g.store.byId(id)
        if (loc == null) {
            titleView = ui.title("Location not found")
            ui.button("Back") { host.back() }
            return scroll
        }
        titleView = ui.title(loc.name)
        val f = g.location.fix
        if (f != null) {
            val d = Geo.distance(f.lat, f.lon, loc.lat, loc.lon)
            val b = Geo.bearing(f.lat, f.lon, loc.lat, loc.lon)
            ui.info("${Fmt.meters(d)}, ${Fmt.direction(b)}, at ${Geo.clockPosition(g.compass.heading, b)}.")
        }
        ui.info(listOfNotNull(
            "Category: ${loc.categoryName}.",
            loc.islandName.takeIf { it.isNotBlank() }?.let { "Island: $it." },
            loc.address.takeIf { it.isNotBlank() }?.let { "Address: $it." },
            when (loc.source) {
                SavedLocation.SOURCE_OSM_MAP -> "Downloaded OpenStreetMap place."
                SavedLocation.SOURCE_GOOGLE -> "Saved from Google Maps."
                SavedLocation.SOURCE_OSM_SEARCH -> "Saved from OpenStreetMap search."
                else -> "Saved extra location."
            }
        ).joinToString(" "))
        ui.button("Start navigation to this place", color = UiColors.BUTTON_ON) {
            g.nav.startPlace(loc)
            host.showTabs("Places")
        }
        ui.button("Set as destination") { onPlacePicked(host, PlacePickMode.DESTINATION, loc) }
        ui.button("Set as current place") { onPlacePicked(host, PlacePickMode.CURRENT, loc) }
        ui.button("Edit name and category") { val d = LocationDraft(); host.show { EditLocationScreen(id, d) } }
        ui.button("Delete this location", color = UiColors.BUTTON_STOP) {
            host.show {
                ConfirmScreen("Are you sure you want to delete ${loc.name}?", "Delete") {
                    g.store.delete(id)
                    if (g.nav.placeDestination?.id == id) g.nav.placeDestination = null
                    if (g.settings.manualCurrentPlaceId == id) g.settings.manualCurrentPlaceId = ""
                    g.speaker.speak("${loc.name} deleted.", force = true)
                    host.back(); host.back()
                }
            }
        }
        ui.button("Back") { host.back() }
        return scroll
    }
}

class EditLocationScreen(private val id: String, private val draft: LocationDraft) : Screen() {
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        val loc = g.store.byId(id)
        if (loc == null) { titleView = ui.title("Location not found"); ui.button("Back") { host.back() }; return scroll }
        if (!draft.loaded) { draft.name = loc.name; draft.category = loc.categoryId; draft.loaded = true }
        titleView = ui.title("Edit location")
        val edit = ui.edit("Location name", draft.name)
        ui.button("Category: ${Categories.name(draft.category)}. Tap to change") { draft.name = edit.text.toString(); host.show(categoryPicker(host, draft)) }
        ui.button("Update GPS position to here: ${if (draft.updateGps) "On" else "Off"}. Tap to change") {
            draft.name = edit.text.toString(); draft.updateGps = !draft.updateGps; host.rebuild()
        }
        ui.button("Update location", color = UiColors.BUTTON_ON) {
            val name = edit.text.toString().trim()
            if (name.isEmpty()) { g.speaker.speak("Please enter a location name.", force = true); return@button }
            if (draft.updateGps) {
                val f = g.location.freshFix(15_000)
                if (f == null || f.accuracy > 150) { g.speaker.speak("GPS is not ready or too weak. Please wait and try again.", force = true); return@button }
                loc.lat = f.lat; loc.lon = f.lon; loc.accuracy = f.accuracy; loc.islandKey = ""
                loc.savedTime = System.currentTimeMillis() / 1000
            }
            loc.name = name
            loc.categoryId = draft.category
            g.store.update(loc)
            g.speaker.speak("Location updated.", force = true)
            host.back()
        }
        ui.button("Cancel") { host.back() }
        return scroll
    }
}
