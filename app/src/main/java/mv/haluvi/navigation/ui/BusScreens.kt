package mv.haluvi.navigation.ui

import android.view.View
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.data.BusRoute
import mv.haluvi.navigation.data.BusStop
import mv.haluvi.navigation.data.SavedLocation
import mv.haluvi.navigation.nav.BuiltinMaps
import mv.haluvi.navigation.nav.Phrases

private fun distText(lat: Double, lon: Double): String {
    val f = AppGraph.location.fix ?: return ""
    return ", " + Phrases.distance(Geo.distance(f.lat, f.lon, lat, lon)) +
        ", " + Phrases.where(AppGraph.compass.heading, Geo.bearing(f.lat, f.lon, lat, lon))
}

private fun busStopLabel(i: Int, s: BusStop): String = "${i + 1}. ${s.name}" + (s.details.takeIf { it.isNotBlank() }?.let { ", $it" } ?: "")

/** Walking to a bus stop: the saved bus stop place there, or the stop itself. */
fun goToBusStop(host: MainActivity, s: BusStop) {
    val near = AppGraph.store.nearest(s.lat, s.lon, category = "bus").firstOrNull()?.takeIf { it.distance <= 30 }?.loc
    goToPlace(host, near ?: SavedLocation(SavedLocation.newId(), s.name, s.lat, s.lon, 0.0, categoryId = "bus"))
}

/** Starts a bus ride from stop [on] to stop [off], announcing every stop. */
fun startBusRide(host: MainActivity, r: BusRoute, on: Int, off: Int) {
    if (!host.hasLocationPermission()) { host.requestPermissionsIfNeeded(); return }
    if (off <= on) { host.speak("Choose a stop after the one where you get on."); return }
    val g = AppGraph
    g.routeTrip.start(g.busRoutes.trip(r, on, off), off - on, announceAll = true)
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
            ChoiceScreen("Where do you get off?", "Got on at ${r.stops[on].name}.", (on + 1 until r.stops.size).map { i ->
                "${busStopLabel(i, r.stops[i])}, ${i - on} stops" to { startBusRide(host, r, on, i) }
            })
        }
    }
    val choices = ArrayList<Pair<String, () -> Unit>>()
    nearest?.let { n -> choices.add("Nearest stop: ${r.stops[n].name}${distText(r.stops[n].lat, r.stops[n].lon)}" to { chooseOff(n) }) }
    (0..last).forEach { i -> choices.add(busStopLabel(i, r.stops[i]) + distText(r.stops[i].lat, r.stops[i].lon) to { chooseOff(i) }) }
    host.show { ChoiceScreen("Where do you get on?", "${r.title}." + (off?.let { " Your stop: ${r.stops[it].name}." } ?: ""), choices) }
}

/** Bus routes: from the built-in maps (OpenStreetMap) and your own. */
class BusRoutesScreen : Screen() {
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Bus routes")
        g.routeTrip.route?.let { r -> ui.button("Riding: ${r.name}. Open", color = UiColors.BUTTON_ON) { host.show { RouteTripScreen() } } }
        if (g.routeRecorder.active) ui.button("Recording: ${g.routeRecorder.name}. Open", color = UiColors.BUTTON_ON) { host.show { RecordRouteScreen() } }
        val all = g.busRoutes.all()
        ui.button("Which bus goes to a place?", color = UiColors.BUTTON_ON) {
            host.show { ChoosePlaceScreen("Where do you want to go?") { loc -> host.show { BusesToPlaceScreen(loc.name, loc.lat, loc.lon) } } }
        }
        val f = g.location.fix
        if (f != null) {
            val near = g.busRoutes.near(f.lat, f.lon, 500.0)
            ui.heading(if (near.isEmpty()) "No bus stops of a route within 500 meters" else "Buses near me")
            near.forEach { h ->
                ui.button("${h.route.title}: ${h.stop.name}${distText(h.stop.lat, h.stop.lon)}") { host.show { BusRouteScreen(h.route.id) } }
            }
        }
        ui.heading("All bus routes: ${all.size}")
        if (all.isEmpty()) ui.info("No bus routes yet. They come with the built-in maps. You can also record one on a ride.")
        all.forEach { r -> ui.button("${r.title}, ${r.stops.size} stops" + if (r.edited || !r.fromOsm) ", changed by you" else "") { host.show { BusRouteScreen(r.id) } } }
        ui.button("Record a bus route on a ride") {
            if (!host.hasLocationPermission()) { host.requestPermissionsIfNeeded(); return@button }
            if (g.routeRecorder.active) { host.show { RecordRouteScreen() }; return@button }
            host.show { TextInputScreen("Record a bus route", "Route name, for example R3 Hulhumale", "") { name ->
                g.routeRecorder.start(name, bus = true)
                host.backTo(this@BusRoutesScreen); host.show { RecordRouteScreen() }
            } }
        }
        if (g.busRoutes.deletedCount() > 0) ui.button("Bring back ${g.busRoutes.deletedCount()} deleted map routes") {
            g.busRoutes.restoreFromMap(null, BuiltinMaps.busRoutesJson()); host.speak("Brought back."); rebuild()
        }
        ui.info("Bus routes come from OpenStreetMap with the built-in maps, and are updated with them. Routes you change or record are kept. Stops may be missing or old: you can fix them. Map data: OpenStreetMap contributors.")
        ui.button("Back") { host.back() }
        return scroll
    }
}

/** One bus route: ride it, its stops, fix it. */
class BusRouteScreen(private val id: String) : Screen() {
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        val r = g.busRoutes.byId(id)
        if (r == null) { titleView = ui.title("Route not found"); ui.button("Back") { host.back() }; return scroll }
        titleView = ui.title(r.title)
        val info = arrayListOf<String>()
        if (r.from.isNotBlank() || r.to.isNotBlank()) info.add("From ${r.from.ifBlank { r.stops.first().name }} to ${r.to.ifBlank { r.stops.last().name }}.")
        info.add("${r.stops.size} stops.")
        if (r.operator.isNotBlank()) info.add("Operator: ${r.operator}.")
        info.add(if (!r.fromOsm) "Made by you." else if (r.edited) "From OpenStreetMap, changed by you." else "From OpenStreetMap.")
        ui.info(info.joinToString(" "))
        if (r.stops.size >= 2) ui.button("Ride this bus", color = UiColors.BUTTON_ON) { rideBus(host, r) }
        ui.heading("Stops")
        r.stops.forEachIndexed { i, s -> ui.button(busStopLabel(i, s) + distText(s.lat, s.lon)) { host.show { BusStopScreen(id, i) } } }
        ui.button("Add a stop at the end") { addStopAt(host, r, r.stops.size) }
        ui.button("Rename route") {
            host.show { TextInputScreen("Rename route", "Route name", r.title) { n -> r.name = n; r.ref = ""; g.busRoutes.changed(r); host.speak("Saved."); host.back() } }
        }
        if (r.fromOsm && r.edited) ui.button("Undo my changes, use the map again") {
            host.show { ConfirmScreen("Undo your changes to ${r.title}?", "Undo changes") {
                g.busRoutes.restoreFromMap(r.id, BuiltinMaps.busRoutesJson()); host.speak("Map version restored."); host.back()
            } }
        }
        ui.button("Delete route", color = UiColors.BUTTON_STOP) {
            host.show { ConfirmScreen("Delete the bus route ${r.title}?", "Delete") {
                g.busRoutes.delete(r.id); host.speak("Deleted."); host.back(); host.back()
            } }
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
        titleView = ui.title(busStopLabel(index, s))
        ui.info("${r.title}, stop ${index + 1} of ${r.stops.size}" + distText(s.lat, s.lon) + ".")
        fun done(msg: String) { g.busRoutes.changed(r); host.speak(msg); host.back() }
        ui.button("Go to this stop", color = UiColors.BUTTON_ON) { goToBusStop(host, s) }
        if (index < r.stops.size - 1) ui.button("Ride from here") {
            host.show {
                ChoiceScreen("Where do you get off?", null, (index + 1 until r.stops.size).map { i ->
                    "${busStopLabel(i, r.stops[i])}, ${i - index} stops" to { startBusRide(host, r, index, i) }
                })
            }
        }
        ui.button("Rename") {
            host.show { TextInputScreen("Rename stop", "Stop name", s.name) { n -> s.name = n; g.busRoutes.changed(r); host.speak("Saved."); host.back(); host.back() } }
        }
        ui.button("Add a stop after this one") { addStopAt(host, r, index + 1) }
        ui.button("Move this stop to my location") {
            val f = g.location.fix
            if (f == null || f.accuracy > 30) host.speak("GPS is not accurate enough. Try again in an open area.")
            else { s.lat = f.lat; s.lon = f.lon; done("Stop moved to your location.") }
        }
        ui.button("Remove this stop", color = UiColors.BUTTON_STOP) {
            host.show { ConfirmScreen("Remove ${s.name} from ${r.title}?", "Remove") { r.stops.removeAt(index); g.busRoutes.changed(r); host.speak("Removed."); host.back(); host.back() } }
        }
        ui.button("Back") { host.back() }
        return scroll
    }
}

/** Which bus goes to a place: routes with a stop near it, and where to get on near you. */
class BusesToPlaceScreen(private val name: String, private val lat: Double, private val lon: Double) : Screen() {
    private data class Option(val r: BusRoute, val on: Int?, val onD: Double, val off: Int, val offD: Double)

    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Buses to $name")
        val f = g.location.fix
        val options = ArrayList<Option>()
        for (r in g.busRoutes.all()) {
            val offs = g.busRoutes.stopsNear(r, lat, lon, 500.0)
            if (offs.isEmpty()) continue
            val ons = f?.let { g.busRoutes.stopsNear(r, it.lat, it.lon, 800.0) }.orEmpty()
            var best: Option? = null
            for (a in ons) for (b in offs) {
                if (b.index <= a.index) continue
                val o = Option(r, a.index, a.distance, b.index, b.distance)
                if (best == null || o.onD + o.offD < best.onD + best.offD) best = o
            }
            options.add(best ?: Option(r, null, 0.0, offs[0].index, offs[0].distance))
        }
        options.sortWith(compareBy({ it.on == null }, { it.onD + it.offD }))
        if (options.isEmpty()) ui.info("No bus route stops within 500 meters of $name.")
        if (f == null) ui.info("Waiting for GPS, to find the stop near you.")
        options.forEach { o ->
            val r = o.r
            val offStop = r.stops[o.off]
            val label = if (o.on != null) {
                val onStop = r.stops[o.on]
                "${r.title}: get on at ${onStop.name}, ${Phrases.distance(o.onD)} from you. Get off at ${offStop.name}, ${o.off - o.on} stops later, ${Phrases.distance(o.offD)} from $name."
            } else "${r.title}: stop ${offStop.name} is ${Phrases.distance(o.offD)} from $name. No stop of this route near you, going this way."
            ui.button(label) {
                if (o.on != null) host.show {
                    ChoiceScreen(r.title, null, listOf(
                        "Ride now, from ${r.stops[o.on].name}" to { startBusRide(host, r, o.on, o.off) },
                        "First walk to ${r.stops[o.on].name}" to { goToBusStop(host, r.stops[o.on]) },
                        "Choose another stop to get on" to { rideBus(host, r, o.off) },
                        "Route details" to { host.show { BusRouteScreen(r.id) } }
                    ))
                } else rideBus(host, r, o.off)
            }
        }
        ui.button("Back") { host.back() }
        return scroll
    }
}
