package mv.haluvi.navigation.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.nav.Destination
import mv.haluvi.navigation.nav.NearbyItem
import mv.haluvi.navigation.nav.Surroundings
import mv.haluvi.navigation.nav.WhereAmI

/** Home: one simple list. */
class HomeScreen : Screen() {
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Haluvi Navigation")
        addPermissionWarning(host, ui)
        g.nav.target?.let { t ->
            ui.button("Going to ${t.name}. Open", color = UiColors.BUTTON_ON) { host.show { NavigationScreen() } }
        }
        ui.button("Where am I") { whereAmI(host) }
        ui.button("Places") { host.show { PlacesScreen() } }
        ui.button("Islands") { host.show { IslandsScreen() } }
        ui.button(if (g.explore.active) "Explore: On" else "Explore: Off") { host.show { ExploreScreen() } }
        ui.button("My places") { host.show { MyPlacesScreen() } }
        ui.button("Settings") { host.show { SettingsScreen() } }
        return scroll
    }

    override fun onLive() {
        // Navigation or Explore started/stopped elsewhere (arrival, notification): refresh labels.
        val navOn = AppGraph.nav.isActive
        if (navOn != lastNav || AppGraph.explore.active != lastExplore) { lastNav = navOn; lastExplore = AppGraph.explore.active; rebuild() }
    }

    private var lastNav = AppGraph.nav.isActive
    private var lastExplore = AppGraph.explore.active
}

fun addPermissionWarning(host: MainActivity, ui: UiBuilder) {
    if (!host.hasLocationPermission()) {
        ui.info("Location permission is needed.")
        ui.row("Allow location" to { host.requestPermissionsIfNeeded() }, "App settings" to { host.openAppSettings() })
    } else if (!AppGraph.location.isGpsEnabled()) {
        ui.info("Location is turned off.")
        ui.button("Turn on location") { host.openLocationSettings() }
    }
}

fun whereAmI(host: MainActivity) {
    host.speak("Checking.")
    host.scope.launch { host.speak(WhereAmI.describe()) }
}

/** While navigating: instruction, distance, direction; Repeat, What's in this direction, More details, Stop. */
class NavigationScreen : Screen() {
    private var instruction: TextView? = null
    private var distance: TextView? = null
    private var direction: TextView? = null
    private var islandLine: TextView? = null
    private var wasActive = false

    override fun create(): View {
        val g = AppGraph
        val nav = g.nav
        val (scroll, ui) = UiBuilder.scrolling(host)
        val t = nav.target
        wasActive = t != null
        if (t == null) {
            titleView = ui.title(nav.lastArrivalText.ifBlank { "Navigation stopped" })
            instruction = null; distance = null; direction = null; islandLine = null
            ui.button("Home") { host.showHome() }
            return scroll
        }
        titleView = ui.title("Going to ${t.name}")
        instruction = ui.info(nav.instructionText)
        distance = ui.info(nav.distanceText)
        direction = ui.info(nav.directionText)
        islandLine = if (t.kind == mv.haluvi.navigation.nav.NavKind.ISLAND) ui.info(g.islandWatcher.lastMessage.ifBlank { "Island stops will be announced." }) else null
        ui.row(
            "Repeat" to { host.speak(nav.repeatText()) },
            "What's in this direction" to { host.speak(Surroundings.inDirection()) }
        )
        ui.button("More details") { host.show { DetailsScreen() } }
        ui.button("Stop navigation", color = UiColors.BUTTON_STOP) { nav.stop(); host.showHome() }
        return scroll
    }

    override fun onLive() {
        val nav = AppGraph.nav
        if (nav.isActive != wasActive) { rebuild(); return }
        instruction?.text = nav.instructionText.ifBlank { "Starting." }
        distance?.text = nav.distanceText.ifBlank { "Distance: waiting for GPS." }
        direction?.text = nav.directionText.ifBlank { " " }
        islandLine?.text = AppGraph.islandWatcher.lastMessage.ifBlank { "Island stops will be announced." }
    }
}

class DetailsScreen : Screen() {
    private val views = ArrayList<TextView>()
    override fun create(): View {
        val nav = AppGraph.nav
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("More details")
        views.clear()
        lines().forEach { views.add(ui.info(it)) }
        if (nav.route != null) {
            ui.heading("Route steps")
            nav.routeStepLines().forEach { ui.info(it) }
        }
        ui.button("Back") { host.back() }
        return scroll
    }

    private fun lines(): List<String> {
        val nav = AppGraph.nav
        return listOf(nav.speedText, nav.altitudeText, nav.gpsText, nav.routeMessage).map { it.ifBlank { " " } }
    }

    override fun onLive() { lines().forEachIndexed { i, s -> views.getOrNull(i)?.text = s } }
}

class ExploreScreen : Screen() {
    private var status: TextView? = null
    private var road: TextView? = null
    private var last: TextView? = null

    override fun create(): View {
        val ex = AppGraph.explore
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Explore")
        ui.button(if (ex.active) "Stop Explore" else "Start Explore", color = if (ex.active) UiColors.BUTTON_STOP else UiColors.BUTTON_ON) {
            if (!host.hasLocationPermission()) { host.requestPermissionsIfNeeded(); return@button }
            ex.toggle(); rebuild()
        }
        if (ex.active) {
            status = ui.info(ex.statusText)
            road = ui.info(ex.roadText.ifBlank { "Road: not known yet." })
            last = ui.info(if (ex.lastSpoken.isBlank()) "Last: nothing yet." else "Last: ${ex.lastSpoken}")
        } else {
            status = null; road = null; last = null
            ui.info("Speaks your road, intersections and places as you pass them.")
        }
        if (!ex.hasMapRoads && ex.active) ui.info("For intersections, download the island map in My places.")
        ui.button("What's around me") { host.show { AroundMeScreen() } }
        ui.button("Name this road") { host.show { RoadNameScreen() } }
        ui.button("Back") { host.back() }
        return scroll
    }

    override fun onLive() {
        val ex = AppGraph.explore
        if ((status == null) == ex.active) { rebuild(); return }
        status?.text = ex.statusText
        road?.text = ex.roadText.ifBlank { "Road: not known yet." }
        last?.text = if (ex.lastSpoken.isBlank()) "Last: nothing yet." else "Last: ${ex.lastSpoken}"
    }
}

/** Speaks the nearest places with clock positions; tap one to go there. */
class AroundMeScreen : Screen() {
    private var items: List<NearbyItem> = emptyList()
    private var note: String? = null
    private var loading = false
    private var loaded = false
    private var job: Job? = null
    private var listBox: LinearLayout? = null

    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("What's around me")
        ui.row(
            "Search again" to { load() },
            "What's in this direction" to { host.speak(Surroundings.inDirection()) }
        )
        ui.button("Category: ${Surroundings.categoryLabel(g.settings.aroundCategory)}") {
            host.show {
                ChoiceScreen("Category", null, Surroundings.categories.map { (id, label) ->
                    (label + if (g.settings.aroundCategory == id) " - selected" else "") to { g.settings.aroundCategory = id; loaded = false; host.back() }
                })
            }
        }
        listBox = UiBuilder.vertical(host, 0)
        ui.root.addView(listBox)
        fillList()
        ui.button("Back") { host.back() }
        if (!loaded && !loading) load()
        return scroll
    }

    private fun fillList() {
        val box = listBox ?: return
        box.removeAllViews()
        val ui = UiBuilder(host, box)
        when {
            loading -> ui.info("Searching.")
            items.isEmpty() && loaded -> ui.info(listOfNotNull("Nothing found nearby.", note).joinToString(" "))
            else -> {
                val heading = AppGraph.compass.heading
                items.forEach { it2 ->
                    ui.button(mv.haluvi.navigation.nav.Phrases.place(it2.name, heading, it2.bearing, it2.distance)) {
                        goToPlace(host, it2.toSavedLocation())
                    }
                }
                note?.let { ui.info(it) }
            }
        }
    }

    private fun load() {
        job?.cancel()
        loading = true
        fillList()
        job = host.scope.launch {
            val (list, n) = Surroundings.aroundMe(AppGraph.settings.aroundCategory)
            items = list; note = n; loading = false; loaded = true
            if (host.currentScreen() === this@AroundMeScreen) fillList()
            host.speak(Surroundings.speakList(list, AppGraph.compass.heading, n))
        }
    }

    override fun onDispose() { job?.cancel(); loading = false }
}
