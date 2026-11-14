package mv.haluvi.navigation.ui

import android.view.View
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.data.Categories
import mv.haluvi.navigation.data.Island
import mv.haluvi.navigation.data.SavedLocation
import mv.haluvi.navigation.nav.OfflineMaps
import mv.haluvi.navigation.net.OnlinePlace

/** A Google place found for an island, with its category and island. */
data class GoogleFind(val place: OnlinePlace, val categoryId: String, val island: Island) {
    val key: String get() = place.placeId.ifBlank { place.key }
}

/** Results kept while the user selects and saves. */
object GooglePick {
    var results: List<GoogleFind> = emptyList()
    val selected = LinkedHashSet<String>()
    var job: Job? = null
    var progress = ""
    var lastMessage = ""
    val running: Boolean get() = job?.isActive == true

    /** One Google search per category and island. */
    val categories = listOf(
        "mosque" to "mosques", "health" to "hospitals, health centres, clinics and pharmacies", "shop" to "shops and supermarkets",
        "food" to "restaurants and cafes", "ferry" to "ferry terminals, harbours and jetties", "school" to "schools",
        "bank" to "banks and ATMs", "hotel" to "hotels and guest houses", "police" to "police stations and council offices"
    )
}

/**
 * Get Google places for my island, a chosen island or an atoll. Google's terms allow saving places the
 * user chooses (with the place ID; positions refreshed regularly), but not storing Google data in bulk,
 * so the user reviews the list and saves what they want (Select all is offered at the top).
 */
class GooglePlacesScreen : Screen() {
    private var progressView: TextView? = null

    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Get Google places")
        if (!g.settings.googleReady) {
            ui.info("Google Maps is off or the API key is missing.")
            ui.button("Google Maps settings") { host.show { GoogleSettingsScreen() } }
            ui.button("Back") { host.back() }
            return scroll
        }
        if (GooglePick.running) {
            progressView = ui.info(GooglePick.progress)
            ui.button("Cancel", color = UiColors.BUTTON_STOP) {
                GooglePick.job?.cancel(); mv.haluvi.navigation.net.Http.cancelAll()
                GooglePick.progress = "Stopping."; progressView?.text = GooglePick.progress
            }
            ui.button("Back") { host.back() }
            return scroll
        }
        progressView = null
        ui.info("Finds places on Google Maps for an island. You choose which ones to save. Saved places work offline for routing, Explore and What's around me.")
        if (GooglePick.lastMessage.isNotBlank()) ui.info(GooglePick.lastMessage)
        if (GooglePick.results.isNotEmpty()) ui.button("Show last results: ${GooglePick.results.size}") { host.show { GooglePickResultsScreen() } }
        val mine = OfflineMaps.myIsland()
        if (mine != null) ui.button("My island: ${mine.name}", color = UiColors.BUTTON_ON) { start(listOf(mine), mine.name) }
        else ui.info("My island: waiting for GPS.")
        ui.button("Choose island") {
            host.show { AtollPickerScreen { isl -> host.backTo(this@GooglePlacesScreen); if (isl.atollIndex >= 0) start(listOf(isl), isl.name) else host.speak("Choose an inhabited island.") } }
        }
        ui.button("Choose atoll") {
            host.show {
                ChoiceScreen("Choose atoll", null, g.islands.atolls.map { a ->
                    "${a.fullName}, ${a.islands.size} islands" to {
                        val n = a.islands.size * GooglePick.categories.size
                        host.show {
                            ConfirmScreen("Search ${a.fullName}: about $n Google searches. This may use part of your Google free monthly amount or cost money. Continue?", "Search") {
                                host.backTo(this@GooglePlacesScreen); start(a.islands, a.fullName)
                            }
                        }
                    }
                })
            }
        }
        ui.button("Back") { host.back() }
        return scroll
    }

    private fun start(islands: List<Island>, label: String) {
        val g = AppGraph
        GooglePick.results = emptyList(); GooglePick.selected.clear()
        GooglePick.progress = "Searching Google for $label."
        host.speak("Searching Google places for $label.")
        GooglePick.job = host.scope.launch {
            val found = LinkedHashMap<String, GoogleFind>()
            var failed = 0
            val msg = try {
                for ((ii, isl) in islands.withIndex()) {
                    for ((ci, cat) in GooglePick.categories.withIndex()) {
                        val step = "${if (islands.size > 1) "Island ${ii + 1} of ${islands.size}, " else ""}${isl.name}: ${cat.second} (${ci + 1} of ${GooglePick.categories.size}). ${found.size} places so far."
                        GooglePick.progress = step
                        progressView?.takeIf { host.currentScreen() === this@GooglePlacesScreen }?.text = step
                        val radius = (g.shapes.ring(isl.key)?.let { mv.haluvi.navigation.data.IslandShapes.radius(it) * 1.5 } ?: 1500.0).toInt().coerceIn(300, 5000)
                        val r = withContext(Dispatchers.IO) {
                            runCatching { g.google.searchText("${cat.second} in ${isl.name}, ${isl.atollFullName}, Maldives", isl.lat, isl.lon, radius) }
                        }
                        r.onSuccess { list ->
                            for (p in list) {
                                // Keep only places on this island (inside its outline, or at its shore).
                                if (g.shapes.shoreDistance(isl, p.lat, p.lon) > 150) continue
                                if (g.store.findDuplicate(p.name, p.lat, p.lon) != null) continue
                                val f = GoogleFind(p, cat.first.takeIf { it != "police" } ?: Categories.guess(p.name + " " + p.typeText).let { if (it == "other") "police" else it }, isl)
                                found.putIfAbsent(f.key, f)
                            }
                        }.onFailure { failed++ }
                    }
                }
                GooglePick.results = found.values.toList()
                "${found.size} new Google places found for $label." + (if (failed > 0) " $failed searches failed." else "") +
                    if (found.isEmpty()) "" else " Select the ones to save."
            } catch (e: CancellationException) {
                GooglePick.results = found.values.toList()
                "Stopped. ${found.size} places found so far."
            }
            GooglePick.lastMessage = msg
            host.speak(msg)
            if (host.currentScreen() === this@GooglePlacesScreen) {
                if (GooglePick.results.isNotEmpty()) host.show { GooglePickResultsScreen() } else rebuild()
            }
        }
        rebuild()
    }
}

/** Results with Select all at the top, then Save selected. */
class GooglePickResultsScreen : Screen() {
    override fun create(): View {
        val (scroll, ui) = UiBuilder.scrolling(host)
        val res = GooglePick.results
        val sel = GooglePick.selected
        titleView = ui.title("${res.size} Google places")
        ui.row(
            (if (sel.size == res.size && res.isNotEmpty()) "Unselect all" else "Select all") to {
                if (sel.size == res.size) sel.clear() else { sel.clear(); res.forEach { sel.add(it.key) } }
                host.say("${sel.size} selected"); rebuild()
            },
            "Save selected: ${sel.size}" to { save() }
        )
        res.forEach { f ->
            val on = sel.contains(f.key)
            val text = "${f.place.name}, ${Categories.name(f.categoryId)}, ${f.island.name}" + if (on) ", selected" else ""
            ui.button(text, color = if (on) UiColors.BUTTON_ON else UiColors.BUTTON, stroke = if (on) UiColors.STROKE_ON else null) {
                if (!sel.add(f.key)) sel.remove(f.key)
                host.say(if (sel.contains(f.key)) "Selected" else "Unselected")
                rebuild()
            }
        }
        if (res.size > 8) ui.button("Save selected: ${sel.size}") { save() }
        ui.button("Back") { host.back() }
        return scroll
    }

    private fun save() {
        val g = AppGraph
        val sel = GooglePick.selected
        if (sel.isEmpty()) { host.speak("Select places first."); return }
        var added = 0
        GooglePick.results.filter { sel.contains(it.key) }.forEach { f ->
            val loc = SavedLocation(SavedLocation.newId(), f.place.name, f.place.lat, f.place.lon, 0.0,
                source = SavedLocation.SOURCE_GOOGLE, categoryId = f.categoryId, islandKey = f.island.key,
                islandName = f.island.fullName, address = f.place.address, placeId = f.place.placeId)
            if (g.store.add(loc, persist = false)) added++
        }
        g.store.save()
        GooglePick.results = GooglePick.results.filter { !sel.contains(it.key) }
        sel.clear()
        host.speak("$added places saved to My places.")
        rebuild()
    }
}
