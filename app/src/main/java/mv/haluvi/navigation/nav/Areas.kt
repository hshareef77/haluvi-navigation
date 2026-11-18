package mv.haluvi.navigation.nav

import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.LatLon
import mv.haluvi.navigation.data.Island
import mv.haluvi.navigation.data.IslandShapes
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Areas inside one island (for example Hulhumalé Phase 1 and Phase 2). The island stays one island for trips
 * and island alerts; the area is added to what you hear: "You are on Hulhumalé, Phase 2". Each area has a
 * reference point; between two areas the border is the one you set, else a line learned from places whose
 * address names the area, else the halfway line.
 */
object Areas {
    data class Area(val id: String, val islandKey: String, var name: String, var lat: Double, var lon: Double) {
        val zone: JoinedIslands.Zone get() = JoinedIslands.Zone("area:$id", name, LatLon(lat, lon))
    }

    private var file: File? = null
    private val items = ArrayList<Area>()

    private fun ensure() {
        if (file != null) return
        val f = File(AppGraph.appContext.filesDir, "areas.json"); file = f
        if (f.exists()) runCatching {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(Area(o.getString("id"), o.getString("island"), o.getString("name"), o.getDouble("lat"), o.getDouble("lon")))
            }
        } else {
            // Built-in: Hulhumalé Phase 1 (south) and Phase 2 (north). Set the border on the spot for best results.
            items.add(Area("hulhumale1", IslandShapes.HULHUMALE, "Phase 1", 4.2140, 73.5400))
            items.add(Area("hulhumale2", IslandShapes.HULHUMALE, "Phase 2", 4.2330, 73.5445))
            save()
        }
    }

    private fun save() {
        val arr = JSONArray()
        items.forEach { a -> arr.put(JSONObject().put("id", a.id).put("island", a.islandKey).put("name", a.name).put("lat", a.lat).put("lon", a.lon)) }
        runCatching { file?.writeText(arr.toString()) }
        cacheVersion++
    }

    @Volatile private var cacheVersion = 0

    @Synchronized fun of(islandKey: String): List<Area> { ensure(); return items.filter { it.islandKey == islandKey } }
    @Synchronized fun all(): List<Area> { ensure(); return items.toList() }

    @Synchronized
    fun add(islandKey: String, name: String, lat: Double, lon: Double): Area {
        ensure()
        val a = Area("a" + System.currentTimeMillis().toString(36), islandKey, name.trim(), lat, lon)
        items.add(a); save(); return a
    }

    @Synchronized fun rename(a: Area, name: String) { ensure(); a.name = name.trim(); save() }
    @Synchronized fun movePoint(a: Area, lat: Double, lon: Double) { ensure(); a.lat = lat; a.lon = lon; save() }
    @Synchronized fun delete(a: Area) { ensure(); items.removeAll { it.id == a.id }; save() }

    /** "Phase 2", "phase II", "Phase-2" all become "phase2". */
    private fun norm(s: String): String = s.lowercase()
        .replace(Regex("\\bii\\b"), "2").replace(Regex("\\biii\\b"), "3").replace(Regex("\\bi\\b"), "1")
        .replace(Regex("[^a-z0-9]"), "")

    private fun fromAddress(address: String?, areas: List<Area>): Area? {
        if (address.isNullOrBlank()) return null
        val a = norm(address)
        return areas.filter { norm(it.name).length >= 3 && a.contains(norm(it.name)) }.maxByOrNull { norm(it.name).length }
    }

    private fun splitOf(x: Area, y: Area): JoinedIslands.Split = JoinedIslands.split(x.zone, y.zone) {
        val pair = listOf(x, y)
        AppGraph.store.items.mapNotNull { p ->
            if (p.islandKey != x.islandKey) return@mapNotNull null
            fromAddress(p.address, pair)?.let { LatLon(p.lat, p.lon) to (it.id == y.id) }
        }
    }

    /** The area of a position (and address) on an island, or null when the island has fewer than 2 areas. */
    fun at(islandKey: String, lat: Double, lon: Double, address: String? = null): Area? {
        val list = of(islandKey)
        if (list.size < 2) return list.firstOrNull()
        return fromAddress(address, list) ?: JoinedIslands.byZones(list, { it.zone }, { x, y -> splitOf(x, y) }, lat, lon)
    }

    /** ", Phase 2" for announcements, or "". */
    fun suffix(isl: Island, lat: Double, lon: Double): String = at(isl.key, lat, lon)?.let { ", ${it.name}" } ?: ""

    fun pairText(x: Area, y: Area): String {
        val sp = splitOf(x, y)
        return "${x.name} and ${y.name}: " + when {
            sp.userSet -> "border set by you."
            sp.labelled == 0 -> "no addresses name these areas yet; the halfway line between their points is used."
            else -> "line learned from ${sp.labelled} places whose address names the area; ${sp.correct * 100 / sp.labelled}% agree."
        }
    }
}
