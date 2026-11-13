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
import mv.haluvi.navigation.data.Island
import mv.haluvi.navigation.data.IslandHit
import mv.haluvi.navigation.data.IslandRepository
import mv.haluvi.navigation.data.LocationStore
import mv.haluvi.navigation.data.PlaceHit
import mv.haluvi.navigation.data.SavedLocation
import mv.haluvi.navigation.data.Settings
import mv.haluvi.navigation.data.TravelMode
import mv.haluvi.navigation.feedback.Haptics
import mv.haluvi.navigation.feedback.Speaker
import mv.haluvi.navigation.net.GoogleMaps
import mv.haluvi.navigation.net.Route
import mv.haluvi.navigation.net.RouteStep
import mv.haluvi.navigation.sensors.CompassTracker
import mv.haluvi.navigation.sensors.Fix
import mv.haluvi.navigation.sensors.LocationTracker
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class NavKind { PLACE, ISLAND }

data class NavTarget(val name: String, val lat: Double, val lon: Double, val kind: NavKind, val placeId: String? = null, val islandKey: String? = null)

data class FocusTarget(val key: String, val name: String, val lat: Double, val lon: Double)

/**
 * Destination navigation for saved places and islands.
 * Ports the final behaviour of the Lua app (v130) and fixes its problems:
 *  - keeps running with the screen off (driven by NavigationService);
 *  - turn-by-turn instructions are SPOKEN ahead of each turn (Lua only showed them on screen);
 *  - automatic re-route when you leave the Google route;
 *  - the "Direction accuracy" setting really controls the focus window (Lua had it hard-coded);
 *  - screens update text in place instead of being rebuilt every second (no lost TalkBack focus).
 */
class NavigationEngine(
    private val context: Context,
    private val settings: Settings,
    private val islands: IslandRepository,
    private val store: LocationStore,
    private val location: LocationTracker,
    private val compass: CompassTracker,
    private val speaker: Speaker,
    private val haptics: Haptics,
    private val google: GoogleMaps
) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    // ---------- selection (before Start) ----------
    var placeDestination: SavedLocation? = null
    var islandDestination: Island? = null

    // ---------- active navigation ----------
    var target: NavTarget? = null
        private set
    val isActive: Boolean get() = target != null
    val isPlaceNav: Boolean get() = target?.kind == NavKind.PLACE
    val isIslandNav: Boolean get() = target?.kind == NavKind.ISLAND

    var statusText = ""; private set
    var instructionText = ""; private set
    var distanceText = ""; private set
    var speedText = ""; private set
    var etaText = ""; private set
    var altitudeText = ""; private set
    var focusText = ""; private set
    var routeMessage = ""; private set
    var lastArrivalText = ""; private set

    var route: Route? = null; private set
    var stepIndex = 0; private set
    var stepsVisible = false
    private var routeBusy = false
    private var lastRouteRequestMs = 0L
    private var routeAnnounced = false
    private val spokenManeuvers = HashSet<String>()
    private var offRouteCount = 0
    private var stepLiveIndex = -1
    private var stepLiveRemaining: Double? = null
    private var stepLiveLat = 0.0
    private var stepLiveLon = 0.0
    private var lastRemaining: Double? = null

    // ---------- nearby targets ----------
    var placesNearbyOn = false; private set
    var islandsNearbyOn = false; private set
    var nearbyPlaces: List<PlaceHit> = emptyList(); private set
    var nearbyIslands: List<IslandHit> = emptyList(); private set
    val extraTargets = LinkedHashMap<String, FocusTarget>()

    // ---------- internal ----------
    private val distHistory = ArrayList<Double>()
    private var startedMs = 0L
    private var arrivalCount = 0
    private var lastDistSpoken = -1
    private var lastDistSpeakMs = 0L
    private var lastGpsWaitSpeakMs = 0L
    private var smoothedSpeed: Double? = null
    private var lastSpeedDist: Double? = null
    private var lastSpeedMs = 0L
    private var lastNearIslandName = ""
    private var lastFocusName = ""
    private var lastFocusSpeakMs = 0L
    private var focusActive = false
    private var focusStatus = ""
    private var lastHintSpeakMs = 0L
    private var lastFocusProcessMs = 0L

    private val fixListener: (Fix) -> Unit = { f ->
        compass.updateDeclination(f.lat, f.lon, f.altitude)
        if (isActive) tick()
    }
    private val headingListener: (Double) -> Unit = { h -> onHeading(h) }

    private val ticker = object : Runnable {
        override fun run() {
            if (!isActive) return
            val fresh = location.fix
            if (fresh == null || fresh.ageMs > 2500) tick()
            main.postDelayed(this, 1000)
        }
    }

    init {
        location.addListener(fixListener)
        compass.addListener(headingListener)
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() { listeners.forEach { runCatching { it() } } }

    private val now: Long get() = SystemClock.elapsedRealtime()

    // =====================================================================
    // Current place / island
    // =====================================================================

    fun currentIsland(): Island? {
        islands.byKey(settings.manualCurrentIslandKey)?.let { return it }
        val f = location.fix ?: return null
        return islands.nearest(f.lat, f.lon)?.island
    }

    fun currentIslandIsManual(): Boolean = islands.byKey(settings.manualCurrentIslandKey) != null

    fun currentPlaceManual(): SavedLocation? = store.byId(settings.manualCurrentPlaceId)

    /** Nearest saved place to the GPS position within the nearest-location distance. */
    fun nearestSavedPlace(maxMeters: Double = settings.nearestLocationDistance.toDouble()): PlaceHit? {
        val f = location.fix ?: return null
        return store.nearest(f.lat, f.lon).firstOrNull()?.takeIf { it.distance <= maxMeters }
    }

    fun currentPlaceButtonText(): String {
        currentPlaceManual()?.let { return "Current place: ${it.name}. Tap to change" }
        val hit = nearestSavedPlace(50.0)
        return if (hit != null) "Current place: ${hit.loc.name}, by GPS. Tap to change" else "Current place: GPS position. Tap to change"
    }

    fun placePreviewText(): String {
        val dest = placeDestination ?: return "Choose a destination, then press Start navigation."
        val origin = currentPlaceManual()?.let { it.lat to it.lon } ?: location.fix?.let { it.lat to it.lon }
            ?: return "Destination: ${dest.name}. Waiting for GPS."
        val d = Geo.distance(origin.first, origin.second, dest.lat, dest.lon)
        val b = Geo.bearing(origin.first, origin.second, dest.lat, dest.lon)
        return "Destination: ${dest.name}. Distance: ${Fmt.metersWithMiles(d)}. Direction: ${Fmt.direction(b)}."
    }

    fun islandPreviewText(): String {
        val dest = islandDestination ?: return "Choose a destination island, then press Start navigation."
        val cur = currentIsland()
        val f = location.fix
        val origin = when {
            cur != null && currentIslandIsManual() -> cur.lat to cur.lon
            f != null -> f.lat to f.lon
            cur != null -> cur.lat to cur.lon
            else -> return "Destination: ${dest.fullName}. Waiting for GPS."
        }
        val d = Geo.distance(origin.first, origin.second, dest.lat, dest.lon)
        val b = Geo.bearing(origin.first, origin.second, dest.lat, dest.lon)
        return "Distance and direction to ${dest.name}: ${Fmt.metersWithMiles(d)}, ${Fmt.direction(b)}."
    }

    // =====================================================================
    // Start / stop
    // =====================================================================

    fun startPlace(loc: SavedLocation) {
        placeDestination = loc
        start(NavTarget(loc.name, loc.lat, loc.lon, NavKind.PLACE, placeId = loc.id))
    }

    fun startIsland(island: Island) {
        islandDestination = island
        start(NavTarget(island.fullName, island.lat, island.lon, NavKind.ISLAND, islandKey = island.key))
    }

    private fun start(t: NavTarget) {
        clearActiveState()
        target = t
        startedMs = now
        lastArrivalText = ""
        statusText = "Starting navigation to ${t.name}."
        NavigationService.ensureRunning(context)
        haptics.tick()
        speaker.speak("Navigation started to ${t.name}.")
        main.removeCallbacks(ticker)
        main.post(ticker)
        tick()
    }

    fun stop(silent: Boolean = false) {
        if (!isActive) return
        clearActiveState()
        statusText = ""
        if (!silent) speaker.speak("Navigation stopped.")
        NavigationService.updateOrStop(context)
        notifyChanged()
    }

    private fun clearActiveState() {
        main.removeCallbacks(ticker)
        target = null
        route = null; stepIndex = 0; routeBusy = false; routeAnnounced = false
        routeMessage = ""; instructionText = ""; distanceText = ""; speedText = ""; etaText = ""; altitudeText = ""; focusText = ""
        spokenManeuvers.clear(); offRouteCount = 0; lastRouteRequestMs = 0
        stepLiveIndex = -1; stepLiveRemaining = null; lastRemaining = null
        distHistory.clear(); arrivalCount = 0
        lastDistSpoken = -1; lastDistSpeakMs = 0; lastGpsWaitSpeakMs = 0
        smoothedSpeed = null; lastSpeedDist = null; lastSpeedMs = 0
        lastNearIslandName = ""; lastFocusName = ""; focusActive = false; focusStatus = ""
        placesNearbyOn = false; islandsNearbyOn = false
        nearbyPlaces = emptyList(); nearbyIslands = emptyList(); extraTargets.clear()
        haptics.stopFocus()
    }

    // =====================================================================
    // Navigation tick (GPS)
    // =====================================================================

    private fun tick() {
        val t = target ?: return
        val f = location.freshFix(30_000)
        if (f == null) {
            statusText = if (!location.isGpsEnabled()) "Location is turned off. Please turn on location." else "Waiting for GPS signal. Please stay in an open area."
            distanceText = "Distance: waiting for GPS"
            if (now - lastGpsWaitSpeakMs >= 15_000) { lastGpsWaitSpeakMs = now; speaker.speak(statusText) }
            notifyChanged()
            return
        }

        val raw = Geo.distance(f.lat, f.lon, t.lat, t.lon)
        val (dist, spike) = filterDistance(raw)
        val bearing = Geo.bearing(f.lat, f.lon, t.lat, t.lon)
        val speed = estimateSpeed(f, dist)
        speedText = Fmt.speed(speed)
        altitudeText = altitudeText(f)

        // ---- arrival ----
        if (confirmArrival(dist, f)) {
            arrive(t)
            return
        }

        // ---- island identity while travelling between islands ----
        if (t.kind == NavKind.ISLAND && settings.islandArrivalIdentityOn) {
            islands.nearest(f.lat, f.lon)?.let { near ->
                if (near.distance <= settings.islandArrivalDistance && near.island.key != t.islandKey && near.island.name != lastNearIslandName) {
                    lastNearIslandName = near.island.name
                    speaker.speak("You are near ${near.island.fullName}.")
                }
            }
        }

        val r = route
        if (t.kind == NavKind.PLACE && r != null) {
            routeTick(f, r, speed)
        } else {
            distanceText = "Distance: " + Fmt.metersWithMiles(dist) + if (spike) " (stabilizing)" else ""
            etaText = if (speed != null && speed >= 0.3) Fmt.eta(dist / speed) else Fmt.eta(null)
            instructionText = ""
            statusText = "Direction: ${Fmt.direction(bearing)}. ${focusSentence(bearing)}"
            directDistanceSpeech(t, dist, bearing)
            if (t.kind == NavKind.PLACE) maybeRequestRoute(f)
        }
        notifyChanged()
    }

    private fun focusSentence(bearing: Double): String {
        val diff = Geo.headingDifference(compass.heading, bearing)
        val a = abs(diff).roundToInt()
        val tol = settings.focusAccuracy
        return when {
            a <= tol -> "Facing destination."
            diff > 0 -> "Turn left $a degrees. Destination at ${Geo.clockPosition(compass.heading, bearing)}."
            else -> "Turn right $a degrees. Destination at ${Geo.clockPosition(compass.heading, bearing)}."
        }
    }

    private fun directDistanceSpeech(t: NavTarget, dist: Double, bearing: Double) {
        val d = dist.roundToInt()
        if (lastDistSpoken < 0) {
            lastDistSpoken = d; lastDistSpeakMs = now
            speaker.speak("${Fmt.meters(dist)} to ${t.name}, at ${Geo.clockPosition(compass.heading, bearing)}.", flush = false)
            return
        }
        val milestones = if (t.kind == NavKind.ISLAND) intArrayOf(10000, 5000, 2000, 1000, 500) else intArrayOf(500, 200, 100, 50, 25, 10)
        var shouldSpeak = milestones.any { lastDistSpoken > it && d <= it }
        if (!shouldSpeak && now - lastDistSpeakMs >= 30_000) shouldSpeak = true
        if (shouldSpeak) {
            lastDistSpeakMs = now; lastDistSpoken = d
            speaker.speak("${Fmt.meters(dist)}, at ${Geo.clockPosition(compass.heading, bearing)}.")
        }
    }

    private fun filterDistance(newDist: Double): Pair<Double, Boolean> {
        if (distHistory.isEmpty()) { distHistory.add(newDist); return newDist to false }
        val prev = distHistory.last()
        var spike = prev > 5 && newDist > prev * 3.0 && abs(newDist - prev) > 20
        if (!spike && distHistory.size >= 2) {
            val pp = distHistory[distHistory.size - 2]
            if (prev <= pp + 5 && newDist > prev + 30) spike = true
        }
        if (spike) return prev to true
        distHistory.add(newDist)
        if (distHistory.size > 4) distHistory.removeAt(0)
        return newDist to false
    }

    private fun estimateSpeed(f: Fix, dist: Double): Double? {
        var chosen = f.speedMps?.takeIf { f.ageMs <= 6000 }
        if (lastSpeedDist != null && lastSpeedMs > 0) {
            val dt = (now - lastSpeedMs) / 1000.0
            if (dt >= 2) {
                val calc = (lastSpeedDist!! - dist) / dt
                if (calc in 0.0..60.0 && (chosen == null || chosen < 0.2)) chosen = calc
                lastSpeedDist = dist; lastSpeedMs = now
            }
        } else { lastSpeedDist = dist; lastSpeedMs = now }
        val c = chosen
        if (c != null && c >= 0) smoothedSpeed = smoothedSpeed?.let { it * 0.65 + c * 0.35 } ?: c
        return smoothedSpeed
    }

    private fun altitudeText(f: Fix): String {
        val alt = f.altitude ?: return "Altitude: waiting for GPS"
        val m = alt.roundToInt(); val ft = (alt * 3.280839895).roundToInt()
        return if (f.altitudeIsMsl) "Altitude: $m meters, $ft feet above sea level" else "Altitude: $m meters, $ft feet, GPS altitude"
    }

    private fun confirmArrival(dist: Double, f: Fix): Boolean {
        val t = target ?: return false
        val arrivalDist = if (t.kind == NavKind.ISLAND) settings.islandArrivalDistance.toDouble() else settings.arrivalDistance.toDouble()
        if (f.accuracy >= 200 || dist > arrivalDist) { arrivalCount = 0; return false }
        if (now - startedMs < 5000 && dist > max(2.0, arrivalDist / 2)) return false
        arrivalCount++
        return arrivalCount >= 2
    }

    private fun arrive(t: NavTarget) {
        val text = "Arrived at ${t.name}."
        clearActiveState()
        lastArrivalText = text
        statusText = text
        speaker.speak(text, force = false)
        haptics.arrival()
        NavigationService.updateOrStop(context)
        notifyChanged()
    }

    // =====================================================================
    // Google route guidance
    // =====================================================================

    private fun maybeRequestRoute(f: Fix, force: Boolean = false) {
        if (!settings.googleReady || routeBusy) return
        val t = target ?: return
        if (t.kind != NavKind.PLACE) return
        if (!force && now - lastRouteRequestMs < 60_000) return
        // No route for very short trips: direct guidance is better than a road route.
        if (!force && Geo.distance(f.lat, f.lon, t.lat, t.lon) < 25) return
        routeBusy = true
        lastRouteRequestMs = now
        routeMessage = if (route == null) "Getting route." else "Updating route."
        val mode = settings.travelMode
        val targetAtRequest = t
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { google.route(f.lat, f.lon, t.lat, t.lon, mode) } }
            routeBusy = false
            if (target != targetAtRequest) return@launch
            result.onSuccess { r ->
                val wasRerouting = route != null || routeAnnounced
                route = r; stepIndex = 0; spokenManeuvers.clear(); offRouteCount = 0
                stepLiveIndex = -1; stepLiveRemaining = null; lastRemaining = null
                routeMessage = "Route active. ${Fmt.meters(r.distanceMeters)}, about ${Fmt.duration(r.durationSeconds)}."
                location.fix?.let { routeTick(it, r, smoothedSpeed, announceFirst = true, rerouted = wasRerouting) }
                routeAnnounced = true
                notifyChanged()
            }.onFailure { e ->
                routeMessage = "Road route unavailable. Direct guidance active. " + google.describeError(e)
                notifyChanged()
            }
        }
    }

    /** Re-requests the route, e.g. after the travel mode changes. */
    fun refreshRoute() {
        if (!isPlaceNav) return
        route = null
        location.fix?.let { maybeRequestRoute(it, force = true) }
    }

    private fun travelHeading(f: Fix?): Double = f?.bearing ?: compass.heading

    private fun stepBearing(s: RouteStep): Double? {
        if (s.polyline.size >= 2) {
            val a = s.polyline[0]
            val b = s.polyline.firstOrNull { Geo.distance(a.lat, a.lon, it.lat, it.lon) >= 8 } ?: s.polyline.last()
            return Geo.bearing(a.lat, a.lon, b.lat, b.lon)
        }
        val st = s.start ?: return null; val en = s.end ?: return null
        return Geo.bearing(st.lat, st.lon, en.lat, en.lon)
    }

    private fun advanceSteps(f: Fix, r: Route, speed: Double?): Boolean {
        val driving = settings.travelMode == TravelMode.DRIVING
        var reach = if (driving) 20.0 else 12.0
        if (f.accuracy in 0.0..30.0) reach = max(reach, min(if (driving) 30.0 else 18.0, f.accuracy * 0.8))
        val sp = speed ?: 0.0
        var advanced = false
        while (stepIndex < r.steps.size - 1) {
            val cur = r.steps[stepIndex]
            val next = r.steps[stepIndex + 1]
            val curEnd = cur.end?.let { Geo.distance(f.lat, f.lon, it.lat, it.lon) }
            val nextStart = next.start?.let { Geo.distance(f.lat, f.lon, it.lat, it.lon) }
            val reached = (curEnd != null && curEnd <= reach) || (nextStart != null && nextStart <= reach)
            var aligned = false
            val nb = stepBearing(next)
            val close = (curEnd != null && curEnd <= (if (driving) 45.0 else 28.0)) || (nextStart != null && nextStart <= (if (driving) 45.0 else 28.0))
            if (nb != null && close && sp >= (if (driving) 2.0 else 0.45) && Geo.angleBetween(travelHeading(f), nb) <= 42) aligned = true
            // Already clearly on the next segment's line and away from the current one.
            var onNext = false
            if (next.polyline.size >= 2 && cur.polyline.size >= 2) {
                val dNext = Geo.distanceToPolyline(f.lat, f.lon, next.polyline)
                val dCur = Geo.distanceToPolyline(f.lat, f.lon, cur.polyline)
                onNext = dNext <= 12 && dCur >= dNext + 15
            }
            if (reached || aligned || onNext) {
                stepIndex++
                stepLiveIndex = -1
                advanced = true
            } else break
        }
        return advanced
    }

    private fun currentStepRemaining(f: Fix, r: Route): Double? {
        val step = r.steps.getOrNull(stepIndex) ?: return null
        var raw = step.end?.let { Geo.distance(f.lat, f.lon, it.lat, it.lon) } ?: step.distanceMeters
        if (step.distanceMeters > 0) raw = min(raw, step.distanceMeters * 1.35)
        val prev = stepLiveRemaining
        if (stepLiveIndex != stepIndex || prev == null) {
            stepLiveIndex = stepIndex
            stepLiveRemaining = raw
        } else {
            val moved = Geo.distance(stepLiveLat, stepLiveLon, f.lat, f.lon)
            stepLiveRemaining = when {
                raw <= prev -> raw
                moved >= 3 && f.accuracy <= 45 && raw <= prev + max(12.0, moved * 1.5) -> raw
                raw > prev + max(25.0, f.accuracy) -> prev
                else -> prev * 0.65 + raw * 0.35
            }
        }
        stepLiveLat = f.lat; stepLiveLon = f.lon
        return stepLiveRemaining?.coerceAtLeast(0.0)
    }

    fun maneuverText(step: RouteStep?): String {
        if (step == null) return ""
        val m = step.maneuver.lowercase()
        val low = step.instruction.lowercase()
        var action = when {
            m.contains("uturn") || m.contains("u-turn") || low.contains("u-turn") || low.contains("u turn") -> "Make a U-turn"
            m.contains("roundabout") || low.contains("roundabout") -> "Enter the roundabout"
            m.contains("ferry") || low.contains("ferry") -> "Take the ferry"
            m.contains("slight") && m.contains("left") || low.contains("slight left") -> "Slight left"
            m.contains("slight") && m.contains("right") || low.contains("slight right") -> "Slight right"
            m.contains("sharp") && m.contains("left") || low.contains("sharp left") -> "Sharp left"
            m.contains("sharp") && m.contains("right") || low.contains("sharp right") -> "Sharp right"
            m.contains("left") || low.contains("turn left") || low.startsWith("left") -> "Turn left"
            m.contains("right") || low.contains("turn right") || low.startsWith("right") -> "Turn right"
            low.contains("destination") || low.contains("arrive") -> "Arrive at destination"
            m.contains("straight") || low.contains("continue straight") -> "Continue straight"
            else -> "Continue"
        }
        val road = step.roadName
        if (road.isNotBlank() && action != "Arrive at destination") {
            action += if (action.startsWith("Continue") || action == "Take the ferry") " on $road" else " onto $road"
        }
        return action
    }

    private fun routeTick(f: Fix, r: Route, speed: Double?, announceFirst: Boolean = false, rerouted: Boolean = false) {
        val advanced = advanceSteps(f, r, speed)
        val stepRemaining = currentStepRemaining(f, r)
        var remaining = (stepRemaining ?: 0.0) + r.steps.drop(stepIndex + 1).sumOf { it.distanceMeters }
        lastRemaining?.let { old -> if (remaining > old + 80 && f.accuracy > 35) remaining = old }
        lastRemaining = remaining

        distanceText = "Distance left: " + Fmt.metersWithMiles(remaining)
        var sec: Double? = null
        if (r.distanceMeters > 0 && r.durationSeconds > 0) sec = r.durationSeconds * (remaining / r.distanceMeters)
        if (speed != null && speed >= 0.3) { val live = remaining / speed; sec = sec?.let { it * 0.65 + live * 0.35 } ?: live }
        etaText = Fmt.eta(sec)

        val following = r.steps.getOrNull(stepIndex + 1)
        val current = r.steps.getOrNull(stepIndex)
        val d = stepRemaining?.roundToInt()
        val driving = settings.travelMode == TravelMode.DRIVING
        val nowDist = if (driving) 25 else 8
        instructionText = if (following != null) {
            val man = maneuverText(following)
            when {
                d != null && d <= nowDist -> if (man.startsWith("Arrive")) "$man." else "$man now."
                d != null -> "Continue ${Fmt.meters(d.toDouble())}, then ${Fmt.lowerFirst(man)}."
                else -> "Continue, then ${Fmt.lowerFirst(man)}."
            }
        } else {
            val man = maneuverText(current)
            if (man.startsWith("Arrive")) "$man." else if (d != null) "Continue ${Fmt.meters(d.toDouble())} to destination." else "Continue toward destination."
        }
        val dirBearing = current?.let { stepBearing(it) } ?: target?.let { Geo.bearing(f.lat, f.lon, it.lat, it.lon) } ?: 0.0
        statusText = "$instructionText Direction: ${Fmt.direction(dirBearing)}."

        // ---- spoken guidance ----
        if (announceFirst) {
            val prefix = if (rerouted) "New route. " else "Route ready, ${Fmt.meters(r.distanceMeters)}, about ${Fmt.duration(r.durationSeconds)}. "
            speaker.speak(prefix + instructionText)
            spokenManeuvers.add("$stepIndex:first")
        } else if (advanced && settings.turnAnnouncementsOn) {
            speaker.speak(instructionText)
        } else if (settings.turnAnnouncementsOn && following != null && d != null) {
            val thresholds = if (driving) intArrayOf(250, 80) else intArrayOf(50, 15)
            val key = when {
                d <= nowDist -> "$stepIndex:now"
                d <= thresholds[1] -> "$stepIndex:${thresholds[1]}"
                d <= thresholds[0] -> "$stepIndex:${thresholds[0]}"
                else -> null
            }
            if (key != null && spokenManeuvers.add(key)) {
                // Saying a far threshold after a nearer one would be confusing; mark the far ones as spoken too.
                if (key.endsWith(":now")) { spokenManeuvers.add("$stepIndex:${thresholds[1]}"); spokenManeuvers.add("$stepIndex:${thresholds[0]}") }
                else if (key.endsWith(":${thresholds[1]}")) spokenManeuvers.add("$stepIndex:${thresholds[0]}")
                speaker.speak(instructionText)
            }
        }

        // ---- off-route detection ----
        val line = (current?.polyline.orEmpty() + following?.polyline.orEmpty())
        if (line.size >= 2 && !routeBusy) {
            val off = Geo.distanceToPolyline(f.lat, f.lon, line)
            val limit = max(if (driving) 50.0 else 30.0, f.accuracy * 1.5)
            offRouteCount = if (off > limit && f.accuracy < 60) offRouteCount + 1 else 0
            if (offRouteCount >= 3 && now - lastRouteRequestMs >= 15_000) {
                offRouteCount = 0
                speaker.speak("Off route. Getting a new route.")
                maybeRequestRoute(f, force = true)
            }
        }
    }

    /** True when a route maneuver is very close; Explore stays quiet so it does not talk over the turn. */
    fun imminentManeuver(): Boolean {
        if (!isPlaceNav || route == null) return false
        val d = stepLiveRemaining ?: return false
        return d <= if (settings.travelMode == TravelMode.DRIVING) 55 else 30
    }

    fun currentRouteRoadName(): String = route?.steps?.getOrNull(stepIndex)?.roadName.orEmpty()

    fun routeStepLines(): List<String> {
        val r = route ?: return emptyList()
        return r.steps.mapIndexed { i, s ->
            val marker = when {
                i < stepIndex -> "Done. "
                i == stepIndex -> "Current. "
                else -> ""
            }
            "${i + 1}. $marker${s.instruction}, ${Fmt.meters(s.distanceMeters)}."
        }
    }

    // =====================================================================
    // Direction focus (compass)
    // =====================================================================

    private fun focusTargets(): List<FocusTarget> {
        val t = target ?: return emptyList()
        val list = ArrayList<FocusTarget>()
        list.add(FocusTarget("destination", t.name, t.lat, t.lon))
        if ((t.kind == NavKind.PLACE && placesNearbyOn) || (t.kind == NavKind.ISLAND && islandsNearbyOn)) list.addAll(extraTargets.values)
        return list
    }

    private fun onHeading(heading: Double) {
        if (!isActive) return
        if (now - lastFocusProcessMs < 60) return
        lastFocusProcessMs = now
        val f = location.fix ?: return
        val tol = settings.focusAccuracy.toDouble()
        var best: FocusTarget? = null
        var bestAbs = 999.0
        for (ft in focusTargets()) {
            val diff = Geo.angleBetween(heading, Geo.bearing(f.lat, f.lon, ft.lat, ft.lon))
            if (diff <= tol && diff < bestAbs) { best = ft; bestAbs = diff }
        }
        if (best != null) {
            focusActive = true
            haptics.startFocus()
            if (best.name != lastFocusName && now - lastFocusSpeakMs > 250) {
                lastFocusName = best.name
                lastFocusSpeakMs = now
                speaker.speak(best.name)
            }
        } else {
            if (focusActive) haptics.stopFocus()
            focusActive = false
            lastFocusName = ""
        }

        // Live focus text and optional voice hints.
        val t = target ?: return
        val bearing = Geo.bearing(f.lat, f.lon, t.lat, t.lon)
        val diff = Geo.headingDifference(heading, bearing)
        val a = abs(diff).roundToInt()
        val status = when { a <= tol -> "facing"; diff > 0 -> "left"; else -> "right" }
        focusText = when (status) {
            "facing" -> "Direction focus: facing destination."
            "left" -> "Direction focus: turn left $a degrees."
            else -> "Direction focus: turn right $a degrees."
        }
        if (settings.directionVoiceHintsOn && compass.isStable() && status != "facing") {
            if (now - lastHintSpeakMs >= settings.correctionIntervalSec * 1000L || status != focusStatus) {
                lastHintSpeakMs = now
                speaker.speak(if (status == "left") "Turn left $a degrees" else "Turn right $a degrees")
            }
        }
        focusStatus = status
    }

    // =====================================================================
    // Nearby lists (toggle targets for focus vibration)
    // =====================================================================

    fun setPlacesNearby(on: Boolean) {
        placesNearbyOn = on
        if (on) refreshNearbyPlaces() else { extraTargets.clear(); haptics.stopFocus() }
        notifyChanged()
    }

    fun refreshNearbyPlaces() {
        val f = location.fix ?: run { nearbyPlaces = emptyList(); return }
        val destId = target?.placeId
        nearbyPlaces = store.nearest(f.lat, f.lon).filter { it.distance <= settings.nearestLocationDistance && it.loc.id != destId }.take(30)
    }

    fun setIslandsNearby(on: Boolean) {
        islandsNearbyOn = on
        if (on) refreshNearbyIslands() else { extraTargets.clear(); haptics.stopFocus() }
        notifyChanged()
    }

    fun refreshNearbyIslands() {
        val f = location.fix ?: run { nearbyIslands = emptyList(); return }
        val destKey = target?.islandKey
        nearbyIslands = islands.inFront(f.lat, f.lon, settings.nearestIslandArea.toDouble(), compass.heading).filter { it.island.key != destKey }
    }

    fun toggleExtraTarget(ft: FocusTarget): Boolean {
        val on = if (extraTargets.containsKey(ft.key)) { extraTargets.remove(ft.key); false } else { extraTargets[ft.key] = ft; true }
        if (!on && lastFocusName == ft.name) { haptics.stopFocus(); lastFocusName = "" }
        notifyChanged()
        return on
    }

    fun notificationText(): String = when {
        target == null -> ""
        instructionText.isNotBlank() -> instructionText
        else -> "${target?.name}: ${distanceText.removePrefix("Distance: ")}"
    }
}
