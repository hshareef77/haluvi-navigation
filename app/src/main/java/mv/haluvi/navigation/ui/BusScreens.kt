package mv.haluvi.navigation.ui

import android.view.View
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.data.BusRoute
import mv.haluvi.navigation.data.BusStop
import mv.haluvi.navigation.data.Island
import mv.haluvi.navigation.data.RouteStop
import mv.haluvi.navigation.data.SavedLocation
import mv.haluvi.navigation.data.TripRoute
import mv.haluvi.navigation.nav.BuiltinMaps
import mv.haluvi.navigation.nav.Phrases
import java.util.Calendar
import java.util.Locale

const val RTL_CREDIT = "Bus and ferry data: RTL (Raajje Transport Link). Times can change."

fun distText(lat: Double, lon: Double): String {
    val f = AppGraph.location.fix ?: return ""
    return ", " + Phrases.distance(Geo.distance(f.lat, f.lon, lat, lon)) +
        ", " + Phrases.where(AppGraph.compass.heading, Geo.bearing(f.lat, f.lon, lat, lon))
}

private fun simple(s: String) = s.lowercase().replace(Regex("[^a-z]"), "")

/** The island of a ferry stop: by its key, else by name among the other islands (airports, industrial islands). */
fun stopIsland(s: BusStop): Island? {
    val g = AppGraph
    g.islands.byKey(s.island)?.let { return it }
    g.shapes.extras.firstOrNull { it.key == s.island }?.let { return it }
    val n = simple(s.name.substringAfter('.'))
    return g.shapes.extras.firstOrNull { simple(it.name) == n }
}

/** Spoken stop name: the island for ferry stops ("Dhidhoo" for "Ha.Dhihdhoo"). */
fun stopName(r: BusRoute, s: BusStop): String =
    if (r.isFerry) stopIsland(s)?.name ?: s.name.substringAfter('.').trim() else s.name

/** "R10 Orchid", "Ferry S1C1: Thuraakunu to Dhidhoo", "Vilimale Ferry". */
fun routeTitle(r: BusRoute): String =
    if (r.isFerry && r.stops.size >= 2) "Ferry ${r.ref}: ${stopName(r, r.stops.first())} to ${stopName(r, r.stops.last())}" else r.title

private fun kindWord(r: BusRoute) = if (r.isBoat) "ferry" else "bus"

/** The next times at a stop: "next at 14:15, 14:30 and 14:45", or "" when RTL gives no times. */
fun nextTimes(s: BusStop, count: Int = 3): String {
    if (s.times.isEmpty()) return ""
    val c = Calendar.getInstance()
    val now = String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    val next = s.times.filter { it >= now }.take(count)
    return if (next.isEmpty()) "no more today, first at ${s.times.first()}"
    else "next at " + if (next.size == 1) next[0] else next.dropLast(1).joinToString(", ") + " and " + next.last()
}

private fun stopLabel(r: BusRoute, i: Int, s: BusStop): String =
    "${i + 1}. ${stopName(r, s)}" + (s.details.takeIf { it.isNotBlank() }?.let { ", $it" } ?: "")

/** Walking to a stop: the saved bus stop place there, or the stop itself. */
fun goToBusStop(host: MainActivity, r: BusRoute, s: BusStop) {
    val near = AppGraph.store.nearest(s.lat, s.lon, category = "bus").firstOrNull()?.takeIf { it.distance <= 30 }?.loc
    goToPlace(host, near ?: SavedLocation(SavedLocation.newId(), stopName(r, s), s.lat, s.lon, 0.0, categoryId = if (r.isBoat) "ferry" else "bus"))
}

/** The part of a route from stop [on] to stop [off], to travel with. Ferry stops are islands (with their outlines). */
fun busTrip(r: BusRoute, on: Int, off: Int): TripRoute =
    TripRoute("bus:${r.id}:$on", routeTitle(r), r.stops.subList(on, off + 1).map { s ->
        if (r.isFerry) {
            val isl = stopIsland(s)
            if (isl != null) RouteStop(isl.name, isl.lat, isl.lon, RouteStop.KIND_ISLAND, isl.key)
            else RouteStop(stopName(r, s), s.lat, s.lon, RouteStop.KIND_ISLAND, "")
        } else RouteStop(s.name, s.lat, s.lon, RouteStop.KIND_PLACE)
    }.toMutableList())

/** Starts a ride from stop [on] to stop [off], announcing every stop. */
fun startBusRide(host: MainActivity, r: BusRoute, on: Int, off: Int) {
    if (!host.hasLocationPermission()) { host.requestPermissionsIfNeeded(); return }
    if (off <= on) { host.speak("Choose a stop after the one where you get on."); return }
    AppGraph.routeTrip.start(busTrip(r, on, off), off - on, announceAll = true)
    host.show { RouteTripScreen() }
}

/** Where do you get on, then where do you get off. [off] is already chosen when coming from a place. */
fun rideBus(host: MainActivity, r: BusRoute, off: Int? = null) {
    val f = AppGraph.location.fix
    val last = (off ?: r.stops.size) - 1
    if (last < 0) { host.speak("No stops before your stop on this route."); return }
    val nearest = f?.let { fx -> (0..last).minByOrNull { Geo.distance(fx.lat, fx.lon, r.stops[it].lat, r.stops[it].lon) } }
    fun chooseOff(on: Int) {
        if (off != null) { startBusRide(host, r, on, off); return }
        host.show {
            ChoiceScreen("Where do you get off?", "Getting on at ${stopName(r, r.stops[on])}.", (on + 1 until r.stops.size).map { i ->
                "${stopLabel(r, i, r.stops[i])}, ${i - on} stops" to { startBusRide(host, r, on, i) }
            })
        }
    }
    fun timesOf(i: Int) = nextTimes(r.stops[i]).takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""
    val choices = ArrayList<Pair<String, () -> Unit>>()
    nearest?.let { n -> choices.add("Nearest stop: ${stopName(r, r.stops[n])}${distText(r.stops[n].lat, r.stops[n].lon)}${timesOf(n)}" to { chooseOff(n) }) }
    (0..last).forEach { i -> choices.add(stopLabel(r, i, r.stops[i]) + distText(r.stops[i].lat, r.stops[i].lon) + timesOf(i) to { chooseOff(i) }) }
    host.show { ChoiceScreen("Where do you get on?", "${routeTitle(r)}." + (off?.let { " Your stop: ${stopName(r, r.stops[it])}." } ?: ""), choices) }
}

/** "Greater Malé", "Kulhudhuffushi", "Addu City" for RTL area names. */
fun prettyArea(a: String): String = when {
    a.isBlank() -> "Other"
    a == "Greater Male" -> "Greater Malé"
    else -> a.substringAfter('.').trim().replace("Kulhudhufushi", "Kulhudhuffushi")
}

/** One bus or ferry route: Start or Stop, stops, times, more options. [nearIndex]: the stop near you (from Near me). */
class BusRouteScreen(private val id: String, private val nearIndex: Int? = null) : Screen() {
    private var wasActive = false
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        val r = g.busRoutes.byId(id)
        if (r == null) { titleView = ui.title("Route not found"); ui.button("Back") { host.back() }; return scroll }
        titleView = ui.title(routeTitle(r))
        val info = arrayListOf("From ${stopName(r, r.stops.first())} to ${stopName(r, r.stops.last())}, ${r.stops.size} stops.")
        if (r.area.isNotBlank() && !r.isFerry) info.add("${prettyArea(r.area)}.")
        if (r.fare.isNotBlank()) info.add("Fare ${r.fare.removeSuffix(".0")} rufiyaa.")
        info.add(when { !r.builtin -> "Made by you."; r.edited -> "Changed by you."; r.fromRtl -> "From RTL."; else -> "From OpenStreetMap." })
        ui.info(info.joinToString(" "))
        val near = nearIndex?.takeIf { it in r.stops.indices }
        val active = g.routeTrip.busRouteId == r.id
        wasActive = active
        if (active) ui.button("Stop ride", color = UiColors.BUTTON_STOP) { g.routeTrip.stop(); rebuild() }
        else if (r.stops.size >= 2) ui.button("Start" + (near?.takeIf { it < r.stops.size - 1 }?.let { ", getting on at ${stopName(r, r.stops[it])}" } ?: ""), color = UiColors.BUTTON_ON) {
            if (!host.hasLocationPermission()) { host.requestPermissionsIfNeeded(); return@button }
            confirmSwitchRide(host, routeTitle(r)) {
                if (near != null && near < r.stops.size - 1) host.show {
                    ChoiceScreen("Where do you get off?", "Getting on at ${stopName(r, r.stops[near])}.", (near + 1 until r.stops.size).map { i ->
                        "${stopLabel(r, i, r.stops[i])}, ${i - near} stops" to { startBusRide(host, r, near, i) }
                    })
                } else rideBus(host, r)
            }
        }
        if (near != null) ui.button("Go to the stop: ${stopName(r, r.stops[near])}" + distText(r.stops[near].lat, r.stops[near].lon)) { goToBusStop(host, r, r.stops[near]) }
        ui.button("Stops (${r.stops.size})") { host.show { BusStopsScreen(id) } }
        val ts = r.stops[near ?: 0]
        if (ts.times.isNotEmpty()) ui.button("Times at ${stopName(r, ts)}: ${nextTimes(ts)}") {
            host.show { TextScreen("Times at ${stopName(r, ts)}", listOf(ts.times.joinToString(", "), RTL_CREDIT)) }
        }
        val self = this
        ui.button("More options") {
            val opts = ArrayList<Pair<String, () -> Unit>>()
            opts.add("Rename route" to {
                host.show { TextInputScreen("Rename route", "Route name", r.title) { n -> r.name = n; r.ref = ""; g.busRoutes.changed(r); host.speak("Saved."); host.backTo(self) } }
            })
            if (!r.isFerry) opts.add("Add a stop at the end" to { host.backTo(self); addStopAt(host, r, r.stops.size) })
            if (r.builtin && r.edited) opts.add("Undo my changes" to {
                host.show { ConfirmScreen("Undo your changes to ${routeTitle(r)}?", "Undo changes") {
                    g.busRoutes.restore(r.id, BuiltinMaps.busRoutesJson(), BuiltinMaps.rtlJson()); host.speak("Restored."); host.backTo(self)
                } }
            })
            opts.add("Delete route" to {
                host.show { ConfirmScreen("Delete the route ${routeTitle(r)}?", "Delete") {
                    if (g.routeTrip.busRouteId == r.id) g.routeTrip.stop(silent = true)
                    g.busRoutes.delete(r.id); host.speak("Deleted."); host.backTo(self); host.back()
                } }
            })
            host.show { ChoiceScreen("More options", routeTitle(r), opts) }
        }
        if (r.fromRtl) ui.info(RTL_CREDIT)
        ui.button("Back") { host.back() }
        return scroll
    }

    override fun onSecond() { if ((AppGraph.routeTrip.busRouteId == id) != wasActive) rebuild() }
}

/** The stops of a bus or ferry route, with distance and next times. */
class BusStopsScreen(private val id: String) : Screen() {
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        val r = g.busRoutes.byId(id)
        if (r == null) { titleView = ui.title("Route not found"); ui.button("Back") { host.back() }; return scroll }
        titleView = ui.title("Stops of ${routeTitle(r)}: ${r.stops.size}")
        r.stops.forEachIndexed { i, s ->
            val t = nextTimes(s, 2).takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""
            ui.button(stopLabel(r, i, s) + distText(s.lat, s.lon) + t) { host.show { BusStopScreen(id, i) } }
        }
        ui.button("Back") { host.back() }
        return scroll
    }
}

/** Adds a stop to [r] at position [index]: here, or a place. */
fun addStopAt(host: MainActivity, r: BusRoute, index: Int) {
    val g = AppGraph
    fun add(s: BusStop) {
        r.stops.add(index.coerceIn(0, r.stops.size), s); g.busRoutes.changed(r)
        host.speak("Added ${s.name}, stop ${index + 1}."); host.back()
    }
    host.show {
        ChoiceScreen("Add a stop", "It becomes stop ${index + 1}.", listOf(
            "Here, where I am" to {
                val f = g.location.fix
                if (f == null || f.accuracy > 40) host.speak("GPS is not accurate enough. Try again in an open area.")
                else {
                    val near = g.store.nearest(f.lat, f.lon, category = "bus").firstOrNull()?.takeIf { it.distance <= 40 }?.loc
                    if (near != null) add(BusStop(near.name, near.lat, near.lon))
                    else host.show { TextInputScreen("Stop name", "Stop name", "") { n -> host.back(); add(BusStop(n, f.lat, f.lon)) } }
                }
            },
            "Choose a bus stop or place" to {
                host.show { ChoosePlaceScreen("Choose the stop", startCategory = "bus") { loc -> host.back(); add(BusStop(loc.name, loc.lat, loc.lon)) } }
            }
        ))
    }
}

class BusStopScreen(private val routeId: String, private val index: Int) : Screen() {
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        val r = g.busRoutes.byId(routeId)
        val s = r?.stops?.getOrNull(index)
        if (r == null || s == null) { titleView = ui.title("Stop not found"); ui.button("Back") { host.back() }; return scroll }
        titleView = ui.title(stopLabel(r, index, s))
        ui.info("${routeTitle(r)}, stop ${index + 1} of ${r.stops.size}" + distText(s.lat, s.lon) + "." +
            (nextTimes(s, 5).takeIf { it.isNotBlank() }?.let { " Times: $it." } ?: "") +
            (s.dv.takeIf { it.isNotBlank() }?.let { " Dhivehi name: $it." } ?: ""))
        fun done(msg: String) { g.busRoutes.changed(r); host.speak(msg); host.back() }
        ui.button("Go to this stop", color = UiColors.BUTTON_ON) { goToBusStop(host, r, s) }
        if (index < r.stops.size - 1) ui.button("Ride from here") {
            host.show {
                ChoiceScreen("Where do you get off?", null, (index + 1 until r.stops.size).map { i ->
                    "${stopLabel(r, i, r.stops[i])}, ${i - index} stops" to { startBusRide(host, r, index, i) }
                })
            }
        }
        if (s.times.isNotEmpty()) ui.button("All times at this stop: ${s.times.size}") {
            host.show { TextScreen("Times at ${stopName(r, s)}", listOf(s.times.joinToString(", "), RTL_CREDIT)) }
        }
        ui.button("Rename") {
            host.show { TextInputScreen("Rename stop", "Stop name", s.name) { n -> s.name = n; g.busRoutes.changed(r); host.speak("Saved."); host.back(); host.back() } }
        }
        if (!r.isFerry) {
            ui.button("Add a stop after this one") { addStopAt(host, r, index + 1) }
            ui.button("Move this stop to my location") {
                val f = g.location.fix
                if (f == null || f.accuracy > 30) host.speak("GPS is not accurate enough. Try again in an open area.")
                else { s.lat = f.lat; s.lon = f.lon; done("Stop moved to your location.") }
            }
        }
        ui.button("Remove this stop", color = UiColors.BUTTON_STOP) {
            host.show { ConfirmScreen("Remove ${stopName(r, s)} from ${routeTitle(r)}?", "Remove") { r.stops.removeAt(index); g.busRoutes.changed(r); host.speak("Removed."); host.back(); host.back() } }
        }
        ui.button("Back") { host.back() }
        return scroll
    }
}

/**
 * Which bus or ferry goes to a place (or to an island, [islandKey]): routes with a stop near it, and where to get on
 * near you.
 */
class BusesToPlaceScreen(private val name: String, private val lat: Double, private val lon: Double,
                         private val islandKey: String? = null) : Screen() {
    private data class Option(val r: BusRoute, val on: Int?, val onD: Double, val off: Int, val offD: Double)

    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title((if (islandKey != null) "Ferries to " else "Buses and ferries to ") + name)
        val f = g.location.fix
        val options = ArrayList<Option>()
        for (r in g.busRoutes.all()) {
            if (islandKey != null && !r.isFerry) continue
            val offs = if (islandKey != null) r.stops.indices.filter { stopIsland(r.stops[it])?.key == islandKey }.map { mv.haluvi.navigation.data.BusStopHit(r, it, 0.0) }
                else g.busRoutes.stopsNear(r, lat, lon, if (r.isBoat) 2500.0 else 500.0)
            if (offs.isEmpty()) continue
            val ons = f?.let { g.busRoutes.stopsNear(r, it.lat, it.lon, if (r.isBoat) 3000.0 else 800.0) }.orEmpty()
            var best: Option? = null
            for (a in ons) for (b in offs) {
                if (b.index <= a.index) continue
                val o = Option(r, a.index, a.distance, b.index, b.distance)
                if (best == null || o.onD + o.offD < best.onD + best.offD) best = o
            }
            options.add(best ?: Option(r, null, 0.0, offs[0].index, offs[0].distance))
        }
        options.sortWith(compareBy({ it.on == null }, { it.onD + it.offD }))
        if (options.isEmpty()) ui.info(if (islandKey != null) "No RTL ferry route stops at $name." else "No bus or ferry stops near $name.")
        if (f == null) ui.info("Waiting for GPS, to find the stop near you.")
        options.forEach { o ->
            val r = o.r
            val offStop = r.stops[o.off]
            val label = if (o.on != null) {
                val onStop = r.stops[o.on]
                val t = nextTimes(onStop, 2).takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""
                "${routeTitle(r)}: get on at ${stopName(r, onStop)}, ${Phrases.distance(o.onD)} from you$t. " +
                    "Get off at ${stopName(r, offStop)}, ${o.off - o.on} stops later" + (if (o.offD > 0) ", ${Phrases.distance(o.offD)} from $name." else ".")
            } else "${routeTitle(r)}: stops at ${stopName(r, offStop)}" + (if (o.offD > 0) ", ${Phrases.distance(o.offD)} from $name" else "") + ". Not from near you, going this way."
            ui.button(label) {
                if (o.on != null) host.show {
                    ChoiceScreen(routeTitle(r), null, listOf(
                        "Ride now, from ${stopName(r, r.stops[o.on])}" to { startBusRide(host, r, o.on, o.off) },
                        "First walk to ${stopName(r, r.stops[o.on])}" to { goToBusStop(host, r, r.stops[o.on]) },
                        "Choose another stop to get on" to { rideBus(host, r, o.off) },
                        "Route details" to { host.show { BusRouteScreen(r.id) } }
                    ))
                } else rideBus(host, r, o.off)
            }
        }
        ui.info(RTL_CREDIT)
        ui.button("Back") { host.back() }
        return scroll
    }
}
