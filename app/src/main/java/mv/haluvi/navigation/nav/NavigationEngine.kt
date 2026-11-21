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
import mv.haluvi.navigation.data.IslandShapes
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
data class Destination(val name: String, val lat: Double, val lon: Double, val kind: NavKind, val placeId: String? = null, val islandKey: String? = null,
                       /** The point on the road in front of the place, when it was saved with its side. */
                       val access: mv.haluvi.navigation.core.LatLon? = null) {
    companion object {
        fun of(loc: SavedLocation) = Destination(loc.name, loc.lat, loc.lon, NavKind.PLACE, placeId = loc.id,
            access = if (loc.hasAccess) mv.haluvi.navigation.core.LatLon(loc.accessLat, loc.accessLon) else null)
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
    private val shapes: IslandShapes,
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

    /** One short line: what kind of guidance is used now. */
    val guidanceText: String get() {
        val t = target ?: return ""
        if (t.kind == NavKind.ISLAND) return "Guidance: island trip, direct."
        val r = route ?: return if (routeBusy) "Guidance: finding a route." else "Guidance: direct, no route."
        return if (r.source == "Offline") "Guidance: offline route." else "Guidance: Google route."
    }
    var lastArrivalText = ""; private set
    /** The destination of the last finished trip (for Go back to start and Save this place). */
    var lastArrived: Destination? = null; private set
    /** Where the last trip started (for Go back to start). */
    var lastStart: mv.haluvi.navigation.core.LatLon? = null; private set

    var route: Route? = null; private set
    var stepIndex = 0; private set
    private var routeBusy = false
    private var lastRouteRequestMs = 0L
    /** Changes when you choose a route yourself (Route steps), so an older route request does not replace it. */
    private var routeGen = 0
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
    private var pointing = false
    private var pointStatus = ""
    private var lastHintSpeakMs = 0L
    private var lastPointProcessMs = 0L
    private var lastDistance: Double? = null
    // Final approach / passed detection (places)
    private var minDist = Double.MAX_VALUE
    private var passedAnnounced = false
    private val approachSpoken = HashSet<Int>()
    /** Side of the destination, decided once near the end and kept ("left", "right" or "ahead"). */
    private var lockedSide: String? = null
    /** How sure the locked side is: 3 route (Google or offline), 2 road direction, 1 walking direction. */
    private var lockedRank = 0

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

    /**
     * "300 meters, at 2 o'clock." from the GPS position, or from a chosen starting point
     * ([fromLat]/[fromLon], used for planning; directions are then given as compass directions).
     */
    fun previewText(d: Destination, fromLat: Double? = null, fromLon: Double? = null): String {
        if (fromLat != null && fromLon != null) {
            val dist = Geo.distance(fromLat, fromLon, d.lat, d.lon)
            val b = Geo.bearing(fromLat, fromLon, d.lat, d.lon)
            // From another place your facing direction is unknown, so a clock position cannot be given.
            return if (settings.clockDirections) "${Phrases.distance(dist)}, towards the ${Geo.directionName(b).lowercase()}."
            else "${Phrases.distance(dist)}, ${Geo.directionName(b)}, ${Geo.degreesInt(b)} degrees."
        }
        val f = location.fix ?: return "Waiting for GPS."
        val dist = Geo.distance(f.lat, f.lon, d.lat, d.lon)
        val b = Geo.bearing(f.lat, f.lon, d.lat, d.lon)
        return "${Phrases.distance(dist)}, ${Phrases.where(compass.heading, b)}."
    }

    /** The island you are on: the outline you are inside when outlines are downloaded, otherwise the nearest island centre. */
    fun currentIsland(): Island? = location.fix?.let { shapes.islandAt(it.lat, it.lon) ?: islands.nearest(it.lat, it.lon)?.island }

    // =====================================================================
    // Start / stop
    // =====================================================================

    fun start(d: Destination) {
        clearActiveState()
        target = d
        startedMs = now
        lastArrivalText = ""
        lastStart = location.fix?.let { mv.haluvi.navigation.core.LatLon(it.lat, it.lon) }
        instructionText = "Starting."
        NavigationService.ensureRunning(context)
        haptics.tick()
        val road = if (d.kind == NavKind.PLACE) (d.placeId?.let { store.byId(it) }?.let { RoadInfo.of(it) }
            ?: RoadInfo.nearestNamed(d.lat, d.lon, null))?.takeIf { !d.name.equals(it, true) } else null
        speaker.speak("Going to ${d.name}" + (road?.let { ", on $it" } ?: "") + ".")
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
        minDist = Double.MAX_VALUE; passedAnnounced = false; approachSpoken.clear(); lockedSide = null; lockedRank = 0; noRouteSaid = false
        awayMin = Double.MAX_VALUE; awayLastMs = 0
        pointing = false; pointStatus = ""
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

        val moving = isMoving(f)
        val heading = travelHeading(f)
        // For places, also measure to where the place meets the road (end of the Google route),
        // because a saved position is often the middle of a building.
        val accessDist = t.access?.let { Geo.distance(f.lat, f.lon, it.lat, it.lon) }
        val effDist = if (t.kind == NavKind.PLACE) minOf(dist, accessDist ?: roadPointDistance(f) ?: dist) else dist

        updateLockedSide(f, t, effDist, route)
        if (confirmArrival(effDist, f, speed)) { arrive(t, f); return }

        directionText = ""
        val r = route
        if (t.kind == NavKind.PLACE && r != null) {
            routeTick(f, r, speed)
        } else {
            val eta = if (speed != null && speed >= 0.3) ", about ${Fmt.duration(dist / speed)}" else ""
            distanceText = "${Phrases.distance(dist)} left$eta."
            instructionText = "Destination ${Phrases.where(heading, bearing)}."
            directDistanceSpeech(t, dist, bearing, heading)
            if (t.kind == NavKind.PLACE) maybeRequestRoute(f)
        }
        if (t.kind == NavKind.PLACE) {
            val onLastStep = r == null || stepIndex >= r.steps.size - 1
            if (onLastStep) finalApproach(t, f, effDist, bearing, heading, moving, r)
            checkPassed(t, f, effDist, bearing, heading, moving)
            checkMovingAway(t, f, effDist, bearing, heading, moving)
        }
        notifyChanged()
    }

    /** Moving, including slow walking (0.5 m per second and more). */
    private fun isMoving(f: Fix) = f.bearing != null && (f.speedMps ?: 0.0) >= 0.5

    /** Travel direction from GPS movement when moving (reliable in a car or pocket); phone compass when standing. */
    private fun travelHeading(f: Fix): Double = if (isMoving(f)) f.bearing!! else compass.heading

    private fun driving(speed: Double?) = settings.travelMode == TravelMode.DRIVING || (speed ?: 0.0) >= 4.0

    private fun roadPointDistance(f: Fix): Double? = route?.steps?.lastOrNull()?.end?.let { Geo.distance(f.lat, f.lon, it.lat, it.lon) }

    /**
     * Works out which side the destination is on, best source first:
     *  3) the route's own side (Google's "Destination will be on the right", or the offline route's side);
     *  2) the direction of the downloaded road you are on (stable even when walking slowly or standing);
     *  1) your walking or driving direction.
     * Returns "left", "right", "ahead" or null, with its rank.
     */
    private fun sideNow(f: Fix, t: Destination, r: Route?): Pair<String, Int>? {
        r?.steps?.lastOrNull { it.destinationSide.isNotBlank() }?.destinationSide?.let { return it to 3 }
        val bearing = Geo.bearing(f.lat, f.lon, t.lat, t.lon)
        val d = Geo.distance(f.lat, f.lon, t.lat, t.lon)
        if (d < 4) return null // too close for a direction from GPS
        val moving = isMoving(f)
        RoadDetector.roadDirectionAt(f)?.let { roadB ->
            // The road runs both ways: use the way you are moving, or else the way towards the destination.
            val ref = if (moving) f.bearing!! else bearing
            val dirB = if (Geo.angleBetween(ref, roadB) <= 90) roadB else Geo.normalize(roadB + 180)
            return sideWord(dirB, bearing, d) to 2
        }
        if (moving) return sideWord(f.bearing!!, bearing, d) to 1
        return null
    }

    /**
     * Which side of the road line the destination is on. Measured as metres to the left or right of the line
     * you travel along (not as an angle), so a shop 10 m beside the road is "on your right" also from 60 m away.
     * "ahead" only when it is within 3 m of the line (for example at the end of the road).
     */
    private fun sideWord(heading: Double, bearing: Double, d: Double): String {
        val diff = Geo.headingDifference(heading, bearing) // positive = target to the left
        val r = Math.toRadians(diff)
        val along = d * kotlin.math.cos(r)
        val lateral = d * kotlin.math.abs(kotlin.math.sin(r))
        return when { along < -5 -> "behind"; lateral < 3 -> "ahead"; diff > 0 -> "left"; else -> "right" }
    }

    /** Decides the side near the end (60 m walking, 200 m driving) and keeps it, so it does not change at the last moment. */
    private fun updateLockedSide(f: Fix, t: Destination, d: Double, r: Route?) {
        if (t.kind != NavKind.PLACE) return
        val zone = if (driving(f.speedMps)) 200.0 else 60.0
        if (d > zone) return
        val s = sideNow(f, t, r) ?: return
        if (s.first == "behind") return
        if (s.second > lockedRank || (s.second == lockedRank && lockedRank < 3 && d > 15)) { lockedSide = s.first; lockedRank = s.second }
    }

    /** "destination on your right", "destination straight ahead", or a clock position when the side is not known. */
    private fun sidePhrase(f: Fix, t: Destination): String {
        val side = lockedSide ?: sideNow(f, t, route)?.first?.takeIf { it != "behind" }
        return when (side) {
            "left", "right" -> "destination on your $side"
            "ahead" -> "destination straight ahead"
            else -> "destination ${Phrases.where(travelHeading(f), Geo.bearing(f.lat, f.lon, t.lat, t.lon))}"
        }
    }

    /** Short, frequent messages near the end: "In 50 meters, destination on your right." */
    private fun finalApproach(t: Destination, f: Fix, d: Double, bearing: Double, heading: Double, moving: Boolean, r: Route?) {
        val thresholds = if (driving(f.speedMps)) intArrayOf(300, 150, 50) else intArrayOf(100, 50, 30, 10)
        val hit = thresholds.filter { d <= it }.minOrNull() ?: return
        if (!approachSpoken.add(hit)) return
        thresholds.filter { it > hit }.forEach { approachSpoken.add(it) } // never say a farther one later
        speaker.speak("In ${Phrases.distance(d)}, ${sidePhrase(f, t)}.")
        lastDistSpeakMs = now; lastDistSpoken = d.roundToInt()
    }

    /** Tells the user clearly when the destination is behind them, then finds a new route. */
    private fun checkPassed(t: Destination, f: Fix, d: Double, bearing: Double, heading: Double, moving: Boolean) {
        val drive = driving(f.speedMps)
        if (passedAnnounced && d < minDist - 5) { passedAnnounced = false; minDist = d } // coming back towards it
        if (d < minDist) minDist = d
        if (passedAnnounced || !moving) return
        val nearEnough = minDist <= if (drive) 80.0 else 35.0
        val goingAway = d >= minDist + (if (drive) 25.0 else 12.0)
        if (nearEnough && goingAway && Geo.relativeSide(heading, bearing) == "behind") {
            passedAnnounced = true
            approachSpoken.clear(); lockedSide = null; lockedRank = 0
            val passedText = "You passed ${t.name}. It is ${Phrases.distance(d)} behind you. Turn around when safe."
            speaker.speak(passedText)
            mv.haluvi.navigation.feedback.TripAlerts.alert(context, passedText)
            if (route != null) location.fix?.let { maybeRequestRoute(it, force = true) }
        }
    }

    private var noRouteSaid = false
    private var awayMin = Double.MAX_VALUE
    private var awayLastMs = 0L

    /** Without a route: warns when the distance keeps growing ("You are moving away"), with the direction to turn to. */
    private fun checkMovingAway(t: Destination, f: Fix, d: Double, bearing: Double, heading: Double, moving: Boolean) {
        if (route != null || !moving) return
        if (d < awayMin) awayMin = d
        val drive = driving(f.speedMps)
        if (minDist <= (if (drive) 80.0 else 35.0)) return // near the place: "You passed" handles it
        val step = if (drive) 80.0 else 25.0
        if (d >= awayMin + step && now - awayLastMs > 60_000) {
            awayLastMs = now; awayMin = d
            speaker.speak("You are moving away from ${t.name}. Destination ${Phrases.where(heading, bearing)}, ${Phrases.distance(d)}.")
        }
    }

    /** "Where is it?": direction from the way the phone points, and distance. */
    fun whereIsItText(): String {
        val t = target ?: return "No navigation."
        val f = location.fix ?: return "Waiting for GPS."
        val b = Geo.bearing(f.lat, f.lon, t.lat, t.lon)
        val d = Geo.distance(f.lat, f.lon, t.lat, t.lon)
        return "${t.name}: ${Phrases.where(compass.heading, b).removePrefix("at ")}, ${Phrases.distance(d)}. Point the phone; it vibrates when it points at it."
    }

    private fun directDistanceSpeech(t: Destination, dist: Double, bearing: Double, heading: Double) {
        val d = dist.roundToInt()
        val text = "${Phrases.distance(dist)}, ${Phrases.where(heading, bearing).removePrefix("at ")}."
        if (lastDistSpoken < 0) {
            lastDistSpoken = d; lastDistSpeakMs = now
            speaker.speak("${t.name}, $text", flush = false)
            return
        }
        val milestones = when {
            t.kind == NavKind.ISLAND && settings.islandTravelAir -> intArrayOf(100000, 50000, 20000, 10000, 5000)
            t.kind == NavKind.ISLAND -> intArrayOf(20000, 10000, 5000, 2000, 1000)
            // Near the end of a place trip, finalApproach() speaks (100, 50, 30, 10 m).
            settings.travelMode == TravelMode.DRIVING -> intArrayOf(2000, 1000, 500)
            else -> intArrayOf(500, 200)
        }
        if (t.kind == NavKind.PLACE && dist <= (if (settings.travelMode == TravelMode.DRIVING) 300 else 100)) return
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

    private fun confirmArrival(dist: Double, f: Fix, speed: Double?): Boolean {
        val t = target ?: return false
        if (t.kind == NavKind.ISLAND) {
            val key = t.islandKey.orEmpty()
            // With an outline: arrived when on the island (stops at the shore are handled by IslandWatcher).
            val onIsland = if (shapes.has(key)) shapes.inside(key, f.lat, f.lon)
            else dist <= (if (settings.islandTravelAir) maxOf(2000.0, settings.islandArrivalDistance.toDouble()) else settings.islandArrivalDistance.toDouble()) &&
                (f.speedMps ?: 0.0) < (if (settings.islandTravelAir) 15.0 else 3.0)
            if (!onIsland || f.accuracy >= 100) { arrivalCount = 0; return false }
            arrivalCount++
            return arrivalCount >= 2
        }
        val arrivalDist = settings.arrivalDistance.toDouble()
        // Driving: a bigger zone (you stop or park near the place). Weak GPS: a slightly bigger circle.
        val limit = if (driving(speed)) max(40.0, arrivalDist) else max(arrivalDist, min(f.accuracy * 0.8, 25.0))
        if (f.accuracy >= 100 || dist > limit) { arrivalCount = 0; return false }
        if (now - startedMs < 5000 && dist > max(2.0, limit / 2)) return false
        arrivalCount++
        // When moving fast one reading inside the zone is enough (the next one may already be past it).
        return arrivalCount >= 2 || (speed ?: 0.0) >= 3.0
    }

    /** Called by IslandWatcher when the boat or plane has stopped at the destination island. */
    fun arrivedAtIsland() { target?.takeIf { it.kind == NavKind.ISLAND }?.let { arrive(it, null) } }

    private fun arrive(t: Destination, f: Fix?) {
        val text = when {
            t.kind == NavKind.ISLAND -> "Arrived at ${t.name}."
            f == null -> "Arrived at ${t.name}."
            else -> {
                val side = lockedSide ?: sideNow(f, t, route)?.first?.takeIf { it != "behind" }
                when {
                    side == "left" || side == "right" -> "Arrived at ${t.name}, destination on your $side."
                    side == "ahead" -> "Arrived at ${t.name}, straight ahead."
                    Geo.distance(f.lat, f.lon, t.lat, t.lon) >= 4 ->
                        "Arrived at ${t.name}, ${Phrases.where(travelHeading(f), Geo.bearing(f.lat, f.lon, t.lat, t.lon))}."
                    else -> "Arrived at ${t.name}."
                }
            }
        }
        clearActiveState()
        lastArrivalText = text
        lastArrived = t
        speaker.speak(text)
        mv.haluvi.navigation.feedback.TripAlerts.alert(context, text)
        haptics.arrival()
        NavigationService.updateOrStop(context)
        notifyChanged()
    }

    // =====================================================================
    // Google route guidance
    // =====================================================================

    /**
     * Gets a road route: Google when online with a key; otherwise (or when Google fails) an offline
     * route calculated on the phone from downloaded roads. Without either, direct guidance continues.
     */
    private fun maybeRequestRoute(f: Fix, force: Boolean = false) {
        if (routeBusy) return
        val t = target ?: return
        if (t.kind != NavKind.PLACE) return
        if (!force && now - lastRouteRequestMs < 60_000) return
        if (!force && Geo.distance(f.lat, f.lon, t.lat, t.lon) < 30) return
        routeBusy = true
        lastRouteRequestMs = now
        routeMessage = if (route == null) "Getting route." else "Updating route."
        val mode = settings.travelMode
        val targetAtRequest = t
        val genAtRequest = routeGen
        val useGoogle = settings.googleReady
        scope.launch {
            var r: Route? = null
            var googleError: Throwable? = null
            if (useGoogle) {
                r = withContext(Dispatchers.IO) { runCatching { google.route(f.lat, f.lon, t.lat, t.lon, mode) } }
                    .onFailure { googleError = it }.getOrNull()
            }
            if (r == null) {
                r = withContext(Dispatchers.Default) {
                    runCatching { OfflineRouter.route(mv.haluvi.navigation.core.LatLon(f.lat, f.lon), mv.haluvi.navigation.core.LatLon(t.lat, t.lon), mode) }.getOrNull()
                }
            }
            routeBusy = false
            if (target != targetAtRequest || routeGen != genAtRequest) return@launch
            val got = r
            if (got != null && got.steps.isNotEmpty()) {
                val rerouted = route != null || spokenManeuvers.isNotEmpty()
                route = got; stepIndex = 0; spokenManeuvers.clear(); offRouteCount = 0
                stepLiveIndex = -1; stepLiveRemaining = null; lastRemaining = null
                val source = if (got.source == "Offline") "Offline route" else "Google route"
                routeMessage = "$source: ${Phrases.distance(got.distanceMeters)}, about ${Fmt.duration(got.durationSeconds)}."
                location.fix?.let { routeTick(it, got, smoothedSpeed, announceFirst = true, rerouted = rerouted) }
            } else {
                if (!noRouteSaid) { noRouteSaid = true; speaker.speak("No road route here, I will guide by direction.", flush = false) }
                routeMessage = "No road route. Using direct guidance." +
                    (googleError?.let { " " + google.describeError(it) } ?: if (!useGoogle) " Download the offline map of this island for routes without internet." else "")
            }
            notifyChanged()
        }
    }

    /** Follows a route you chose in Route steps (instead of the one found automatically). */
    fun useRoute(r: Route) {
        if (target?.kind != NavKind.PLACE || r.steps.isEmpty()) return
        routeGen++
        routeBusy = false
        lastRouteRequestMs = now
        route = r; stepIndex = 0; spokenManeuvers.clear(); offRouteCount = 0
        stepLiveIndex = -1; stepLiveRemaining = null; lastRemaining = null
        routeMessage = "Your chosen route: ${Phrases.distance(r.distanceMeters)}, about ${Fmt.duration(r.durationSeconds)}."
        location.fix?.let { routeTick(it, r, smoothedSpeed, announceFirst = true) }
        notifyChanged()
    }

    /** Re-requests the route, e.g. after the travel mode changes. */
    fun refreshRoute() {
        if (target?.kind != NavKind.PLACE) return
        route = null
        location.fix?.let { maybeRequestRoute(it, force = true) }
    }


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
        // Offline routes carry their own complete wording ("take the second road on the right at the harbour").
        if (step.spokenAction.isNotBlank()) return step.spokenAction
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
        val gSide = lockedSide ?: r.steps.lastOrNull { it.destinationSide.isNotBlank() }?.destinationSide
        val arriveText = when (gSide) { "left", "right" -> "destination on your $gSide"; "ahead" -> "destination straight ahead"; else -> "arrive at destination" }
        val nowDist = if (driving) 25 else 8
        instructionText = if (following != null) {
            val man = maneuverText(following)
            when {
                man == "arrive" && d != null && d <= nowDist -> "Arriving, ${arriveText.removePrefix("arrive at ")}."
                man == "arrive" && d != null -> "In ${Phrases.distance(d.toDouble())}, $arriveText."
                d != null && d <= nowDist -> "Now ${man}."
                d != null -> "In ${Phrases.distance(d.toDouble())}, ${man}."
                else -> "Then ${man}."
            }
        } else {
            if (d != null) "In ${Phrases.distance(d.toDouble())}, $arriveText." else "Continue to destination."
        }
        current?.let { stepBearing(it) }?.let { b -> directionText = "The road goes towards ${Phrases.where(compass.heading, b).removePrefix("at ")}." }

        // ---- spoken guidance ----
        if (announceFirst) {
            val kind = "Route"
            speaker.speak((if (rerouted) "New route. " else "$kind ready, ${Phrases.distance(r.distanceMeters)}. ") + instructionText)
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
        // In a moving car or boat the phone is not being pointed; pointing vibration would mislead.
        if ((f.speedMps ?: 0.0) > 3.0) { if (pointing) { pointing = false; haptics.stopFocus() }; return }
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
