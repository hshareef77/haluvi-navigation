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

    /** Stops at once: closes the open server connection instead of waiting for its answer. */
    fun cancel(host: MainActivity) {
        job?.cancel()
        mv.haluvi.navigation.net.Http.cancelAll()
        progress = "Stopping."
        (host.currentScreen() as? OfflineProgressView)?.showProgress(progress)
        host.say("Stopping")
    }
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

/** Download places for many islands: OpenStreetMap (free, roads and places) or Google (API key, may cost). */
class OfflineMapsScreen : Screen(), OfflineProgressView {
    private var progressView: TextView? = null

    override fun showProgress(text: String) { progressView?.text = text }

    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Download places")
        if (OfflineDownload.active) {
            progressView = ui.info(OfflineDownload.progress)
            ui.button("Cancel download", color = UiColors.BUTTON_STOP) { OfflineDownload.cancel(host) }
            ui.button("Back") { host.back() }
            return scroll
        }
        progressView = null
        if (OfflineDownload.lastResult.isNotBlank()) ui.info(OfflineDownload.lastResult)

        ui.heading("OpenStreetMap: free, roads and places")
        ui.info("${OfflineMaps.statuses().size} islands downloaded. Roads are needed for offline routes.")
        val mine = OfflineMaps.myIsland()
        if (mine != null) {
            ui.button("My island: ${mine.name}" + if (OfflineMaps.status(mine.key) != null) ". Update" else ". Download", color = UiColors.BUTTON_ON) {
                startOfflineDownload(host, listOf(mine), mine.name)
            }
        } else ui.info("My island: waiting for GPS.")
        ui.button("Choose atoll or island") { host.show { AtollsScreen() } }
        ui.button("Whole Maldives") {
            host.show {
                ConfirmScreen("Download all ${g.islands.allIslands.size} islands in all 20 atolls? It takes about 10 to 30 minutes and needs good internet. You can keep using the phone.", "Download all") {
                    host.backTo(this@OfflineMapsScreen)
                    startOfflineDownload(host, g.islands.allIslands, "the whole Maldives")
                }
            }
        }
        ui.heading("Google: needs your API key, may cost money")
        ui.button("Get Google places") { host.show { GooglePlacesScreen() } }
        ui.info("Map data © OpenStreetMap contributors.")
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
