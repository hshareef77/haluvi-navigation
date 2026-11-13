package mv.haluvi.navigation.net

import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.data.DownloadedRoad
import org.json.JSONArray
import org.json.JSONObject

/** OpenStreetMap search (Overpass + Nominatim) and island map data download. */
class OpenStreetMap {
    private val overpassServers = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    )

    data class MapCategory(val key: String, val label: String)

    /** Download categories for island map data (same set as Lua v73). */
    val mapCategories = listOf(
        MapCategory("mosque", "mosques and masjids"),
        MapCategory("shop", "shops, stores, markets and supermarkets"),
        MapCategory("food", "restaurants, cafes and food places"),
        MapCategory("school", "schools and education places"),
        MapCategory("health", "health places and pharmacies"),
        MapCategory("ferry", "ferry terminals, harbors and jetties"),
        MapCategory("bank", "banks and ATMs"),
        MapCategory("police", "police stations"),
        MapCategory("hotel", "hotels and guest houses"),
        MapCategory("common", "other named map places")
    )

    fun categoryForQuery(text: String): String {
        val q = text.trim().lowercase()
        fun has(vararg w: String) = w.any { q.contains(it) }
        return when {
            has("mosque", "masjid") -> "mosque"
            has("hospital", "health", "clinic", "pharmacy", "doctor") -> "health"
            has("school", "college", "kindergarten", "university") -> "school"
            has("shop", "store", "market", "supermarket") -> "shop"
            has("ferry", "harbor", "harbour", "jetty", "port", "pier") -> "ferry"
            has("restaurant", "cafe", "food") -> "food"
            has("bank", "atm") -> "bank"
            has("police") -> "police"
            has("hotel", "guest") -> "hotel"
            q == "all" || has("nearby", "common", "places") -> "common"
            else -> "keyword"
        }
    }

    private fun filters(category: String): List<String> = when (category) {
        "mosque" -> listOf("[\"amenity\"=\"place_of_worship\"][\"religion\"=\"muslim\"]", "[\"building\"=\"mosque\"]")
        "health" -> listOf("[\"amenity\"=\"hospital\"]", "[\"amenity\"=\"clinic\"]", "[\"amenity\"=\"doctors\"]", "[\"amenity\"=\"pharmacy\"]", "[\"healthcare\"]")
        "school" -> listOf("[\"amenity\"=\"school\"]", "[\"amenity\"=\"college\"]", "[\"amenity\"=\"kindergarten\"]", "[\"amenity\"=\"university\"]")
        "shop" -> listOf("[\"shop\"]", "[\"amenity\"=\"marketplace\"]")
        "ferry" -> listOf("[\"amenity\"=\"ferry_terminal\"]", "[\"man_made\"=\"pier\"]", "[\"harbour\"]", "[\"public_transport\"]")
        "food" -> listOf("[\"amenity\"=\"restaurant\"]", "[\"amenity\"=\"cafe\"]", "[\"amenity\"=\"fast_food\"]")
        "bank" -> listOf("[\"amenity\"=\"bank\"]", "[\"amenity\"=\"atm\"]")
        "police" -> listOf("[\"amenity\"=\"police\"]")
        "hotel" -> listOf("[\"tourism\"=\"hotel\"]", "[\"tourism\"=\"guest_house\"]", "[\"tourism\"=\"apartment\"]")
        "common" -> listOf("[\"amenity\"][\"name\"]", "[\"shop\"][\"name\"]", "[\"tourism\"][\"name\"]", "[\"public_transport\"][\"name\"]", "[\"man_made\"=\"pier\"]", "[\"leisure\"][\"name\"]", "[\"office\"][\"name\"]")
        else -> listOf("[\"name\"][\"amenity\"]", "[\"name\"][\"shop\"]", "[\"name\"][\"tourism\"]", "[\"name\"][\"public_transport\"]", "[\"name\"][\"leisure\"]", "[\"name\"][\"office\"]", "[\"name\"][\"building\"]")
    }

    private fun overpass(query: String): String {
        var last: Exception? = null
        for (server in overpassServers) {
            try {
                return Http.post(server, "data=" + Http.enc(query), "application/x-www-form-urlencoded", connectMs = 10000, readMs = 40000)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("OpenStreetMap is not available")
    }

    /** Places around a point. [keyword] filters by name when the query is not a known category. */
    fun searchNearby(text: String, lat: Double, lon: Double, radius: Int): List<OnlinePlace> {
        val category = categoryForQuery(text)
        val around = "(around:$radius,$lat,$lon)"
        val parts = filters(category).joinToString("") { f -> "node$around$f;way$around$f;" }
        val json = overpass("[out:json][timeout:30];($parts);out center tags;")
        var items = parseOverpass(json)
        if (category == "keyword") {
            val words = text.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
            items = items.filter { p -> words.any { w -> p.name.lowercase().contains(w) } }
        }
        items.forEach { it.distance = Geo.distance(lat, lon, it.lat, it.lon); it.bearing = Geo.bearing(lat, lon, it.lat, it.lon) }
        return items.sortedBy { it.distance }
    }

    private fun parseOverpass(json: String): List<OnlinePlace> {
        val arr = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<OnlinePlace>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            val lat = if (e.has("lat")) e.optDouble("lat") else e.optJSONObject("center")?.optDouble("lat") ?: continue
            val lon = if (e.has("lon")) e.optDouble("lon") else e.optJSONObject("center")?.optDouble("lon") ?: continue
            val type = tagType(tags)
            val name = tags.optString("name:en").ifBlank { tags.optString("name") }.ifBlank { unnamedLabel(type) }
            val addr = listOf(tags.optString("addr:street"), tags.optString("addr:housename"), tags.optString("addr:city"))
                .filter { it.isNotBlank() }.joinToString(", ")
            val p = OnlinePlace(name, lat, lon, addr, type, "osm")
            if (seen.add(p.key)) out.add(p)
        }
        return out
    }

    private fun tagType(t: JSONObject): String {
        for (k in listOf("amenity", "shop", "tourism", "healthcare", "public_transport", "man_made", "leisure", "office", "building", "harbour")) {
            val v = t.optString(k)
            if (v.isNotBlank() && v != "yes") return v.replace('_', ' ')
            if (v == "yes") return k
        }
        return ""
    }

    private fun unnamedLabel(type: String) = if (type.isBlank()) "Unnamed place" else "Unnamed " + type

    /** Name search across the Maldives (and the given island first). */
    fun searchNominatim(text: String, islandName: String?, lat: Double?, lon: Double?): List<OnlinePlace> {
        val queries = ArrayList<String>()
        if (!islandName.isNullOrBlank()) queries.add("$text $islandName Maldives")
        queries.add("$text Maldives")
        val out = LinkedHashMap<String, OnlinePlace>()
        var lastErr: Exception? = null
        for (q in queries) {
            try {
                val arr = JSONArray(Http.get("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=40&addressdetails=1&countrycodes=mv&q=${Http.enc(q)}"))
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = o.optString("name").ifBlank { o.optString("display_name").substringBefore(",") }.ifBlank { "Place" }
                    val p = OnlinePlace(name, o.optString("lat").toDouble(), o.optString("lon").toDouble(),
                        o.optString("display_name"), o.optString("type").replace('_', ' '), "osm")
                    out.putIfAbsent(p.key, p)
                }
                if (out.isNotEmpty()) break
            } catch (e: Exception) { lastErr = e }
        }
        if (out.isEmpty() && lastErr != null) throw lastErr
        val list = out.values.toList()
        if (lat != null && lon != null) list.forEach { it.distance = Geo.distance(lat, lon, it.lat, it.lon); it.bearing = Geo.bearing(lat, lon, it.lat, it.lon) }
        return list.sortedBy { it.distance ?: 0.0 }
    }

    /** Combined search: nearby Overpass first, Nominatim added for typed names (same strategy as Lua v45). */
    fun search(text: String, lat: Double?, lon: Double?, islandName: String?, wide: Boolean): List<OnlinePlace> {
        val category = categoryForQuery(text)
        val result = LinkedHashMap<String, OnlinePlace>()
        var err: Exception? = null
        if (lat != null && lon != null) {
            try { searchNearby(text, lat, lon, if (wide) 6000 else 2000).forEach { result.putIfAbsent(it.key, it) } } catch (e: Exception) { err = e }
        }
        if (category == "keyword" || result.isEmpty()) {
            try { searchNominatim(text, islandName, lat, lon).forEach { result.putIfAbsent(it.key, it) } } catch (e: Exception) { if (err == null) err = e }
        }
        if (result.isEmpty() && err != null) throw err
        return result.values.sortedBy { it.distance ?: 0.0 }
    }

    /** All places of one category around an island centre (island map download). */
    fun islandPlaces(categoryKey: String, lat: Double, lon: Double, radius: Int): List<OnlinePlace> =
        searchNearby(if (categoryKey == "common") "common" else categoryKey, lat, lon, radius)

    /** Roads (walkable/drivable ways with geometry) around an island centre, for junction detection. */
    fun islandRoads(lat: Double, lon: Double, radius: Int): List<DownloadedRoad> {
        val q = "[out:json][timeout:40];way(around:$radius,$lat,$lon)[\"highway\"~\"^(primary|secondary|tertiary|unclassified|residential|living_street|service|pedestrian|footway|path|track|road)$\"];out geom tags;"
        val arr = JSONObject(overpass(q)).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<DownloadedRoad>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val geom = e.optJSONArray("geometry") ?: continue
            val pts = (0 until geom.length()).map { j -> val g = geom.getJSONObject(j); doubleArrayOf(g.getDouble("lat"), g.getDouble("lon")) }
            if (pts.size < 2) continue
            val tags = e.optJSONObject("tags")
            val name = tags?.optString("name:en").orEmpty().ifBlank { tags?.optString("name").orEmpty() }.ifBlank { tags?.optString("ref").orEmpty() }
            out.add(DownloadedRoad(name, pts))
        }
        return out
    }
}
