package mv.haluvi.navigation.ui

import android.view.View
import android.widget.Button
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.data.BusRoute
import mv.haluvi.navigation.data.BusStopHit
import mv.haluvi.navigation.nav.BuiltinMaps
import mv.haluvi.navigation.nav.Phrases

/**
 * Routes: the route type at the top (Bus, Ferry, My routes), a filter below it (area, atoll, Near me), then all
 * routes of that type and filter. Tools (which bus goes there, record, settings) at the bottom.
 */
class RoutesScreen : Screen() {
    private val nearButtons = ArrayList<Button>()
    private var nearTick = 0
    private var wasRiding = false

    private val type: String get() = AppGraph.settings.routeType.takeIf { it in TYPES } ?: "bus"

    private fun typeName(t: String) = when (t) { "ferry" -> "Ferry"; "mine" -> "My routes"; else -> "Bus" }

    private var filter: String
        get() = AppGraph.settings.let { s -> when (type) { "ferry" -> s.routeFilterFerry; "mine" -> s.routeFilterMine; else -> s.routeFilterBus } }
        set(v) { AppGraph.settings.let { s -> when (type) { "ferry" -> s.routeFilterFerry = v; "mine" -> s.routeFilterMine = v; else -> s.routeFilterBus = v } } }

    private fun busRoutes() = AppGraph.busRoutes.all().filter { it.kind == BusRoute.KIND_BUS && it.builtin }
    private fun ferryRoutes() = AppGraph.busRoutes.all().filter { it.isBoat && it.builtin }

    /** Atoll indexes a ferry route stops in. */
    private fun atollsOf(r: BusRoute): Set<Int> = r.stops.mapNotNull { stopIsland(it)?.atollIndex?.takeIf { i -> i >= 0 } }.toSet()

    /** (filter id, label) choices for the current type. */
    private fun filters(): List<Pair<String, String>> {
        val g = AppGraph
        return when (type) {
            "ferry" -> {
                val idx = ferryRoutes().filter { it.isFerry }.flatMap { atollsOf(it) }.toSortedSet()
                listOf("all" to "All", "near" to "Near me", "male" to "Male' ferries: Vilimale, Gulhifalhu, Thilafushi") +
                    idx.map { "atoll:$it" to g.islands.atolls[it].fullName }
            }
            "mine" -> listOf("all" to "All", "bus" to "Bus", "ferry" to "Ferry and boat", "other" to "Other")
            else -> listOf("all" to "All areas", "near" to "Near me") +
                busRoutes().map { it.area }.distinct().sortedWith(compareBy<String>({ if (it == "Greater Male") 0 else 1 }, { prettyArea(it) }))
                    .map { "area:$it" to prettyArea(it) }
        }
    }

    private fun filterLabel(): String = filters().firstOrNull { it.first == filter }?.second ?: "All"

    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Routes")
        PlacePick.handler = null
        nearButtons.clear()
        wasRiding = g.routeTrip.active
        if (filters().none { it.first == filter }) filter = "all"
        g.routeTrip.route?.let { r -> ui.button("Riding: ${r.name}. Open", color = UiColors.BUTTON_ON) { host.show { RouteTripScreen() } } }
        if (g.routeRecorder.active) ui.button("Recording: ${g.routeRecorder.name}. Open", color = UiColors.BUTTON_ON) { host.show { RecordRouteScreen() } }

        ui.button("Route type: ${typeName(type)}", color = UiColors.BUTTON_ON) {
            val next = TYPES[(TYPES.indexOf(type) + 1) % TYPES.size]
            g.settings.routeType = next
            host.say(typeName(next)); rebuild()
        }
        ui.button("Filter: ${filterLabel()}") {
            host.show { ChoiceScreen("Filter: ${typeName(type)}", null, filters().map { (id, label) ->
                (label + if (id == filter) ", selected" else "") to { filter = id; host.back() }
            }) }
        }

        when {
            type != "mine" && filter == "near" -> nearList(ui)
            type == "ferry" -> routeList(ui, ferryRoutes().filter { r ->
                when {
                    filter == "male" -> r.kind == BusRoute.KIND_VESSEL
                    filter.startsWith("atoll:") -> r.isFerry && atollsOf(r).contains(filter.removePrefix("atoll:").toIntOrNull() ?: -1)
                    else -> true
                }
            }, "ferry routes")
            type == "mine" -> myList(ui)
            else -> routeList(ui, busRoutes().filter { r -> !filter.startsWith("area:") || r.area == filter.removePrefix("area:") }, "bus routes")
        }

        ui.heading("More")
        ui.button("Which bus or ferry goes to a place?") {
            host.show { ChoosePlaceScreen("Where do you want to go?") { loc -> host.show { BusesToPlaceScreen(loc.name, loc.lat, loc.lon) } } }
        }
        ui.button("Which ferry goes to an island?") {
            host.show { AtollPickerScreen { isl -> host.show { BusesToPlaceScreen(isl.name, isl.lat, isl.lon, isl.key) } } }
        }
        if (type == "mine") ui.button("New route") {
            host.show { TextInputScreen("New route", "Route name", "") { name ->
                val r = mv.haluvi.navigation.data.TripRoute(mv.haluvi.navigation.data.TripRoute.newId(), name, ArrayList())
                g.routes.add(r)
                host.backTo(this@RoutesScreen); host.show { RouteEditScreen(r.id) }
            } }
        }
        if (g.routeRecorder.active) ui.button("Recording ${g.routeRecorder.name}: open to finish") { host.show { RecordRouteScreen() } }
        else ui.button("Record a route on a ride") {
            if (!host.hasLocationPermission()) { host.requestPermissionsIfNeeded(); return@button }
            fun record(bus: Boolean) {
                host.show { TextInputScreen("Record a route", "Route name", "") { name ->
                    g.routeRecorder.start(name, bus)
                    host.backTo(this@RoutesScreen); host.show { RecordRouteScreen() }
                } }
            }
            host.show { ChoiceScreen("Record a route on a ride", null, listOf(
                "A bus route: bus stops are added by themselves" to { record(true) },
                "Another route, for example a boat: you save each stop" to { record(false) }
            )) }
        }
        ui.button("Route settings") { host.show { RouteSettingsScreen() } }
        if (g.busRoutes.deletedCount() > 0) ui.button("Bring back ${g.busRoutes.deletedCount()} deleted routes") {
            g.busRoutes.restore(null, BuiltinMaps.busRoutesJson(), BuiltinMaps.rtlJson()); host.speak("Brought back."); rebuild()
        }
        ui.info(RTL_CREDIT)
        ui.button("Back") { host.back() }
        return scroll
    }

    private fun routeList(ui: UiBuilder, list: List<BusRoute>, what: String) {
        ui.heading("${list.size} $what")
        list.forEach { r ->
            ui.button("${routeTitle(r)}, ${r.stops.size} stops" + if (r.edited) ", changed by you" else "") { host.show { BusRouteScreen(r.id) } }
        }
    }

    private fun myList(ui: UiBuilder) {
        val g = AppGraph
        val mineBus = g.busRoutes.all().filter { !it.builtin || it.edited }.filter { r ->
            when (filter) { "bus" -> r.kind == BusRoute.KIND_BUS; "ferry" -> r.isBoat; "other" -> false; else -> true }
        }
        val trips = g.routes.all().sortedBy { it.name.lowercase() }.filter { t ->
            val boat = t.stops.any { it.isIsland }
            when (filter) { "bus" -> false; "ferry" -> boat; "other" -> !boat; else -> true }
        }
        ui.heading("${mineBus.size + trips.size} of my routes")
        if (mineBus.isEmpty() && trips.isEmpty()) ui.info("None yet. Make one with New route, record one on a ride, or change a bus or ferry route.")
        mineBus.forEach { r ->
            ui.button("${routeTitle(r)}, ${r.stops.size} stops, " + if (r.builtin) "changed by you" else "recorded by you") { host.show { BusRouteScreen(r.id) } }
        }
        trips.forEach { t -> ui.button("${t.name}, ${t.stops.size} stops") { host.show { RouteEditScreen(t.id) } } }
    }

    // Near me: routes with a stop near you, nearest first; refreshed every 10 seconds in place (focus stays).

    private fun nearHits(): List<BusStopHit>? {
        val f = AppGraph.location.fix ?: return null
        val kinds = if (type == "ferry") setOf(BusRoute.KIND_FERRY, BusRoute.KIND_VESSEL) else setOf(BusRoute.KIND_BUS)
        return AppGraph.busRoutes.near(f.lat, f.lon, 500.0, 2000.0, kinds)
    }

    private fun hitLabel(h: BusStopHit): String {
        val t = nextTimes(h.stop).takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""
        return "${routeTitle(h.route)}: ${stopName(h.route, h.stop)}${distText(h.stop.lat, h.stop.lon)}$t"
    }

    private fun nearList(ui: UiBuilder) {
        val hits = nearHits()
        val word = if (type == "ferry") "ferry" else "bus"
        if (hits == null) { ui.info("Waiting for GPS, to find the stops near you."); return }
        if (hits.isEmpty()) { ui.info(nothingNear(word)); return }
        ui.heading("${hits.size} $word routes near me, nearest first")
        hits.forEach { h -> nearButtons.add(ui.button(hitLabel(h)) { host.show { BusRouteScreen(h.route.id, h.index) } }) }
    }

    /** "No bus stops within 500 meters. The nearest is Maaveyo Maizaan, on R10, 1.2 kilometers, 3 o'clock." */
    private fun nothingNear(word: String): String {
        val f = AppGraph.location.fix ?: return "No $word stops near you."
        val far = AppGraph.busRoutes.near(f.lat, f.lon, 1e8, 1e8, if (word == "ferry") setOf(BusRoute.KIND_FERRY, BusRoute.KIND_VESSEL) else setOf(BusRoute.KIND_BUS)).firstOrNull()
            ?: return "No $word routes."
        val within = if (word == "ferry") "2 kilometers" else "500 meters"
        return "No $word stops within $within. The nearest is ${stopName(far.route, far.stop)}, on ${routeTitle(far.route)}" +
            ", ${Phrases.distance(far.distance)}, ${Phrases.where(AppGraph.compass.heading, Geo.bearing(f.lat, f.lon, far.stop.lat, far.stop.lon))}."
    }

    override fun onSecond() {
        if (AppGraph.routeTrip.active != wasRiding) { rebuild(); return }
        if (type == "mine" || filter != "near") return
        if (++nearTick < 10) return
        nearTick = 0
        val hits = nearHits() ?: return
        if (hits.size != nearButtons.size) { rebuild(); return }
        hits.forEachIndexed { i, h ->
            val b = nearButtons[i]
            b.text = hitLabel(h)
            b.setOnClickListener { host.show { BusRouteScreen(h.route.id, h.index) } }
        }
    }

    companion object {
        val TYPES = listOf("bus", "ferry", "mine")
    }
}
