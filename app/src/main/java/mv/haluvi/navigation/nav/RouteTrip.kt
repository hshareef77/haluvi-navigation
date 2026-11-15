package mv.haluvi.navigation.nav

import android.content.Context
import android.os.SystemClock
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.data.RouteStop
import mv.haluvi.navigation.data.TripRoute
import mv.haluvi.navigation.feedback.TripAlerts
import mv.haluvi.navigation.sensors.Fix
import java.util.concurrent.CopyOnWriteArrayList

/** Distance to a stop: to the shore for an island stop with an outline (0 when on it), otherwise to the point. */
fun stopDistance(s: RouteStop, lat: Double, lon: Double): Double {
    val shapes = AppGraph.shapes
    if (s.isIsland && s.islandKey.isNotBlank() && shapes.has(s.islandKey)) {
        if (shapes.inside(s.islandKey, lat, lon)) return 0.0
        val ring = shapes.ring(s.islandKey) ?: return Geo.distance(lat, lon, s.lat, s.lon)
        return Geo.distanceToPolyline(lat, lon, ring + ring.first())
    }
    val d = Geo.distance(lat, lon, s.lat, s.lon)
    return if (s.isIsland) maxOf(0.0, d - 300) else d
}

/**
 * Travelling with a route: announces the next stop, approaching, stopping at and passing each stop
 * (place stops by distance and the vehicle slowing down; island stops by the island outline), warns before
 * your own stop and announces arrival there. Works in the background with notifications (Trip alerts).
 * Stops are taken strictly in order, so close stops or a street used twice do not confuse it.
 */
class RouteTrip(private val context: Context) {
    private class Limits(val approach: Double, val at: Double, val passMin: Double, val awayStep: Double, val slow: Double)
    private val placeLimits = Limits(approach = 250.0, at = 40.0, passMin = 90.0, awayStep = 25.0, slow = 1.5)
    private val islandLimits = Limits(approach = 1500.0, at = 150.0, passMin = 1500.0, awayStep = 100.0, slow = 2.0)
    private fun limits(s: RouteStop) = if (s.isIsland) islandLimits else placeLimits

    var route: TripRoute? = null; private set
    var targetIndex = 0; private set
    var nextIndex = 0; private set
    var lastMessage = ""; private set
    val active: Boolean get() = route != null
    val activeRouteName: String? get() = route?.name

    private var approached = false
    private var minD = Double.MAX_VALUE
    private var away = 0
    private var slowCount = 0
    private var offRouteMs = 0L
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val fixListener: (Fix) -> Unit = { f -> runCatching { onFix(f) } }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { listeners.forEach { runCatching { it() } } }

    val nextStop: RouteStop? get() = route?.stops?.getOrNull(nextIndex)
    val targetStop: RouteStop? get() = route?.stops?.getOrNull(targetIndex)
    val stopsLeft: Int get() = (targetIndex - nextIndex + 1).coerceAtLeast(0)

    /** Starts travelling on [r], getting off at stop [target]. */
    fun start(r: TripRoute, target: Int) {
        stop(silent = true)
        if (r.stops.isEmpty()) return
        route = r
        targetIndex = target.coerceIn(0, r.stops.size - 1)
        nextIndex = startIndex(r, AppGraph.location.fix)
        resetStop()
        AppGraph.location.addListener(fixListener)
        AppGraph.location.acquire()
        NavigationService.ensureRunning(context)
        val t = r.stops[targetIndex]
        val text = if (nextIndex > targetIndex) "You are already at your stop, ${t.name}."
        else buildString {
            append("Route ${r.name}. Your stop: ${t.name}, ")
            append(if (stopsLeft == 1) "the next stop." else "$stopsLeft stops away. Next stop: ${r.stops[nextIndex].name}.")
        }
        say(text, alert = false)
        if (nextIndex > targetIndex) { stop(silent = true); lastMessage = text }
        changed()
    }

    fun stop(silent: Boolean = false) {
        if (route == null) return
        route = null
        AppGraph.location.removeListener(fixListener)
        AppGraph.location.release()
        if (!silent) AppGraph.speaker.speak("Route stopped.")
        NavigationService.updateOrStop(context)
        changed()
    }

    fun repeatText(): String {
        val r = route ?: return lastMessage.ifBlank { "No route." }
        val next = nextStop ?: return "No route."
        val t = targetStop ?: return "No route."
        val f = AppGraph.location.fix
        val dist = f?.let { " ${Phrases.distance(stopDistance(next, it.lat, it.lon))} away." } ?: ""
        return if (nextIndex == targetIndex) "Your stop is next: ${t.name}.$dist"
        else "Next stop: ${next.name}.$dist Your stop: ${t.name}, $stopsLeft stops away. Route ${r.name}."
    }

    /** Where to begin: at a stop you are standing at, or the next stop ahead of you. */
    private fun startIndex(r: TripRoute, f: Fix?): Int {
        if (f == null) return 0
        val last = targetIndex
        var best = 0; var bestD = Double.MAX_VALUE
        for (i in 0..last) {
            val d = stopDistance(r.stops[i], f.lat, f.lon)
            if (d < bestD) { bestD = d; best = i }
        }
        val s = r.stops[best]
        if (bestD <= limits(s).at * 2) return best + 1 // at this stop now: the next one comes next
        if (best < last) {
            // Already past the nearest stop? Then you are closer to the following stop than that stop is.
            val n = r.stops[best + 1]
            val gap = Geo.distance(s.lat, s.lon, n.lat, n.lon)
            if (stopDistance(n, f.lat, f.lon) < gap) return best + 1
        }
        return best
    }

    private fun resetStop() { approached = false; minD = Double.MAX_VALUE; away = 0; slowCount = 0 }

    private fun say(text: String, alert: Boolean = true) {
        lastMessage = text
        AppGraph.speaker.speak(text, flush = false)
        if (alert) TripAlerts.alert(context, text)
    }

    private fun onFix(f: Fix) {
        val r = route ?: return
        if (f.accuracy > 60) return
        val s = r.stops.getOrNull(nextIndex) ?: return
        val lim = limits(s)
        val settings = AppGraph.settings
        val isTarget = nextIndex == targetIndex
        val speed = f.speedMps ?: 0.0
        val d = stopDistance(s, f.lat, f.lon)
        if (d < minD) { minD = d; away = 0 } else if (d > minD + lim.awayStep) away++

        // Approaching (your own stop: earlier, with the warning distance).
        val approachAt = if (isTarget) (if (s.isIsland) 2000.0 else settings.routeWarnMeters.toDouble()) else lim.approach
        if (!approached && d <= approachAt && (speed >= 1.5 || d <= approachAt / 2)) {
            approached = true
            if (isTarget) {
                say("Your stop is coming: ${s.name}." + if (s.isIsland) "" else " Press the bell.")
                AppGraph.haptics.tick()
            } else if (settings.routeAnnounceAll) say("Approaching ${s.name}.", alert = false)
        }

        // Stopped at the stop.
        if (d <= lim.at && speed < lim.slow) slowCount++ else if (d > lim.at) slowCount = 0
        if (slowCount >= 2) {
            if (isTarget) { arrive(s); return }
            if (settings.routeAnnounceAll) say("Stopping at ${s.name}.")
            advance(); return
        }
        // Your stop reached without stopping (very close): still arrived.
        if (isTarget && !s.isIsland && d <= lim.at / 2) { arrive(s); return }

        // Passed without stopping.
        if (minD <= lim.passMin && away >= 2 && speed >= 2.0) {
            if (isTarget) {
                say("You passed your stop, ${s.name}. Tell the driver.")
                stop(silent = true); return
            }
            if (settings.routeAnnounceAll) say("Passed ${s.name}.", alert = false)
            advance(); return
        }

        // Missed by GPS: the following stop is clearly closer than this one.
        if (!isTarget) {
            val n = r.stops[nextIndex + 1]
            val d2 = stopDistance(n, f.lat, f.lon)
            if (d > lim.approach && d2 + limits(n).at < d && d2 <= limits(n).approach) {
                if (settings.routeAnnounceAll) say("Passed ${s.name}.", alert = false)
                advance(); return
            }
        }

        // Off the route (bus legs): far from the line between the last and the next stop.
        if (nextIndex > 0 && !s.isIsland) {
            val p = r.stops[nextIndex - 1]
            if (!p.isIsland) {
                val off = Geo.distanceToSegment(f.lat, f.lon, p.lat, p.lon, s.lat, s.lon)
                val nowMs = SystemClock.elapsedRealtime()
                if (off > 600 && nowMs - offRouteMs > 300_000) { offRouteMs = nowMs; say("You seem to be off the route.") }
            }
        }
        changed()
    }

    private fun advance() {
        val r = route ?: return
        nextIndex++
        resetStop()
        val n = r.stops.getOrNull(nextIndex) ?: run { stop(silent = true); return }
        if (nextIndex == targetIndex) say("Your stop is next: ${n.name}. Get ready.")
        else if (AppGraph.settings.routeAnnounceAll) say("Next stop: ${n.name}.", alert = false)
        changed()
    }

    private fun arrive(s: RouteStop) {
        say("Arrived at your stop: ${s.name}.")
        AppGraph.haptics.arrival()
        stop(silent = true)
    }
}

/**
 * Records a route while you ride: when the vehicle stops for 10 seconds after moving, the phone vibrates and
 * asks whether to save the stop (you decide: a traffic stop is not a bus stop).
 */
class RouteRecorder(private val context: Context) {
    var active = false; private set
    var name = ""
    val stops = ArrayList<RouteStop>()
    /** A stop the vehicle is standing at now, waiting for the user to save or skip. */
    var candidate: Fix? = null; private set
    var status = ""; private set
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var lastStopLat = Double.NaN
    private var lastStopLon = Double.NaN
    private var moved = false
    private var slowSinceMs = 0L
    private val fixListener: (Fix) -> Unit = { f -> runCatching { onFix(f) } }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { listeners.forEach { runCatching { it() } } }

    fun start(routeName: String) {
        if (active) return
        active = true; name = routeName; stops.clear(); candidate = null
        moved = false; slowSinceMs = 0; lastStopLat = Double.NaN; lastStopLon = Double.NaN
        status = "Recording. Stops are offered when the vehicle stops."
        AppGraph.location.addListener(fixListener)
        AppGraph.location.acquire()
        NavigationService.ensureRunning(context)
        AppGraph.speaker.speak("Recording route $routeName. When the vehicle stops, I will ask to save the stop.")
        changed()
    }

    fun finish(): TripRoute? {
        if (!active) return null
        active = false; candidate = null
        AppGraph.location.removeListener(fixListener)
        AppGraph.location.release()
        NavigationService.updateOrStop(context)
        changed()
        if (stops.isEmpty()) return null
        return TripRoute(TripRoute.newId(), name, stops.toMutableList())
    }

    fun skip() { candidate = null; status = "Skipped."; changed() }

    /** Saves a stop at the current position: a place stop, or [island] as an island stop. */
    fun addStop(stop: RouteStop) {
        stops.add(stop)
        lastStopLat = stop.lat; lastStopLon = stop.lon
        candidate = null; moved = false
        status = "${stops.size} stops saved. Last: ${stop.name}."
        changed()
    }

    private fun onFix(f: Fix) {
        if (f.accuracy > 40) return
        val speed = f.speedMps ?: 0.0
        val fromLast = if (lastStopLat.isNaN()) Double.MAX_VALUE else Geo.distance(lastStopLat, lastStopLon, f.lat, f.lon)
        if (speed >= 3.0 && fromLast >= 150) { moved = true; slowSinceMs = 0; if (candidate != null) { candidate = null; changed() } }
        if (!moved || candidate != null) return
        val nowMs = SystemClock.elapsedRealtime()
        if (speed < 0.8) {
            if (slowSinceMs == 0L) slowSinceMs = nowMs
            if (nowMs - slowSinceMs >= 10_000) {
                candidate = f
                status = "Stopped. Save as a stop?"
                AppGraph.haptics.arrival()
                AppGraph.speaker.speak("Stopped. Save as a stop?", flush = false)
                TripAlerts.alert(context, "Stopped. Open Haluvi to save the stop.")
                changed()
            }
        } else slowSinceMs = 0
    }
}
