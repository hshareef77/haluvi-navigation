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
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.data.Categories
import mv.haluvi.navigation.data.DownloadedRoad
import mv.haluvi.navigation.data.IslandRepository
import mv.haluvi.navigation.data.LocationStore
import mv.haluvi.navigation.data.SavedLocation
import mv.haluvi.navigation.data.Settings
import mv.haluvi.navigation.data.TravelMode
import mv.haluvi.navigation.feedback.Speaker
import mv.haluvi.navigation.net.GoogleMaps
import mv.haluvi.navigation.sensors.Fix
import mv.haluvi.navigation.sensors.LocationTracker
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Explore: speaks only three things, and only when it is sure.
 *  1. Your road ("On Majeedhee Magu.")
 *  2. Intersections on your road ("Intersection with Chandhanee Magu, 30 meters.")
 *  3. Important places as you pass ("Pharmacy, on your right, 20 meters.")
 *
 * Reliability rules:
 *  - Uses only fresh, accurate GPS. When GPS is weak it says once "Explore paused" and stays quiet.
 *  - Left/right comes from your walking direction (GPS movement), never from a phone in a pocket.
 *  - Road comes from downloaded map roads when available (matched to the road line and your direction),
 *    otherwise from Google, and must be confirmed twice before it is spoken.
 *  - Intersections only on the road you are on (needs downloaded island map data).
 *  - Each place is spoken once; important places first; it never talks over a navigation turn.
 */
class ExploreEngine(
    private val context: Context,
    private val settings: Settings,
    private val islands: IslandRepository,
    private val store: LocationStore,
    private val location: LocationTracker,
    private val speaker: Speaker,
    private val google: GoogleMaps,
    private val nav: NavigationEngine
) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val now: Long get() = SystemClock.elapsedRealtime()

    var active = false; private set
    var paused = false; private set
    var statusText = "Explore is off."; private set
    var roadText = ""; private set
    var lastSpoken = ""; private set
    var hasMapRoads = false; private set
    /** Set by AppGraph: true when the position is on the sea (island announcements take over). */
    var atSea: (Fix) -> Boolean = { false }

    // Recent good positions (for matching the track to a Google road)
    private val track = ArrayList<mv.haluvi.navigation.core.LatLon>()

    // GPS gate
    private var goodCount = 0
    private var badSinceMs = 0L

    // Road
    private var confirmedRoad = ""
    private var candidateRoad = ""
    private var candidateCount = 0
    private var roadBusy = false
    private var roadLastMs = 0L
    private var roadLat = 0.0
    private var roadLon = 0.0
    private var currentRoadIndex = -1

    // Roads / junctions cache for the current island
    private var roadsIslandKey = ""
    private var roads: List<DownloadedRoad> = emptyList()
    private var junctions: List<Junction> = emptyList()
    private val spokenJunctions = HashMap<String, Long>()

    // Places
    private var googlePlaces: List<PlaceCandidate> = emptyList()
    private var googleBusy = false
    private var googleLastMs = 0L
    private var googleLat = 0.0
    private var googleLon = 0.0
    private val spokenPlaces = HashMap<String, Long>()
    private var lastSpeakMs = 0L

    private data class Junction(val key: String, val lat: Double, val lon: Double, val roadIdx: Set<Int>)
    private data class PlaceCandidate(val name: String, val lat: Double, val lon: Double, val categoryId: String, val saved: Boolean) {
        val key: String get() = name.lowercase() + "@" + String.format(Locale.US, "%.4f,%.4f", lat, lon)
    }

    private val loop = object : Runnable {
        override fun run() {
            if (!active) return
            runCatching { step() }
            main.postDelayed(this, 2000)
        }
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() { listeners.forEach { runCatching { it() } } }

    fun start() {
        if (active) return
        active = true; paused = false
        goodCount = 0; badSinceMs = 0
        confirmedRoad = ""; candidateRoad = ""; candidateCount = 0; currentRoadIndex = -1
        spokenPlaces.clear(); spokenJunctions.clear(); googlePlaces = emptyList(); googleLastMs = 0
        roadText = ""; lastSpoken = ""
        statusText = "Explore is on. Waiting for good GPS."
        NavigationService.ensureRunning(context)
        speaker.speak("Explore on.")
        main.removeCallbacks(loop)
        main.post(loop)
        notifyChanged()
    }

    fun stop(silent: Boolean = false) {
        if (!active) return
        active = false
        main.removeCallbacks(loop)
        statusText = "Explore is off."
        roadText = ""
        if (!silent) speaker.speak("Explore off.")
        NavigationService.updateOrStop(context)
        notifyChanged()
    }

    fun toggle() { if (active) stop() else start() }

    // ------------------------------------------------------------------

    private fun step() {
        val f = location.fix
        val driving = settings.travelMode == TravelMode.DRIVING
        val good = f != null && f.ageMs <= 6000 && f.accuracy <= (if (driving) 30.0 else 25.0)
        if (!good) {
            goodCount = 0
            if (badSinceMs == 0L) badSinceMs = now
            if (!paused && now - badSinceMs >= 8000) {
                paused = true
                statusText = "GPS is weak. Explore paused."
                say("GPS is weak. Explore paused.", force = true)
            } else if (!paused) {
                statusText = "Waiting for good GPS."
            }
            notifyChanged()
            return
        }
        badSinceMs = 0
        goodCount++
        f?.let { track.add(mv.haluvi.navigation.core.LatLon(it.lat, it.lon)); if (track.size > 5) track.removeAt(0) }
        if (paused) {
            if (goodCount < 2) return
            paused = false
            say("GPS good. Explore on.", force = true)
        }
        f!!
        if (atSea(f)) {
            // On a ferry or boat: roads and shop announcements make no sense; island announcements run instead.
            statusText = "At sea. Island announcements are on."
            roadText = ""
            notifyChanged()
            return
        }
        loadRoadsFor(f)
        val moving = (f.speedMps ?: 0.0) >= 0.5 && f.bearing != null
        statusText = if (moving) "Exploring." else "Exploring. Start walking to hear places on your left and right."

        // One announcement per step, in priority order: road > intersection > place.
        val spokeRoad = checkRoad(f, moving)
        if (!spokeRoad && moving) {
            val spokeJunction = checkJunction(f)
            if (!spokeJunction) checkPlaces(f)
        }
        if (moving) fetchGooglePlaces(f)
        notifyChanged()
    }

    private fun say(text: String, force: Boolean = false): Boolean {
        if (!force) {
            if (nav.imminentManeuver()) return false
            if (now - lastSpeakMs < 3000) return false
        }
        lastSpeakMs = now
        lastSpoken = text
        speaker.speak(text, flush = force)
        return true
    }

    // ---------------- roads ----------------

    private var roadsVersion = -1

    private fun loadRoadsFor(f: Fix) {
        val key = islands.nearest(f.lat, f.lon)?.island?.key ?: return
        if (key == roadsIslandKey && roadsVersion == RoadDetector.version) return
        roadsIslandKey = key; roadsVersion = RoadDetector.version
        roads = store.loadRoads(key)
        hasMapRoads = roads.isNotEmpty()
        currentRoadIndex = -1
        spokenJunctions.clear()
        // Junctions: points shared by two or more roads (OpenStreetMap roads share a node at real intersections).
        val nodes = HashMap<String, Pair<DoubleArray, MutableSet<Int>>>()
        roads.forEachIndexed { ri, r ->
            r.points.forEach { p ->
                val k = String.format(Locale.US, "%.6f,%.6f", p[0], p[1])
                nodes.getOrPut(k) { p to HashSet() }.second.add(ri)
            }
        }
        junctions = nodes.filter { it.value.second.size >= 2 }.map { (k, v) -> Junction(k, v.first[0], v.first[1], v.second) }
    }

    /** Matches the position to a downloaded road: close to the line and, when moving, along your direction. */
    private fun snapToRoad(f: Fix, moving: Boolean): Pair<Int, Double>? {
        if (roads.isEmpty()) return null
        val maxD = maxOf(15.0, f.accuracy)
        var best = -1
        var bestScore = Double.MAX_VALUE
        var bestD = 0.0
        roads.forEachIndexed { ri, r ->
            for (i in 0 until r.points.size - 1) {
                val a = r.points[i]; val b = r.points[i + 1]
                val d = Geo.distanceToSegment(f.lat, f.lon, a[0], a[1], b[0], b[1])
                if (d > maxD) continue
                var score = d
                if (moving && f.bearing != null) {
                    val segB = Geo.bearing(a[0], a[1], b[0], b[1])
                    val diff = Geo.angleBetween(f.bearing, segB)
                    val along = minOf(diff, 180 - diff) // either direction along the road
                    if (along > 45) continue
                    score += along * 0.3
                }
                if (ri == currentRoadIndex) score -= 3 // small preference to stay on the same road
                if (score < bestScore) { bestScore = score; best = ri; bestD = d }
            }
        }
        return if (best >= 0) best to bestD else null
    }

    private fun checkRoad(f: Fix, moving: Boolean): Boolean {
        // 1) Active Google route knows the road.
        val routeRoad = nav.currentRouteRoadName()
        if (routeRoad.isNotBlank()) return acceptRoad(routeRoad, moving, strong = true)

        // 2) Downloaded map roads.
        val snap = snapToRoad(f, moving)
        if (snap != null) {
            currentRoadIndex = snap.first
            val name = roads[snap.first].name
            if (name.isBlank()) { roadText = "Road: unnamed road."; return false }
            return acceptRoad(name, moving, strong = snap.second <= 8 && moving)
        }
        if (roads.isNotEmpty()) currentRoadIndex = -1

        // 3) Google reverse geocoding (only while moving; it is unreliable near corners when standing).
        if (!settings.googleReady || roadBusy || !moving) return false
        val driving = settings.travelMode == TravelMode.DRIVING
        val moved = Geo.distance(roadLat, roadLon, f.lat, f.lon)
        if (now - roadLastMs < (if (driving) 5000 else 8000) || moved < 10) return false
        roadBusy = true; roadLastMs = now; roadLat = f.lat; roadLon = f.lon
        val recent = track.toList()
        scope.launch {
            // Roads API matches the recent track to the road (reliable at corners); address lookup as fallback.
            val name = withContext(Dispatchers.IO) {
                runCatching { google.snappedRoadName(recent) }.getOrNull()
                    ?: runCatching { google.reverseGeocode(f.lat, f.lon).road }.getOrNull()
            }
            roadBusy = false
            if (!active) return@launch
            name?.takeIf { it.isNotBlank() }?.let { acceptRoad(it, true, strong = recent.size >= 3 && google.roadsApiWorks) }
            notifyChanged()
        }
        return false
    }

    /** A road is spoken only after it is confirmed (twice, or once when the match is very clear). */
    private fun acceptRoad(road: String, moving: Boolean, strong: Boolean): Boolean {
        if (candidateRoad.equals(road, true)) candidateCount++ else { candidateRoad = road; candidateCount = 1 }
        val needed = if (strong) 1 else 2
        if (candidateCount < needed) return false
        roadText = "Road: $road."
        if (confirmedRoad.equals(road, true)) return false
        confirmedRoad = road
        if (!moving && lastSpoken.isNotEmpty()) return false
        return say("On $road.")
    }

    private fun checkJunction(f: Fix): Boolean {
        val ri = currentRoadIndex
        if (ri < 0 || f.bearing == null) return false
        val driving = settings.travelMode == TravelMode.DRIVING || (f.speedMps ?: 0.0) >= 3.5
        val maxD = if (driving) 120.0 else 50.0
        var best: Junction? = null
        var bestD = Double.MAX_VALUE
        for (j in junctions) {
            if (ri !in j.roadIdx) continue
            val d = Geo.distance(f.lat, f.lon, j.lat, j.lon)
            if (d < 8 || d > maxD) continue
            if (Geo.angleBetween(f.bearing, Geo.bearing(f.lat, f.lon, j.lat, j.lon)) > 30) continue
            if (d < bestD) { bestD = d; best = j }
        }
        val j = best ?: return false
        if ((spokenJunctions[j.key] ?: 0L) > now - 180_000) return false
        val others = j.roadIdx.filter { it != ri }.map { roads[it].name }.filter { it.isNotBlank() && !it.equals(confirmedRoad, true) }.distinct()
        val text = if (others.isNotEmpty()) "Intersection with ${others.first()}, ${Phrases.distance(bestD)}." else "Intersection, ${Phrases.distance(bestD)}."
        if (say(text)) { spokenJunctions[j.key] = now; return true }
        return false
    }

    // ---------------- places ----------------

    private fun priority(p: PlaceCandidate): Int = when {
        p.saved -> 0
        p.categoryId in setOf("mosque", "health", "ferry") -> 1
        p.categoryId in setOf("shop", "food", "school", "bank", "police", "hotel") -> 2
        else -> 3
    }

    private fun checkPlaces(f: Fix): Boolean {
        val heading = f.bearing ?: return false
        val driving = settings.travelMode == TravelMode.DRIVING || (f.speedMps ?: 0.0) >= 3.5
        val maxD = if (driving) 100.0 else 30.0
        val candidates = ArrayList<Triple<PlaceCandidate, Double, String>>()
        fun consider(p: PlaceCandidate) {
            if (p.name.startsWith("Unnamed")) return
            val d = Geo.distance(f.lat, f.lon, p.lat, p.lon)
            if (d > maxD) return
            if ((spokenPlaces[p.key] ?: 0L) > now - 600_000) return
            val side = Geo.relativeSide(heading, Geo.bearing(f.lat, f.lon, p.lat, p.lon))
            if (side == "behind") return
            candidates.add(Triple(p, d, side))
        }
        store.nearest(f.lat, f.lon).asSequence().takeWhile { it.distance <= maxD }.forEach {
            consider(PlaceCandidate(it.loc.name, it.loc.lat, it.loc.lon, it.loc.categoryId, it.loc.source == SavedLocation.SOURCE_MANUAL))
        }
        googlePlaces.forEach { consider(it) }
        val pick = candidates.minWithOrNull(compareBy({ priority(it.first) }, { it.second })) ?: return false
        val (p, d, side) = pick
        val text = "${p.name}, $side, ${Phrases.distance(d)}."
        if (say(text)) {
            spokenPlaces[p.key] = now
            // Also mark near-duplicates (same name close by) as spoken.
            candidates.filter { it.first.name.equals(p.name, true) }.forEach { spokenPlaces[it.first.key] = now }
            return true
        }
        return false
    }

    private fun fetchGooglePlaces(f: Fix) {
        if (!settings.googleReady || googleBusy) return
        val moved = Geo.distance(googleLat, googleLon, f.lat, f.lon)
        if (googleLastMs != 0L && moved < 80 && now - googleLastMs < 120_000) return
        googleBusy = true; googleLastMs = now; googleLat = f.lat; googleLon = f.lon
        val radius = if (settings.travelMode == TravelMode.DRIVING) 400 else 150
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { google.nearby(f.lat, f.lon, radius) } }
            googleBusy = false
            r.onSuccess { list ->
                googlePlaces = list.map { PlaceCandidate(it.name, it.lat, it.lon, Categories.guess(it.name + " " + it.typeText), false) }
            }
        }
    }
}
