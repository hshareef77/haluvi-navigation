package mv.haluvi.navigation.ui

import android.view.View
import android.widget.TextView
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.nav.BuiltinMaps
import mv.haluvi.navigation.nav.OfflineMaps

/** The first-start guide: 4 short steps, shown once (and again from Settings). */
class GuideScreen : Screen() {
    private var step = 1
    private var askedPermission = false
    private var mapStarted = false
    private var info: TextView? = null

    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        if (step == 2 && askedPermission) { step = 3; askedPermission = false }
        when (step) {
            1 -> {
                titleView = ui.title("Step 1 of 4: Welcome")
                ui.info("Welcome to Haluvi Navigation. It guides you by voice to places and islands, also without internet.")
                ui.button("Next", color = UiColors.BUTTON_ON) { step = 2; rebuild() }
            }
            2 -> {
                titleView = ui.title("Step 2 of 4: Location")
                ui.info("Haluvi needs your location to guide you. Tap Allow, then choose While using the app. Then allow notifications, so you hear arrival alerts with the screen off.")
                ui.button("Allow", color = UiColors.BUTTON_ON) {
                    if (host.hasLocationPermission()) { step = 3; rebuild() } else { askedPermission = true; host.requestPermissionsIfNeeded() }
                }
                ui.button("Later") { step = 3; rebuild() }
            }
            3 -> {
                titleView = ui.title("Step 3 of 4: Map of your island")
                info = ui.info(mapText())
                val mine = OfflineMaps.myIsland()
                val code = mine?.let { g.islands.atolls.getOrNull(it.atollIndex)?.code }
                when {
                    mine == null -> ui.button("Next", color = UiColors.BUTTON_ON) { step = 4; rebuild() }
                    OfflineMaps.status(mine.key) != null -> ui.button("Next", color = UiColors.BUTTON_ON) { step = 4; rebuild() }
                    code != null && BuiltinMaps.has(code) -> {
                        // Built into the app: install it straight away, no internet needed.
                        if (!mapStarted && !OfflineDownload.active) { mapStarted = true; scroll.post { startOfflineDownload(host, listOf(mine), mine.name, builtin = true) } }
                        ui.button("Next", color = UiColors.BUTTON_ON) { step = 4; rebuild() }
                    }
                    else -> {
                        ui.button("Download", color = UiColors.BUTTON_ON) { startOfflineDownload(host, listOf(mine), mine.name); step = 4; rebuild() }
                        ui.button("Later") { step = 4; rebuild() }
                    }
                }
            }
            4 -> {
                titleView = ui.title("Step 4 of 4: Google, optional")
                ui.info("Optional: more places from Google Maps. You need your own Google API key, which may cost money. You can do this later in Settings.")
                ui.button("Skip", color = UiColors.BUTTON_ON) { step = 5; rebuild() }
                ui.button("Add key") { step = 5; host.show { GoogleSettingsScreen() } }
            }
            else -> {
                titleView = ui.title("You're ready")
                ui.info("To go somewhere: Places. To hear where you are: Where am I.")
                ui.button("Start", color = UiColors.BUTTON_ON) { g.settings.guideDone = true; host.showHome() }
            }
        }
        return scroll
    }

    private fun mapText(): String {
        val g = AppGraph
        val mine = OfflineMaps.myIsland() ?: return "Your island is not known yet. You can get maps later in Settings, Manage islands and places."
        val name = mine.fullName
        if (OfflineMaps.status(mine.key) != null) return "The map of $name is ready. It works without internet."
        val code = g.islands.atolls.getOrNull(mine.atollIndex)?.code
        if (code != null && BuiltinMaps.has(code)) return if (OfflineDownload.active) "Preparing the map of $name. One moment." else "The map of $name is ready. It works without internet."
        return "Download the free map of $name? Roads and places, for directions without internet. Takes about 1 minute."
    }

    override fun onSecond() { if (step == 3) info?.text = mapText() }
}
