package mv.haluvi.navigation.ui

import android.view.View
import android.widget.TextView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.data.Island
import mv.haluvi.navigation.data.SavedLocation
import mv.haluvi.navigation.data.TravelMode
import mv.haluvi.navigation.nav.Destination
import mv.haluvi.navigation.nav.IslandShapeDownloader
import mv.haluvi.navigation.nav.NavKind

/** The destinations chosen on the Places and Islands screens (kept while the app runs). */
object Selection {
    var placeTo: SavedLocation? = null
    var islandTo: Island? = null
}

/** When set, the next place chosen in Search, What's around me or My places goes here (route stops). */
object PlacePick {
    var handler: ((SavedLocation) -> Unit)? = null
}

/** Sets the Places destination and opens the Places screen (used by every place picker). */
fun goToPlace(host: MainActivity, loc: SavedLocation) {
    // A route editor is choosing a stop: give it the place instead of going there.
    PlacePick.handler?.let { h -> PlacePick.handler = null; h(loc); return }
    Selection.placeTo = loc
    host.say("To: ${loc.name}")
    host.showTrip { PlacesScreen() }
}

/** Places: walking or driving in a city or on an island. From, To, Travel, Start. */
class PlacesScreen : Screen() {
    private var fromButton: android.widget.Button? = null
    private var toButton: android.widget.Button? = null
    private var tick = 0

    private fun fromLabel(): String {
        val from = AppGraph.store.byId(AppGraph.settings.placeFromId)
        return "From: " + (from?.let { "${it.name}, chosen" } ?: "my location, ${mv.haluvi.navigation.nav.WhereAmI.placeParts()}") + ". Tap to change"
    }

    private fun toLabel(): String {
        val to = Selection.placeTo ?: return "To: not chosen. Tap to choose"
        val from = AppGraph.store.byId(AppGraph.settings.placeFromId)
        return "To: ${to.name}, " + AppGraph.nav.previewText(Destination.of(to), from?.lat, from?.lon).trimEnd('.') + ". Tap to change"
    }

    override fun create(): View {
        val g = AppGraph
        val s = g.settings
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Places")
        addPermissionWarning(host, ui)
        val active = g.nav.target?.takeIf { it.kind == NavKind.PLACE }
        if (active != null) ui.button("Going to ${active.name}. Open", color = UiColors.BUTTON_ON) { host.showNavigation() }

        fromButton = ui.button(fromLabel()) {
            host.show { ChoosePlaceScreen("From", "My location" to { s.placeFromId = ""; host.say("From: my location"); host.backTo(this@PlacesScreen) }) { loc ->
                // A From place must be saved (it is remembered by its id).
                val saved = g.store.byId(loc.id) ?: g.store.findDuplicate(loc.name, loc.lat, loc.lon, loc.placeId) ?: loc.also { g.store.add(it) }
                s.placeFromId = saved.id; host.say("From: ${saved.name}"); host.backTo(this@PlacesScreen)
            } }
        }
        val to = Selection.placeTo
        toButton = ui.button(toLabel()) {
            host.show { ChoosePlaceScreen("To") { loc -> Selection.placeTo = loc; host.say("To: ${loc.name}"); host.backTo(this@PlacesScreen) } }
        }
        val fromPlace = g.store.byId(s.placeFromId)
        if (fromPlace != null && to != null) ui.button("Swap From and To") {
            val newFrom = g.store.byId(to.id) ?: g.store.findDuplicate(to.name, to.lat, to.lon, to.placeId) ?: to.also { g.store.add(it) }
            Selection.placeTo = fromPlace; s.placeFromId = newFrom.id
            host.say("From ${newFrom.name}, to ${fromPlace.name}"); rebuild()
        }
        ui.button("Travel: ${s.travelMode.label}") {
            s.travelMode = if (s.travelMode == TravelMode.WALKING) TravelMode.DRIVING else TravelMode.WALKING
            g.nav.refreshRoute()
            host.say("Travel: ${s.travelMode.label}")
            rebuild()
        }
        ui.button("Start", color = UiColors.BUTTON_ON) {
            val dest = Selection.placeTo
            when {
                !host.hasLocationPermission() -> host.requestPermissionsIfNeeded()
                dest == null -> { host.speak("Please choose where to go."); toButton?.performClick() }
                else -> { g.nav.start(Destination.of(dest)); host.showNavigation() }
            }
        }
        if (to != null && g.store.byId(to.id) == null && g.store.findDuplicate(to.name, to.lat, to.lon, to.placeId) == null) {
            ui.button("Save ${to.name} to my places") { g.store.add(to); host.speak("Saved."); rebuild() }
        }
        ui.button("Save this place, where I am") { host.show { SavePlaceScreen() } }
        ui.button("Back") { host.back() }
        return scroll
    }

    /** Labels follow you as you move (every 5 seconds, so screen readers are not interrupted all the time). */
    override fun onSecond() {
        if (tick++ % 5 != 0) return
        fromButton?.text = fromLabel()
        toButton?.text = toLabel()
    }
}


/** Islands: sea or air travel between islands. From, To, Travel, Start. */
class IslandsScreen : Screen() {
    private var fromButton: android.widget.Button? = null
    private var toButton: android.widget.Button? = null
    private var tick = 0

    private fun fromIsland(): Island? = AppGraph.islands.byKey(AppGraph.settings.islandFromKey)

    private fun islandName(i: Island) = if (i.type == "inhabited") i.fullName else i.spokenName

    private fun fromLabel(): String {
        val manual = fromIsland()
        return "From: " + (manual?.let { "${islandName(it)}, chosen" } ?: mv.haluvi.navigation.nav.WhereAmI.islandFromText()) + ". Tap to change"
    }

    private fun toLabel(): String {
        val to = Selection.islandTo ?: return "To: not chosen. Tap to choose"
        val from = fromIsland()
        return "To: ${islandName(to)}, " + AppGraph.nav.previewText(Destination.of(to), from?.lat, from?.lon).trimEnd('.') + ". Tap to change"
    }

    override fun create(): View {
        val g = AppGraph
        val s = g.settings
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Islands")
        addPermissionWarning(host, ui)
        val active = g.nav.target?.takeIf { it.kind == NavKind.ISLAND }
        if (active != null) ui.button("Going to ${active.name}. Open", color = UiColors.BUTTON_ON) { host.showNavigation() }

        fromButton = ui.button(fromLabel()) {
            host.show {
                ChoiceScreen("From", null, listOf(
                    "My island, by GPS" to { s.islandFromKey = ""; host.say("From: my island"); host.backTo(this@IslandsScreen) },
                    "Choose island" to { host.show { AtollPickerScreen { isl -> s.islandFromKey = isl.key; host.say("From: ${isl.name}"); host.backTo(this@IslandsScreen) } } }
                ))
            }
        }
        toButton = ui.button(toLabel()) {
            host.show { AtollPickerScreen { isl -> Selection.islandTo = isl; host.say("To: ${isl.name}"); host.backTo(this@IslandsScreen) } }
        }
        ui.button(if (s.islandTravelAir) "Travel: by air, plane or seaplane" else "Travel: by sea, ferry or boat") {
            s.islandTravelAir = !s.islandTravelAir
            host.say(if (s.islandTravelAir) "By air" else "By sea")
            rebuild()
        }
        ui.button("Start", color = UiColors.BUTTON_ON) {
            val dest = Selection.islandTo
            when {
                !host.hasLocationPermission() -> host.requestPermissionsIfNeeded()
                dest == null -> host.speak("Please choose the destination island.")
                else -> { g.nav.start(Destination.of(dest)); host.showNavigation() }
            }
        }
        islandAlertsToggle(this, ui)
        ui.button("Back") { host.back() }
        return scroll
    }

    override fun onSecond() {
        if (tick++ % 5 != 0) return
        fromButton?.text = fromLabel()
        toButton?.text = toLabel()
    }
}

/** Download of island outlines from OpenStreetMap, with a clear status, missing list and report. */
class IslandOutlinesScreen : Screen() {
    private var job: Job? = null
    private var downloading = false
    private var progressView: TextView? = null
    private var progress = ""

    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Island outlines")
        if (downloading) {
            progressView = ui.info(progress)
            ui.button("Cancel", color = UiColors.BUTTON_STOP) {
                job?.cancel(); mv.haluvi.navigation.net.Http.cancelAll()
                progress = "Stopping."; progressView?.text = progress; host.say("Stopping")
            }
            return scroll
        }
        progressView = null
        ui.info(if (g.shapes.isEmpty) "Outlines make island stop announcements exact. Download once; they then work offline."
            else IslandShapeDownloader.summary().message())
        // Show the last message only when it says something the summary above does not.
        if (progress.isNotBlank() && progress != IslandShapeDownloader.summary().message()) ui.info(progress)
        val missing = IslandShapeDownloader.missingNames()
        // Outlines are built into the app; downloading is only offered when some are missing.
        if (g.shapes.isEmpty || missing.isNotEmpty()) ui.button(if (g.shapes.isEmpty) "Download" else "Download missing", color = UiColors.BUTTON_ON) { download(full = false) }
        if (missing.isNotEmpty() && !g.shapes.isEmpty) {
            ui.heading("Islands without an outline: ${missing.size}")
            missing.take(50).forEach { ui.info(it) }
        }
        if (!g.shapes.isEmpty) {
            ui.button("Share island report") { share(IslandShapeDownloader.report()) }
        }
        ui.button("Back") { host.back() }
        return scroll
    }

    private fun share(text: String) {
        val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_SUBJECT, "Haluvi island outline report")
            .putExtra(android.content.Intent.EXTRA_TEXT, text)
        runCatching { host.startActivity(android.content.Intent.createChooser(i, "Share island report")) }
    }

    private fun download(full: Boolean) {
        downloading = true
        progress = "Starting."
        host.speak("Downloading island outlines.")
        job = host.scope.launch {
            val msg = try {
                IslandShapeDownloader.download(full) { p -> progress = p; progressView?.text = p }.message()
            } catch (e: kotlinx.coroutines.CancellationException) {
                "Stopped. What was downloaded is kept. Press Download missing to continue."
            }
            downloading = false
            progress = msg
            host.speak(msg)
            if (host.currentScreen() === this@IslandOutlinesScreen) rebuild()
        }
        rebuild()
    }
}
