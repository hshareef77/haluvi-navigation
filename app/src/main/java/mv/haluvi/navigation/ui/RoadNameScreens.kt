package mv.haluvi.navigation.ui

import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.TextView
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.data.NamedRoad
import mv.haluvi.navigation.nav.Phrases
import mv.haluvi.navigation.nav.RoadDetector

/**
 * Name this road: walk or drive along a downloaded road; when the app is sure which road it is,
 * type or dictate its name and save. The name is then used in Explore, offline routes and junctions.
 */
class RoadNameScreen : Screen() {
    private var status: TextView? = null
    private var edit: EditText? = null
    private var ready: RoadDetector.State.Ready? = null
    private var readyMs = 0L
    private var lastKind = ""

    override fun create(): View {
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Name this road")
        ui.info("Walk or drive along the road for 30 meters, away from junctions. When the road is found, type or dictate its name and press Save.")
        status = ui.info(RoadDetector.message(RoadDetector.evaluate()))
        edit = ui.edit("Road name")
        ui.button("Save", color = UiColors.BUTTON_ON) { save() }
        ui.button("Check again") { check(speakAlways = true) }
        ui.button("My road names") { host.show { MyRoadNamesScreen() } }
        ui.button("Back") { host.back() }
        return scroll
    }

    override fun onSecond() { check(speakAlways = false) }

    private fun check(speakAlways: Boolean) {
        val s = RoadDetector.evaluate()
        val nowMs = SystemClock.elapsedRealtime()
        if (s is RoadDetector.State.Ready) {
            val first = ready == null || nowMs - readyMs > 60_000
            ready = s; readyMs = nowMs
            status?.text = "Road found: ${s.description}"
            if (first || speakAlways) {
                host.speak("Road found. ${s.description} Type the name and press Save.")
                if (s.currentName.isNotBlank() && edit?.text.isNullOrBlank()) edit?.setText(s.currentName)
            }
            lastKind = "ready"
            return
        }
        // Keep the found road for a minute while the user types (they may stop or reach a corner).
        if (ready != null && nowMs - readyMs <= 60_000) {
            if (speakAlways) host.speak("Road found. ${ready!!.description}")
            return
        }
        ready = null
        val text = RoadDetector.message(s)
        status?.text = text
        val kind = s::class.java.simpleName
        // Speak only changes that need action, not every progress step.
        if (speakAlways || (kind != lastKind && s !is RoadDetector.State.Walk)) host.speak(text)
        lastKind = kind
    }

    private fun save() {
        val name = edit?.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) { host.speak("Type the road name first."); return }
        val r = ready?.takeIf { SystemClock.elapsedRealtime() - readyMs <= 60_000 }
        if (r == null) { host.speak("The road is not found yet. " + RoadDetector.message(RoadDetector.evaluate())); return }
        AppGraph.store.roadNames.add(r.islandKey, name, r.points)
        RoadDetector.version++
        host.speak("Saved. This road is now called $name.")
        ready = null; edit?.setText("")
    }
}

/** The user's road names: rename or delete. */
class MyRoadNamesScreen : Screen() {
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("My road names")
        val list = g.store.roadNames.all().sortedWith(compareBy({ g.islands.byKey(it.islandKey)?.name ?: "" }, { it.name.lowercase() }))
        if (list.isEmpty()) ui.info("No road names yet. Use Name this road while walking or driving along a road.")
        list.forEach { n ->
            val island = g.islands.byKey(n.islandKey)?.name ?: ""
            ui.button("${n.name}, $island, ${Phrases.distance(n.lengthMeters)}") {
                host.show {
                    ChoiceScreen(n.name, null, listOf(
                        "Rename" to { host.show { RenameRoadScreen(n) } },
                        "Delete" to {
                            host.show { ConfirmScreen("Delete the road name ${n.name}?", "Delete") {
                                g.store.roadNames.delete(n.id); RoadDetector.version++
                                host.speak("Deleted."); host.backTo(this@MyRoadNamesScreen)
                            } }
                        }
                    ))
                }
            }
        }
        ui.button("Back") { host.back() }
        return scroll
    }
}

class RenameRoadScreen(private val road: NamedRoad) : Screen() {
    override fun create(): View {
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Rename road")
        val e = ui.edit("Road name", road.name)
        ui.button("Save", color = UiColors.BUTTON_ON) {
            val name = e.text.toString().trim()
            if (name.isEmpty()) { host.speak("Type the road name first."); return@button }
            AppGraph.store.roadNames.rename(road.id, name); RoadDetector.version++
            host.speak("Saved.")
            host.back(); host.back()
        }
        ui.button("Back") { host.back() }
        return scroll
    }
}
