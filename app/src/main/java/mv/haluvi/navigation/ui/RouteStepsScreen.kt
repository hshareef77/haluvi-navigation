package mv.haluvi.navigation.ui

import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.Fmt
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.core.LatLon
import mv.haluvi.navigation.data.SavedLocation
import mv.haluvi.navigation.nav.Destination
import mv.haluvi.navigation.nav.OfflineRouter
import mv.haluvi.navigation.net.Route
import mv.haluvi.navigation.net.RouteStep
import kotlin.math.roundToInt

/** "about 120 meters" (rounded to 5 m below 100 m, to 10 m below 1 km). */
private fun about(m: Double): String {
    if (m >= 1000) return "about " + Fmt.meters(m)
    val r = if (m < 100) ((m / 5).roundToInt() * 5).coerceAtLeast(5) else (m / 10).roundToInt() * 10
    return "about $r meters"
}

private fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

/** One step in simple words: "Turn right onto Chandhanee Magu. Go straight about 80 meters. You pass 2 side roads." */
fun simpleStep(r: Route, i: Int, destName: String): String {
    val s = r.steps[i]
    if (s.maneuver == "ARRIVE" || i == r.steps.size - 1 && s.spokenAction == "arrive") {
        return "Arrive at $destName" + if (s.destinationSide.isNotBlank()) ", on your ${s.destinationSide}." else "."
    }
    val head = when {
        i == 0 && s.spokenAction.startsWith("head") -> "Start" + (if (s.roadName.isNotBlank()) " on ${s.roadName}" else "") +
            ", heading " + s.spokenAction.removePrefix("head").trim()
        s.spokenAction.isNotBlank() -> s.spokenAction.replaceFirstChar { it.uppercase() }
        else -> s.instruction
    }
    val go = if (s.distanceMeters >= 3) " Go straight ${about(s.distanceMeters)}." else ""
    val side = if (s.sideRoads > 0) " You pass ${plural(s.sideRoads, "side road")}." else ""
    return "$head.$go$side"
}

/** "450 meters, about 6 minutes, 2 turns, 1 main road to cross, mostly on Majeedhee Magu." */
fun routeSummary(r: Route): String {
    val parts = arrayListOf(Fmt.meters(r.distanceMeters), "about ${Fmt.duration(r.durationSeconds)}")
    if (r.source == "Offline") {
        parts.add(if (r.turns == 0) "no turns" else plural(r.turns, "turn"))
        if (r.crossings > 0) parts.add("${plural(r.crossings, "main road")} to cross")
        if (r.mainRoad.isNotBlank()) parts.add("mostly on ${r.mainRoad}")
    } else parts.add("from Google")
    return parts.joinToString(", ") + "."
}

/** Named places within 20 m of a step, in order along it. */
private fun placesAlong(s: RouteStep): List<String> {
    val line = s.polyline
    if (line.size < 2) return emptyList()
    val pad = 0.0003
    val s0 = line.minOf { it.lat } - pad; val n0 = line.maxOf { it.lat } + pad
    val w0 = line.minOf { it.lon } - pad; val e0 = line.maxOf { it.lon } + pad
    val hits = AppGraph.store.items.asSequence()
        .filter { it.lat in s0..n0 && it.lon in w0..e0 && !it.name.startsWith("Unnamed") }
        .filter { Geo.distanceToPolyline(it.lat, it.lon, line) <= 20 }
        .map { loc -> loc to line.indices.minByOrNull { Geo.distance(loc.lat, loc.lon, line[it].lat, line[it].lon) }!! }
        .sortedBy { it.second }
        .map { it.first.name }.distinct().take(8).toList()
    return hits
}

/**
 * Route steps: the whole route in simple words before or during the walk, with up to 3 routes (easiest first).
 * During navigation ([forNavigation]) the chosen route is followed with "Use this route"; from the Places screen
 * "Start with this route" starts navigation on it.
 */
class RouteStepsScreen(
    private val dest: SavedLocation,
    private val fromLat: Double?,
    private val fromLon: Double?,
    private val forNavigation: Boolean
) : Screen() {
    private var routes: List<Route>? = null
    private var selected = 0
    private var busy = false

    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Route steps to ${dest.name}")
        val list = routes
        if (list == null) {
            ui.info(if (busy) "Finding routes." else "Waiting for GPS.")
            if (!busy) find()
            ui.button("Back") { host.back() }
            return scroll
        }
        if (list.isEmpty()) {
            ui.info("No road route here. The map has no roads that connect these places. Navigation guides by direction instead.")
            ui.button("Back") { host.back() }
            return scroll
        }
        val r = list[selected.coerceIn(0, list.size - 1)]
        val label = r.label.ifBlank { if (r.source == "Offline") "" else "Google" }
        if (list.size > 1) ui.button("Route ${selected + 1} of ${list.size}: $label", color = UiColors.BUTTON_ON) {
            selected = (selected + 1) % list.size
            val n = list[selected]
            host.say("Route ${selected + 1}: ${n.label}. ${routeSummary(n)}"); rebuild()
        } else ui.info("Only one route.")
        ui.info(routeSummary(r))
        val nav = g.nav
        val inUse = forNavigation && nav.route === r
        if (inUse) ui.info("You are at step ${nav.stepIndex + 1} of ${r.steps.size}.")
        r.steps.indices.forEach { i ->
            val text = simpleStep(r, i, dest.name)
            ui.button("${i + 1}. $text") {
                val along = placesAlong(r.steps[i])
                host.speak("Step ${i + 1} of ${r.steps.size}. $text" +
                    (r.steps[i].roadName.takeIf { it.isNotBlank() }?.let { " Road: $it." } ?: "") +
                    (if (along.isEmpty()) " No saved places along this step." else " Places along this step: ${along.joinToString(", ")}."))
            }
        }
        when {
            inUse -> ui.info("Navigation is following this route.")
            forNavigation -> ui.button("Use this route", color = UiColors.BUTTON_ON) {
                if (!nav.isActive) { host.speak("Navigation has stopped."); return@button }
                nav.useRoute(r); host.speak("Using route ${selected + 1}."); host.back()
            }
            else -> ui.button("Start with this route", color = UiColors.BUTTON_ON) {
                if (!host.hasLocationPermission()) { host.requestPermissionsIfNeeded(); return@button }
                nav.start(Destination.of(dest)); nav.useRoute(r); host.showNavigation()
            }
        }
        ui.button("Find routes again") { routes = null; find(); rebuild() }
        ui.button("Back") { host.back() }
        return scroll
    }

    private fun find() {
        val g = AppGraph
        val f = g.location.fix
        val lat = fromLat ?: f?.lat ?: return
        val lon = fromLon ?: f?.lon ?: return
        busy = true
        host.scope.launch {
            var found = withContext(Dispatchers.Default) {
                runCatching { OfflineRouter.routes(LatLon(lat, lon), LatLon(dest.lat, dest.lon), g.settings.travelMode, 3) }.getOrDefault(emptyList())
            }
            // No offline roads: show the route navigation already has (Google), if any.
            if (found.isEmpty() && forNavigation) g.nav.route?.let { found = listOf(it) }
            busy = false; routes = found; selected = 0
            if (found.size > 1) host.speak("${found.size} routes. Route 1, ${found[0].label}: ${routeSummary(found[0])}")
            rebuild()
        }
    }

    override fun onSecond() { if (routes == null && !busy && AppGraph.location.fix != null) { find(); rebuild() } }
}
