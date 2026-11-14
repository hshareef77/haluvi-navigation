package mv.haluvi.navigation.data

import android.content.Context
import mv.haluvi.navigation.core.Geo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Category(val id: String, val name: String)

object Categories {
    val all = listOf(
        Category("none", "None"),
        Category("mosque", "Mosque"),
        Category("shop", "Shop"),
        Category("food", "Restaurant / Cafe"),
        Category("health", "Health"),
        Category("school", "School"),
        Category("ferry", "Ferry / Harbor"),
        Category("bank", "Bank / ATM"),
        Category("police", "Police"),
        Category("hotel", "Hotel / Guest house"),
        Category("other", "Other")
    )

    fun name(id: String?): String = when (id) {
        null, "" -> "None"
        "all" -> "All categories"
        else -> all.firstOrNull { it.id == id }?.name ?: "Other"
    }

    /** Guess a category from a place name / map tag text (used for online results). */
    fun guess(text: String): String {
        val t = text.lowercase()
        fun has(vararg w: String) = w.any { t.contains(it) }
        return when {
            has("mosque", "masjid", "miskiy", "place_of_worship") -> "mosque"
            has("hospital", "clinic", "health", "pharmacy", "doctor", "dental") -> "health"
            has("school", "college", "kindergarten", "university", "academy") -> "school"
            has("shop", "store", "market", "supermarket", "grocery", "mart") -> "shop"
            has("restaurant", "cafe", "café", "coffee", "food", "bakery", "fast_food") -> "food"
            has("ferry", "harbor", "harbour", "jetty", "pier", "terminal", "port") -> "ferry"
            has("bank", "atm") -> "bank"
            has("police") -> "police"
            has("hotel", "guest house", "guesthouse", "guest_house", "resort", "inn") -> "hotel"
            else -> "other"
        }
    }
}

data class SavedLocation(
    val id: String,
    var name: String,
    var lat: Double,
    var lon: Double,
    var accuracy: Double = 0.0,
    var savedTime: Long = System.currentTimeMillis() / 1000,
    var source: String = SOURCE_MANUAL,
    var categoryId: String = "none",
    var islandKey: String = "",
    var islandName: String = "",
    var address: String = "",
    /** Google place ID for places saved from Google (their position is refreshed every few weeks). */
    var placeId: String = ""
) {
    val isDownloadedMapPlace: Boolean get() = source == SOURCE_OSM_MAP
    val categoryName: String get() = Categories.name(categoryId)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("lat", lat).put("lon", lon)
        .put("accuracy", accuracy).put("savedTime", savedTime).put("source", source)
        .put("categoryId", categoryId).put("islandKey", islandKey).put("islandName", islandName)
        .put("address", address).put("placeId", placeId)

    companion object {
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_OSM_MAP = "osm_map"
        const val SOURCE_GOOGLE = "google"
        const val SOURCE_OSM_SEARCH = "osm_search"

        fun newId(): String = "loc_" + UUID.randomUUID().toString().replace("-", "").take(16)

        fun fromJson(o: JSONObject): SavedLocation? {
            if (!o.has("lat") || !o.has("lon")) return null
            return SavedLocation(
                id = o.optString("id").ifBlank { newId() },
                name = o.optString("name", "Location").ifBlank { "Location" },
                lat = o.optDouble("lat"), lon = o.optDouble("lon"),
                accuracy = o.optDouble("accuracy", 0.0),
                savedTime = o.optLong("savedTime", System.currentTimeMillis() / 1000),
                source = o.optString("source", SOURCE_MANUAL),
                categoryId = o.optString("categoryId", "none"),
                islandKey = o.optString("islandKey", ""),
                islandName = o.optString("islandName", ""),
                address = o.optString("address", ""),
                placeId = o.optString("placeId", "")
            ).takeIf { !it.lat.isNaN() && !it.lon.isNaN() }
        }
    }
}

data class PlaceHit(val loc: SavedLocation, val distance: Double, val bearing: Double)

/** A road or path from the map. [kind] is the OpenStreetMap highway type (residential, footway, ...). */
data class DownloadedRoad(val name: String, val points: List<DoubleArray>, val kind: String = "", val oneway: Boolean = false)

/**
 * Saved places (manual extra locations, downloaded OpenStreetMap places, saved online results)
 * and downloaded OpenStreetMap roads (for junction announcements in Explore).
 * Stored as JSON in the app's private files directory.
 */
class LocationStore(private val context: Context, private val islands: IslandRepository) {
    private val file = File(context.filesDir, "locations.json")
    private val roadsDir = File(context.filesDir, "roads").apply { mkdirs() }
    private val _items = ArrayList<SavedLocation>()
    val items: List<SavedLocation> get() = _items

    init { load() }

    @Synchronized
    fun load() {
        _items.clear()
        if (!file.exists()) return
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) SavedLocation.fromJson(arr.getJSONObject(i))?.let { ensureIsland(it); _items.add(it) }
        }
    }

    @Synchronized
    fun save() {
        val arr = JSONArray()
        _items.forEach { arr.put(it.toJson()) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) { file.writeText(arr.toString()); tmp.delete() }
    }

    fun ensureIsland(loc: SavedLocation) {
        if (loc.islandKey.isNotBlank() && islands.byKey(loc.islandKey) != null) {
            if (loc.islandName.isBlank()) loc.islandName = islands.byKey(loc.islandKey)!!.fullName
            return
        }
        islands.nearest(loc.lat, loc.lon)?.let { loc.islandKey = it.island.key; loc.islandName = it.island.fullName }
    }

    fun byId(id: String?): SavedLocation? = if (id.isNullOrBlank()) null else _items.firstOrNull { it.id == id }

    /** Finds a stored place that is the same as a new one (same name within 30 m, or any place within 3 m). */
    fun findDuplicate(name: String, lat: Double, lon: Double): SavedLocation? = _items.firstOrNull {
        val d = Geo.distance(lat, lon, it.lat, it.lon)
        (d <= 30 && it.name.equals(name.trim(), ignoreCase = true)) || d <= 3
    }

    /** Adds a place unless it already exists. Returns true when added. */
    @Synchronized
    fun add(loc: SavedLocation, persist: Boolean = true): Boolean {
        if (findDuplicate(loc.name, loc.lat, loc.lon) != null) return false
        ensureIsland(loc)
        _items.add(loc)
        if (persist) save()
        return true
    }

    @Synchronized
    fun update(loc: SavedLocation) {
        ensureIsland(loc)
        save()
    }

    @Synchronized
    fun delete(id: String) {
        _items.removeAll { it.id == id }
        save()
    }

    @Synchronized
    fun deleteDownloadedForIsland(islandKey: String): Int {
        val before = _items.size
        _items.removeAll { it.isDownloadedMapPlace && it.islandKey == islandKey }
        save()
        File(roadsDir, safeName(islandKey) + ".json").delete()
        return before - _items.size
    }

    fun countDownloadedForIsland(islandKey: String) = _items.count { it.isDownloadedMapPlace && it.islandKey == islandKey }

    fun nearest(lat: Double, lon: Double, query: String = "", category: String = "all", islandKey: String? = null): List<PlaceHit> {
        val q = query.trim().lowercase()
        return _items.asSequence()
            .filter { category == "all" || it.categoryId == category }
            .filter { islandKey == null || it.islandKey == islandKey }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.categoryName.lowercase().contains(q) || it.islandName.lowercase().contains(q) }
            .map { PlaceHit(it, Geo.distance(lat, lon, it.lat, it.lon), Geo.bearing(lat, lon, it.lat, it.lon)) }
            .sortedBy { it.distance }
            .toList()
    }

    fun filtered(query: String = "", category: String = "all"): List<SavedLocation> {
        val q = query.trim().lowercase()
        return _items.filter { (category == "all" || it.categoryId == category) && (q.isEmpty() || it.name.lowercase().contains(q) || it.islandName.lowercase().contains(q)) }
            .sortedBy { it.name.lowercase() }
    }

    // ---------------- Roads ----------------

    fun saveRoads(islandKey: String, roads: List<DownloadedRoad>) {
        val arr = JSONArray()
        roads.forEach { r ->
            val pts = JSONArray()
            r.points.forEach { p -> pts.put(JSONArray().put(p[0]).put(p[1])) }
            arr.put(JSONObject().put("name", r.name).put("points", pts).put("kind", r.kind).put("oneway", r.oneway))
        }
        File(roadsDir, safeName(islandKey) + ".json").writeText(arr.toString())
    }

    fun loadRoads(islandKey: String): List<DownloadedRoad> {
        val f = File(roadsDir, safeName(islandKey) + ".json")
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val pts = o.getJSONArray("points")
                DownloadedRoad(o.optString("name"), (0 until pts.length()).map { j ->
                    val p = pts.getJSONArray(j); doubleArrayOf(p.getDouble(0), p.getDouble(1))
                }, o.optString("kind"), o.optBoolean("oneway"))
            }
        }.getOrDefault(emptyList())
    }

    fun roadCount(islandKey: String): Int = loadRoads(islandKey).size

    private fun safeName(key: String) = key.replace(Regex("[^A-Za-z0-9_-]"), "_")

    // ---------------- Import / export ----------------

    fun exportJson(): String {
        val arr = JSONArray()
        _items.forEach { arr.put(it.toJson()) }
        return JSONObject().put("app", "Haluvi Navigation").put("version", 1).put("locations", arr).toString(2)
    }

    /**
     * Imports either a Haluvi JSON export or the old Lua app file "nav_assistant_locations.txt"
     * (pipe separated: id|name|lat|lon|accuracy|savedTime|road|island|atoll|dist|source|categoryId|...).
     * Returns (added, skipped).
     */
    @Synchronized
    fun import(text: String): Pair<Int, Int> {
        var added = 0
        var skipped = 0
        val parsed = ArrayList<SavedLocation>()
        val trimmed = text.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val arr = if (trimmed.startsWith("{")) JSONObject(trimmed).optJSONArray("locations") ?: JSONArray() else JSONArray(trimmed)
            for (i in 0 until arr.length()) SavedLocation.fromJson(arr.getJSONObject(i))?.let { parsed.add(it) }
        } else {
            trimmed.lineSequence().forEach { line ->
                val p = line.split("|").map { unescLegacy(it) }
                if (p.size < 4) return@forEach
                val lat = p[2].toDoubleOrNull() ?: return@forEach
                val lon = p[3].toDoubleOrNull() ?: return@forEach
                val legacySource = p.getOrNull(10).orEmpty()
                val source = when {
                    legacySource == "osm_map" -> SavedLocation.SOURCE_OSM_MAP
                    legacySource.contains("google", true) -> SavedLocation.SOURCE_GOOGLE
                    legacySource.contains("osm", true) || legacySource.contains("openstreet", true) -> SavedLocation.SOURCE_OSM_SEARCH
                    else -> SavedLocation.SOURCE_MANUAL
                }
                val cat = p.getOrNull(11).orEmpty().ifBlank { "none" }
                parsed.add(SavedLocation(
                    id = p[0].ifBlank { SavedLocation.newId() },
                    name = p[1].ifBlank { "Location" },
                    lat = lat, lon = lon,
                    accuracy = p.getOrNull(4)?.toDoubleOrNull() ?: 0.0,
                    savedTime = p.getOrNull(5)?.toLongOrNull() ?: (System.currentTimeMillis() / 1000),
                    source = source,
                    categoryId = if (Categories.all.any { it.id == cat }) cat else "other"
                ))
            }
        }
        for (loc in parsed) {
            if (byId(loc.id) != null || findDuplicate(loc.name, loc.lat, loc.lon) != null) { skipped++; continue }
            ensureIsland(loc)
            _items.add(loc)
            added++
        }
        if (added > 0) save()
        return added to skipped
    }

    private fun unescLegacy(s: String) = s.replace("%0A", "\n").replace("%7C", "|").replace("%25", "%").trim()
}
