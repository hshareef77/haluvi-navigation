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
    /** True from start until the result is ready (cleared before the screen is rebuilt, so it never shows an empty progress). */
    @Volatile var running = false
    val active: Boolean get() = running

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
fun startOfflineDownload(host: MainActivity, islands: List<Island>, label: String, builtin: Boolean = false,
                         quiet: Boolean = false, onFinished: ((ok: Boolean) -> Unit)? = null) {
    if (OfflineDownload.active) { host.speak("A download is already running."); return }
    OfflineDownload.progress = "Starting: $label."
    OfflineDownload.running = true
    if (!quiet) host.speak(if (builtin) "Installing the built-in map of $label." else "Downloading $label.")
    OfflineDownload.job = host.scope.launch {
        var stopped = false
        val msg = try {
            OfflineMaps.download(islands, builtin) { p -> if (p.isNotBlank()) { OfflineDownload.progress = p; (host.currentScreen() as? OfflineProgressView)?.showProgress(p) } }
        } catch (e: CancellationException) {
            stopped = true
            "What was downloaded is kept."
        } catch (e: Exception) {
            "Error: ${e.message ?: "unknown"}."
        }
        val failed = !stopped && (msg.contains("fail", true) || msg.startsWith("Error"))
        val what = if (builtin) "Built-in map" else "Download"
        val head = when { stopped -> "$what stopped"; failed -> "$what failed or partly failed"; else -> if (builtin) "Built-in map installed" else "Download completed" }
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
        OfflineDownload.lastResult = "$head at $time, $label. $msg"
        OfflineDownload.progress = ""
        OfflineDownload.running = false
        if (!quiet || failed) host.speak("$head. $msg") else host.speak("$head.")
        onFinished?.invoke(!failed && !stopped)
        (host.currentScreen() as? OfflineProgressView)?.let { (it as Screen).rebuild() }
    }
    // Never left "running" by an unexpected error.
    OfflineDownload.job?.invokeOnCompletion { if (OfflineDownload.running) { OfflineDownload.running = false; host.runOnUiThread { host.rebuild() } } }
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
            progressView = ui.info(OfflineDownload.progress.ifBlank { "Starting." })
            ui.button("Cancel download", color = UiColors.BUTTON_STOP) { OfflineDownload.cancel(host) }
            ui.button("Back") { host.back() }
            return scroll
        }
        progressView = null
        if (OfflineDownload.lastResult.isNotBlank()) ui.info(OfflineDownload.lastResult)

        mv.haluvi.navigation.nav.BuiltinMaps.date()?.let { d ->
            ui.heading("Built-in maps: installed automatically")
            ui.info(if (g.settings.builtinInstalledDate == d) "OpenStreetMap roads and places from $d are installed for all islands, also resorts and airports. No internet needed."
                else "OpenStreetMap roads and places from $d are built into the app. They are installed automatically when the app starts.")
            ui.button("Re-install for an atoll or island") { host.show { AtollsScreen() } }
            ui.button("Re-install for all islands") {
                host.show { ConfirmScreen("Re-install the built-in maps of all islands? No internet needed; it takes a minute or two. Google places and your own places are kept.", "Re-install") {
                    host.backTo(this@OfflineMapsScreen)
                    startOfflineDownload(host, g.islands.allIslands, "the whole Maldives", builtin = true) { ok -> if (ok) g.settings.builtinInstalledDate = d }
                } }
            }
        }
        ui.heading("OpenStreetMap from the internet: free, newest")
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
        if (g.settings.googleReady) {
            ui.heading("Google: your API key, may cost money")
            ui.button("Get Google places") { host.show { GooglePlacesScreen() } }
        }
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
