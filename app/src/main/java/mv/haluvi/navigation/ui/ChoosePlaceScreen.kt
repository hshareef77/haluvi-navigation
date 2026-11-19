package mv.haluvi.navigation.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.data.Categories
import mv.haluvi.navigation.data.Island
import mv.haluvi.navigation.data.SavedLocation
import mv.haluvi.navigation.nav.Phrases
import mv.haluvi.navigation.net.Http
import mv.haluvi.navigation.net.OnlinePlace

/**
 * Choose a place (for To or From): where to look (near me, an island, a category), what to search in
 * (places on the phone, offline; OpenStreetMap online; Google online, only with a key), and a search box.
 * On the phone the list follows what you type; online searches start with the Search button.
 * Tap a place to choose it; the screen reader's actions (or a long press) open its options.
 */
class ChoosePlaceScreen(
    private val title: String,
    private val firstChoice: Pair<String, () -> Unit>? = null,
    private val onPick: (SavedLocation) -> Unit
) : Screen() {
    private enum class Source { PHONE, OSM, GOOGLE }

    private var island: Island? = null
    private var category: String? = null
    private var source = Source.PHONE
    private var query = ""
    private var listBox: LinearLayout? = null
    private var status: TextView? = null
    private var online: List<OnlinePlace> = emptyList()
    private var job: Job? = null
    private var searching = false
    private var limit = 40

    private fun whereText(): String = when {
        island != null -> "island ${island!!.name}"
        category != null -> "category ${Categories.name(category)}"
        else -> "near me"
    }

    private fun sourceText(): String = when (source) {
        Source.PHONE -> "on my phone, offline"
        Source.OSM -> "OpenStreetMap, online, free"
        Source.GOOGLE -> "Google, online, your API key"
    }

    override fun create(): View {
        val g = AppGraph
        if (source == Source.GOOGLE && !g.settings.googleReady) source = Source.PHONE
        PlacePick.handler = null
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title(title)
        firstChoice?.let { (label, action) -> ui.button(label, color = UiColors.BUTTON_ON) { action() } }
        ui.button("Where: ${whereText()}. Change") {
            host.show {
                ChoiceScreen("Where", null, listOf(
                    "Near me" to { island = null; category = null; host.backTo(this@ChoosePlaceScreen) },
                    "By island" to { host.show { AtollPickerScreen { isl -> island = isl; category = null; host.backTo(this@ChoosePlaceScreen) } } },
                    "By category" to { host.show { ChoiceScreen("By category", null, Categories.all.filter { it.id != "none" }.map { c ->
                        c.name to { category = c.id; island = null; host.backTo(this@ChoosePlaceScreen) }
                    }) } }
                ))
            }
        }
        ui.button("Search in: ${sourceText()}. Change") {
            host.show {
                ChoiceScreen("Search in", null, listOfNotNull(
                    "On my phone, offline" to { source = Source.PHONE; online = emptyList(); host.backTo(this@ChoosePlaceScreen) },
                    "OpenStreetMap, online, free" to { source = Source.OSM; online = emptyList(); host.backTo(this@ChoosePlaceScreen) },
                    if (g.settings.googleReady) "Google, online, your API key" to { source = Source.GOOGLE; online = emptyList(); host.backTo(this@ChoosePlaceScreen) } else null
                ))
            }
        }
        val edit = ui.edit(if (source == Source.PHONE) "Type a name to filter" else "Place name to search", query) { query = it.trim(); go() }
        if (source == Source.PHONE) {
            // The list follows what you type (offline, instant).
            edit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim().orEmpty(); limit = 40; fill() }
            })
        }
        ui.row(
            (if (source == Source.PHONE) "Find" else "Search") to { query = edit.text.toString().trim(); go() },
            "What's around me" to { PlacePick.handler = onPick; host.show { AroundMeScreen() } }
        )
        status = ui.info("")
        listBox = UiBuilder.vertical(host, 0)
        ui.root.addView(listBox)
        fill()
        ui.button("Back") { host.back() }
        return scroll
    }

    private fun go() {
        if (source == Source.PHONE) { limit = 40; fill(); host.say(status?.text?.toString().orEmpty()) } else searchOnline()
    }

    private fun place(loc: SavedLocation, d: Double?, b: Double?): String {
        val parts = arrayListOf(loc.name)
        if (loc.categoryId != "none") parts.add(Categories.name(loc.categoryId))
        if (island == null && loc.islandName.isNotBlank() && d != null && d > 3000) parts.add(loc.islandName)
        if (d != null && b != null) parts.add(Phrases.where(AppGraph.compass.heading, b).removePrefix("at ") + ", " + Phrases.distance(d))
        return parts.joinToString(", ")
    }

    private fun fill() {
        val box = listBox ?: return
        box.removeAllViews()
        val ui = UiBuilder(host, box)
        val g = AppGraph
        if (source != Source.PHONE) {
            status?.text = when {
                searching -> "Searching for $query."
                online.isEmpty() -> "Type a name and press Search."
                else -> "${online.size} places found."
            }
            online.forEach { p ->
                val text = listOfNotNull(p.name, p.typeText.ifBlank { null }, if (p.distance != null && p.bearing != null)
                    Phrases.where(g.compass.heading, p.bearing!!).removePrefix("at ") + ", " + Phrases.distance(p.distance!!) else null).joinToString(", ")
                ui.button(text) {
                    onPick(SavedLocation(SavedLocation.newId(), p.name, p.lat, p.lon,
                        source = if (p.source == "google") SavedLocation.SOURCE_GOOGLE else SavedLocation.SOURCE_OSM_SEARCH,
                        categoryId = Categories.guess(p.name + " " + p.typeText), address = p.address, placeId = p.placeId))
                }
            }
            return
        }
        val f = g.location.fix
        val cat = category ?: "all"
        val rows: List<Triple<SavedLocation, Double?, Double?>> = if (f != null)
            g.store.nearest(f.lat, f.lon, query, cat, island?.key).map { Triple(it.loc, it.distance, it.bearing) }
        else g.store.filtered(query, cat).filter { island == null || it.islandKey == island!!.key }.map { Triple(it, null, null) }
        status?.text = (if (rows.isEmpty()) "No places found." else "${rows.size} places, nearest first.") +
            (if (f == null) " Waiting for GPS." else "") +
            (if (rows.isEmpty() && g.store.items.isEmpty()) " Install the built-in map in Settings, Manage islands and places." else "")
        rows.take(limit).forEach { (loc, d, b) ->
            ui.button(place(loc, d, b), onLong = { host.show { PlaceScreen(loc.id) } }, longLabel = "Place options") { onPick(loc) }
        }
        if (rows.size > limit) ui.button("More places") { limit += 40; fill() }
    }

    private fun searchOnline() {
        val q = query
        if (q.isEmpty()) { host.speak("Please type a place name."); return }
        val g = AppGraph
        val f = g.location.fix
        val chosen = island
        val cLat = chosen?.lat ?: f?.lat
        val cLon = chosen?.lon ?: f?.lon
        val islandName = chosen?.name ?: f?.let { g.islands.nearest(it.lat, it.lon)?.island?.name }
        searching = true; online = emptyList(); fill()
        host.speak("Searching.")
        val useGoogle = source == Source.GOOGLE
        job?.cancel()
        job = host.scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (useGoogle) g.google.searchText(if (islandName != null) "$q, $islandName, Maldives" else "$q, Maldives", cLat, cLon, if (chosen != null) 3000 else 5000)
                    else g.osm.search(q, cLat, cLon, islandName, wide = false)
                }
            }
            searching = false
            val list = r.getOrElse { emptyList() }
            if (f != null) list.forEach { it.distance = Geo.distance(f.lat, f.lon, it.lat, it.lon); it.bearing = Geo.bearing(f.lat, f.lon, it.lat, it.lon) }
            online = list.sortedBy { it.distance ?: 0.0 }.take(30)
            val msg = when {
                online.isNotEmpty() -> "${online.size} places found."
                r.isFailure -> Http.friendly(r.exceptionOrNull()!!, "Search")
                else -> "Nothing found."
            }
            host.speak(msg)
            if (host.currentScreen() === this@ChoosePlaceScreen) { fill(); status?.text = msg }
        }
    }

    override fun onDispose() { if (searching) { job?.cancel(); searching = false } }
}
