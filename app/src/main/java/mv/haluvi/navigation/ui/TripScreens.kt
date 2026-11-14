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

/** Sets the Places destination and opens the Places screen (used by every place picker). */
fun goToPlace(host: MainActivity, loc: SavedLocation) {
    Selection.placeTo = loc
    host.say("To: ${loc.name}")
    host.showTrip { PlacesScreen() }
}

/** Places: walking or driving in a city or on an island. From, To, Travel, Start. */
class PlacesScreen : Screen() {
    private var preview: TextView? = null

    override fun create(): View {
        val g = AppGraph
        val s = g.settings
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Places")
        addPermissionWarning(host, ui)
        val active = g.nav.target?.takeIf { it.kind == NavKind.PLACE }
        if (active != null) ui.button("Going to ${active.name}. Open", color = UiColors.BUTTON_ON) { host.showNavigation() }

        val from = g.store.byId(s.placeFromId)
        ui.button("From: " + (from?.name ?: "my location")) {
            host.show { MyPlacesScreen(title = "From", firstChoice = "My location" to { s.placeFromId = ""; host.say("From: my location"); host.backTo(this@PlacesScreen) }) { loc ->
                s.placeFromId = loc.id; host.say("From: ${loc.name}"); host.backTo(this@PlacesScreen)
            } }
        }
        val to = Selection.placeTo
        ui.button("To: " + (to?.name ?: "choose")) { host.show { ChooseToScreen() } }
        preview = to?.let { ui.info(previewText(it)) }
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
                dest == null -> { host.speak("Please choose where to go."); host.show { ChooseToScreen() } }
                else -> { g.nav.start(Destination.of(dest)); host.showNavigation() }
            }
        }
        if (to != null && g.store.byId(to.id) == null && g.store.findDuplicate(to.name, to.lat, to.lon) == null) {
            ui.button("Save ${to.name} to my places") { g.store.add(to); host.speak("Saved."); rebuild() }
        }
        ui.button("What's around me") { host.show { AroundMeScreen() } }
        ui.button("Back") { host.back() }
        return scroll
    }

    private fun previewText(to: SavedLocation): String {
        val from = AppGraph.store.byId(AppGraph.settings.placeFromId)
        return AppGraph.nav.previewText(Destination.of(to), from?.lat, from?.lon)
    }

    override fun onSecond() { Selection.placeTo?.let { preview?.text = previewText(it) } }
}

/** Where to go: My places, Search, What's around me. */
class ChooseToScreen : Screen() {
    override fun create(): View {
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("To")
        ui.button("My places") { host.show { MyPlacesScreen(title = "To") { loc -> goToPlace(host, loc) } } }
        ui.button("Search") { host.show { SearchScreen() } }
        ui.button("What's around me") { host.show { AroundMeScreen() } }
        ui.button("Back") { host.back() }
        return scroll
    }
}

/** Islands: sea or air travel between islands. From, To, Travel, Start. */
class IslandsScreen : Screen() {
    private var preview: TextView? = null

    private fun fromIsland(): Island? = AppGraph.islands.byKey(AppGraph.settings.islandFromKey)

    override fun create(): View {
        val g = AppGraph
        val s = g.settings
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Islands")
        addPermissionWarning(host, ui)
        val active = g.nav.target?.takeIf { it.kind == NavKind.ISLAND }
        if (active != null) ui.button("Going to ${active.name}. Open", color = UiColors.BUTTON_ON) { host.showNavigation() }

        val manual = fromIsland()
        val gpsIsland = g.nav.currentIsland()
        val fromText = manual?.name ?: gpsIsland?.let { "${it.name}, by GPS" } ?: "my GPS island"
        ui.button("From: $fromText") {
            host.show {
                ChoiceScreen("From", null, listOf(
                    "My GPS island" to { s.islandFromKey = ""; host.say("From: GPS island"); host.backTo(this@IslandsScreen) },
                    "Choose island" to { host.show { AtollPickerScreen { isl -> s.islandFromKey = isl.key; host.say("From: ${isl.name}"); host.backTo(this@IslandsScreen) } } }
                ))
            }
        }
        val to = Selection.islandTo
        ui.button("To: " + (to?.fullName ?: "choose island")) {
            host.show { AtollPickerScreen { isl -> Selection.islandTo = isl; host.say("To: ${isl.name}"); host.backTo(this@IslandsScreen) } }
        }
        preview = to?.let { ui.info(previewText(it)) }
        ui.button("Travel: ${if (s.islandTravelAir) "Air" else "Sea"}") {
            s.islandTravelAir = !s.islandTravelAir
            host.say("Travel: ${if (s.islandTravelAir) "air" else "sea"}")
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
        if (g.shapes.isEmpty) {
            ui.info("For exact island stop announcements, download the island outlines once.")
            ui.button("Download island outlines") { host.show { IslandOutlinesScreen() } }
        } else ui.button("Island outlines") { host.show { IslandOutlinesScreen() } }
        ui.button("Back") { host.back() }
        return scroll
    }

    private fun previewText(to: Island): String {
        val from = fromIsland()
        return AppGraph.nav.previewText(Destination.of(to), from?.lat, from?.lon)
    }

    override fun onSecond() { Selection.islandTo?.let { preview?.text = previewText(it) } }
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
            ui.button("Cancel", color = UiColors.BUTTON_STOP) { job?.cancel() }
            return scroll
        }
        progressView = null
        val oldRules = g.shapes.formatVersion in 1..3
        ui.info(when {
            g.shapes.isEmpty -> "Outlines make island stop announcements exact. Download once; they then work offline."
            oldRules -> "Your outlines were downloaded with older matching rules. Press Download to update them with the corrected matching."
            else -> IslandShapeDownloader.summary().message()
        })
        // Show the last message only when it says something the summary above does not.
        if (progress.isNotBlank() && progress != IslandShapeDownloader.summary().message()) ui.info(progress)
        ui.button(if (g.shapes.isEmpty || oldRules) "Download" else "Download missing", color = UiColors.BUTTON_ON) { download(full = false) }
        val missing = IslandShapeDownloader.missingNames()
        if (missing.isNotEmpty() && !g.shapes.isEmpty) {
            ui.heading("Islands without an outline: ${missing.size}")
            missing.take(50).forEach { ui.info(it) }
        }
        if (!g.shapes.isEmpty) {
            ui.button("Share island report") { share(IslandShapeDownloader.report()) }
            ui.button("Export outlines file") {
                host.createFile("haluvi-island-outlines.json", "application/json") { uri ->
                    if (uri == null) return@createFile
                    host.scope.launch {
                        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { host.contentResolver.openOutputStream(uri)?.use { it.write(g.shapes.exportText().toByteArray()) } }.isSuccess
                        }
                        host.speak(if (ok) "Exported." else "Export failed.")
                    }
                }
            }
            ui.button("Download everything again") { download(full = true) }
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
