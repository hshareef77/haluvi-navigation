package mv.haluvi.navigation.ui

import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.Fmt
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.data.Categories
import mv.haluvi.navigation.data.Island
import mv.haluvi.navigation.data.SavedLocation
import mv.haluvi.navigation.net.Http
import mv.haluvi.navigation.net.OnlinePlace

/** Search state shared by the search, results and details screens. */
object OnlineSearchState {
    var query = ""
    var areaIsland: Island? = null
    var results: List<OnlinePlace> = emptyList()
    val selected = LinkedHashSet<String>()
    var provider = "Google Maps"
    var wide = false
}

class OnlineSearchScreen(private val mode: Mode) : Screen() {
    enum class Mode { DESTINATION, MANAGE }

    private var job: Job? = null
    private var busyText: String? = null

    override fun create(): View {
        val g = AppGraph
        val st = OnlineSearchState
        st.provider = g.settings.onlineProvider
        val (scroll, ui) = UiBuilder.scrolling(host)
        if (busyText != null) {
            titleView = ui.title("Searching, please wait")
            ui.info(busyText!!)
            ui.button("Cancel search") { job?.cancel(); busyText = null; host.rebuild() }
            return scroll
        }
        titleView = ui.title(if (mode == Mode.DESTINATION) "Online destination search" else "Online search for new places")
        ui.button("Search with: ${st.provider}. Tap to switch") {
            g.settings.onlineProvider = if (st.provider == "Google Maps") "OpenStreetMap" else "Google Maps"
            host.rebuild()
        }
        if (st.provider == "Google Maps" && !g.settings.googleReady) {
            ui.info("Google Maps is off or the API key is missing. Use OpenStreetMap, or add a key in Settings.")
            ui.button("Open Settings") { host.showTabs("Settings") }
        }
        ui.button("Search area: " + (st.areaIsland?.fullName ?: "my current GPS position") + ". Tap to change") {
            host.show {
                ChoiceScreen("Search area", null, listOf(
                    "My current GPS position" to { st.areaIsland = null; host.back() },
                    "Choose island" to { host.show { AtollPickerScreen("Search area atoll") { island -> st.areaIsland = island; host.back(); host.back(); host.back() } } }
                ))
            }
        }
        val edit = ui.edit("Type place name or category", st.query) { doSearch(it) }
        ui.row("Search" to { doSearch(edit.text.toString()) }, "Clear" to { st.query = ""; host.rebuild() })
        ui.heading("Quick searches")
        listOf(
            "Mosques" to "mosque", "Hospitals and pharmacies" to "hospital health clinic pharmacy",
            "Shops and markets" to "shop supermarket market", "Restaurants and cafes" to "restaurant cafe",
            "Ferry terminals and harbors" to "ferry terminal harbor jetty", "Schools" to "school",
            "Banks and ATMs" to "bank atm", "Hotels and guest houses" to "hotel guest house"
        ).forEach { (label, q) -> ui.button(label) { doSearch(q) } }
        if (st.results.isNotEmpty()) ui.button("Show last results: ${st.results.size}") { host.show { OnlineResultsScreen(mode) } }
        ui.button("Back") { host.back() }
        return scroll
    }

    private fun doSearch(text: String, wide: Boolean = false) {
        val g = AppGraph
        val st = OnlineSearchState
        val q = text.trim()
        if (q.isEmpty()) { g.speaker.speak("Please type a place name or category.", force = true); return }
        st.query = q
        st.wide = wide
        val f = g.location.fix
        val center = st.areaIsland?.let { it.lat to it.lon } ?: f?.let { it.lat to it.lon }
        val islandName = st.areaIsland?.name ?: center?.let { g.islands.nearest(it.first, it.second)?.island?.name }
        busyText = "Searching ${st.provider} for $q" + (islandName?.let { " near $it" } ?: "") + "."
        host.rebuild()
        g.speaker.speak("Searching.", force = true)
        val provider = st.provider
        job = host.scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (provider == "Google Maps") {
                        val osmCat = g.osm.categoryForQuery(q)
                        val gq = if (osmCat != "keyword" && islandName != null) "$q in $islandName, Maldives" else if (islandName != null) "$q, $islandName, Maldives" else "$q, Maldives"
                        g.google.searchText(gq, center?.first, center?.second, if (wide) 20000 else 5000)
                    } else {
                        g.osm.search(q, center?.first, center?.second, islandName, wide)
                    }
                }
            }
            busyText = null
            result.onSuccess { list ->
                val ref = f?.let { it.lat to it.lon } ?: center
                if (ref != null) list.forEach { it.distance = Geo.distance(ref.first, ref.second, it.lat, it.lon); it.bearing = Geo.bearing(ref.first, ref.second, it.lat, it.lon) }
                st.results = list.sortedBy { it.distance ?: 0.0 }
                st.selected.clear()
                val msg = if (list.isEmpty()) "No places found. Try another name or category." else "${list.size} places found."
                g.speaker.speak(msg, force = true)
                if (list.isEmpty()) host.rebuild() else host.show { OnlineResultsScreen(mode) }
            }.onFailure { e ->
                val msg = if (provider == "Google Maps") g.google.describeError(e) else Http.friendly(e, "OpenStreetMap")
                g.speaker.speak(msg, force = true)
                host.say(msg)
                host.rebuild()
            }
        }
    }

    /** Called from the results screen to search a wider area. */
    fun loadMore() = doSearch(OnlineSearchState.query, wide = true)

    override fun onDispose() {
        if (busyText != null) { job?.cancel(); busyText = null }
    }
}

fun placeLine(p: OnlinePlace, index: Int): String {
    val parts = arrayListOf("$index. ${p.name}")
    p.distance?.let { parts.add(Fmt.meters(it)) }
    p.bearing?.let { parts.add(Geo.directionName(it)) }
    if (p.typeText.isNotBlank()) parts.add(p.typeText)
    return parts.joinToString(", ")
}

fun onlineToSaved(p: OnlinePlace): SavedLocation = SavedLocation(
    SavedLocation.newId(), p.name, p.lat, p.lon, 0.0,
    source = if (p.source == "google") SavedLocation.SOURCE_GOOGLE else SavedLocation.SOURCE_OSM_SEARCH,
    categoryId = Categories.guess(p.name + " " + p.typeText),
    address = p.address
)

class OnlineResultsScreen(private val mode: OnlineSearchScreen.Mode) : Screen() {
    override fun create(): View {
        val g = AppGraph
        val st = OnlineSearchState
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("${st.results.size} results for ${st.query}")
        if (mode == OnlineSearchScreen.Mode.MANAGE) {
            ui.info("Tap a place to select or unselect it. Long press, or use the TalkBack action Open options, for navigation and single save.")
            ui.row(
                (if (st.selected.size == st.results.size && st.results.isNotEmpty()) "Unselect all" else "Select all") to {
                    if (st.selected.size == st.results.size) st.selected.clear() else { st.selected.clear(); st.results.forEach { st.selected.add(it.key) } }
                    host.say("${st.selected.size} selected"); host.rebuild()
                },
                "Save selected: ${st.selected.size}" to { saveSelected() }
            )
        } else {
            ui.info("Tap a place for options.")
        }
        st.results.forEachIndexed { i, p ->
            val sel = st.selected.contains(p.key)
            val text = placeLine(p, i + 1) + if (mode == OnlineSearchScreen.Mode.MANAGE) (if (sel) ", selected" else ", not selected") else ""
            if (mode == OnlineSearchScreen.Mode.MANAGE) {
                ui.button(text, color = if (sel) UiColors.BUTTON_ON else UiColors.BUTTON, stroke = if (sel) UiColors.STROKE_ON else null,
                    onLong = { host.show { OnlineResultDetailsScreen(i, mode) } }, longLabel = "Open options") {
                    if (!st.selected.add(p.key)) st.selected.remove(p.key)
                    host.say(if (st.selected.contains(p.key)) "Selected" else "Unselected")
                    host.rebuild()
                }
            } else {
                ui.button(text) { host.show { OnlineResultDetailsScreen(i, mode) } }
            }
        }
        if (!st.wide) ui.button("Load more: search a wider area") {
            host.back()
            (host.currentScreen() as? OnlineSearchScreen)?.loadMore()
        }
        if (mode == OnlineSearchScreen.Mode.MANAGE && st.selected.isNotEmpty()) ui.button("Save selected: ${st.selected.size}") { saveSelected() }
        ui.button("Back to search") { host.back() }
        return scroll
    }

    private fun saveSelected() {
        val g = AppGraph
        val st = OnlineSearchState
        if (st.selected.isEmpty()) { g.speaker.speak("Select places first.", force = true); return }
        var added = 0; var skipped = 0
        st.results.filter { st.selected.contains(it.key) }.forEach { if (g.store.add(onlineToSaved(it), persist = false)) added++ else skipped++ }
        g.store.save()
        st.selected.clear()
        val msg = "$added places saved." + if (skipped > 0) " $skipped already saved places skipped." else ""
        g.speaker.speak(msg, force = true)
        host.rebuild()
    }
}

class OnlineResultDetailsScreen(private val index: Int, private val mode: OnlineSearchScreen.Mode) : Screen() {
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        val p = OnlineSearchState.results.getOrNull(index)
        if (p == null) { titleView = ui.title("Result not found"); ui.button("Back") { host.back() }; return scroll }
        titleView = ui.title(p.name)
        if (p.address.isNotBlank()) ui.info(p.address)
        if (p.typeText.isNotBlank()) ui.info("Type: ${p.typeText}.")
        p.distance?.let { d -> ui.info("Distance: ${Fmt.metersWithMiles(d)}. Direction: ${p.bearing?.let { Fmt.direction(it) } ?: "unknown"}.") }
        ui.info("Source: " + if (p.source == "google") "Google Maps." else "OpenStreetMap.")
        ui.button("Start navigation without saving", color = UiColors.BUTTON_ON) {
            g.nav.startPlace(onlineToSaved(p)); host.showTabs("Places")
        }
        if (mode == OnlineSearchScreen.Mode.DESTINATION) {
            ui.button("Choose as destination") { g.nav.placeDestination = onlineToSaved(p); host.say("Destination: ${p.name}"); host.showTabs("Places") }
        }
        ui.button("Save this place") {
            val ok = g.store.add(onlineToSaved(p))
            g.speaker.speak(if (ok) "${p.name} saved." else "${p.name} is already saved.", force = true)
        }
        ui.button("Save and start navigation") {
            val loc = onlineToSaved(p)
            val existing = g.store.findDuplicate(loc.name, loc.lat, loc.lon)
            if (existing == null) g.store.add(loc)
            g.nav.startPlace(existing ?: loc)
            host.showTabs("Places")
        }
        ui.button("Back to results") { host.back() }
        return scroll
    }
}
