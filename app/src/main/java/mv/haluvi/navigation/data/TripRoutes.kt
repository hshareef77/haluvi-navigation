package mv.haluvi.navigation.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One stop of a route: a place (bus stop, harbour, ...) or an island (ferry stop, with its outline). */
data class RouteStop(
    var name: String,
    var lat: Double,
    var lon: Double,
    /** "place" or "island". */
    val kind: String,
    /** For island stops: the island key, so its outline is used. */
    val islandKey: String = ""
) {
    val isIsland: Boolean get() = kind == KIND_ISLAND

    fun toJson(): JSONObject = JSONObject().put("name", name).put("lat", lat).put("lon", lon).put("kind", kind).put("island", islandKey)

    companion object {
        const val KIND_PLACE = "place"
        const val KIND_ISLAND = "island"
        fun fromJson(o: JSONObject): RouteStop? = runCatching {
            RouteStop(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"), o.optString("kind", KIND_PLACE), o.optString("island"))
        }.getOrNull()
    }
}

/** A fixed route with stops in order (a bus line, a ferry route, or both). */
data class TripRoute(val id: String, var name: String, val stops: MutableList<RouteStop>) {
    fun toJson(): JSONObject {
        val arr = JSONArray(); stops.forEach { arr.put(it.toJson()) }
        return JSONObject().put("id", id).put("name", name).put("stops", arr)
    }

    companion object {
        fun newId(): String = "route_" + UUID.randomUUID().toString().replace("-", "").take(12)
        fun fromJson(o: JSONObject): TripRoute? = runCatching {
            val arr = o.getJSONArray("stops")
            TripRoute(o.optString("id").ifBlank { newId() }, o.getString("name"),
                (0 until arr.length()).mapNotNull { RouteStop.fromJson(arr.getJSONObject(it)) }.toMutableList())
        }.getOrNull()
    }
}

/** The user's routes, stored as JSON in the app's private files. */
class TripRoutes(private val file: File) {
    private val items = ArrayList<TripRoute>()

    init { load() }

    @Synchronized fun all(): List<TripRoute> = items.toList()
    @Synchronized fun byId(id: String?): TripRoute? = items.firstOrNull { it.id == id }

    @Synchronized
    fun add(r: TripRoute) { items.removeAll { it.id == r.id }; items.add(r); save() }

    @Synchronized
    fun delete(id: String) { items.removeAll { it.id == id }; save() }

    /** Call after changing a route's name or stops. */
    @Synchronized
    fun save() {
        val arr = JSONArray(); items.forEach { arr.put(it.toJson()) }
        runCatching { file.writeText(arr.toString()) }
    }

    /** Adds routes from a backup. A route with the same name and the same stops is skipped. Returns (added, skipped). */
    @Synchronized
    fun import(arr: JSONArray): Pair<Int, Int> {
        var added = 0; var skipped = 0
        for (i in 0 until arr.length()) {
            val r = TripRoute.fromJson(arr.getJSONObject(i)) ?: continue
            val same = items.any { it.name.equals(r.name, true) && it.stops.map { s -> s.name } == r.stops.map { s -> s.name } }
            if (same) { skipped++; continue }
            items.removeAll { it.id == r.id }
            items.add(r); added++
        }
        save()
        return added to skipped
    }

    @Synchronized
    fun exportJson(): JSONArray { val arr = JSONArray(); items.forEach { arr.put(it.toJson()) }; return arr }

    private fun load() {
        items.clear()
        if (!file.exists()) return
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) TripRoute.fromJson(arr.getJSONObject(i))?.let { items.add(it) }
        }
    }
}
