package mv.haluvi.navigation.ui

import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
                // A name suggestion from what nearby places give as their address (for example Google places).
                val sug = if (s.currentName.isBlank()) mv.haluvi.navigation.nav.RoadInfo.suggestName(s.points) else null
                host.speak("Road found. ${s.description}" + (sug?.let { " Places on this road say: $it. It is filled in; press Save to use it, or type another name." } ?: " Type the name and press Save."))
                if (edit?.text.isNullOrBlank()) edit?.setText(sug ?: s.currentName)
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
        host.speak("Saved. ${Phrases.distance(mv.haluvi.navigation.data.RoadNames.length(r.points))} of road are now called $name.")
        ready = null; edit?.setText("")
    }
}

/** My roads: check the road here, record a missing road, name roads, and share them with OpenStreetMap. */
class MyRoadsScreen : Screen() {
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("My roads")
        ui.button("Check this road", color = UiColors.BUTTON_ON) { host.speak(RoadDetector.describeHere()) }
        ui.button("Record a new road") { host.show { RecordRoadScreen() } }
        ui.button("Name this road, by walking along it") { host.show { RoadNameScreen() } }
        ui.button("Unnamed roads near me") { host.show { UnnamedRoadsScreen() } }
        val list = g.store.roadNames.all().sortedWith(compareBy({ g.islands.byKey(it.islandKey)?.name ?: "" }, { it.name.lowercase() }))
        ui.heading("My roads: ${list.size}")
        if (list.isEmpty()) ui.info("None yet. Name a map road, or record a road that is missing.")
        list.forEach { n ->
            val island = g.islands.byKey(n.islandKey)?.name ?: ""
            val kind = if (n.recorded) "recorded" else "named"
            val shared = if (n.sharedMs > 0) "shared with OpenStreetMap on ${java.text.DateFormat.getDateInstance().format(java.util.Date(n.sharedMs))}" else "not shared"
            ui.button("${n.name.ifBlank { "Unnamed road" }}, $island, ${Phrases.distance(n.lengthMeters)}, $kind, $shared") {
                host.show { RoadOptionsScreen(n.id) }
            }
        }
        ui.info("Sharing sends a public OpenStreetMap note with the road's position and name, so volunteer mappers can add it to the map for everyone. Nothing about you is sent.")
        ui.button("Back") { host.back() }
        return scroll
    }
}

class RoadOptionsScreen(private val id: String) : Screen() {
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        val n = g.store.roadNames.all().firstOrNull { it.id == id }
        if (n == null) { titleView = ui.title("Road not found"); ui.button("Back") { host.back() }; return scroll }
        titleView = ui.title(n.name.ifBlank { "Unnamed road" })
        val island = g.islands.byKey(n.islandKey)?.name ?: ""
        ui.info("${if (n.recorded) "Recorded by you" else "Your name for a map road"}, $island, ${Phrases.distance(n.lengthMeters)}." +
            (if (n.sharedMs > 0) " Already shared with OpenStreetMap." else " Not shared yet."))
        ui.button("Rename") { host.show { RenameRoadScreen(n) } }
        ui.button(if (n.sharedMs > 0) "Share with OpenStreetMap again" else "Share with OpenStreetMap") {
            host.show { ConfirmScreen("Send a public OpenStreetMap note: \"" + mv.haluvi.navigation.net.OsmNotes.text(n, island).substringBefore(" Line:") +
                "\" The road's position is included. Send?", "Send") {
                host.back()
                host.speak("Sending.")
                host.scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { mv.haluvi.navigation.net.OsmNotes.send(n, island) } }
                    if (ok.isSuccess) { g.store.roadNames.markShared(n.id); host.speak("Shared with OpenStreetMap. Thank you.") }
                    else host.speak("Could not send. Check the internet and try again.")
                    rebuild()
                }
            } }
        }
        ui.button("Delete", color = UiColors.BUTTON_STOP) {
            host.show { ConfirmScreen("Delete ${n.name.ifBlank { "this road" }} from my roads?", "Delete") {
                g.store.roadNames.delete(n.id); RoadDetector.version++
                host.speak("Deleted."); host.back(); host.back()
            } }
        }
        ui.button("Back") { host.back() }
        return scroll
    }
}

/** Record a missing road by walking along its middle (or one side when the middle is not safe). */
class RecordRoadScreen : Screen() {
    private var status: TextView? = null

    override fun create(): View {
        val rec = mv.haluvi.navigation.nav.RoadRecorder
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Record a new road")
        if (!rec.active) {
            status = null
            ui.info("Stand where the road starts, best at a junction. Walk along the middle of the road to its end. " +
                "If the middle is not safe, walk along one side; that is close enough. Stopping does not spoil the line.")
            ui.button("Start recording", color = UiColors.BUTTON_ON) {
                if (!host.hasLocationPermission()) { host.requestPermissionsIfNeeded(); return@button }
                rec.start(); host.speak("Recording. Walk to the end of the road, then press Finish."); rebuild()
            }
        } else {
            status = ui.info(statusText())
            ui.button("Finish", color = UiColors.BUTTON_ON) { finish() }
            ui.button("Cancel recording", color = UiColors.BUTTON_STOP) { rec.cancel(); host.speak("Recording cancelled."); rebuild() }
        }
        ui.button("Back") { host.back() }
        return scroll
    }

    private fun statusText(): String {
        val rec = mv.haluvi.navigation.nav.RoadRecorder
        return "Recording: ${Phrases.distance(rec.meters)} walked." + if (rec.weakGps) " GPS is weak; points are skipped until it is better." else ""
    }

    override fun onSecond() { status?.text = statusText() }

    private fun finish() {
        val pts = mv.haluvi.navigation.nav.RoadRecorder.finish()
        if (pts.size < 2) { host.speak("The road is too short or GPS was too weak. Nothing saved."); rebuild(); return }
        val key = RoadDetector.islandKeyAt(pts[0][0], pts[0][1])
        if (key == null) { host.speak("Not near an island. Nothing saved."); rebuild(); return }
        val len = mv.haluvi.navigation.data.RoadNames.length(pts)
        // Already on the map? Then naming that road is better than saving a double.
        val rs = AppGraph.store.loadRoads(key).filter { !it.recorded }
        val sample = mv.haluvi.navigation.data.RoadNames.sample(pts)
        val covered = sample.count { p -> rs.any { r -> mv.haluvi.navigation.data.RoadNames.distanceToLine(p[0], p[1], r.points) <= 12 } }
        if (rs.isNotEmpty() && covered >= sample.size * 0.8) {
            val mid = pts[pts.size / 2]
            val idx = rs.indices.minByOrNull { mv.haluvi.navigation.data.RoadNames.distanceToLine(mid[0], mid[1], rs[it].points) }!!
            val line = RoadDetector.joined(RoadDetector.chain(idx, rs), rs)
            val existing = rs[idx].name
            host.speak("This road is already on the map" + (if (existing.isNotBlank()) ", called $existing" else "") + ". Name it instead?")
            host.show {
                ChoiceScreen("Already on the map", "The road you walked is already on the map" + (if (existing.isNotBlank()) ", called $existing." else ", without a name."), listOf(
                    "Name the map road" to { host.show { TextInputScreen("Name this road", "Road name", mv.haluvi.navigation.nav.RoadInfo.suggestName(line) ?: existing) { name ->
                        AppGraph.store.roadNames.add(key, name, line); RoadDetector.version++
                        host.speak("Saved. ${Phrases.distance(mv.haluvi.navigation.data.RoadNames.length(line))} of road are now called $name."); host.back(); host.back(); host.back()
                    } } },
                    "Save as a new road anyway" to { askWidth(len) { w -> saveRecorded(key, w, pts, len) } }
                ))
            }
            return
        }
        askWidth(len) { w -> saveRecorded(key, w, pts, len) }
    }

    private fun saveRecorded(key: String, width: String, pts: List<DoubleArray>, len: Double) {
        host.show { TextInputScreen("Road name", "Road name, or Unnamed road", mv.haluvi.navigation.nav.RoadInfo.suggestName(pts) ?: "Unnamed road") { name ->
            AppGraph.store.roadNames.addRecorded(key, name, width, pts); RoadDetector.version++
            host.speak("Road saved, ${Phrases.distance(len)}. It is joined to roads at its ends, and used in offline routes.")
            host.backTo(this@RecordRoadScreen)
        } }
    }

    private fun askWidth(len: Double, save: (String) -> Unit) {
        host.speak("${Phrases.distance(len)} recorded. How wide is the road?")
        host.show {
            ChoiceScreen("How wide is the road?", "${Phrases.distance(len)} recorded.", listOf(
                "Narrow lane, walking only" to { save("narrow") },
                "Normal road" to { save("normal") },
                "Wide road" to { save("wide") }
            ))
        }
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

/** Unnamed roads near you, each described so you can tell which one it is, to name without walking. */
class UnnamedRoadsScreen : Screen() {
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Unnamed roads near me")
        val f = g.location.fix
        if (f == null) { ui.info("Waiting for GPS."); ui.button("Back") { host.back() }; return scroll }
        val list = RoadDetector.unnamedNear(f.lat, f.lon)
        if (list.isEmpty()) ui.info("No unnamed roads within 600 meters.")
        else ui.info("${list.size} unnamed roads, nearest first. Walking along a road with Name this road is the surest way; here you choose by the description.")
        list.take(30).forEach { r ->
            val sug = mv.haluvi.navigation.nav.RoadInfo.suggestName(r.points)
            ui.button("Unnamed road, ${r.description}" + (sug?.let { " Places on it say: $it." } ?: "")) {
                host.show { TextInputScreen("Name this road", "Road name", sug ?: "") { name ->
                    g.store.roadNames.add(r.islandKey, name, r.points); RoadDetector.version++
                    host.speak("Saved. ${Phrases.distance(r.length)} of road are now called $name."); host.back(); rebuild()
                } }
            }
        }
        ui.button("Back") { host.back() }
        return scroll
    }
}
