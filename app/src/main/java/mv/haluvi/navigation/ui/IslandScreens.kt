package mv.haluvi.navigation.ui

import android.view.View
import android.widget.TextView
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.data.Atoll
import mv.haluvi.navigation.data.Categories
import mv.haluvi.navigation.data.Island
import mv.haluvi.navigation.data.IslandOverrides
import mv.haluvi.navigation.data.SavedLocation
import mv.haluvi.navigation.nav.OfflineMaps
import mv.haluvi.navigation.nav.Phrases

private fun typeName(t: String) = when (t) {
    "inhabited" -> "Inhabited"; "resort" -> "Resort"; "airport" -> "Airport"; else -> "Uninhabited"
}

/** "34 offline places: 20 OpenStreetMap, 10 Google, 4 saved by me." */
private fun placesBreakdown(keys: Collection<String>): String {
    val set = keys.toSet()
    val items = AppGraph.store.items.filter { it.islandKey in set }
    if (items.isEmpty()) return "No offline places."
    val parts = listOf(
        SavedLocation.SOURCE_OSM_MAP to "OpenStreetMap", SavedLocation.SOURCE_GOOGLE to "Google",
        SavedLocation.SOURCE_MANUAL to "saved by me", SavedLocation.SOURCE_OSM_SEARCH to "search results"
    ).mapNotNull { (src, label) -> items.count { it.source == src }.takeIf { it > 0 }?.let { "$it $label" } }
    return "${offlinePlacesText(items.size).replaceFirstChar { it.uppercase() }}: ${parts.joinToString(", ")}."
}

/**
 * Manage islands and places: everything about islands and the places saved on the phone in one section.
 */
class ManageScreen : Screen() {
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Manage islands and places")
        ui.info("${offlinePlacesText(g.store.items.size).replaceFirstChar { it.uppercase() }} on the phone. ${OfflineMaps.statuses().size} islands with an offline map.")
        ui.button("Atolls and islands", color = UiColors.BUTTON_ON) { host.show { AtollsScreen() } }
        ui.button("Download places: OpenStreetMap or Google") { host.show { OfflineMapsScreen() } }
        ui.button("All places: by source or category") {
            host.show {
                ChoiceScreen("All places", null,
                    listOf(SavedLocation.SOURCE_MANUAL, SavedLocation.SOURCE_OSM_MAP, SavedLocation.SOURCE_GOOGLE, SavedLocation.SOURCE_OSM_SEARCH).map { src ->
                        "${sourceName(src)}: ${g.store.items.count { it.source == src }}" to {
                            host.show { MyPlacesScreen(filter = PlaceFilter(source = src, label = sourceName(src))) }
                        }
                    } + listOf("By category" to {
                        host.show { ChoiceScreen("By category", null, Categories.all.map { c ->
                            "${c.name}: ${g.store.items.count { it.categoryId == c.id }}" to {
                                host.show { MyPlacesScreen(filter = PlaceFilter(category = c.id, label = c.name)) }
                            }
                        }) }
                    })
                )
            }
        }
        ui.button("My roads: ${g.store.roadNames.all().size}") { host.show { MyRoadsScreen() } }
        ui.heading("Backup")
        ui.button("Export backup: places, roads and routes") { exportBackup(host) }
        ui.button("Import backup") { importBackup(host) }
        ui.heading("Advanced")
        ui.button("Joined islands and borders") { host.show { JoinedIslandsScreen() } }
        ui.button("Island outlines details") { host.show { IslandOutlinesScreen() } }
        ui.button("Share offline maps report") { shareText(host, "Haluvi offline maps report", OfflineMaps.report()) }
        ui.button("Back") { host.back() }
        return scroll
    }
}

/** Atolls with counts, nearest islands first; opens atoll and island details. */
class AtollsScreen : Screen() {
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Atolls and islands")
        ui.button("Find island, resort or airport") {
            host.show { AtollPickerScreen { isl -> host.show { IslandDetailsScreen(isl) } } }
        }
        g.location.fix?.let { f ->
            ui.heading("Nearest islands")
            g.islands.allIslands.sortedBy { Geo.distance(f.lat, f.lon, it.lat, it.lon) }.take(4)
                .forEach { isl -> ui.button(islandLabel(isl)) { host.show { IslandDetailsScreen(isl) } } }
        }
        ui.heading("Atolls")
        g.islands.atolls.forEach { a -> ui.button(atollLabel(a)) { host.show { AtollDetailsScreen(a) } } }
        ui.button("Back") { host.back() }
        return scroll
    }
}

/** One atoll: counts, offline map and Google places for the whole atoll, and its islands. */
class AtollDetailsScreen(private val atoll: Atoll) : Screen(), OfflineProgressView {
    private var progressView: TextView? = null
    override fun showProgress(text: String) { progressView?.text = text }

    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title(atoll.fullName)
        if (OfflineDownload.active) {
            progressView = ui.info(OfflineDownload.progress)
            ui.button("Cancel download", color = UiColors.BUTTON_STOP) { OfflineDownload.cancel(host) }
            ui.button("Back") { host.back() }
            return scroll
        }
        progressView = null
        val done = atoll.islands.count { OfflineMaps.status(it.key) != null }
        ui.info("${atoll.islands.size} inhabited islands. " + placesBreakdown(atoll.islands.map { it.key }) + " " +
            (if (done == 0) "No OpenStreetMap map yet." else "OpenStreetMap map: $done of ${atoll.islands.size} islands."))
        if (OfflineDownload.lastResult.isNotBlank()) ui.info(OfflineDownload.lastResult)
        ui.heading("Islands")
        val f = g.location.fix
        val list = if (f != null) atoll.islands.sortedBy { Geo.distance(f.lat, f.lon, it.lat, it.lon) } else atoll.islands
        list.forEach { isl -> ui.button(islandLabel(isl, withAtoll = false)) { host.show { IslandDetailsScreen(isl) } } }
        ui.heading("Whole atoll")
        val any = atoll.islands.any { OfflineMaps.status(it.key) != null }
        ui.button(if (any) "Update OpenStreetMap map, free" else "Download OpenStreetMap map, free") { startOfflineDownload(host, atoll.islands, atoll.fullName) }
        ui.button("Get Google places, may cost money") { host.show { GooglePlacesScreen(atoll.islands to atoll.fullName) } }
        if (any) ui.button("Delete OpenStreetMap map", color = UiColors.BUTTON_STOP) {
            host.show { ConfirmScreen("Delete the OpenStreetMap map of ${atoll.fullName}: roads and downloaded places? Places you saved yourself and Google places are kept.", "Delete") {
                val n = OfflineMaps.delete(atoll.islands); host.speak("$n places deleted."); host.back()
            } }
        }
        ui.button("Back") { host.back() }
        return scroll
    }
}

/**
 * One island: its type, outline, offline map and places, with everything you can do with it in one place:
 * go there, see its places, download or delete its maps and places, rename it, change its type.
 */
class IslandDetailsScreen(private val island: Island) : Screen(), OfflineProgressView {
    private var progressView: TextView? = null
    override fun showProgress(text: String) { progressView?.text = text }

    override fun create(): View {
        val g = AppGraph
        val isl = island
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title(if (isl.atollFullName.isBlank()) isl.name else isl.fullName)
        if (OfflineDownload.active) {
            progressView = ui.info(OfflineDownload.progress)
            ui.button("Cancel download", color = UiColors.BUTTON_STOP) { OfflineDownload.cancel(host) }
            ui.button("Back") { host.back() }
            return scroll
        }
        progressView = null
        val parts = ArrayList<String>()
        parts.add(typeName(isl.type) + (if (isl.hasAirport && isl.type != "airport") ", with airport" else "") + ".")
        if (isl.name != isl.originalName) parts.add("Your name. Original name: ${isl.originalName}.")
        g.location.fix?.let { f -> parts.add("${Phrases.distance(Geo.distance(f.lat, f.lon, isl.lat, isl.lon))} from you.") }
        parts.add(placesBreakdown(listOf(isl.key)))
        if (isl.atollIndex >= 0) {
            val st = OfflineMaps.status(isl.key)
            parts.add(if (st != null) "OpenStreetMap map downloaded: ${st.roads} roads." else "No OpenStreetMap map yet.")
        }
        parts.add(if (g.shapes.has(isl.key)) "Outline: yes, arrival at the shore is exact." else "Outline: no, the centre point is used.")
        ui.info(parts.joinToString(" "))
        if (OfflineDownload.lastResult.isNotBlank()) ui.info(OfflineDownload.lastResult)

        ui.button("Go to this island", color = UiColors.BUTTON_ON) {
            Selection.islandTo = isl; host.say("To: ${isl.name}"); host.showTrip { IslandsScreen() }
        }
        ui.button("Places on this island: ${g.store.offlineCount(isl.key)}") {
            host.show { MyPlacesScreen(filter = PlaceFilter(islandKey = isl.key, label = isl.name)) }
        }

        ui.heading("Get places")
        if (isl.atollIndex >= 0) {
            val have = OfflineMaps.status(isl.key) != null
            ui.button(if (have) "Update OpenStreetMap map, free" else "Download OpenStreetMap map, free") { startOfflineDownload(host, listOf(isl), isl.name) }
        }
        ui.button("Get Google places, may cost money") { host.show { GooglePlacesScreen(listOf(isl) to isl.name) } }

        val osm = g.store.countForIsland(isl.key, SavedLocation.SOURCE_OSM_MAP)
        val google = g.store.countForIsland(isl.key, SavedLocation.SOURCE_GOOGLE)
        if ((isl.atollIndex >= 0 && OfflineMaps.status(isl.key) != null) || osm > 0 || google > 0) ui.heading("Delete")
        if (isl.atollIndex >= 0 && OfflineMaps.status(isl.key) != null) ui.button("Delete OpenStreetMap map and its $osm places", color = UiColors.BUTTON_STOP) {
            host.show { ConfirmScreen("Delete the OpenStreetMap map of ${isl.name}: its roads and $osm downloaded places? Places you saved yourself and Google places are kept.", "Delete") {
                val n = OfflineMaps.delete(listOf(isl)); host.speak("$n places deleted."); host.back()
            } }
        }
        if (google > 0) ui.button("Delete Google places: $google", color = UiColors.BUTTON_STOP) {
            host.show { ConfirmScreen("Delete all $google Google places on ${isl.name}?", "Delete") {
                val n = g.store.deleteForIsland(isl.key, SavedLocation.SOURCE_GOOGLE); host.speak("$n places deleted."); host.back()
            } }
        }

        // Joined islands: set the border between this island and its neighbour.
        mv.haluvi.navigation.nav.JoinedIslands.groupOf(isl)?.filter { it.key != isl.key }?.forEach { other ->
            ui.button("Joined with ${other.name}: border") {
                host.show { BorderScreen(isl.key, isl.name, other.key, other.name) { mv.haluvi.navigation.nav.JoinedIslands.recheckGroup(mv.haluvi.navigation.nav.JoinedIslands.groupOf(isl)!!) } }
            }
        }
        ui.button("Areas on this island: ${mv.haluvi.navigation.nav.Areas.of(isl.key).size}") { host.show { AreasScreen(isl) } }

        ui.heading("Change island")
        ui.button("Rename") {
            host.show { TextInputScreen("Rename island", "Island name", isl.name) { n ->
                IslandOverrides.set(isl, name = n); host.speak("Renamed to $n."); host.back()
            } }
        }
        ui.button("Type: ${typeName(isl.type)}. Change") {
            host.show {
                ChoiceScreen("Island type", "For example when the government changes an island's status.", listOf(
                    "Inhabited" to { IslandOverrides.set(isl, type = "inhabited"); host.speak("Inhabited."); host.back() },
                    "Uninhabited" to { IslandOverrides.set(isl, type = "island"); host.speak("Uninhabited."); host.back() },
                    "Resort" to { IslandOverrides.set(isl, type = "resort"); host.speak("Resort."); host.back() },
                    "Airport" to { IslandOverrides.set(isl, type = "airport"); host.speak("Airport."); host.back() }
                ))
            }
        }
        if (isl.type != "airport") ui.button("Has an airport: ${if (isl.hasAirport) "Yes" else "No"}") {
            IslandOverrides.set(isl, hasAirport = !isl.hasAirport); host.say(if (isl.hasAirport) "Has an airport" else "No airport"); rebuild()
        }
        if (isl.type == "airport" || isl.hasAirport) ui.button("Airport name: ${isl.airportName.ifBlank { "not set" }}") {
            host.show { TextInputScreen("Airport name", "For example Velana International Airport", isl.airportName) { n ->
                IslandOverrides.set(isl, airportName = n); host.speak("Saved."); host.back()
            } }
        }
        if (IslandOverrides.hasChange(isl.key)) ui.button("Restore the app's original name and type") {
            IslandOverrides.restore(isl); host.speak("Restored."); rebuild()
        }
        ui.button("Back") { host.back() }
        return scroll
    }
}

/** All joined islands: how each pair is split, and setting the border. */
class JoinedIslandsScreen : Screen() {
    override fun create(): View {
        val g = AppGraph
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Joined islands and borders")
        ui.info("Some islands are joined by land and share one outline, for example Hulhudhoo and Meedhoo. Each place goes to the island named in its address. Otherwise the border decides: the one you set, or a line learned from addresses and postcodes. Setting the border yourself is the most reliable.")
        if (g.settings.joinedRecheckMessage.isNotBlank()) ui.info(g.settings.joinedRecheckMessage)
        val pairs = mv.haluvi.navigation.nav.JoinedIslands.pairs()
        if (pairs.isEmpty()) ui.info("No joined islands.")
        pairs.forEach { (a, b) ->
            ui.info(mv.haluvi.navigation.nav.JoinedIslands.pairText(a, b))
            ui.button("Set border: ${a.name} and ${b.name}") {
                host.show { BorderScreen(a.key, a.name, b.key, b.name) { mv.haluvi.navigation.nav.JoinedIslands.groupOf(a)?.let { mv.haluvi.navigation.nav.JoinedIslands.recheckGroup(it) } ?: 0 } }
            }
        }
        ui.button("Back") { host.back() }
        return scroll
    }
}

/**
 * Sets the border between two joined islands (or two areas): standing at the border, or at a place such as a
 * school at the joining point. [recheck] re-checks the places and returns how many moved.
 */
class BorderScreen(private val aKey: String, private val aName: String, private val bKey: String, private val bName: String,
                   private val recheck: () -> Int) : Screen() {
    override fun create(): View {
        val J = mv.haluvi.navigation.nav.JoinedIslands
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Border: $aName and $bName")
        val cur = J.borderOf(aKey, bKey)
        ui.info(if (cur != null) "A border you set is used." else "No border set yet: a learned line is used.")
        fun apply(lat: Double, lon: Double, how: String) {
            J.setBorder(aKey, bKey, mv.haluvi.navigation.core.LatLon(lat, lon))
            val n = recheck()
            host.speak("Border set $how. $n places moved."); host.backTo(this)
            rebuild()
        }
        ui.button("Set border here", color = UiColors.BUTTON_ON) {
            val f = AppGraph.location.fix
            if (f == null || f.ageMs > 15_000 || f.accuracy > 25) host.speak("GPS is not accurate enough. Wait in an open place and try again.")
            else apply(f.lat, f.lon, "here")
        }
        ui.button("Set border at a place") {
            val pick: (SavedLocation) -> Unit = { loc -> apply(loc.lat, loc.lon, "at ${loc.name}") }
            host.show {
                ChoiceScreen("Border at a place", "For example a school or mosque at the joining point.", listOf(
                    "My places" to { host.show { MyPlacesScreen(title = "Border place") { loc -> pick(loc) } } },
                    "Search" to { PlacePick.handler = pick; host.show { SearchScreen() } }
                ))
            }
        }
        if (cur != null) ui.button("Clear my border", color = UiColors.BUTTON_STOP) {
            J.setBorder(aKey, bKey, null); val n = recheck(); host.speak("Border cleared. $n places moved."); rebuild()
        }
        ui.button("Back") { host.back() }
        return scroll
    }
}

/** Areas inside an island (for example Hulhumalé Phase 1 and Phase 2). */
class AreasScreen(private val isl: Island) : Screen() {
    override fun create(): View {
        val g = AppGraph
        val A = mv.haluvi.navigation.nav.Areas
        val (scroll, ui) = UiBuilder.scrolling(host)
        titleView = ui.title("Areas on ${isl.name}")
        ui.info("The island stays one island for trips and island alerts. The area is added to what you hear, for example \"You are on Hulhumale, Phase 2\".")
        val list = A.of(isl.key)
        val counts = HashMap<String, Int>()
        if (list.size >= 2) g.store.items.filter { it.islandKey == isl.key }.forEach { p ->
            A.at(isl.key, p.lat, p.lon, p.address)?.let { counts[it.id] = (counts[it.id] ?: 0) + 1 }
        }
        list.forEach { a ->
            ui.button("${a.name}: ${counts[a.id] ?: 0} places") {
                host.show { ChoiceScreen(a.name, null, listOf(
                    "Places in ${a.name}" to { host.show { MyPlacesScreen(filter = PlaceFilter(islandKey = isl.key, areaId = a.id, label = "${isl.name}, ${a.name}")) } },
                    "Rename" to { host.show { TextInputScreen("Rename area", "Area name", a.name) { n -> A.rename(a, n); host.speak("Saved."); host.back(); host.back() } } },
                    "Move its point to my location" to {
                        val f = g.location.fix
                        if (f == null || f.accuracy > 30) host.speak("GPS is not accurate enough.") else { A.movePoint(a, f.lat, f.lon); host.speak("Moved."); host.back() }
                    },
                    "Delete" to { host.show { ConfirmScreen("Delete the area ${a.name}? Places are kept.", "Delete") { A.delete(a); host.back(); host.back() } } }
                )) }
            }
        }
        for (i in list.indices) for (j in i + 1 until list.size) {
            val x = list[i]; val y = list[j]
            ui.info(A.pairText(x, y))
            ui.button("Set border: ${x.name} and ${y.name}") { host.show { BorderScreen(x.zone.key, x.name, y.zone.key, y.name) { 0 } } }
        }
        ui.button("Add an area here") {
            val f = g.location.fix
            if (f == null || f.accuracy > 30) host.speak("GPS is not accurate enough. Stand in the middle of the area in an open place.")
            else host.show { TextInputScreen("New area", "Area name, for example Phase 2", "") { n -> A.add(isl.key, n, f.lat, f.lon); host.speak("Area $n added."); host.back() } }
        }
        ui.button("Back") { host.back() }
        return scroll
    }
}
