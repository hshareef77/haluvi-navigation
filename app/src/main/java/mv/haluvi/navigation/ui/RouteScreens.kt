package mv.haluvi.navigation.ui

import android.view.View
import android.widget.TextView
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.data.Island
import mv.haluvi.navigation.data.RouteStop
import mv.haluvi.navigation.data.TripRoute
import mv.haluvi.navigation.nav.Phrases
import mv.haluvi.navigation.nav.stopDistance

/** A screen with one text field and Save. */
class TextInputScreen(private val title: String, private val label: String, private val initial: String,
                      private val onSave: (String) -> Unit) : Screen() {
    override fun create(): View {
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title(title)
        val e = ui.edit(label, initial)
        ui.button("Save", color = UiColors.BUTTON_ON) {
            val t = e.text.toString().trim()
            if (t.isEmpty()) host.speak("Type a name first.") else onSave(t)
        }
        ui.button("Back") { host.back() }
        return scroll
    }
}

private fun stopLabel(i: Int, s: RouteStop) = "${i + 1}. ${s.name}" + if (s.isIsland) ", island" else ""

/** Routes: travel with a route, make one, record one while riding. */
class RoutesScreen : Screen() {
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Routes")
        PlacePick.handler = null
        g.routeTrip.route?.let { r -> ui.button("Travelling: ${r.name}. Open", color = UiColors.BUTTON_ON) { host.show { RouteTripScreen() } } }
        if (g.routeRecorder.active) ui.button("Recording: ${g.routeRecorder.name}. Open", color = UiColors.BUTTON_ON) { host.show { RecordRouteScreen() } }
        ui.info("A route is a bus line or ferry route with its stops in order. Stops can be places or islands. While you travel, stops are announced, and your own stop is warned before it comes.")
        val list = g.routes.all().sortedBy { it.name.lowercase() }
        if (list.isNotEmpty()) ui.heading("My routes")
        list.forEach { r -> ui.button("${r.name}, ${r.stops.size} stops") { host.show { RouteEditScreen(r.id) } } }
        ui.button("New route") {
            host.show { TextInputScreen("New route", "Route name, for example Male bus R1", "") { name ->
                val r = TripRoute(TripRoute.newId(), name, ArrayList())
                g.routes.add(r)
                host.backTo(this@RoutesScreen); host.show { RouteEditScreen(r.id) }
            } }
        }
        if (!g.routeRecorder.active) ui.button("Record route while riding") {
            if (!host.hasLocationPermission()) { host.requestPermissionsIfNeeded(); return@button }
            host.show { TextInputScreen("Record route", "Route name", "") { name ->
                g.routeRecorder.start(name)
                host.backTo(this@RoutesScreen); host.show { RecordRouteScreen() }
            } }
        }
        ui.button("Route settings") { host.show { RouteSettingsScreen() } }
        ui.button("Back") { host.back() }
        return scroll
    }
}

class RouteSettingsScreen : Screen() {
    override fun create(): View {
        val s = AppGraph.settings
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Route settings")
        ui.button("Announce: ${if (s.routeAnnounceAll) "every stop" else "only my stop"}") {
            s.routeAnnounceAll = !s.routeAnnounceAll
            host.say(if (s.routeAnnounceAll) "Every stop" else "Only my stop"); rebuild()
        }
        ui.button("Warn before my stop: ${Phrases.distance(s.routeWarnMeters.toDouble())}") {
            host.show(Pickers.meters(host, "Warn before my stop", listOf(100, 200, 300, 400, 600, 800), s.routeWarnMeters) { s.routeWarnMeters = it })
        }
        ui.info("You also hear \"Your stop is next\" when the stop before yours is reached.")
        ui.button("Back") { host.back() }
        return scroll
    }
}

/** One route: travel with it, edit its stops, add place or island stops. */
class RouteEditScreen(private val id: String) : Screen() {
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        PlacePick.handler = null
        val r = g.routes.byId(id)
        if (r == null) { titleView = ui.title("Route not found"); ui.button("Back") { host.back() }; return scroll }
        titleView = ui.title(r.name)
        if (r.stops.size >= 2) ui.button("Travel with this route", color = UiColors.BUTTON_ON) {
            if (!host.hasLocationPermission()) { host.requestPermissionsIfNeeded(); return@button }
            host.show {
                ChoiceScreen("Where do you get off?", null, r.stops.mapIndexed { i, s ->
                    stopLabel(i, s) to { g.routeTrip.start(r, i); host.backTo(this@RouteEditScreen); host.show { RouteTripScreen() } }
                })
            }
        } else ui.info("Add at least 2 stops.")
        ui.heading("Stops: ${r.stops.size}")
        r.stops.forEachIndexed { i, s -> ui.button(stopLabel(i, s)) { host.show { StopOptionsScreen(id, i) } } }
        ui.button("Add place stop") { addPlaceStop(r) }
        ui.button("Add island stop") {
            host.show { AtollPickerScreen { isl -> addStop(r, islandStop(isl)); host.backTo(this@RouteEditScreen) } }
        }
        ui.button("Rename route") {
            host.show { TextInputScreen("Rename route", "Route name", r.name) { n -> r.name = n; g.routes.save(); host.speak("Saved."); host.back() } }
        }
        ui.button("Delete route", color = UiColors.BUTTON_STOP) {
            host.show { ConfirmScreen("Delete the route ${r.name}?", "Delete") {
                if (g.routeTrip.route?.id == r.id) g.routeTrip.stop(silent = true)
                g.routes.delete(r.id); host.speak("Deleted."); host.back(); host.back()
            } }
        }
        ui.button("Back") { host.back() }
        return scroll
    }

    private fun addStop(r: TripRoute, s: RouteStop) {
        r.stops.add(s); AppGraph.routes.save()
        host.speak("Added ${s.name}, stop ${r.stops.size}.")
    }

    private fun addPlaceStop(r: TripRoute) {
        val pick: (mv.haluvi.navigation.data.SavedLocation) -> Unit = { loc ->
            addStop(r, RouteStop(loc.name, loc.lat, loc.lon, RouteStop.KIND_PLACE))
            host.backTo(this@RouteEditScreen)
        }
        host.show {
            ChoiceScreen("Add place stop", null, listOf(
                "Choose a place" to { host.show { ChoosePlaceScreen("Choose stop", onPick = pick) } },
                "What's around me" to { PlacePick.handler = pick; host.show { AroundMeScreen() } },
                "This location" to {
                    val f = AppGraph.location.fix
                    if (f == null || f.accuracy > 50) host.speak("GPS is not ready. Try again in an open area.")
                    else host.show { TextInputScreen("This location", "Stop name", "") { n ->
                        addStop(r, RouteStop(n, f.lat, f.lon, RouteStop.KIND_PLACE)); host.backTo(this@RouteEditScreen)
                    } }
                }
            ))
        }
    }
}

fun islandStop(isl: Island) = RouteStop(isl.name, isl.lat, isl.lon, RouteStop.KIND_ISLAND, isl.key)

class StopOptionsScreen(private val routeId: String, private val index: Int) : Screen() {
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        val r = g.routes.byId(routeId)
        val s = r?.stops?.getOrNull(index)
        if (r == null || s == null) { titleView = ui.title("Stop not found"); ui.button("Back") { host.back() }; return scroll }
        titleView = ui.title(stopLabel(index, s))
        g.location.fix?.let { f -> ui.info("${Phrases.distance(stopDistance(s, f.lat, f.lon))} from you.") }
        fun done(msg: String) { g.routes.save(); host.speak(msg); host.back() }
        if (index > 0) ui.button("Move up") { r.stops.add(index - 1, r.stops.removeAt(index)); done("Moved up.") }
        if (index < r.stops.size - 1) ui.button("Move down") { r.stops.add(index + 1, r.stops.removeAt(index)); done("Moved down.") }
        ui.button("Rename") {
            host.show { TextInputScreen("Rename stop", "Stop name", s.name) { n -> s.name = n; g.routes.save(); host.speak("Saved."); host.back(); host.back() } }
        }
        if (!s.isIsland) ui.button("Move this stop to my location") {
            val f = g.location.fix
            if (f == null || f.accuracy > 30) host.speak("GPS is not accurate enough. Try again in an open area.")
            else { s.lat = f.lat; s.lon = f.lon; done("Stop moved to your location.") }
        }
        ui.button("Delete stop", color = UiColors.BUTTON_STOP) { r.stops.removeAt(index); done("Stop deleted.") }
        ui.button("Back") { host.back() }
        return scroll
    }
}

/** While travelling with a route. */
class RouteTripScreen : Screen() {
    private var next: TextView? = null
    private var last: TextView? = null
    private var wasActive = false

    override fun create(): View {
        val trip = AppGraph.routeTrip
        val (scroll, ui) = UiBuilder.scrolling(host)
        val r = trip.route
        wasActive = r != null
        if (r == null) {
            titleView = ui.title(trip.lastMessage.ifBlank { "Route stopped" })
            next = null; last = null
            ui.button("Home") { host.showHome() }
            return scroll
        }
        titleView = ui.title("Route: ${r.name}")
        next = ui.info(trip.repeatText())
        last = ui.info(if (trip.lastMessage.isBlank()) "Last: nothing yet." else "Last: ${trip.lastMessage}")
        ui.button("Repeat") { host.speak(trip.repeatText()) }
        ui.button("Where am I") { whereAmI(host) }
        ui.button("Stop route", color = UiColors.BUTTON_STOP) { trip.stop(); rebuild() }
        ui.button("Back") { host.back() }
        return scroll
    }

    override fun onSecond() {
        val trip = AppGraph.routeTrip
        if (trip.active != wasActive) { rebuild(); return }
        next?.text = trip.repeatText()
        last?.text = if (trip.lastMessage.isBlank()) "Last: nothing yet." else "Last: ${trip.lastMessage}"
    }
}

/** Recording a route while riding: save or skip each stop, then finish. */
class RecordRouteScreen : Screen() {
    private var status: TextView? = null
    private var hadCandidate = false

    override fun create(): View {
        val g = AppGraph
        val rec = g.routeRecorder
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Recording: ${rec.name}")
        status = ui.info(rec.status)
        val c = rec.candidate
        hadCandidate = c != null
        val f = c ?: g.location.fix
        if (f != null) {
            val near = g.store.nearest(f.lat, f.lon).firstOrNull()?.takeIf { it.distance <= 60 }?.loc?.name
            val placeName = near ?: "Stop ${rec.stops.size + 1}"
            ui.button((if (c != null) "Save stop: " else "Add stop here: ") + placeName, color = if (c != null) UiColors.BUTTON_ON else UiColors.BUTTON) {
                rec.addStop(RouteStop(placeName, f.lat, f.lon, RouteStop.KIND_PLACE)); host.speak("Saved $placeName."); rebuild()
            }
            val isl = g.shapes.nearby(f.lat, f.lon, 5000.0).firstOrNull { it.shore <= 500 }?.island
            if (isl != null) ui.button("Save island stop: ${isl.name}") {
                rec.addStop(islandStop(isl)); host.speak("Saved ${isl.name}."); rebuild()
            }
            ui.button("Save with a name") {
                host.show { TextInputScreen("Stop name", "Stop name", near ?: "") { n ->
                    rec.addStop(RouteStop(n, f.lat, f.lon, RouteStop.KIND_PLACE)); host.speak("Saved $n."); host.back()
                } }
            }
            if (c != null) ui.button("Skip, not a stop") { rec.skip(); rebuild() }
        } else ui.info("Waiting for GPS.")
        if (rec.stops.isNotEmpty()) {
            ui.heading("Stops: ${rec.stops.size}")
            rec.stops.forEachIndexed { i, s -> ui.info(stopLabel(i, s)) }
        }
        ui.button("Finish and save route", color = UiColors.BUTTON_ON) {
            val r = rec.finish()
            if (r == null) host.speak("No stops saved. Nothing to keep.")
            else { g.routes.add(r); host.speak("Route ${r.name} saved with ${r.stops.size} stops.") }
            host.back()
        }
        ui.button("Cancel recording", color = UiColors.BUTTON_STOP) {
            host.show { ConfirmScreen("Stop recording and throw away the stops?", "Cancel recording") { rec.finish(); host.back(); host.back() } }
        }
        ui.button("Back") { host.back() }
        return scroll
    }

    override fun onSecond() {
        val rec = AppGraph.routeRecorder
        if (!rec.active) return
        if ((rec.candidate != null) != hadCandidate) { rebuild(); return }
        status?.text = rec.status
    }
}
