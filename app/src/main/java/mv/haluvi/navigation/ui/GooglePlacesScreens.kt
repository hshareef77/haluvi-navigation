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
}

/**
 * Finds all Google places on an island by covering its outline with squares and asking Google for the
 * places inside each square's circle (every kind of place, strictly inside the circle). Google answers at
 * most 20 places per request, so a full answer means there may be more: that square is split into 4
 * smaller ones and searched again, until every part of the island has fewer than 20 places.
 */
object GoogleIslandSearch {
    private const val START_SIDE = 500.0   // metres
    /** Crowded spots are searched down to 20 m squares. */
    private const val MIN_SIDE = 20.0
    const val MAX_REQUESTS_PER_ISLAND = 400

    data class Square(val lat: Double, val lon: Double, val side: Double) {
        val radius: Double get() = side * 0.7072 + 5
        fun split(): List<Square> {
            val q = side / 4
            val dLat = q / 111320.0
            val dLon = q / (111320.0 * Math.cos(Math.toRadians(lat)))
            return listOf(Square(lat + dLat, lon - dLon, side / 2), Square(lat + dLat, lon + dLon, side / 2),
                Square(lat - dLat, lon - dLon, side / 2), Square(lat - dLat, lon + dLon, side / 2))
        }
    }

    /** The square reaches the island, and (for joined islands) is not clearly on the other island's side. */
    private fun touches(isl: Island, sq: Square): Boolean = AppGraph.shapes.shoreDistance(isl, sq.lat, sq.lon) <= sq.radius &&
        mv.haluvi.navigation.nav.JoinedIslands.mightBelong(isl, sq.lat, sq.lon, sq.radius)

    /** The starting squares (500 m) that touch the island. */
    fun startSquares(isl: Island): List<Square> {
        val ring = AppGraph.shapes.ring(isl.key)
        val cos = Math.cos(Math.toRadians(isl.lat))
        val minLat: Double; val maxLat: Double; val minLon: Double; val maxLon: Double
        if (ring != null && ring.size >= 3) {
            minLat = ring.minOf { it.lat }; maxLat = ring.maxOf { it.lat }; minLon = ring.minOf { it.lon }; maxLon = ring.maxOf { it.lon }
        } else {
            val dl = 800 / 111320.0; val dn = 800 / (111320.0 * cos)
            minLat = isl.lat - dl; maxLat = isl.lat + dl; minLon = isl.lon - dn; maxLon = isl.lon + dn
        }
        val h = (maxLat - minLat) * 111320.0
        val w = (maxLon - minLon) * 111320.0 * cos
        val ny = Math.max(1, Math.ceil(h / START_SIDE).toInt())
        val nx = Math.max(1, Math.ceil(w / START_SIDE).toInt())
        val cLat = (minLat + maxLat) / 2; val cLon = (minLon + maxLon) / 2
        val out = ArrayList<Square>()
        for (iy in 0 until ny) for (ix in 0 until nx) {
            val lat = cLat + ((iy - (ny - 1) / 2.0) * START_SIDE) / 111320.0
            val lon = cLon + ((ix - (nx - 1) / 2.0) * START_SIDE) / (111320.0 * cos)
            val sq = Square(lat, lon, START_SIDE)
            if (touches(isl, sq)) out.add(sq)
        }
        if (out.isEmpty()) out.add(Square(isl.lat, isl.lon, START_SIDE))
        return out
    }

    class Result(val found: List<GoogleFind>, val alreadySaved: Int, val requests: Int, val failed: Int, val limitReached: Boolean,
                 /** Squares still full (20 places) at the smallest size: some places there may be missing. */
                 val crowded: Int) {
        val complete: Boolean get() = failed == 0 && !limitReached && crowded == 0
    }

    /** Searches one island. [onStep] gets the number of requests made so far. Runs network calls on IO. */
    suspend fun search(isl: Island, onStep: (requests: Int, found: Int, saved: Int) -> Unit): Result {
        val g = AppGraph
        val queue = ArrayDeque(startSquares(isl))
        val joined = mv.haluvi.navigation.nav.JoinedIslands.groupOf(isl)
        val found = LinkedHashMap<String, GoogleFind>()
        val saved = HashSet<String>()
        var requests = 0; var failed = 0; var limit = false; var crowded = 0
        val tries = HashMap<Square, Int>()
        while (queue.isNotEmpty()) {
            if (requests >= MAX_REQUESTS_PER_ISLAND) { limit = true; break }
            val sq = queue.removeFirst()
            requests++
            onStep(requests, found.size, saved.size)
            val r = withContext(Dispatchers.IO) { runCatching { g.google.placesInCircle(sq.lat, sq.lon, sq.radius) } }
            val list = r.getOrNull()
            if (list == null) {
                // Network or server problem: try this square again later, up to 2 more times.
                val t = (tries[sq] ?: 0) + 1; tries[sq] = t
                if (t <= 2) { kotlinx.coroutines.delay(1500L * t); queue.addLast(sq) } else failed++
                continue
            }
            if (list.size >= 20) {
                if (sq.side / 2 >= MIN_SIDE) sq.split().filter { touches(isl, it) }.forEach { queue.addLast(it) } else crowded++
            }
            for (p in list) {
                // Keep only places on this island (inside its outline, or at its shore such as jetties).
                if (g.shapes.shoreDistance(isl, p.lat, p.lon) > 100) continue
                // Joined islands: only places of this island (by address, else the dividing line).
                if (joined != null && (mv.haluvi.navigation.nav.JoinedIslands.fromAddress(p.address, joined)
                        ?: mv.haluvi.navigation.nav.JoinedIslands.byLine(joined, p.lat, p.lon)).key != isl.key) continue
                val f = GoogleFind(p, Categories.guess(p.typeText + " " + p.name), isl)
                if (g.store.findDuplicate(p.name, p.lat, p.lon) != null) { saved.add(f.key); continue }
                found.putIfAbsent(f.key, f)
            }
        }
        return Result(found.values.sortedBy { it.place.name.lowercase() }, saved.size, requests, failed, limit, crowded)
    }
}

/**
 * Get Google places for my island, a chosen island or an atoll. Google's terms allow saving places the
 * user chooses (with the place ID; positions refreshed regularly), but not storing Google data in bulk,
 * so the user reviews the list and saves what they want (Select all is offered at the top).
 */
class GooglePlacesScreen(private val preset: Pair<List<Island>, String>? = null) : Screen() {
    private var progressView: TextView? = null
    private var presetAsked = false

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
        if (preset != null && !presetAsked) { presetAsked = true; scroll.post { confirm(preset.first, preset.second) } }
        ui.info("Google places need your API key and may cost money. Finds all places Google Maps has on an island, of every kind. The island is searched part by part; busy parts are searched more closely. You choose which places to save. Saved places work offline for routing, Explore and What's around me.")
        if (GooglePick.lastMessage.isNotBlank()) ui.info(GooglePick.lastMessage)
        if (GooglePick.results.isNotEmpty()) ui.button("Show last results: ${GooglePick.results.size}") { host.show { GooglePickResultsScreen() } }
        val mine = OfflineMaps.myIsland()
        if (mine != null) ui.button("My island: ${mine.name}", color = UiColors.BUTTON_ON) { confirm(listOf(mine), mine.name) }
        else ui.info("My island: waiting for GPS.")
        ui.button("Choose island") {
            host.show { AtollPickerScreen { isl -> host.backTo(this@GooglePlacesScreen); if (isl.atollIndex >= 0) confirm(listOf(isl), isl.name) else host.speak("Choose an inhabited island.") } }
        }
        ui.button("Choose atoll") {
            host.show {
                ChoiceScreen("Choose atoll", null, g.islands.atolls.map { a ->
                    "${atollGoogleText(a.islands)}, ${GoogleSearchedDates.text(a.islands)}".let { "${a.fullName}, ${islandsText(a.islands.size)}, $it" } to { confirm(a.islands, a.fullName) }
                })
            }
        }
        ui.button("Back") { host.back() }
        return scroll
    }

    /** "2,732 offline places (2,732 Google)" for these islands. */
    private fun atollGoogleText(islands: List<Island>): String {
        val keys = islands.map { it.key }.toSet()
        val items = AppGraph.store.items.filter { it.islandKey in keys }
        val google = items.count { it.source == mv.haluvi.navigation.data.SavedLocation.SOURCE_GOOGLE }
        return offlinePlacesText(items.size) + if (items.isNotEmpty()) " ($google Google)" else ""
    }

    /** Shows what is already saved, when it was last searched and how many Google searches are needed, then starts. */
    private fun confirm(islands: List<Island>, label: String) {
        val n = islands.sumOf { GoogleIslandSearch.startSquares(it).size }
        val what = if (islands.size > 1) "$label (${islandsText(islands.size)})" else label
        val text = "Search $what on Google.\n" +
            "Already saved: ${atollGoogleText(islands)}. ${GoogleSearchedDates.text(islands).replaceFirstChar { it.uppercase() }}.\n" +
            "Estimated: about $n to ${n * 3} Google searches; crowded areas need more. Your free amount is about 5,000 searches a month; after that they may cost money.\n" +
            "Places already saved are not added again."
        host.show {
            ConfirmScreen(text, "Search", cancelFirst = true) {
                host.backTo(this@GooglePlacesScreen); start(islands, label)
            }
        }
    }

    private fun start(islands: List<Island>, label: String) {
        GooglePick.results = emptyList(); GooglePick.selected.clear()
        GooglePick.progress = "Searching Google for $label."
        host.speak("Searching Google places for $label.")
        GooglePick.job = host.scope.launch {
            val all = LinkedHashMap<String, GoogleFind>()
            var saved = 0; var requests = 0; var failed = 0; var limited = 0; var crowded = 0
            val incomplete = ArrayList<String>()
            val msg = try {
                for ((ii, isl) in islands.withIndex()) {
                    val r = GoogleIslandSearch.search(isl) { n, f, sv ->
                        val step = "${if (islands.size > 1) "Island ${ii + 1} of ${islands.size}: " else ""}${isl.name}, search $n. $f new, $sv already saved. ${all.size + f} new places so far."
                        GooglePick.progress = step
                        progressView?.takeIf { host.currentScreen() === this@GooglePlacesScreen }?.text = step
                    }
                    r.found.forEach { all.putIfAbsent(it.key, it) }
                    saved += r.alreadySaved; requests += r.requests; failed += r.failed; crowded += r.crowded
                    if (r.limitReached) limited++
                    if (!r.complete) incomplete.add(isl.name)
                    GoogleSearchedDates.set(isl.key, System.currentTimeMillis())
                }
                GooglePick.results = all.values.toList()
                buildString {
                    append("${all.size} new Google places found for $label, with $requests searches.")
                    if (saved > 0) append(" $saved more are already in My places.")
                    if (failed > 0) append(" $failed searches failed; search again later to fill gaps.")
                    if (limited > 0) append(" Search limit reached on $limited islands; some places may be missing.")
                    if (crowded > 0) append(" $crowded very crowded spots still had 20 places at the smallest size; a few places there may be missing.")
                    append(if (incomplete.isEmpty()) " Search complete." else " May be incomplete: ${incomplete.joinToString(", ")}.")
                    if (all.isNotEmpty()) append(" Select the ones to save.")
                }
            } catch (e: CancellationException) {
                GooglePick.results = all.values.toList()
                "Stopped. ${all.size} places found so far."
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
