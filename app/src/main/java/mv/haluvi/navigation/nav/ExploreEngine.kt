package mv.haluvi.navigation.nav

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mv.haluvi.navigation.core.Fmt
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.data.IslandRepository
import mv.haluvi.navigation.data.LocationStore
import mv.haluvi.navigation.data.SavedLocation
import mv.haluvi.navigation.data.Settings
import mv.haluvi.navigation.data.TravelMode
import mv.haluvi.navigation.feedback.Speaker
import mv.haluvi.navigation.net.GoogleMaps
import mv.haluvi.navigation.net.OnlinePlace
import mv.haluvi.navigation.sensors.CompassTracker
import mv.haluvi.navigation.sensors.Fix
import mv.haluvi.navigation.sensors.LocationTracker
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

data class ExplorePlace(
    val name: String,
    val lat: Double,
    val lon: Double,
    val categoryText: String,
    val source: String,
    val saved: SavedLocation?,
    var distance: Double = 0.0,
    var bearing: Double = 0.0,
    var side: String = ""
) {
    val key: String get() = name.lowercase() + "@" + String.format(Locale.US, "%.5f,%.5f", lat, lon)
}

/**
 * Explore mode: tells you what is around you while you move, without a destination.
 * Speech priority (Lua v130): navigation maneuver > road change > junction > nearby place.
 */
class ExploreEngine(
    private val context: Context,
    private val settings: Settings,
    private val islands: IslandRepository,
    private val store: LocationStore,
    private val location: LocationTracker,
    private val compass: CompassTracker,
    private val speaker: Speaker,
    private val google: GoogleMaps,
    private val nav: NavigationEngine
) {
    companion object {
        val CATEGORIES = listOf(
            "All" to "All places",
            "Shops" to "Shops and markets",
            "Mosque" to "Mosques",
            "Health" to "Hospital, health centre, clinic, pharmacy",
            "Food" to "Restaurants and cafes",
            "Transport" to "Ferry, harbor, jetty, transport",
            "Saved" to "Your saved locations"
        )
        val RADII = listOf(100, 250, 500, 1000, 2000, 5000)
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    var active = false; private set
    var results: List<ExplorePlace> = emptyList(); private set
    var roadStatus = "Current road: checking."; private set
    var message = "Exploration is stopped."; private set

    private var googleResults: List<ExplorePlace> = emptyList()
    private var googleBusy = false
    private var googleLastMs = 0L
    private var googleLat: Double? = null
    private var googleLon: Double? = null

    private var roadBusy = false
    private var roadLastMs = 0L
    private var roadLat: Double? = null
    private var roadLon: Double? = null
    private var confirmedRoad = ""
    private var candidateRoad = ""
    private var candidateCount = 0

    private var speechLastMs = 0L
    private var speechLastPriority = 0
    private val placeSeen = HashMap<String, Pair<Boolean, Long>>()
    private var lastJunctionKey = ""
    private var lastJunctionMs = 0L
    private var junctionCacheKey = ""
    private var junctions: List<Junction> = emptyList()

    private data class Junction(val key: String, val lat: Double, val lon: Double, val names: List<String>)

    private val now: Long get() = SystemClock.elapsedRealtime()

    private val loop = object : Runnable {
        override fun run() {
            if (!active) return
            refresh(false)
            main.postDelayed(this, 3000)
        }
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() { listeners.forEach { runCatching { it() } } }

    val categoryLabel: String get() = settings.exploreCategory
    val radius: Int get() = settings.exploreRadius

    fun start() {
        if (active) return
        active = true
        placeSeen.clear(); speechLastMs = 0; speechLastPriority = 0
        confirmedRoad = ""; candidateRoad = ""; candidateCount = 0
        googleLastMs = 0; roadLastMs = 0; lastJunctionKey = ""
        roadStatus = "Current road: checking."
        message = "Checking nearby places."
        NavigationService.ensureRunning(context)
        speaker.speak("Exploration started.")
        main.removeCallbacks(loop)
        main.post(loop)
        notifyChanged()
    }

    fun stop(silent: Boolean = false) {
        if (!active) return
        active = false
        main.removeCallbacks(loop)
        results = emptyList(); googleResults = emptyList()
        message = "Exploration is stopped."
        if (!silent) speaker.speak("Exploration stopped.")
        NavigationService.updateOrStop(context)
        notifyChanged()
    }

    fun toggle() { if (active) stop() else start() }

    fun setCategory(c: String) { settings.exploreCategory = c; googleLastMs = 0; googleResults = emptyList(); if (active) refresh(true) }
    fun setRadius(r: Int) { settings.exploreRadius = r; googleLastMs = 0; if (active) refresh(true) }

    // ------------------------------------------------------------------

    private fun travelHeading(f: Fix): Double = if (f.bearing != null && (f.speedMps ?: 0.0) >= 0.45) f.bearing else compass.heading

    private fun categoryMatches(text: String, source: String, category: String): Boolean {
        val t = text.lowercase()
        fun has(vararg w: String) = w.any { t.contains(it) }
        return when (category) {
            "All" -> true
            "Saved" -> source == SavedLocation.SOURCE_MANUAL
            "Shops" -> has("shop", "market", "store", "supermarket", "grocery")
            "Mosque" -> has("mosque", "masjid")
            "Health" -> has("hospital", "health", "clinic", "pharmacy", "doctor")
            "Food" -> has("restaurant", "cafe", "coffee", "food", "bakery")
            "Transport" -> has("ferry", "harbor", "harbour", "jetty", "bus", "terminal", "pier")
            else -> true
        }
    }

    private fun googleQuery(): String? = when (settings.exploreCategory) {
        "Saved" -> null
        "Shops" -> "shops supermarkets grocery stores markets"
        "Mosque" -> "mosques masjids"
        "Health" -> "hospitals health centres clinics pharmacies"
        "Food" -> "restaurants cafes"
        "Transport" -> "ferry terminals harbors jetties bus stops"
        else -> "nearby places businesses landmarks services"
    }

    fun refresh(force: Boolean) {
        if (!active) return
        val f = location.freshFix(30_000)
        if (f == null) {
            message = "Waiting for GPS. Please stay in an open area."
            notifyChanged()
            return
        }
        collect(f)
        fetchGoogle(f, force)
        checkRoad(f, force)
        speakNearestPlace(f)
        checkJunction(f)
        notifyChanged()
    }

    private fun collect(f: Fix) {
        val radius = settings.exploreRadius.toDouble()
        val cat = settings.exploreCategory
        val heading = travelHeading(f)
        val out = LinkedHashMap<String, ExplorePlace>()
        fun add(p: ExplorePlace) {
            p.distance = Geo.distance(f.lat, f.lon, p.lat, p.lon)
            if (p.distance > radius) return
            if (!categoryMatches(p.name + " " + p.categoryText + " " + (p.saved?.categoryId ?: ""), p.source, cat)) return
            p.bearing = Geo.bearing(f.lat, f.lon, p.lat, p.lon)
            p.side = Geo.relativeSide(heading, p.bearing)
            out.putIfAbsent(p.key, p)
        }
        store.items.forEach { add(ExplorePlace(it.name, it.lat, it.lon, it.categoryName, it.source, it)) }
        googleResults.forEach { add(it.copy()) }
        results = out.values.sortedBy { it.distance }
        message = if (results.isEmpty()) "No places found within ${Fmt.meters(radius)}." else "${results.size} places nearby."
    }

    private fun fetchGoogle(f: Fix, force: Boolean) {
        if (googleBusy || !settings.googleReady) return
        val q = googleQuery() ?: run { googleResults = emptyList(); return }
        val moved = if (googleLat == null) Double.MAX_VALUE else Geo.distance(googleLat!!, googleLon!!, f.lat, f.lon)
        val fast = (f.speedMps ?: 0.0) >= 3.3
        if (!force && googleLastMs > 0 && now - googleLastMs < (if (fast) 45_000 else 90_000) && moved < (if (fast) 100 else 45)) return
        googleBusy = true
        googleLastMs = now; googleLat = f.lat; googleLon = f.lon
        val island = islands.nearest(f.lat, f.lon)?.island?.name
        val query = if (island != null) "$q in $island, Maldives" else q
        val radius = settings.exploreRadius.coerceIn(100, 5000)
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { google.searchText(query, f.lat, f.lon, radius) } }
            googleBusy = false
            r.onSuccess { list: List<OnlinePlace> ->
                googleResults = list.map { ExplorePlace(it.name, it.lat, it.lon, it.typeText, "google", null) }
                if (active) location.fix?.let { collect(it); notifyChanged() }
            }
        }
    }

    private fun checkRoad(f: Fix, force: Boolean) {
        // Prefer the road from the active Google route (Lua v128).
        val routeRoad = nav.currentRouteRoadName()
        if (routeRoad.isNotBlank()) { acceptRoad(routeRoad, f); return }
        if (roadBusy) return
        if (!settings.googleReady) {
            roadStatus = nearestDownloadedRoad(f)?.let { "Current road: $it." } ?: "Current road: unavailable without Google Maps."
            nearestDownloadedRoad(f)?.let { acceptRoad(it, f) }
            return
        }
        val moved = if (roadLat == null) Double.MAX_VALUE else Geo.distance(roadLat!!, roadLon!!, f.lat, f.lon)
        val speed = f.speedMps ?: 0.0
        val driving = settings.travelMode == TravelMode.DRIVING
        val (interval, need) = when {
            driving || speed >= 3.3 -> 5000L to 10.0
            speed >= 0.5 -> 7000L to 5.0
            else -> 12000L to 7.0
        }
        if (!force && now - roadLastMs < interval && moved < need) return
        roadBusy = true; roadLastMs = now; roadLat = f.lat; roadLon = f.lon
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { google.reverseGeocode(f.lat, f.lon) } }
            roadBusy = false
            if (!active) return@launch
            r.onSuccess { g ->
                when {
                    g.road.isNotBlank() -> acceptRoad(g.road, f)
                    g.address.isNotBlank() -> roadStatus = "Current area: ${g.address}."
                }
            }.onFailure { roadStatus = "Current road: temporarily unavailable." }
            notifyChanged()
        }
    }

    /** Offline fallback: nearest downloaded OpenStreetMap road within 25 m. */
    private fun nearestDownloadedRoad(f: Fix): String? {
        val island = islands.nearest(f.lat, f.lon)?.island ?: return null
        var best: String? = null
        var bestD = 25.0
        for (r in store.loadRoads(island.key)) {
            if (r.name.isBlank()) continue
            for (i in 0 until r.points.size - 1) {
                val a = r.points[i]; val b = r.points[i + 1]
                val d = Geo.distanceToSegment(f.lat, f.lon, a[0], a[1], b[0], b[1])
                if (d < bestD) { bestD = d; best = r.name }
            }
        }
        return best
    }

    private fun acceptRoad(road: String, f: Fix) {
        if (candidateRoad.equals(road, true)) candidateCount++ else { candidateRoad = road; candidateCount = 1 }
        val goodMovingFix = (f.speedMps ?: 0.0) >= 0.7 && f.accuracy <= 35
        val needed = if (confirmedRoad.isEmpty() || goodMovingFix) 1 else 2
        if (candidateCount >= needed) {
            val old = confirmedRoad
            confirmedRoad = road
            roadStatus = "Current road: $road."
            if (old.isEmpty()) say(70, "You are on $road.")
            else if (!old.equals(road, true)) say(70, "You are now on $road.")
        } else {
            roadStatus = "Current road: confirming $road."
        }
    }

    private fun say(priority: Int, text: String, force: Boolean = false): Boolean {
        if (!active || text.isBlank()) return false
        if (!force && nav.imminentManeuver()) return false
        val gap = now - speechLastMs
        if (!force) {
            if (gap < 3500 && priority <= speechLastPriority) return false
            if (gap < 1800) return false
        }
        speechLastMs = now; speechLastPriority = priority
        speaker.speak(text)
        return true
    }

    private fun announceThreshold(f: Fix): Double {
        val speed = f.speedMps ?: 0.0
        val auto = when { speed >= 8 -> 350.0; speed >= 3.3 -> 220.0; speed >= 1.3 -> 130.0; else -> 80.0 }
        return minOf(settings.exploreRadius.toDouble(), auto)
    }

    private fun speakNearestPlace(f: Fix) {
        val heading = travelHeading(f)
        var best: ExplorePlace? = null
        var bestScore = Double.MAX_VALUE
        for (p in results) {
            if (p.side == "behind") continue
            val score = p.distance + (if (p.side == "ahead") 0.0 else 35.0) + Geo.angleBetween(heading, p.bearing) * 0.8
            if (score < bestScore) { bestScore = score; best = p }
        }
        val n = now
        placeSeen.entries.removeAll { n - it.value.second > 120_000 }
        val p = best ?: return
        if (p.distance > announceThreshold(f)) return
        val state = placeSeen[p.key]
        placeSeen[p.key] = (state?.first ?: false) to n
        if (state?.first == true) return
        if (say(40, "${p.name} ${p.side}, ${Fmt.meters(p.distance)}.")) placeSeen[p.key] = true to n
    }

    private fun buildJunctions(islandKey: String): List<Junction> {
        if (junctionCacheKey == islandKey) return junctions
        val nodes = HashMap<String, Pair<DoubleArray, MutableSet<Int>>>()
        val names = HashMap<String, MutableSet<String>>()
        store.loadRoads(islandKey).forEachIndexed { ri, road ->
            road.points.forEach { p ->
                val key = String.format(Locale.US, "%.5f,%.5f", p[0], p[1])
                val node = nodes.getOrPut(key) { p to HashSet() }
                node.second.add(ri)
                if (road.name.isNotBlank()) names.getOrPut(key) { HashSet() }.add(road.name)
            }
        }
        junctions = nodes.filter { it.value.second.size >= 2 }.map { (k, v) ->
            Junction(k, v.first[0], v.first[1], names[k]?.sorted().orEmpty())
        }
        junctionCacheKey = islandKey
        return junctions
    }

    private fun checkJunction(f: Fix) {
        if (nav.imminentManeuver()) return
        val island = islands.nearest(f.lat, f.lon)?.island ?: return
        val list = buildJunctions(island.key)
        if (list.isEmpty()) return
        val driving = settings.travelMode == TravelMode.DRIVING || (f.speedMps ?: 0.0) >= 3.3
        val maxD = minOf(settings.exploreRadius.toDouble(), if (driving) 140.0 else 70.0)
        val heading = travelHeading(f)
        var best: Junction? = null
        var bestScore = Double.MAX_VALUE
        var bestDist = 0.0
        for (j in list) {
            val d = Geo.distance(f.lat, f.lon, j.lat, j.lon)
            if (d > maxD || d < 7) continue
            val diff = Geo.angleBetween(heading, Geo.bearing(f.lat, f.lon, j.lat, j.lon))
            if (diff > 65) continue
            val score = d + diff * 0.6
            if (score < bestScore) { bestScore = score; best = j; bestDist = d }
        }
        val j = best ?: return
        if (j.key == lastJunctionKey && now - lastJunctionMs < 120_000) return
        val other = j.names.firstOrNull { !it.equals(confirmedRoad, true) }
        val text = if (other != null) "Intersection with $other ahead, ${Fmt.meters(bestDist)}." else "Intersection ahead, ${Fmt.meters(bestDist)}."
        if (say(60, text)) { lastJunctionKey = j.key; lastJunctionMs = now }
    }

    fun placeLine(p: ExplorePlace, index: Int): String {
        val parts = arrayListOf("$index. ${p.name}", Fmt.meters(p.distance), p.side)
        val cat = p.categoryText.trim()
        if (cat.isNotBlank() && cat != "None") parts.add(cat)
        return parts.joinToString(", ")
    }
}
