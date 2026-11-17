package mv.haluvi.navigation.ui

import android.view.View
import android.widget.TextView
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.data.Island
import mv.haluvi.navigation.data.IslandOverrides
import mv.haluvi.navigation.data.SavedLocation
import mv.haluvi.navigation.nav.OfflineMaps
import mv.haluvi.navigation.nav.Phrases

private fun typeName(t: String) = when (t) {
    "inhabited" -> "Inhabited"; "resort" -> "Resort"; "airport" -> "Airport"; else -> "Uninhabited"
}

/**
 * One island: its type, outline, offline map and places, with everything you can do with it in one place:
 * go there, see its places, download or delete its offline map, rename it, change its type.
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
        parts.add(if (g.shapes.has(isl.key)) "Outline: yes, arrival at the shore is exact." else "Outline: no, the centre point is used.")
        g.location.fix?.let { f -> parts.add("${Phrases.distance(Geo.distance(f.lat, f.lon, isl.lat, isl.lon))} from you.") }
        ui.info(parts.joinToString(" "))
        if (isl.atollIndex >= 0) ui.info(OfflineMaps.islandText(isl) + ".")
        val places = g.store.countForIsland(isl.key)
        if (OfflineDownload.lastResult.isNotBlank()) ui.info(OfflineDownload.lastResult)

        ui.button("Go to this island", color = UiColors.BUTTON_ON) {
            Selection.islandTo = isl; host.say("To: ${isl.name}"); host.showTrip { IslandsScreen() }
        }
        ui.button("Places on this island: $places") { host.show { MyPlacesScreen(filter = PlaceFilter(islandKey = isl.key, label = isl.name)) } }

        if (isl.atollIndex >= 0) {
            ui.heading("Offline map")
            val have = OfflineMaps.status(isl.key) != null
            ui.button(if (have) "Update offline map" else "Download offline map") { startOfflineDownload(host, listOf(isl), isl.name) }
            if (have) ui.button("Delete offline map", color = UiColors.BUTTON_STOP) {
                host.show { ConfirmScreen("Delete the offline map of ${isl.name}: its roads and downloaded places? Places you saved yourself and Google places are kept.", "Delete") {
                    val n = OfflineMaps.delete(listOf(isl)); host.speak("$n places deleted."); host.back()
                } }
            }
        }
        val google = g.store.countForIsland(isl.key, SavedLocation.SOURCE_GOOGLE)
        if (google > 0) ui.button("Delete Google places on this island: $google", color = UiColors.BUTTON_STOP) {
            host.show { ConfirmScreen("Delete all $google Google places on ${isl.name}?", "Delete") {
                val n = g.store.deleteForIsland(isl.key, SavedLocation.SOURCE_GOOGLE); host.speak("$n places deleted."); host.back()
            } }
        }

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
        if (IslandOverrides.hasChange(isl.key)) ui.button("Restore the app's original name and type") {
            IslandOverrides.restore(isl); host.speak("Restored."); rebuild()
        }
        ui.button("Back") { host.back() }
        return scroll
    }
}
