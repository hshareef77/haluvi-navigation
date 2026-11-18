package mv.haluvi.navigation.ui

import android.view.View
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.data.Atoll
import mv.haluvi.navigation.data.Categories
import mv.haluvi.navigation.data.Island
import mv.haluvi.navigation.data.SavedLocation
import mv.haluvi.navigation.nav.Destination
import mv.haluvi.navigation.nav.Phrases
import mv.haluvi.navigation.net.Http
import mv.haluvi.navigation.net.OnlinePlace

/**
 * Saved and downloaded places, nearest first.
 * With [onPick] it is a chooser (tap = choose); without it, it manages places (tap = place options).
 */
/** Which places to show: one island, one source (saved by me, offline map, Google) or one category. */
data class PlaceFilter(val islandKey: String? = null, val source: String? = null, val category: String? = null, val label: String = "",
                       /** An area inside the island (for example Hulhumalé Phase 2). */
                       val areaId: String? = null)

fun sourceName(source: String): String = when (source) {
    SavedLocation.SOURCE_MANUAL -> "Saved by me"
    SavedLocation.SOURCE_OSM_MAP -> "Offline map"
    SavedLocation.SOURCE_GOOGLE -> "Google"
    else -> "Search results"
}

class MyPlacesScreen(
    private val title: String = "My places",
    private val firstChoice: Pair<String, () -> Unit>? = null,
    private val filter: PlaceFilter? = null,
    private val onPick: ((SavedLocation) -> Unit)? = null
) : Screen() {
    private val forGoTo: Boolean get() = onPick != null
    private var query = ""
    private var limit = 40

    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title(if (filter != null) "$title: ${filter.label}" else title)
        firstChoice?.let { (label, action) -> ui.button(label) { action() } }
        if (!forGoTo && filter == null) {
            ui.button("Save this place") { host.show { SavePlaceScreen() } }
            ui.button("Manage islands and places") { host.show { ManageScreen() } }
        }
        if (filter == null) ui.button("Show: near me. Change") { chooseView() }
        else filter.islandKey?.let { key -> g.islands.byKey(key) ?: g.shapes.extras.firstOrNull { it.key == key } }?.let { isl ->
            if (!forGoTo) ui.button("Island details") { host.show { IslandDetailsScreen(isl) } }
        }
        val edit = ui.edit("Find in my places", query) { query = it.trim(); limit = 40; rebuild() }
        ui.row("Find" to { query = edit.text.toString().trim(); limit = 40; rebuild() }, "Clear" to { query = ""; rebuild() })

        // Nearest first (also when choosing where to go); by name only while there is no position at all.
        val f = g.location.fix
        val cat = filter?.category ?: "all"
        val all: List<Triple<SavedLocation, Double?, Double?>> = if (f != null)
            g.store.nearest(f.lat, f.lon, query, cat, filter?.islandKey).map { Triple(it.loc, it.distance, it.bearing) }
        else g.store.filtered(query, cat).filter { filter?.islandKey == null || it.islandKey == filter.islandKey }.map { Triple(it, null, null) }
        val rows = all.filter { filter?.source == null || it.first.source == filter.source }
            .filter { r -> filter?.areaId == null || mv.haluvi.navigation.nav.Areas.at(r.first.islandKey, r.first.lat, r.first.lon, r.first.address)?.id == filter.areaId }
        if (f == null) ui.info("Waiting for GPS: sorted by name.")
        ui.info("${rows.size} places.")
        if (rows.isEmpty()) ui.info(if (g.store.items.isEmpty()) "No places yet. Save this place, or download the island map." else "No places found.")
        rows.take(limit).forEach { (loc, d, b) ->
            val text = if (d != null && b != null) Phrases.place(loc.name, g.compass.heading, b, d) else loc.name
            ui.button(text) {
                val pick = onPick
                if (pick != null) pick(loc) else host.show { PlaceScreen(loc.id) }
            }
        }
        if (rows.size > limit) ui.button("More places") { limit += 40; rebuild() }
        ui.button("Back") { host.back() }
        return scroll
    }

    /** Near me, by island, by source or by category. Keeps choosing mode ([onPick]) and the title. */
    private fun chooseView() {
        val g = AppGraph
        fun open(fl: PlaceFilter) { host.show { MyPlacesScreen(title, null, fl, onPick) } }
        host.show {
            ChoiceScreen("Show places", null, listOf(
                "Near me" to { host.back() },
                "By island" to { host.show { AtollPickerScreen { isl ->
                    host.backTo(this@MyPlacesScreen); open(PlaceFilter(islandKey = isl.key, label = isl.name))
                } } },
                "By source" to { host.show { ChoiceScreen("By source", null,
                    listOf(SavedLocation.SOURCE_MANUAL, SavedLocation.SOURCE_OSM_MAP, SavedLocation.SOURCE_GOOGLE, SavedLocation.SOURCE_OSM_SEARCH).map { src ->
                        "${sourceName(src)}: ${g.store.items.count { it.source == src }}" to { host.backTo(this@MyPlacesScreen); open(PlaceFilter(source = src, label = sourceName(src))) }
                    }) } },
                "By category" to { host.show { ChoiceScreen("By category", null,
                    Categories.all.map { c ->
                        "${c.name}: ${g.store.items.count { it.categoryId == c.id }}" to { host.backTo(this@MyPlacesScreen); open(PlaceFilter(category = c.id, label = c.name)) }
                    }) } }
            ))
        }
    }

}

/** Import a backup file: places, road names and routes. */
fun importBackup(host: MainActivity) {
    host.pickFile(arrayOf("text/plain", "application/json", "application/octet-stream", "*/*")) { uri ->
        if (uri == null) return@pickFile
        host.scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = host.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    val places = AppGraph.store.import(text)
                    val routes = runCatching {
                        org.json.JSONObject(text.trim()).optJSONArray("routes")?.let { AppGraph.routes.import(it) }
                    }.getOrNull()
                    Triple(places, AppGraph.store.lastImportedRoadNames, routes)
                }
            }
            result.onSuccess { (places, roads, routes) ->
                val (added, skipped) = places
                if (roads > 0) mv.haluvi.navigation.nav.RoadDetector.version++
                host.speak("$added places imported." + (if (skipped > 0) " $skipped already saved." else "") +
                    (if (roads > 0) " $roads road names imported." else "") +
                    (routes?.let { (ra, rs) -> (if (ra > 0) " $ra routes imported." else "") + (if (rs > 0) " $rs routes already saved." else "") } ?: ""))
                host.rebuild()
            }.onFailure { host.speak("This file could not be imported.") }
        }
    }
}

fun exportBackup(host: MainActivity) {
    host.createFile("haluvi-backup.json", "application/json") { uri ->
        if (uri == null) return@createFile
        host.scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val json = org.json.JSONObject(AppGraph.store.exportJson()).put("routes", AppGraph.routes.exportJson()).toString(2)
                    host.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                }.isSuccess
            }
            host.speak(if (ok) "Exported: places, road names and routes." else "Export failed.")
        }
    }
}

class PlaceScreen(private val id: String) : Screen() {
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        val loc = g.store.byId(id)
        if (loc == null) { titleView = ui.title("Place not found"); ui.button("Back") { host.back() }; return scroll }
        titleView = ui.title(loc.name)
        val f = g.location.fix
        val parts = arrayListOf<String>()
        if (f != null) parts.add(Phrases.distance(Geo.distance(f.lat, f.lon, loc.lat, loc.lon)) + ", " + Phrases.where(g.compass.heading, Geo.bearing(f.lat, f.lon, loc.lat, loc.lon)) + ".")
        if (loc.categoryId != "none") parts.add(loc.categoryName + ".")
        if (loc.islandName.isNotBlank()) parts.add(loc.islandName + ".")
        parts.add("Source: ${sourceName(loc.source)}.")
        if (loc.hasAccess) parts.add("Saved with its side of the road.")
        ui.info(parts.joinToString(" "))
        ui.button("Go there", color = UiColors.BUTTON_ON) { goToPlace(host, loc) }
        ui.button("Edit") { host.show { EditPlaceScreen(id) } }
        ui.button("Move to another island") {
            fun move(isl: Island) { mv.haluvi.navigation.nav.JoinedIslands.moveTo(loc, isl); host.speak("${loc.name} is now on ${isl.name}."); host.backTo(this@PlaceScreen) }
            val cur = g.islands.byKey(loc.islandKey)
            val joined = cur?.let { mv.haluvi.navigation.nav.JoinedIslands.groupOf(it) }?.filter { it.key != loc.islandKey }.orEmpty()
            host.show {
                ChoiceScreen("Move ${loc.name} to", "Now on ${cur?.name ?: "no island"}.",
                    joined.map { isl -> "${isl.name}, joined island" to { move(isl) } } +
                        listOf("Choose island" to { host.show { AtollPickerScreen { isl -> move(isl) } } }))
            }
        }
        ui.button("Delete", color = UiColors.BUTTON_STOP) {
            host.show { ConfirmScreen("Delete ${loc.name}?", "Delete") {
                g.store.delete(id); host.speak("Deleted."); host.back(); host.back()
            } }
        }
        ui.button("Back") { host.back() }
        return scroll
    }
}

private fun categoryChooser(host: MainActivity, current: String, set: (String) -> Unit): () -> Screen = {
    ChoiceScreen("Category", null, Categories.all.map { c ->
        (c.name + if (c.id == current) " - selected" else "") to { set(c.id); host.back() }
    })
}

class SavePlaceScreen : Screen() {
    private var name = ""
    private var category = "none"
    /** "here", "left" or "right": where the place is, seen from where you stand on the road. */
    private var side = "here"
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Save this place")
        val f = g.location.fix
        ui.info(if (f == null) "Waiting for GPS." else if (f.accuracy > 30) "GPS is weak. Wait a little if you can." else "GPS is good.")
        val edit = ui.edit("Name", name)
        ui.button("Category: ${Categories.name(category)}") { name = edit.text.toString(); host.show(categoryChooser(host, category) { category = it }) }
        ui.button("Where is the place: " + when (side) { "left" -> "on my left"; "right" -> "on my right"; else -> "here" }) {
            name = edit.text.toString()
            side = when (side) { "here" -> "left"; "left" -> "right"; else -> "here" }
            host.say(when (side) { "left" -> "On my left"; "right" -> "On my right"; else -> "Here" }); rebuild()
        }
        if (side != "here") ui.info("Stand on the road in front of the place, facing along the road. Arrival will then say the correct side.")
        ui.button("Save", color = UiColors.BUTTON_ON) {
            val n = edit.text.toString().trim()
            val fix = g.location.freshFix(15_000)
            when {
                n.isEmpty() -> host.speak("Please type a name.")
                fix == null -> host.speak("GPS is not ready. Please wait and try again.")
                fix.accuracy > 100 -> host.speak("GPS is too weak. Please wait and try again.")
                else -> {
                    val dup = g.store.findDuplicate(n, fix.lat, fix.lon)
                    if (dup != null) host.speak("${dup.name} is already saved here.")
                    else {
                        val loc = SavedLocation(SavedLocation.newId(), n, fix.lat, fix.lon, fix.accuracy, source = SavedLocation.SOURCE_MANUAL, categoryId = category)
                        placeAtSide(loc, fix, side)
                        g.store.add(loc)
                        host.speak("$n saved.")
                        host.back()
                    }
                }
            }
        }
        ui.button("Cancel") { host.back() }
        return scroll
    }
}

/**
 * Puts [loc] at your position, or (side "left"/"right") 8 m to that side of the road you stand on, keeping
 * your position as the road point in front of it, so arrival says the right side from either direction.
 */
fun placeAtSide(loc: SavedLocation, fix: mv.haluvi.navigation.sensors.Fix, side: String) {
    val g = AppGraph
    loc.lat = fix.lat; loc.lon = fix.lon; loc.accessLat = 0.0; loc.accessLon = 0.0
    if (side != "left" && side != "right") return
    // Facing direction: the road direction nearest to where the phone points, else the walking or phone direction.
    val phone = if ((fix.speedMps ?: 0.0) >= 0.5 && fix.bearing != null) fix.bearing else g.compass.heading
    val facing = mv.haluvi.navigation.nav.RoadDetector.roadDirectionAt(fix)?.let { rb ->
        if (Geo.angleBetween(phone, rb) <= 90) rb else Geo.normalize(rb + 180)
    } ?: phone
    val p = mv.haluvi.navigation.core.offsetPoint(fix.lat, fix.lon, Geo.normalize(facing + if (side == "left") -90.0 else 90.0), 8.0)
    loc.lat = p.lat; loc.lon = p.lon; loc.accessLat = fix.lat; loc.accessLon = fix.lon
}

class EditPlaceScreen(private val id: String) : Screen() {
    private var name: String? = null
    private var category: String? = null
    private var moveHere = false
    private var side = "here"
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        val loc = g.store.byId(id)
        if (loc == null) { titleView = ui.title("Place not found"); ui.button("Back") { host.back() }; return scroll }
        if (name == null) { name = loc.name; category = loc.categoryId }
        titleView = ui.title("Edit place")
        val edit = ui.edit("Name", name ?: "")
        ui.button("Category: ${Categories.name(category)}") { name = edit.text.toString(); host.show(categoryChooser(host, category ?: "none") { category = it }) }
        ui.button("Move to my position: ${if (moveHere) "On" else "Off"}") { name = edit.text.toString(); moveHere = !moveHere; rebuild() }
        if (moveHere) ui.button("Where is the place: " + when (side) { "left" -> "on my left"; "right" -> "on my right"; else -> "here" }) {
            name = edit.text.toString()
            side = when (side) { "here" -> "left"; "left" -> "right"; else -> "here" }
            host.say(when (side) { "left" -> "On my left"; "right" -> "On my right"; else -> "Here" }); rebuild()
        }
        ui.button("Save", color = UiColors.BUTTON_ON) {
            val n = edit.text.toString().trim()
            if (n.isEmpty()) { host.speak("Please type a name."); return@button }
            if (moveHere) {
                val f = g.location.freshFix(15_000)
                if (f == null || f.accuracy > 100) { host.speak("GPS is not ready. Please wait and try again."); return@button }
                placeAtSide(loc, f, side); loc.accuracy = f.accuracy; loc.islandKey = ""
            }
            loc.name = n
            loc.categoryId = category ?: "none"
            g.store.update(loc)
            host.speak("Saved.")
            host.back()
        }
        ui.button("Cancel") { host.back() }
        return scroll
    }
}

/** Online search. Uses Google when a key is set, otherwise (or if Google fails) OpenStreetMap. */
class SearchScreen : Screen() {
    /** Island to search on; null = the island I am on. */
    private var area: Island? = null
    private var query = ""
    private var results: List<OnlinePlace> = emptyList()
    private var searching = false
    private var job: Job? = null
    private var message = ""

    override fun create(): View {
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Search")
        ui.button("Search in: " + (area?.let { if (it.type == "inhabited") it.fullName else it.spokenName } ?: "my island")) {
            host.show {
                ChoiceScreen("Search in", null, listOf(
                    "My island" to { area = null; host.backTo(this@SearchScreen) },
                    "Choose island" to { host.show { AtollPickerScreen { isl -> area = isl; host.say("Search in ${isl.name}"); host.backTo(this@SearchScreen) } } }
                ))
            }
        }
        val edit = ui.edit("Place name", query) { search(it) }
        ui.button("Search") { search(edit.text.toString()) }
        ui.row("Mosque" to { search("mosque") }, "Health" to { search("hospital clinic pharmacy") }, "Shop" to { search("shop supermarket") })
        ui.row("Food" to { search("restaurant cafe") }, "Ferry" to { search("ferry terminal harbor jetty") })
        when {
            searching -> ui.info("Searching for $query.")
            message.isNotBlank() -> ui.info(message)
        }
        val g = AppGraph
        results.forEach { p ->
            val text = if (p.distance != null && p.bearing != null) Phrases.place(p.name, g.compass.heading, p.bearing!!, p.distance!!) else p.name
            ui.button(text) {
                val loc = SavedLocation(SavedLocation.newId(), p.name, p.lat, p.lon,
                    source = if (p.source == "google") SavedLocation.SOURCE_GOOGLE else SavedLocation.SOURCE_OSM_SEARCH,
                    categoryId = Categories.guess(p.name + " " + p.typeText), address = p.address, placeId = p.placeId)
                goToPlace(host, loc)
            }
        }
        ui.button("Back") { host.back() }
        return scroll
    }

    private fun search(text: String) {
        val q = text.trim()
        if (q.isEmpty()) { host.speak("Please type a place name."); return }
        query = q; searching = true; message = ""; results = emptyList()
        rebuild()
        host.speak("Searching.")
        val g = AppGraph
        val f = g.location.fix
        val chosen = area
        // Search centre: the chosen island, or my position.
        val cLat = chosen?.lat ?: f?.lat
        val cLon = chosen?.lon ?: f?.lon
        val island = chosen?.name ?: f?.let { g.islands.nearest(it.lat, it.lon)?.island?.name }
        job?.cancel()
        job = host.scope.launch {
            val list = withContext(Dispatchers.IO) {
                // Google (online, with a key) and OpenStreetMap together, without duplicates.
                val out = LinkedHashMap<String, OnlinePlace>()
                var err: Throwable? = null
                if (g.settings.googleReady) {
                    val gq = if (island != null) "$q, $island, Maldives" else "$q, Maldives"
                    runCatching { g.google.searchText(gq, cLat, cLon, if (chosen != null) 3000 else 5000) }
                        .onSuccess { l -> l.forEach { out.putIfAbsent(it.key, it) } }.onFailure { err = it }
                }
                runCatching { g.osm.search(q, cLat, cLon, island, wide = false) }
                    .onSuccess { l -> l.forEach { p -> if (out.values.none { o -> o.name.equals(p.name, true) && Geo.distance(o.lat, o.lon, p.lat, p.lon) < 50 }) out.putIfAbsent(p.key, p) } }
                    .onFailure { if (out.isEmpty() && err == null) err = it }
                out.values.toList() to (if (out.isEmpty()) err else null)
            }
            searching = false
            val (places, err) = list
            if (f != null) places.forEach { it.distance = Geo.distance(f.lat, f.lon, it.lat, it.lon); it.bearing = Geo.bearing(f.lat, f.lon, it.lat, it.lon) }
            results = places.sortedBy { it.distance ?: 0.0 }.take(30)
            message = when {
                results.isNotEmpty() -> "${results.size} places found."
                err != null -> Http.friendly(err!!, "Search")
                else -> "Nothing found."
            }
            host.speak(message)
            if (host.currentScreen() === this@SearchScreen) rebuild()
        }
    }

    override fun onDispose() { if (searching) { job?.cancel(); searching = false } }
}

/** Atolls, with island name search. */
class AtollPickerScreen(private val onPick: (Island) -> Unit) : Screen() {
    private var query = ""
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Islands")
        val edit = ui.edit("Find island, resort or airport", query) { query = it; rebuild() }
        ui.button("Find") { query = edit.text.toString(); rebuild() }
        if (query.isNotBlank()) {
            val found = g.shapes.search(query).take(40)
            if (found.isEmpty()) ui.info("Nothing found.")
            found.forEach { isl -> ui.button(islandLabel(isl)) { onPick(isl) } }
        }
        if (query.isBlank()) g.location.fix?.let { f ->
            // Nearest inhabited islands first (my island first).
            ui.heading("Nearest islands")
            g.islands.allIslands.sortedBy { Geo.distance(f.lat, f.lon, it.lat, it.lon) }.take(6)
                .forEach { isl -> ui.button(islandLabel(isl)) { onPick(isl) } }
            ui.heading("More")
        }
        ui.button("Airports") { host.show { AirportsScreen(onPick) } }
        if (g.shapes.extras.isNotEmpty()) ui.button("Resorts and other islands nearby") { host.show { OtherIslandsScreen(onPick) } }
        ui.heading("Atolls")
        g.islands.atolls.forEach { a -> ui.button(atollLabel(a)) { host.show { IslandPickerScreen(a, onPick) } } }
        ui.button("Back") { host.back() }
        return scroll
    }
}

/** "34 offline places" or "no offline places" (places saved on the phone: they work without internet). */
fun offlinePlacesText(n: Int): String = when (n) { 0 -> "no offline places"; 1 -> "1 offline place"; else -> "$n offline places" }

/** "Kandooma, resort, 3.2 kilometers" or "Faafu Feeali, 34 offline places, 0.2 kilometers". */
fun islandLabel(isl: Island, withAtoll: Boolean = true): String {
    val f = AppGraph.location.fix
    val dist = f?.let { ", " + Phrases.distance(Geo.distance(it.lat, it.lon, isl.lat, isl.lon)) } ?: ""
    val name = if (isl.type == "inhabited" && isl.hasAirport) (if (withAtoll) isl.fullName else isl.name) + ", with airport"
        else if (isl.type == "inhabited") (if (withAtoll) isl.fullName else isl.name) else isl.spokenName
    return name + ", " + offlinePlacesText(AppGraph.store.offlineCount(isl.key)) + dist
}

/** "Faafu, 5 islands, 120 offline places". */
fun atollLabel(a: Atoll): String {
    val counts = AppGraph.store.countsByIsland()
    return "${a.fullName}, ${a.islands.size} islands, ${offlinePlacesText(a.islands.sumOf { counts[it.key] ?: 0 })}"
}

/** Nearest resorts, airports and uninhabited named islands (from the downloaded outlines). */
class OtherIslandsScreen(private val onPick: (Island) -> Unit) : Screen() {
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Resorts, airports and other islands")
        val f = g.location.fix
        val list = if (f != null) g.shapes.extras.sortedBy { Geo.distance(f.lat, f.lon, it.lat, it.lon) } else g.shapes.extras.sortedBy { it.name }
        if (list.isEmpty()) ui.info("None yet. Download island outlines first.")
        list.take(40).forEach { isl -> ui.button(islandLabel(isl)) { onPick(isl) } }
        ui.button("Back") { host.back() }
        return scroll
    }
}

class IslandPickerScreen(private val atoll: Atoll, private val onPick: (Island) -> Unit) : Screen() {
    override fun create(): View {
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title(atollLabel(atoll))
        val f = AppGraph.location.fix
        val list = if (f != null) atoll.islands.sortedBy { Geo.distance(f.lat, f.lon, it.lat, it.lon) } else atoll.islands
        list.forEach { island -> ui.button(islandLabel(island, withAtoll = false)) { onPick(island) } }
        ui.button("Back") { host.back() }
        return scroll
    }
}
