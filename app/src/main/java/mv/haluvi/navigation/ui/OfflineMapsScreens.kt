package mv.haluvi.navigation.ui

import android.content.Intent
import android.view.View
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.data.Atoll
import mv.haluvi.navigation.data.Island
import mv.haluvi.navigation.nav.OfflineMaps

/** The running download, kept outside screens so leaving the screen does not lose its progress. */
object OfflineDownload {
    var job: Job? = null
    var progress = ""
    var lastResult = ""
    val active: Boolean get() = job?.isActive == true
}

fun shareText(host: MainActivity, subject: String, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
    runCatching { host.startActivity(Intent.createChooser(i, subject)) }
}

/** Starts a download of [islands] and shows progress on whatever Offline maps screen is open. */
fun startOfflineDownload(host: MainActivity, islands: List<Island>, label: String) {
    if (OfflineDownload.active) { host.speak("A download is already running."); return }
    OfflineDownload.progress = "Starting: $label."
    host.speak("Downloading $label.")
    OfflineDownload.job = host.scope.launch {
        val msg = try {
            OfflineMaps.download(islands) { p -> OfflineDownload.progress = p; (host.currentScreen() as? OfflineProgressView)?.showProgress(p) }
        } catch (e: CancellationException) {
            "Stopped. What was downloaded is kept."
        }
        OfflineDownload.lastResult = msg
        OfflineDownload.progress = ""
        host.speak(msg)
        (host.currentScreen() as? OfflineProgressView)?.let { (it as Screen).rebuild() }
    }
    host.rebuild()
}

interface OfflineProgressView { fun showProgress(text: String) }

/** Offline maps: download island outlines, roads and places for my island, an island, an atoll or the whole Maldives. */
class OfflineMapsScreen : Screen(), OfflineProgressView {
    private var progressView: TextView? = null

    override fun showProgress(text: String) { progressView?.text = text }

    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Offline maps")
        if (OfflineDownload.active) {
            progressView = ui.info(OfflineDownload.progress)
            ui.button("Cancel download", color = UiColors.BUTTON_STOP) { OfflineDownload.job?.cancel() }
            ui.button("Back") { host.back() }
            return scroll
        }
        progressView = null
        val count = OfflineMaps.statuses().size
        ui.info("Island outlines, roads and places for use without internet. $count islands downloaded.")
        if (OfflineDownload.lastResult.isNotBlank()) ui.info(OfflineDownload.lastResult)

        val mine = OfflineMaps.myIsland()
        if (mine != null) {
            ui.button("My island: ${mine.name}" + if (OfflineMaps.status(mine.key) != null) ". Update" else ". Download", color = UiColors.BUTTON_ON) {
                startOfflineDownload(host, listOf(mine), mine.name)
            }
        } else ui.info("My island: waiting for GPS.")
        ui.button("Choose island") {
            host.show { AtollPickerScreen { isl -> host.backTo(this@OfflineMapsScreen); host.show { OfflineIslandScreen(isl) } } }
        }
        ui.button("Choose atoll") {
            host.show { ChoiceScreen("Choose atoll", null, g.islands.atolls.map { a -> OfflineMaps.atollText(a) to { host.show { OfflineAtollScreen(a) } } }) }
        }
        ui.button("Whole Maldives") {
            host.show {
                ConfirmScreen("Download all ${g.islands.allIslands.size} islands in all 20 atolls? It takes about 10 to 30 minutes and needs good internet. You can keep using the phone.", "Download all") {
                    host.backTo(this@OfflineMapsScreen)
                    startOfflineDownload(host, g.islands.allIslands, "the whole Maldives")
                }
            }
        }
        ui.button("Share offline maps report") { shareText(host, "Haluvi offline maps report", OfflineMaps.report()) }
        ui.button("Island outlines details") { host.show { IslandOutlinesScreen() } }
        ui.button("Back") { host.back() }
        return scroll
    }
}

class OfflineAtollScreen(private val atoll: Atoll) : Screen(), OfflineProgressView {
    private var progressView: TextView? = null
    override fun showProgress(text: String) { progressView?.text = text }

    override fun create(): View {
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title(atoll.fullName)
        if (OfflineDownload.active) {
            progressView = ui.info(OfflineDownload.progress)
            ui.button("Cancel download", color = UiColors.BUTTON_STOP) { OfflineDownload.job?.cancel() }
            ui.button("Back") { host.back() }
            return scroll
        }
        progressView = null
        ui.info(OfflineMaps.atollText(atoll))
        if (OfflineDownload.lastResult.isNotBlank()) ui.info(OfflineDownload.lastResult)
        val any = atoll.islands.any { OfflineMaps.status(it.key) != null }
        ui.button(if (any) "Update atoll" else "Download atoll", color = UiColors.BUTTON_ON) { startOfflineDownload(host, atoll.islands, atoll.fullName) }
        if (any) ui.button("Delete atoll", color = UiColors.BUTTON_STOP) {
            host.show { ConfirmScreen("Delete the offline map of ${atoll.fullName}? Places you saved yourself are kept.", "Delete") {
                val n = OfflineMaps.delete(atoll.islands); host.speak("$n places deleted."); host.back()
            } }
        }
        ui.heading("Islands")
        atoll.islands.forEach { isl -> ui.button(OfflineMaps.islandText(isl)) { host.show { OfflineIslandScreen(isl) } } }
        ui.button("Back") { host.back() }
        return scroll
    }
}

class OfflineIslandScreen(private val island: Island) : Screen(), OfflineProgressView {
    private var progressView: TextView? = null
    override fun showProgress(text: String) { progressView?.text = text }

    override fun create(): View {
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title(island.fullName)
        if (OfflineDownload.active) {
            progressView = ui.info(OfflineDownload.progress)
            ui.button("Cancel download", color = UiColors.BUTTON_STOP) { OfflineDownload.job?.cancel() }
            ui.button("Back") { host.back() }
            return scroll
        }
        progressView = null
        if (island.atollIndex < 0) {
            ui.info("${island.spokenName}. Offline maps are downloaded for inhabited islands. This island's outline is already offline.")
            ui.button("Back") { host.back() }
            return scroll
        }
        ui.info(OfflineMaps.islandText(island))
        if (OfflineDownload.lastResult.isNotBlank()) ui.info(OfflineDownload.lastResult)
        val have = OfflineMaps.status(island.key) != null
        ui.button(if (have) "Update" else "Download", color = UiColors.BUTTON_ON) { startOfflineDownload(host, listOf(island), island.name) }
        if (have) ui.button("Delete", color = UiColors.BUTTON_STOP) {
            host.show { ConfirmScreen("Delete the offline map of ${island.name}? Places you saved yourself are kept.", "Delete") {
                val n = OfflineMaps.delete(listOf(island)); host.speak("$n places deleted."); host.back()
            } }
        }
        ui.button("Back") { host.back() }
        return scroll
    }
}

/** All airport islands (Velana, Gan, Hanimaadhoo, ...), nearest first. */
class AirportsScreen(private val onPick: (Island) -> Unit) : Screen() {
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Airports")
        val f = g.location.fix
        val list = g.shapes.airports().let { l -> if (f != null) l.sortedBy { mv.haluvi.navigation.core.Geo.distance(f.lat, f.lon, it.lat, it.lon) } else l.sortedBy { it.name } }
        if (list.isEmpty()) ui.info("No airports yet. Download offline maps or island outlines first.")
        list.forEach { isl -> ui.button(islandLabel(isl)) { onPick(isl) } }
        ui.button("Back") { host.back() }
        return scroll
    }
}
