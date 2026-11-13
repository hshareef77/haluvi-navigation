package mv.haluvi.navigation.ui

import android.view.View
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.Fmt
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.data.Atoll
import mv.haluvi.navigation.data.Categories
import mv.haluvi.navigation.data.Island
import mv.haluvi.navigation.data.SavedLocation
import mv.haluvi.navigation.net.Http

/** Atoll list (with island name search). [onPick] decides where to go after an island is chosen. */
class AtollPickerScreen(private val title: String, private val onPick: (Island) -> Unit) : Screen() {
    private var query = ""
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title(title)
        val edit = ui.edit("Search island by name", query) { query = it; host.rebuild() }
        ui.row("Search" to { query = edit.text.toString(); host.rebuild() }, "Clear" to { query = ""; host.rebuild() })
        if (query.isNotBlank()) {
            val found = g.islands.search(query)
            ui.heading("${found.size} islands found")
            found.forEach { islandButton(ui, it) }
        }
        ui.heading("Atolls")
        g.islands.atolls.forEach { a -> ui.button("${a.fullName}, ${a.code}, ${a.islands.size} islands") { host.show { IslandPickerScreen(a, onPick) } } }
        ui.button("Back") { host.back() }
        return scroll
    }

    private fun islandButton(ui: UiBuilder, island: Island) {
        val f = AppGraph.location.fix
        val dist = f?.let { ", " + Fmt.meters(Geo.distance(it.lat, it.lon, island.lat, island.lon)) } ?: ""
        ui.button(island.fullName + dist) { onPick(island) }
    }
}

class IslandPickerScreen(private val atoll: Atoll, private val onPick: (Island) -> Unit) : Screen() {
    override fun create(): View {
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("${atoll.fullName} islands")
        val f = AppGraph.location.fix
        atoll.islands.forEach { island ->
            val extra = f?.let { ", " + Fmt.meters(Geo.distance(it.lat, it.lon, island.lat, island.lon)) + ", " + Geo.directionName(Geo.bearing(it.lat, it.lon, island.lat, island.lon)) } ?: ""
            ui.button(island.name + extra) { onPick(island) }
        }
        ui.button("Back to atolls") { host.back() }
        return scroll
    }
}

class CurrentIslandScreen : Screen() {
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Current island")
        val f = g.location.fix
        val gpsIsland = f?.let { g.islands.nearest(it.lat, it.lon) }
        ui.info("GPS island: " + (gpsIsland?.let { "${it.island.fullName}, ${Fmt.meters(it.distance)} from island centre." } ?: "waiting for GPS."))
        ui.button("Use GPS current island" + if (!g.nav.currentIslandIsManual()) " - selected" else "") {
            g.settings.manualCurrentIslandKey = ""
            host.say("Current island: GPS")
            host.showTabs("Islands")
        }
        ui.button("Choose current island manually") {
            host.show { AtollPickerScreen("Current island atoll") { island ->
                g.settings.manualCurrentIslandKey = island.key
                host.say("Current island set: ${island.fullName}")
                host.showTabs("Islands")
            } }
        }
        ui.button("Back") { host.back() }
        return scroll
    }
}

/** Download OpenStreetMap places and roads for one island for offline use (Lua v60-v74, plus roads). */
class MapDataScreen : Screen() {
    private var job: Job? = null
    private var progressView: TextView? = null
    private var progress = ""

    private fun selectedIsland(): Island? {
        val g = AppGraph
        return g.islands.byKey(g.settings.mapDataIslandKey) ?: g.nav.currentIsland()
    }

    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Island map data")
        val island = selectedIsland()
        if (job?.isActive == true) {
            progressView = ui.info(progress)
            ui.button("Cancel download", color = UiColors.BUTTON_STOP) { job?.cancel(); progress = "Download cancelled."; host.rebuild() }
            return scroll
        }
        ui.info("Download OpenStreetMap places and roads of one island. After download, places work offline in search, Explore and navigation, and roads let Explore announce intersections.")
        ui.button("Island: " + (island?.fullName ?: "not selected") + ". Tap to change") {
            host.show { AtollPickerScreen("Map data atoll") { picked -> g.settings.mapDataIslandKey = picked.key; host.back(); host.back() } }
        }
        if (island != null) {
            ui.info("${g.store.countDownloadedForIsland(island.key)} downloaded places and ${g.store.roadCount(island.key)} roads stored for ${island.name}.")
            if (progress.isNotBlank()) ui.info(progress)
            ui.button("Download or update ${island.name} map data", color = UiColors.BUTTON_ON) { download(island) }
            ui.button("Delete downloaded data for ${island.name}", color = UiColors.BUTTON_STOP) {
                host.show { ConfirmScreen("Delete all downloaded map places and roads of ${island.fullName}? Your own saved places are kept.", "Delete") {
                    val n = g.store.deleteDownloadedForIsland(island.key)
                    g.speaker.speak("$n downloaded places deleted.", force = true)
                    host.back()
                } }
            }
        } else {
            ui.info("Waiting for GPS. You can also choose the island manually.")
        }
        ui.button("Back") { host.back() }
        return scroll
    }

    private fun setProgress(text: String) {
        progress = text
        progressView?.text = text
    }

    private fun download(island: Island) {
        val g = AppGraph
        val cats = g.osm.mapCategories
        val total = cats.size + 1
        progress = "Starting download for ${island.fullName}."
        g.speaker.speak("Downloading map data. You can leave this screen open.", force = true)
        job = host.scope.launch {
            var added = 0; var skipped = 0; var offIsland = 0; var failed = 0
            for ((i, cat) in cats.withIndex()) {
                if (!isActive) return@launch
                setProgress("Step ${i + 1} of $total: ${cat.label}. $added places saved so far.")
                val r = withContext(Dispatchers.IO) { runCatching { g.osm.islandPlaces(cat.key, island.lat, island.lon, 4000) } }
                r.onSuccess { list ->
                    list.forEach { p ->
                        val near = g.islands.nearest(p.lat, p.lon)
                        if (near?.island?.key != island.key) { offIsland++; return@forEach }
                        val catId = if (cat.key == "common") Categories.guess(p.name + " " + p.typeText) else cat.key
                        val loc = SavedLocation(SavedLocation.newId(), p.name, p.lat, p.lon, 0.0, source = SavedLocation.SOURCE_OSM_MAP,
                            categoryId = catId, islandKey = island.key, islandName = island.fullName, address = p.address)
                        if (g.store.add(loc, persist = false)) added++ else skipped++
                    }
                    g.store.save()
                }.onFailure { failed++ }
            }
            if (!isActive) return@launch
            setProgress("Step $total of $total: roads. $added places saved.")
            val roads = withContext(Dispatchers.IO) { runCatching { g.osm.islandRoads(island.lat, island.lon, 4000) } }
            var roadText = ""
            roads.onSuccess { list ->
                val onIsland = list.filter { r -> r.points.any { p -> g.islands.nearest(p[0], p[1])?.island?.key == island.key } }
                withContext(Dispatchers.IO) { g.store.saveRoads(island.key, onIsland) }
                roadText = " ${onIsland.size} roads saved."
            }.onFailure { e -> roadText = " Roads failed: ${Http.friendly(e, "OpenStreetMap")}"; failed++ }
            val msg = "Download finished for ${island.name}. $added new places saved." +
                (if (skipped > 0) " $skipped already saved places skipped." else "") + roadText +
                (if (failed > 0) " $failed steps failed; press Download again to retry." else "")
            progress = msg
            g.speaker.speak(msg, force = true)
            if (host.currentScreen() === this@MapDataScreen) host.rebuild()
        }
        host.rebuild()
    }

    override fun onDispose() {
        // Keep downloading in the background if the user leaves; the screen shows progress when reopened.
    }
}
