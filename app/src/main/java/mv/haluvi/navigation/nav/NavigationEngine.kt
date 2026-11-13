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
import mv.haluvi.navigation.data.IslandRepository
import mv.haluvi.navigation.data.LocationStore
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

/** A destination: a place (saved or found online) or an island. */
data class Destination(val name: String, val lat: Double, val lon: Double, val kind: NavKind, val placeId: String? = null, val islandKey: String? = null) {
    companion object {
        fun of(loc: SavedLocation) = Destination(loc.name, loc.lat, loc.lon, NavKind.PLACE, placeId = loc.id)
        fun of(island: Island) = Destination(island.fullName, island.lat, island.lon, NavKind.ISLAND, islandKey = island.key)
    }
}

/**
 * Destination navigation for places and islands.
 *  - Runs with the screen off (driven by NavigationService).
 *  - Google route guidance with spoken turn instructions and automatic re-route.
 *  - Without Google: clock-position guidance ("2 o'clock, 300 meters").
 *  - Pointing: the phone vibrates while it points at the destination.
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

    var target: Destination? = null
        private set
    val isActive: Boolean get() = target != null

    /** Main line: the next instruction ("In 40 meters, turn left onto Majeedhee Magu."). */
    var instructionText = ""; private set
    /** "250 meters left, about 4 minutes." */
    var distanceText = ""; private set
    /** "Destination at 2 o'clock." */
    var directionText = ""; private set
    var speedText = ""; private set
    var altitudeText = ""; private set
    var gpsText = ""; private set
    var routeMessage = ""; private set
    var lastArrivalText = ""; private set

    var route: Route? = null; private set
    var stepIndex = 0; private set
    private var routeBusy = false
    private var lastRouteRequestMs = 0L
    private val spokenManeuvers = HashSet<String>()
    private var offRouteCount = 0
    private var stepLiveIndex = -1
    private var stepLiveRemaining: Double? = null
    private var stepLiveLat = 0.0
    private var stepLiveLon = 0.0
    private var lastRemaining: Double? = null

    private val distHistory = ArrayList<Double>()
    private var startedMs = 0L
    private var arrivalCount = 0
    private var lastDistSpoken = -1
    private var lastDistSpeakMs = 0L
    private var lastGpsWaitSpeakMs = 0L
    private var smoothedSpeed: Double? = null
    private var lastSpeedDist: Double? = null
    private var lastSpeedMs = 0L
    private var lastNearIslandKey = ""
    private var pointing = false
    private var pointStatus = ""
    private var lastHintSpeakMs = 0L
    private var lastPointProcessMs = 0L
    private var lastDistance: Double? = null

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
    // Before start
    // =====================================================================

    /** "300 meters, at 2 o'clock." from the current GPS position, or a GPS message. */
    fun previewText(d: Destination): String {
        val f = location.fix ?: return "Waiting for GPS."
        val dist = Geo.distance(f.lat, f.lon, d.lat, d.lon)
        val b = Geo.bearing(f.lat, f.lon, d.lat, d.lon)
        return "${Phrases.distance(dist)}, ${Phrases.where(compass.heading, b)}."
    }

    fun currentIsland(): Island? = location.fix?.let { islands.nearest(it.lat, it.lon)?.island }

    // =====================================================================
    // Start / stop
    // =====================================================================

    fun start(d: Destination) {
        clearActiveState()
        target = d
        startedMs = now
        lastArrivalText = ""
        instructionText = "Starting."
        NavigationService.ensureRunning(context)
        haptics.tick()
        speaker.speak("Going to ${d.name}.")
        main.removeCallbacks(ticker)
        main.post(ticker)
        tick()
    }

    fun stop(silent: Boolean = false) {
        if (!isActive) return
        clearActiveState()
        if (!silent) speaker.speak("Navigation stopped.")
        NavigationService.updateOrStop(context)
        notifyChanged()
    }

    private fun clearActiveState() {
        main.removeCallbacks(ticker)
        target = null
        route = null; stepIndex = 0; routeBusy = false
        routeMessage = ""; instructionText = ""; distanceText = ""; directionText = ""
        speedText = ""; altitudeText = ""; gpsText = ""
        spokenManeuvers.clear(); offRouteCount = 0; lastRouteRequestMs = 0
        stepLiveIndex = -1; stepLiveRemaining = null; lastRemaining = null
        distHistory.clear(); arrivalCount = 0
        lastDistSpoken = -1; lastDistSpeakMs = 0; lastGpsWaitSpeakMs = 0
        smoothedSpeed = null; lastSpeedDist = null; lastSpeedMs = 0; lastDistance = null
        lastNearIslandKey = ""; pointing = false; pointStatus = ""
        haptics.stopFocus()
    }

    /** What the Repeat button says. */
    fun repeatText(): String {
        val t = target ?: return "No navigation."
        if (instructionText.isBlank() || instructionText == "Starting.") return "Going to ${t.name}. $gpsText"
        return listOf(instructionText, distanceText).filter { it.isNotBlank() }.joinToString(" ")
    }

    // =====================================================================
    // Navigation tick (GPS)
    // =====================================================================

    private fun tick() {
        val t = target ?: return
        val f = location.freshFix(20_000)
        if (f == null) {
            instructionText = if (!location.isGpsEnabled()) "Location is turned off. Please turn on location." else "Waiting for GPS. Please move to an open area."
            gpsText = instructionText
            if (now - lastGpsWaitSpeakMs >= 20_000) { lastGpsWaitSpeakMs = now; speaker.speak(instructionText) }
            notifyChanged()
            return
        }
        gpsText = if (f.accuracy > 30) "GPS is weak, about ${Fmt.meters(f.accuracy)} accuracy." else "GPS good, ${Fmt.meters(f.accuracy)} accuracy."

        val raw = Geo.distance(f.lat, f.lon, t.lat, t.lon)
        val dist = filterDistance(raw)
        lastDistance = dist
        val bearing = Geo.bearing(f.lat, f.lon, t.lat, t.lon)
        val speed = estimateSpeed(f, dist)
        speedText = Fmt.speed(speed)
        altitudeText = f.altitude?.let { alt ->
            val m = alt.roundToInt()
            if (f.altitudeIsMsl) "Altitude: $m meters above sea level" else "Altitude: $m meters, GPS"
        } ?: "Altitude: waiting for GPS"

        if (confirmArrival(dist, f)) { arrive(t); return }

        if (t.kind == NavKind.ISLAND && settings.islandArrivalIdentityOn) {
            islands.nearest(f.lat, f.lon)?.let { near ->
                if (near.distance <= settings.islandArrivalDistance && near.island.key != t.islandKey && near.island.key != lastNearIslandKey) {
                    lastNearIslandKey = near.island.key
                    speaker.speak("Passing ${near.island.name}.")
                }
            }
        }

        directionText = "Destination ${Phrases.where(compass.heading, bearing)}."
        val r = route
        if (t.kind == NavKind.PLACE && r != null) {
            routeTick(f, r, speed)
        } else {
            val eta = if (speed != null && speed >= 0.3) ", about ${Fmt.duration(dist / speed)}" else ""
            distanceText = "${Phrases.distance(dist)} left$eta."
            instructionText = "${t.name}, ${Phrases.where(compass.heading, bearing).removePrefix("at ")}, ${Phrases.distance(dist)}."
            directDistanceSpeech(t, dist, bearing)
            if (t.kind == NavKind.PLACE) maybeRequestRoute(f)
        }
        notifyChanged()
    }

    private fun directDistanceSpeech(t: Destination, dist: Double, bearing: Double) {
        val d = dist.roundToInt()
        val text = "${Phrases.distance(dist)}, ${Phrases.where(compass.heading, bearing).removePrefix("at ")}."
        if (lastDistSpoken < 0) {
            lastDistSpoken = d; lastDistSpeakMs = now
            speaker.speak("${t.name}, $text", flush = false)
            return
        }
        val milestones = if (t.kind == NavKind.ISLAND) intArrayOf(20000, 10000, 5000, 2000, 1000, 500) else intArrayOf(500, 200, 100, 50, 20)
        var shouldSpeak = milestones.any { lastDistSpoken > it && d <= it }
        if (!shouldSpeak && now - lastDistSpeakMs >= 60_000) shouldSpeak = true
        if (shouldSpeak) { lastDistSpeakMs = now; lastDistSpoken = d; speaker.speak(text) }
    }

    /** Ignores single GPS jumps that would make the distance suddenly grow. */
    private fun filterDistance(newDist: Double): Double {
        if (distHistory.isEmpty()) { distHistory.add(newDist); return newDist }
        val prev = distHistory.last()
        var spike = prev > 5 && newDist > prev * 3.0 && abs(newDist - prev) > 20
        if (!spike && distHistory.size >= 2) {
            val pp = distHistory[distHistory.size - 2]
            if (prev <= pp + 5 && newDist > prev + 30) spike = true
        }
        if (spike) return prev
        distHistory.add(newDist)
        if (distHistory.size > 4) distHistory.removeAt(0)
        return newDist
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

    private fun confirmArrival(dist: Double, f: Fix): Boolean {
        val t = target ?: return false
        val arrivalDist = if (t.kind == NavKind.ISLAND) settings.islandArrivalDistance.toDouble() else settings.arrivalDistance.toDouble()
        // Accept a slightly larger circle when GPS is weak, so arrival is not missed.
        val limit = if (t.kind == NavKind.PLACE) max(arrivalDist, min(f.accuracy * 0.8, 25.0)) else arrivalDist
        if (f.accuracy >= 100 || dist > limit) { arrivalCount = 0; return false }
        if (now - startedMs < 5000 && dist > max(2.0, limit / 2)) return false
        arrivalCount++
        return arrivalCount >= 2
    }

    private fun arrive(t: Destination) {
        val text = "Arrived at ${t.name}."
        clearActiveState()
        lastArrivalText = text
        speaker.speak(text)
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
        if (!force && Geo.distance(f.lat, f.lon, t.lat, t.lon) < 30) return
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
                val rerouted = route != null || spokenManeuvers.isNotEmpty()
                route = r; stepIndex = 0; spokenManeuvers.clear(); offRouteCount = 0
                stepLiveIndex = -1; stepLiveRemaining = null; lastRemaining = null
                routeMessage = "Route: ${Phrases.distance(r.distanceMeters)}, about ${Fmt.duration(r.durationSeconds)}."
                location.fix?.let { routeTick(it, r, smoothedSpeed, announceFirst = true, rerouted = rerouted) }
                notifyChanged()
            }.onFailure { e ->
                routeMessage = "No road route. Using direct guidance. " + google.describeError(e)
                notifyChanged()
            }
        }
    }

    /** Re-requests the route, e.g. after the travel mode changes. */
    fun refreshRoute() {
        if (target?.kind != NavKind.PLACE) return
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
            val nb = stepBearing(next)
            val closeLimit = if (driving) 45.0 else 28.0
            val close = (curEnd != null && curEnd <= closeLimit) || (nextStart != null && nextStart <= closeLimit)
            val aligned = nb != null && close && sp >= (if (driving) 2.0 else 0.45) && f.bearing != null && Geo.angleBetween(f.bearing, nb) <= 42
            var onNext = false
            if (next.polyline.size >= 2 && cur.polyline.size >= 2) {
                val dNext = Geo.distanceToPolyline(f.lat, f.lon, next.polyline)
                val dCur = Geo.distanceToPolyline(f.lat, f.lon, cur.polyline)
                onNext = dNext <= 12 && dCur >= dNext + 15
            }
            if (reached || aligned || onNext) { stepIndex++; stepLiveIndex = -1; advanced = true } else break
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
            m.contains("uturn") || m.contains("u-turn") || low.contains("u-turn") || low.contains("u turn") -> "make a U-turn"
            m.contains("roundabout") || low.contains("roundabout") -> "enter the roundabout"
            m.contains("ferry") || low.contains("ferry") -> "take the ferry"
            (m.contains("slight") && m.contains("left")) || low.contains("slight left") -> "keep slightly left"
            (m.contains("slight") && m.contains("right")) || low.contains("slight right") -> "keep slightly right"
            (m.contains("sharp") && m.contains("left")) || low.contains("sharp left") -> "turn sharp left"
            (m.contains("sharp") && m.contains("right")) || low.contains("sharp right") -> "turn sharp right"
            m.contains("left") || low.contains("turn left") || low.startsWith("left") -> "turn left"
            m.contains("right") || low.contains("turn right") || low.startsWith("right") -> "turn right"
            low.contains("destination") || low.contains("arrive") -> "arrive"
            m.contains("straight") || low.contains("continue straight") -> "go straight"
            else -> "continue"
        }
        val road = step.roadName
        if (road.isNotBlank() && action != "arrive") action += if (action == "continue" || action == "go straight" || action == "take the ferry") " on $road" else " onto $road"
        return action
    }

    private fun routeTick(f: Fix, r: Route, speed: Double?, announceFirst: Boolean = false, rerouted: Boolean = false) {
        val advanced = advanceSteps(f, r, speed)
        val stepRemaining = currentStepRemaining(f, r)
        var remaining = (stepRemaining ?: 0.0) + r.steps.drop(stepIndex + 1).sumOf { it.distanceMeters }
        lastRemaining?.let { old -> if (remaining > old + 80 && f.accuracy > 35) remaining = old }
        lastRemaining = remaining

        var sec: Double? = null
        if (r.distanceMeters > 0 && r.durationSeconds > 0) sec = r.durationSeconds * (remaining / r.distanceMeters)
        if (speed != null && speed >= 0.3) { val live = remaining / speed; sec = sec?.let { it * 0.65 + live * 0.35 } ?: live }
        distanceText = "${Phrases.distance(remaining)} left" + (sec?.let { ", about ${Fmt.duration(it)}" } ?: "") + "."

        val following = r.steps.getOrNull(stepIndex + 1)
        val current = r.steps.getOrNull(stepIndex)
        val d = stepRemaining?.roundToInt()
        val driving = settings.travelMode == TravelMode.DRIVING
        val nowDist = if (driving) 25 else 8
        instructionText = if (following != null) {
            val man = maneuverText(following)
            when {
                man == "arrive" && d != null && d <= nowDist -> "Arriving at destination."
                man == "arrive" && d != null -> "In ${Phrases.distance(d.toDouble())}, arrive at destination."
                d != null && d <= nowDist -> "Now ${man}."
                d != null -> "In ${Phrases.distance(d.toDouble())}, ${man}."
                else -> "Then ${man}."
            }
        } else {
            if (d != null) "In ${Phrases.distance(d.toDouble())}, arrive at destination." else "Continue to destination."
        }
        current?.let { stepBearing(it) }?.let { b -> directionText = "Road direction ${Phrases.where(compass.heading, b)}." }

        // ---- spoken guidance ----
        if (announceFirst) {
            speaker.speak((if (rerouted) "New route. " else "Route ready, ${Phrases.distance(r.distanceMeters)}. ") + instructionText)
        } else if (advanced && settings.turnAnnouncementsOn) {
            speaker.speak(instructionText)
        } else if (settings.turnAnnouncementsOn && following != null && d != null) {
            val far = if (driving) 250 else 50
            val near = if (driving) 80 else 15
            val key = when {
                d <= nowDist -> "$stepIndex:now"
                d <= near -> "$stepIndex:near"
                d <= far -> "$stepIndex:far"
                else -> null
            }
            if (key != null && spokenManeuvers.add(key)) {
                // Never say a farther warning after a nearer one.
                if (key.endsWith(":now")) { spokenManeuvers.add("$stepIndex:near"); spokenManeuvers.add("$stepIndex:far") }
                else if (key.endsWith(":near")) spokenManeuvers.add("$stepIndex:far")
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
                speaker.speak("Off route. Finding a new route.")
                maybeRequestRoute(f, force = true)
            }
        }
    }

    /** True when a turn is very close; Explore stays quiet so it does not talk over the turn. */
    fun imminentManeuver(): Boolean {
        if (route == null || !isActive) return false
        val d = stepLiveRemaining ?: return false
        return d <= if (settings.travelMode == TravelMode.DRIVING) 60 else 30
    }

    fun currentRouteRoadName(): String = route?.steps?.getOrNull(stepIndex)?.roadName.orEmpty()

    fun routeStepLines(): List<String> {
        val r = route ?: return emptyList()
        return r.steps.mapIndexed { i, s ->
            val marker = when { i < stepIndex -> "Done. "; i == stepIndex -> "Now. "; else -> "" }
            "${i + 1}. $marker${s.instruction}, ${Phrases.distance(s.distanceMeters)}."
        }
    }

    // =====================================================================
    // Pointing (compass): vibrate while the phone points at the destination
    // =====================================================================

    private fun onHeading(heading: Double) {
        val t = target ?: return
        if (now - lastPointProcessMs < 60) return
        lastPointProcessMs = now
        val f = location.fix ?: return
        val bearing = Geo.bearing(f.lat, f.lon, t.lat, t.lon)
        val diff = Geo.headingDifference(heading, bearing)
        val a = abs(diff)
        val tol = settings.focusAccuracy.toDouble()
        if (a <= tol) {
            if (!pointing) { pointing = true; haptics.startFocus() }
        } else if (pointing && a > tol + 1.5) {
            // Small extra margin before stopping avoids flicker at the edge of the window.
            pointing = false
            haptics.stopFocus()
        }
        val status = when { a <= tol -> "facing"; diff > 0 -> "left"; else -> "right" }
        if (settings.directionVoiceHintsOn && compass.isStable() && status != "facing") {
            if (now - lastHintSpeakMs >= 4000 || status != pointStatus) {
                lastHintSpeakMs = now
                speaker.speak("Turn $status ${a.roundToInt()} degrees")
            }
        }
        pointStatus = status
    }

    fun notificationText(): String = when {
        target == null -> ""
        instructionText.isNotBlank() -> instructionText
        else -> "Going to ${target?.name}"
    }
}
