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

fun sourceName(source: String): String = when (source) {
    SavedLocation.SOURCE_MANUAL -> "Saved by me"
    SavedLocation.SOURCE_OSM_MAP -> "OpenStreetMap"
    SavedLocation.SOURCE_GOOGLE -> "Google"
    else -> "Search results"
}

private var importedExtras = 0
private var importedRoadIslands = 0

/** Import a backup file: places, road names, routes, borders, areas and island changes. */
fun importBackup(host: MainActivity) {
    host.pickFile(arrayOf("text/plain", "application/json", "application/octet-stream", "*/*")) { uri ->
        if (uri == null) return@pickFile
        host.scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = host.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    importedRoadIslands = 0; importedExtras = 0
                    val places = AppGraph.store.import(text)
                    val root = runCatching { org.json.JSONObject(text.trim()) }.getOrNull()
                    val routes = runCatching { root?.optJSONArray("routes")?.let { AppGraph.routes.import(it) } }.getOrNull()
                    runCatching { root?.optJSONArray("busRoutes")?.let { importedExtras += AppGraph.busRoutes.import(it) } }
                    // Borders, areas and island changes, then the places on joined islands are re-checked.
                    var extra = 0
                    runCatching { root?.optJSONObject("islandChanges")?.let { extra += mv.haluvi.navigation.data.IslandOverrides.importJson(it, AppGraph.islands.allIslands + AppGraph.shapes.extras) } }
                    runCatching { root?.optJSONArray("areas")?.let { extra += mv.haluvi.navigation.nav.Areas.importJson(it) } }
                    runCatching { root?.optJSONObject("borders")?.let { extra += mv.haluvi.navigation.nav.JoinedIslands.importJson(it) } }
                    // Downloaded roads per island: added only where the island has none yet (nothing is replaced).
                    runCatching {
                        root?.optJSONObject("osmRoads")?.let { o ->
                            val done = HashMap<String, Int>()
                            o.keys().forEach { k -> val arr = o.getJSONArray(k); if (AppGraph.store.importRoadsJson(k, arr)) done[k] = arr.length() }
                            if (done.isNotEmpty()) { mv.haluvi.navigation.nav.OfflineMaps.markImported(done.keys, done); mv.haluvi.navigation.nav.RoadDetector.version++ }
                            importedRoadIslands = done.size
                        }
                    }
                    if (extra > 0) runCatching { mv.haluvi.navigation.nav.JoinedIslands.recheckAll() }
                    Triple(places, AppGraph.store.lastImportedRoadNames, routes).also { importedExtras += extra }
                }
            }
            result.onSuccess { (places, roads, routes) ->
                val (added, skipped) = places
                if (roads > 0) mv.haluvi.navigation.nav.RoadDetector.version++
                host.speak("$added places imported." + (if (skipped > 0) " $skipped already saved." else "") +
                    (if (roads > 0) " $roads road names imported." else "") +
                    (routes?.let { (ra, rs) -> (if (ra > 0) " $ra routes imported." else "") + (if (rs > 0) " $rs routes already saved." else "") } ?: "") +
                    (if (importedRoadIslands > 0) " Roads of $importedRoadIslands islands added." else "") +
                    (if (importedExtras > 0) " Bus routes, borders, areas and island changes restored." else ""))
                host.rebuild()
            }.onFailure { host.speak("This file could not be imported.") }
        }
    }
}

/**
 * Export. Without [share]: a full backup for your own phone (everything, also Google places and roads).
 * With [share]: a map to share with others for those islands: OpenStreetMap places and roads, places you saved
 * yourself, road names, recorded roads, routes, borders, areas and island changes. Google places are left out,
 * because Google's terms do not allow sharing them.
 */
fun exportBackup(host: MainActivity, share: List<Island>? = null, label: String = "") {
    val fileName = if (share == null) "haluvi-backup.json" else "haluvi-map-" + label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') + ".json"
    host.createFile(fileName, "application/json") { uri ->
        if (uri == null) return@createFile
        host.scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val g = AppGraph
                    val allIslands = g.islands.allIslands + g.shapes.extras
                    val keys = (share ?: allIslands).map { it.key }.toSet()
                    val root = if (share == null) org.json.JSONObject(g.store.exportJson()) else {
                        val arr = org.json.JSONArray()
                        g.store.items.filter { it.islandKey in keys && it.source != SavedLocation.SOURCE_GOOGLE }.forEach { arr.put(it.toJson()) }
                        val names = org.json.JSONArray()
                        val all = g.store.roadNames.exportJson()
                        for (i in 0 until all.length()) { val o = all.getJSONObject(i); if (o.optString("island") in keys) names.put(o) }
                        org.json.JSONObject().put("app", "Haluvi Navigation").put("version", 2).put("locations", arr).put("roadNames", names)
                    }
                    root.put("kind", if (share == null) "full" else "share")
                    // Downloaded (OpenStreetMap) roads per island.
                    val roads = org.json.JSONObject()
                    var roadIslands = 0
                    for (k in keys) g.store.roadsJson(k)?.takeIf { it.length() > 0 }?.let { roads.put(k, it); roadIslands++ }
                    root.put("osmRoads", roads)
                    root.put("routes", g.routes.exportJson())
                        .put("busRoutes", g.busRoutes.exportJson())
                        .put("borders", mv.haluvi.navigation.nav.JoinedIslands.exportJson())
                        .put("areas", mv.haluvi.navigation.nav.Areas.exportJson())
                        .put("islandChanges", mv.haluvi.navigation.data.IslandOverrides.exportJson())
                    host.contentResolver.openOutputStream(uri)?.use { it.write(root.toString().toByteArray()) }
                    root.getJSONArray("locations").length() to roadIslands
                }
            }
            result.onSuccess { (places, roadIslands) ->
                host.speak((if (share == null) "Full backup saved: " else "Map to share saved: ") + "$places places, roads of $roadIslands islands, road names, routes, borders, areas and island changes." +
                    if (share != null) " Google places are not included." else "")
            }.onFailure { host.speak("Export failed.") }
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
        mv.haluvi.navigation.nav.RoadInfo.of(loc)?.let { parts.add("On $it.") }
        if (loc.islandName.isNotBlank()) parts.add(loc.islandName + ".")
        parts.add("Source: ${sourceName(loc.source)}.")
        if (loc.hasAccess) parts.add("Saved with its side of the road.")
        ui.info(parts.joinToString(" "))
        ui.button("Go there", color = UiColors.BUTTON_ON) { goToPlace(host, loc) }
        val buses = g.busRoutes.near(loc.lat, loc.lon, 500.0)
        if (buses.isNotEmpty()) ui.button("Which bus goes here? ${buses.size} routes") { host.show { BusesToPlaceScreen(loc.name, loc.lat, loc.lon) } }
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
    val roads = mv.haluvi.navigation.nav.OfflineMaps.status(isl.key)?.roads?.let { if (it == 1) ", 1 road" else ", $it roads" } ?: ""
    return name + ", " + offlinePlacesText(AppGraph.store.offlineCount(isl.key)) + roads + dist
}

/** "Faafu, 5 islands, 120 offline places". */
fun atollLabel(a: Atoll): String {
    val counts = AppGraph.store.countsByIsland()
    val st = a.islands.mapNotNull { mv.haluvi.navigation.nav.OfflineMaps.status(it.key) }
    val roads = if (st.isEmpty()) ", no roads" else ", ${st.sumOf { it.roads }} roads" + if (st.size < a.islands.size) " on ${st.size} of ${a.islands.size} islands" else ""
    return "${a.fullName}, ${islandsText(a.islands.size)}, ${offlinePlacesText(a.islands.sumOf { counts[it.key] ?: 0 })}$roads"
}

fun extrasOfAtoll(a: Atoll): List<Island> = mv.haluvi.navigation.nav.OfflineMaps.extrasOf(a)

fun islandsText(n: Int) = if (n == 1) "1 island" else "$n islands"

/** When each island was last searched on Google (kept on the phone). */
object GoogleSearchedDates {
    private val file get() = java.io.File(AppGraph.appContext.filesDir, "google_searched.json")
    private var map: MutableMap<String, Long>? = null
    @Synchronized private fun m(): MutableMap<String, Long> = map ?: HashMap<String, Long>().also { mm ->
        runCatching { val o = org.json.JSONObject(file.readText()); o.keys().forEach { k -> mm[k] = o.getLong(k) } }
        map = mm
    }
    @Synchronized fun get(key: String): Long? = m()[key]
    @Synchronized fun set(key: String, ms: Long) {
        m()[key] = ms
        val o = org.json.JSONObject(); m().forEach { (k, v) -> o.put(k, v) }
        runCatching { file.writeText(o.toString()) }
    }
    fun dateText(ms: Long): String = java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(java.util.Date(ms))

    /** "last Google search 15 November 2026" (latest of the islands), "never searched on Google", or partly. */
    fun text(islands: List<mv.haluvi.navigation.data.Island>): String {
        val dates = islands.mapNotNull { get(it.key) }
        return when {
            dates.isEmpty() -> "never searched on Google"
            dates.size < islands.size -> "last Google search ${dateText(dates.max())} (${dates.size} of ${islands.size} islands)"
            else -> "last Google search ${dateText(dates.max())}"
        }
    }
}

class IslandPickerScreen(private val atoll: Atoll, private val onPick: (Island) -> Unit) : Screen() {
    override fun create(): View {
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title(atollLabel(atoll))
        val f = AppGraph.location.fix
        val list = if (f != null) atoll.islands.sortedBy { Geo.distance(f.lat, f.lon, it.lat, it.lon) } else atoll.islands
        list.forEach { island -> ui.button(islandLabel(island, withAtoll = false)) { onPick(island) } }
        val others = extrasOfAtoll(atoll).let { l -> if (f != null) l.sortedBy { Geo.distance(f.lat, f.lon, it.lat, it.lon) } else l.sortedBy { it.name } }
        if (others.isNotEmpty()) {
            ui.heading("Airports, resorts and other islands")
            others.take(60).forEach { isl -> ui.button(islandLabel(isl)) { onPick(isl) } }
        }
        ui.button("Back") { host.back() }
        return scroll
    }
}
