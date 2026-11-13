package mv.haluvi.navigation.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.Fmt
import mv.haluvi.navigation.data.SavedLocation
import mv.haluvi.navigation.nav.ExploreEngine

class ExploreScreen : Screen() {
    private var roadView: TextView? = null
    private var messageView: TextView? = null
    private var listBox: LinearLayout? = null
    private var shownKeys = ""

    override fun create(): View {
        val g = AppGraph
        val ex = g.explore
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Explore")
        ui.info("Explore tells you the current road, intersections ahead and places as you approach them. No destination is needed. It keeps working with the screen off.")
        ui.button(if (ex.active) "Stop Exploration" else "Start Exploration", color = if (ex.active) UiColors.BUTTON_STOP else UiColors.BUTTON_ON) {
            if (!host.hasLocationPermission()) { host.requestPermissionsIfNeeded(); return@button }
            ex.toggle(); host.rebuild()
        }
        ui.row(
            "Category: ${ex.categoryLabel}" to {
                host.show {
                    ChoiceScreen("Explore category", "Choose what Explore lists around you.", ExploreEngine.CATEGORIES.map { (key, label) ->
                        (label + if (ex.categoryLabel == key) " - selected" else "") to { ex.setCategory(key); host.back() }
                    })
                }
            },
            "Distance: ${Fmt.meters(ex.radius.toDouble())}" to {
                host.show(Pickers.meters(host, "Explore distance", ExploreEngine.RADII, ex.radius) { ex.setRadius(it) })
            }
        )
        if (!g.settings.googleReady) ui.info("Google Maps is off, so Explore uses your saved and downloaded places, and downloaded roads for the current road and intersections.")
        if (ex.active) {
            roadView = ui.info(ex.roadStatus)
            messageView = ui.info(ex.message)
            listBox = UiBuilder.vertical(host, 0)
            ui.root.addView(listBox)
            fillList()
        } else {
            roadView = null; messageView = null; listBox = null
            ui.info("Exploration is stopped.")
        }
        ui.button("Back") { host.back() }
        return scroll
    }

    private fun fillList() {
        val box = listBox ?: return
        val ex = AppGraph.explore
        box.removeAllViews()
        val ui = UiBuilder(host, box)
        val items = ex.results.take(15)
        shownKeys = items.joinToString("|") { it.key }
        items.forEachIndexed { i, p ->
            ui.button(ex.placeLine(p, i + 1)) {
                val loc = p.saved ?: SavedLocation(SavedLocation.newId(), p.name, p.lat, p.lon, source = SavedLocation.SOURCE_GOOGLE)
                AppGraph.nav.startPlace(loc)
                host.showTabs("Places")
            }
        }
        if (items.isEmpty()) ui.info("Waiting for nearby places.")
    }

    override fun onLive() {
        val ex = AppGraph.explore
        roadView?.text = ex.roadStatus
        messageView?.text = ex.message
    }

    override fun onSecond() {
        // Refresh the list only when its members change, so TalkBack focus is not disturbed every second.
        val keys = AppGraph.explore.results.take(15).joinToString("|") { it.key }
        if (keys != shownKeys && listBox != null) {
            val focusedInList = (0 until (listBox?.childCount ?: 0)).any { listBox?.getChildAt(it)?.createAccessibilityNodeInfo()?.isAccessibilityFocused == true }
            if (!focusedInList) fillList()
        }
    }
}
