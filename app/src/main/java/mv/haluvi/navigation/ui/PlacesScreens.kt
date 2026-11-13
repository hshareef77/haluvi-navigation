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

/** Go to: My places, Search, Islands. */
class GoToScreen : Screen() {
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Go to")
        ui.button("My places") { host.show { MyPlacesScreen(forGoTo = true) } }
        ui.button("Search") { host.show { SearchScreen() } }
        ui.button("Islands") { host.show { AtollPickerScreen { island -> host.show { StartScreen(Destination.of(island)) } } } }
        val f = g.location.fix
        if (f != null) {
            val near = g.store.nearest(f.lat, f.lon).take(5)
            if (near.isNotEmpty()) {
                ui.heading("Nearest of my places")
                near.forEach { hit ->
                    ui.button(Phrases.place(hit.loc.name, g.compass.heading, hit.bearing, hit.distance)) {
                        host.show { StartScreen(Destination.of(hit.loc)) }
                    }
                }
            }
        }
        ui.button("Back") { host.back() }
        return scroll
    }
}

/** Saved and downloaded places. [forGoTo]: tapping a place opens Start; otherwise it opens place options. */
class MyPlacesScreen(private val forGoTo: Boolean) : Screen() {
    private var query = ""
    private var limit = 40

    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("My places")
        if (!forGoTo) {
            ui.button("Save this place") { host.show { SavePlaceScreen() } }
            ui.button("Download island map") { host.show { MapDataScreen() } }
        }
        val edit = ui.edit("Find in my places", query) { query = it.trim(); limit = 40; rebuild() }
        ui.row("Find" to { query = edit.text.toString().trim(); limit = 40; rebuild() }, "Clear" to { query = ""; rebuild() })

        val f = g.location.fix
        val rows: List<Triple<SavedLocation, Double?, Double?>> = if (f != null)
            g.store.nearest(f.lat, f.lon, query).map { Triple(it.loc, it.distance, it.bearing) }
        else g.store.filtered(query).map { Triple(it, null, null) }
        if (rows.isEmpty()) ui.info(if (g.store.items.isEmpty()) "No places yet. Save this place, or download the island map." else "No places found.")
        rows.take(limit).forEach { (loc, d, b) ->
            val text = if (d != null && b != null) Phrases.place(loc.name, g.compass.heading, b, d) else loc.name
            ui.button(text) {
                if (forGoTo) host.show { StartScreen(Destination.of(loc)) } else host.show { PlaceScreen(loc.id) }
            }
        }
        if (rows.size > limit) ui.button("More places") { limit += 40; rebuild() }
        if (!forGoTo) {
            ui.row("Import" to { importPlaces() }, "Export" to { exportPlaces() })
        }
        ui.button("Back") { host.back() }
        return scroll
    }

    private fun importPlaces() {
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
                    host.speak("$added places imported." + if (skipped > 0) " $skipped already saved." else "")
                    rebuild()
                }.onFailure { host.speak("This file could not be imported.") }
            }
        }
    }

    private fun exportPlaces() {
        host.createFile("haluvi-places.json", "application/json") { uri ->
            if (uri == null) return@createFile
            host.scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { host.contentResolver.openOutputStream(uri)?.use { it.write(AppGraph.store.exportJson().toByteArray()) } }.isSuccess
                }
                host.speak(if (ok) "Places exported." else "Export failed.")
            }
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
        if (parts.isNotEmpty()) ui.info(parts.joinToString(" "))
        ui.button("Go there", color = UiColors.BUTTON_ON) { host.show { StartScreen(Destination.of(loc)) } }
        ui.button("Edit") { host.show { EditPlaceScreen(id) } }
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
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Save this place")
        val f = g.location.fix
        ui.info(if (f == null) "Waiting for GPS." else if (f.accuracy > 30) "GPS is weak. Wait a little if you can." else "GPS is good.")
        val edit = ui.edit("Name", name)
        ui.button("Category: ${Categories.name(category)}") { name = edit.text.toString(); host.show(categoryChooser(host, category) { category = it }) }
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
                        g.store.add(SavedLocation(SavedLocation.newId(), n, fix.lat, fix.lon, fix.accuracy, source = SavedLocation.SOURCE_MANUAL, categoryId = category))
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

class EditPlaceScreen(private val id: String) : Screen() {
    private var name: String? = null
    private var category: String? = null
    private var moveHere = false
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
        ui.button("Save", color = UiColors.BUTTON_ON) {
            val n = edit.text.toString().trim()
            if (n.isEmpty()) { host.speak("Please type a name."); return@button }
            if (moveHere) {
                val f = g.location.freshFix(15_000)
                if (f == null || f.accuracy > 100) { host.speak("GPS is not ready. Please wait and try again."); return@button }
                loc.lat = f.lat; loc.lon = f.lon; loc.accuracy = f.accuracy; loc.islandKey = ""
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
    private var query = ""
    private var results: List<OnlinePlace> = emptyList()
    private var searching = false
    private var job: Job? = null
    private var message = ""

    override fun create(): View {
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Search")
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
                    categoryId = Categories.guess(p.name + " " + p.typeText), address = p.address)
                host.show { StartScreen(Destination.of(loc), loc) }
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
        val island = f?.let { g.islands.nearest(it.lat, it.lon)?.island?.name }
        job?.cancel()
        job = host.scope.launch {
            val list = withContext(Dispatchers.IO) {
                var out: List<OnlinePlace> = emptyList()
                var err: Throwable? = null
                if (g.settings.googleReady) {
                    val gq = if (island != null) "$q, $island, Maldives" else "$q, Maldives"
                    runCatching { g.google.searchText(gq, f?.lat, f?.lon, 5000) }.onSuccess { out = it }.onFailure { err = it }
                }
                if (out.isEmpty()) {
                    runCatching { g.osm.search(q, f?.lat, f?.lon, island, wide = false) }.onSuccess { out = it; err = null }.onFailure { if (err == null) err = it }
                }
                out to err
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
        val edit = ui.edit("Find island", query) { query = it; rebuild() }
        ui.button("Find") { query = edit.text.toString(); rebuild() }
        if (query.isNotBlank()) {
            val found = g.islands.search(query)
            if (found.isEmpty()) ui.info("No island found.")
            found.forEach { ui.button(it.fullName) { onPick(it) } }
        }
        g.islands.atolls.forEach { a -> ui.button(a.fullName) { host.show { IslandPickerScreen(a, onPick) } } }
        ui.button("Back") { host.back() }
        return scroll
    }
}

class IslandPickerScreen(private val atoll: Atoll, private val onPick: (Island) -> Unit) : Screen() {
    override fun create(): View {
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title(atoll.fullName)
        val f = AppGraph.location.fix
        atoll.islands.forEach { island ->
            val extra = f?.let { ", " + Phrases.distance(Geo.distance(it.lat, it.lon, island.lat, island.lon)) } ?: ""
            ui.button(island.name + extra) { onPick(island) }
        }
        ui.button("Back") { host.back() }
        return scroll
    }
}

/** Downloads places and roads of one island from OpenStreetMap for offline use. */
class MapDataScreen : Screen() {
    private var job: Job? = null
    private var progressView: TextView? = null
    private var progress = ""

    private fun island(): Island? = AppGraph.islands.byKey(AppGraph.settings.mapDataIslandKey) ?: AppGraph.nav.currentIsland()

    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Island map")
        val isl = island()
        if (job?.isActive == true) {
            progressView = ui.info(progress)
            ui.button("Cancel download", color = UiColors.BUTTON_STOP) { job?.cancel(); progress = "Download cancelled."; rebuild() }
            return scroll
        }
        progressView = null
        ui.button("Island: " + (isl?.fullName ?: "waiting for GPS")) {
            host.show { AtollPickerScreen { picked -> g.settings.mapDataIslandKey = picked.key; host.backTo(this@MapDataScreen) } }
        }
        if (isl != null) {
            ui.info("${g.store.countDownloadedForIsland(isl.key)} places and ${g.store.roadCount(isl.key)} roads downloaded.")
            if (progress.isNotBlank()) ui.info(progress)
            ui.button("Download", color = UiColors.BUTTON_ON) { download(isl) }
            ui.button("Delete downloaded map", color = UiColors.BUTTON_STOP) {
                host.show { ConfirmScreen("Delete the downloaded map of ${isl.name}? Places you saved yourself are kept.", "Delete") {
                    val n = g.store.deleteDownloadedForIsland(isl.key)
                    host.speak("$n places deleted.")
                    host.back()
                } }
            }
        }
        ui.button("Back") { host.back() }
        return scroll
    }

    private fun setProgress(text: String) { progress = text; progressView?.text = text }

    private fun download(island: Island) {
        val g = AppGraph
        val cats = g.osm.mapCategories
        val total = cats.size + 1
        progress = "Starting."
        host.speak("Downloading map of ${island.name}.")
        job = host.scope.launch {
            var added = 0; var failed = 0
            for ((i, cat) in cats.withIndex()) {
                if (!isActive) return@launch
                setProgress("Step ${i + 1} of $total. $added places so far.")
                val r = withContext(Dispatchers.IO) { runCatching { g.osm.islandPlaces(cat.key, island.lat, island.lon, 4000) } }
                r.onSuccess { list ->
                    list.forEach { p ->
                        if (g.islands.nearest(p.lat, p.lon)?.island?.key != island.key) return@forEach
                        val catId = if (cat.key == "common") Categories.guess(p.name + " " + p.typeText) else cat.key
                        val loc = SavedLocation(SavedLocation.newId(), p.name, p.lat, p.lon, 0.0, source = SavedLocation.SOURCE_OSM_MAP,
                            categoryId = catId, islandKey = island.key, islandName = island.fullName, address = p.address)
                        if (g.store.add(loc, persist = false)) added++
                    }
                    g.store.save()
                }.onFailure { failed++ }
            }
            if (!isActive) return@launch
            setProgress("Step $total of $total: roads.")
            var roadCount = -1
            withContext(Dispatchers.IO) { runCatching { g.osm.islandRoads(island.lat, island.lon, 4000) } }.onSuccess { list ->
                val onIsland = list.filter { r -> r.points.any { p -> g.islands.nearest(p[0], p[1])?.island?.key == island.key } }
                withContext(Dispatchers.IO) { g.store.saveRoads(island.key, onIsland) }
                roadCount = onIsland.size
            }.onFailure { failed++ }
            val msg = "Done. $added new places" + (if (roadCount >= 0) " and $roadCount roads." else ".") +
                (if (failed > 0) " Some parts failed. Press Download again to retry." else "")
            progress = msg
            host.speak(msg)
            if (host.currentScreen() === this@MapDataScreen) rebuild()
        }
        rebuild()
    }
}
