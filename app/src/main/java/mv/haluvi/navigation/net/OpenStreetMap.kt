package mv.haluvi.navigation.net

import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.core.LatLon
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

    /** Optional progress messages ("Asking map server 2 of 3"), set by downloads. Called on a background thread. */
    @Volatile var onStatus: ((String) -> Unit)? = null

    /**
     * Sends a query to the Overpass servers, one after another. [readSeconds]: how long one server may
     * take (big requests like an atoll's outlines need longer). While waiting, [onStatus] gets
     * "Waiting for map server 1, 40 seconds" every 10 seconds.
     */
    private fun overpass(query: String, readSeconds: Int = 30): String {
        var last: Exception? = null
        val started = System.currentTimeMillis()
        for ((i, server) in overpassServers.withIndex()) {
            if (Http.cancelledSince(started)) throw java.io.IOException("Cancelled")
            onStatus?.invoke("Asking map server ${i + 1} of ${overpassServers.size}.")
            val askedAt = System.currentTimeMillis()
            val timer = java.util.Timer(true)
            timer.scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() {
                    val secs = ((System.currentTimeMillis() - askedAt) / 1000).toInt()
                    onStatus?.invoke("Waiting for map server ${i + 1}, $secs seconds.")
                }
            }, 10_000L, 10_000L)
            try {
                val text = Http.post(server, "data=" + Http.enc(query), "application/x-www-form-urlencoded", connectMs = 10000, readMs = readSeconds * 1000)
                onStatus?.invoke("Received answer from map server ${i + 1}.")
                return text
            } catch (e: Exception) {
                last = e
                if (Http.cancelledSince(started)) throw e
                onStatus?.invoke("Map server ${i + 1} is busy or not answering.")
            } finally {
                timer.cancel()
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

    private fun isBusStop(t: JSONObject): Boolean =
        t.optString("highway") == "bus_stop" || t.optString("amenity") == "bus_station" ||
            (t.has("public_transport") && (t.optString("bus") == "yes" || t.optString("amenity") == "bus_station"))

    private fun tagType(t: JSONObject): String {
        if (t.optString("amenity") == "bus_station") return "bus station"
        if (isBusStop(t)) return "bus stop"
        for (k in listOf("aeroway", "amenity", "shop", "tourism", "healthcare", "public_transport", "man_made", "leisure", "office", "building", "harbour")) {
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

    /**
     * A closed island ring from OpenStreetMap.
     * [type]: "inhabited", "resort", "airport" or "island" (no people, no resort).
     */
    data class Outline(val name: String, val ring: List<LatLon>, val type: String, val places: List<NamedPoint> = emptyList())

    /** A named village/town/island point inside an outline (used to place joined islands exactly). */
    data class NamedPoint(val name: String, val lat: Double, val lon: Double)

    private data class MapPoint(val lat: Double, val lon: Double, val name: String, val kind: String)

    /**
     * Island outlines inside a box: islands tagged as islands, plus coastline pieces joined into
     * complete islands (big joined islands like Malé or Addu are often stored in pieces).
     * Unnamed outlines get their name from a named place, resort or airport inside them.
     */
    fun islandOutlines(south: Double, west: Double, north: Double, east: Double): List<Outline> {
        val bbox = "$south,$west,$north,$east"
        val q = "[out:json][timeout:180];(" +
            "way[\"place\"~\"^(island|islet)$\"]($bbox);relation[\"place\"~\"^(island|islet)$\"]($bbox);" +
            "way[\"natural\"=\"coastline\"]($bbox);" +
            ");out geom tags;" +
            "(node[\"place\"~\"^(island|islet|city|town|village|hamlet)$\"]($bbox);" +
            "nwr[\"tourism\"~\"^(resort|hotel)$\"]($bbox);nwr[\"aeroway\"=\"aerodrome\"]($bbox););out center tags;"
        val arr = JSONObject(overpass(q, readSeconds = 90)).optJSONArray("elements") ?: return emptyList()
        fun geom(a: JSONArray?): List<LatLon> {
            if (a == null) return emptyList()
            return (0 until a.length()).mapNotNull { j -> a.optJSONObject(j)?.let { LatLon(it.getDouble("lat"), it.getDouble("lon")) } }
        }
        fun closed(g: List<LatLon>) = g.size >= 4 && Geo.distance(g.first().lat, g.first().lon, g.last().lat, g.last().lon) < 5

        val named = ArrayList<Pair<String, List<LatLon>>>()
        val coastPieces = ArrayList<List<LatLon>>()
        val points = ArrayList<MapPoint>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: JSONObject()
            val name = tags.optString("name:en").ifBlank { tags.optString("name") }
            val isIslandTag = tags.optString("place").let { it == "island" || it == "islet" }
            val geometry = e.optJSONArray("geometry")
            // Points (place nodes, resorts, airports): from nodes, or the centre of ways/relations.
            val pLat = if (e.has("lat")) e.optDouble("lat") else e.optJSONObject("center")?.optDouble("lat")
            val pLon = if (e.has("lon")) e.optDouble("lon") else e.optJSONObject("center")?.optDouble("lon")
            if (geometry == null && e.optString("type") != "relation" || e.has("center")) {
                if (pLat != null && pLon != null) {
                    val kind = when {
                        tags.optString("aeroway") == "aerodrome" -> "airport"
                        tags.optString("tourism") == "resort" || tags.optString("tourism") == "hotel" -> "resort"
                        isIslandTag -> "island"
                        tags.has("place") -> "inhabited"
                        else -> ""
                    }
                    if (kind.isNotEmpty()) points.add(MapPoint(pLat, pLon, name, kind))
                }
                if (e.has("center")) continue
            }
            when (e.optString("type")) {
                "way" -> {
                    val g = geom(geometry)
                    if (isIslandTag && closed(g)) named.add(name to g)
                    else if (tags.optString("natural") == "coastline" && g.size >= 2) coastPieces.add(g)
                }
                "relation" -> if (isIslandTag) {
                    val members = e.optJSONArray("members") ?: continue
                    val parts = ArrayList<List<LatLon>>()
                    for (m in 0 until members.length()) {
                        val mo = members.getJSONObject(m)
                        if (mo.optString("type") == "way" && mo.optString("role") != "inner") geom(mo.optJSONArray("geometry")).takeIf { it.size >= 2 }?.let { parts.add(it) }
                    }
                    stitch(parts).forEach { if (it.size >= 4) named.add(name to it) }
                }
            }
        }

        // Join coastline pieces; keep rings that are not already present as a tagged island.
        val rings = ArrayList<Pair<String, List<LatLon>>>(named)
        for (ring in stitch(coastPieces)) {
            if (ring.size < 4) continue
            val c = centroidOf(ring)
            // Same island stored twice (tagged island + coastline): each centre inside the other and similar size.
            val a1 = ringArea(ring)
            val dup = named.any { (_, r) ->
                val c2 = centroidOf(r)
                pointInRingSimple(c.lat, c.lon, r) && pointInRingSimple(c2.lat, c2.lon, ring) && ringArea(r).let { a2 -> a2 > 0 && a1 / a2 in 0.6..1.6 }
            }
            if (!dup) rings.add("" to ring)
        }

        // Tagged islands can also be duplicated (way + relation): keep one per island.
        val unique = ArrayList<Pair<String, List<LatLon>>>()
        for (cand in rings) {
            val c = centroidOf(cand.second); val a1 = ringArea(cand.second)
            val same = unique.indexOfFirst { (_, r) ->
                val c2 = centroidOf(r)
                pointInRingSimple(c.lat, c.lon, r) && pointInRingSimple(c2.lat, c2.lon, cand.second) && ringArea(r).let { a2 -> a2 > 0 && a1 / a2 in 0.6..1.6 }
            }
            if (same < 0) unique.add(cand) else if (unique[same].first.isBlank() && cand.first.isNotBlank()) unique[same] = cand
        }

        return unique.map { (nm, ring) ->
            val inside = points.filter { pointInRingSimple(it.lat, it.lon, ring) }
            // "Inhabited" is decided only by the app's official island list, never by map guesses.
            // Here: airport (Gan), resort, or uninhabited island. An inhabited island matched to an
            // "airport" outline is an inhabited island with an airport (Hanimaadhoo).
            val type = when {
                inside.any { it.kind == "airport" } -> "airport"
                inside.any { it.kind == "resort" } -> "resort"
                else -> "island"
            }
            val name = nm.ifBlank {
                inside.firstOrNull { it.kind == "island" && it.name.isNotBlank() }?.name
                    ?: inside.firstOrNull { it.kind == "inhabited" && it.name.isNotBlank() }?.name
                    ?: inside.firstOrNull { it.kind == "airport" && it.name.isNotBlank() }?.name
                    ?: inside.firstOrNull { it.kind == "resort" && it.name.isNotBlank() }?.name
                    ?: ""
            }
            val places = inside.filter { (it.kind == "inhabited" || it.kind == "island") && it.name.isNotBlank() }.map { NamedPoint(it.name, it.lat, it.lon) }
            Outline(shortName(name), ring, type, places)
        }
    }

    private fun ringArea(r: List<LatLon>): Double {
        if (r.size < 3) return 0.0
        val k = kotlin.math.cos(Math.toRadians(r[0].lat)) * 111_320.0
        var s = 0.0
        for (i in r.indices) { val a = r[i]; val b = r[(i + 1) % r.size]; s += (a.lon * k) * (b.lat * 110_540.0) - (b.lon * k) * (a.lat * 110_540.0) }
        return kotlin.math.abs(s) / 2
    }

    private fun centroidOf(r: List<LatLon>) = LatLon(r.sumOf { it.lat } / r.size, r.sumOf { it.lon } / r.size)

    private fun pointInRingSimple(lat: Double, lon: Double, ring: List<LatLon>): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]; val b = ring[j]
            if ((a.lat > lat) != (b.lat > lat) && lon < (b.lon - a.lon) * (lat - a.lat) / (b.lat - a.lat) + a.lon) inside = !inside
            j = i
        }
        return inside
    }

    /** Shorter spoken names: "SAii Lagoon Maldives, Curio Collection by Hilton" -> "SAii Lagoon". */
    fun shortName(name: String): String {
        var n = name.substringBefore(",").trim()
        for (suffix in listOf(" Maldives Resort", " Island Resort & Spa", " Resort & Spa", " Resort and Spa", " Island Resort", " Maldives", " Resort")) {
            if (n.endsWith(suffix, true) && n.length > suffix.length + 3) n = n.dropLast(suffix.length).trim()
        }
        if (n.startsWith("The ", true) && n.length > 10) n = n.substring(4)
        return n
    }

    /** Joins way pieces that share end points into closed rings (fast: end points looked up by key). */
    private fun stitch(pieces: List<List<LatLon>>): List<List<LatLon>> {
        fun key(p: LatLon) = String.format(java.util.Locale.US, "%.6f,%.6f", p.lat, p.lon)
        val alive = BooleanArray(pieces.size) { true }
        val byEnd = HashMap<String, MutableList<Int>>()
        pieces.forEachIndexed { i, p -> byEnd.getOrPut(key(p.first())) { ArrayList() }.add(i); byEnd.getOrPut(key(p.last())) { ArrayList() }.add(i) }
        val rings = ArrayList<List<LatLon>>()
        for (start in pieces.indices) {
            if (!alive[start]) continue
            alive[start] = false
            val ring = ArrayList(pieces[start])
            var guard = 0
            while (key(ring.first()) != key(ring.last()) && guard++ < 5000) {
                val endKey = key(ring.last())
                val next = byEnd[endKey]?.firstOrNull { alive[it] } ?: break
                alive[next] = false
                val p = pieces[next]
                if (key(p.first()) == endKey) ring.addAll(p.drop(1)) else ring.addAll(p.reversed().drop(1))
            }
            if (ring.size >= 4 && key(ring.first()) == key(ring.last())) rings.add(ring)
        }
        return rings
    }

    /** A place from the map with its category (mosque, shop, health, ...). */
    data class MapPlace(val name: String, val lat: Double, val lon: Double, val categoryId: String, val typeText: String, val address: String)

    /** All named places inside a box, in one request (used by Offline maps for an atoll or island). */
    fun placesInBox(south: Double, west: Double, north: Double, east: Double): List<MapPlace> {
        val b = "($south,$west,$north,$east)"
        val f = listOf(
            "[\"amenity\"]", "[\"shop\"]", "[\"tourism\"]", "[\"healthcare\"]", "[\"leisure\"][\"name\"]",
            "[\"office\"][\"name\"]", "[\"aeroway\"~\"^(aerodrome|terminal)$\"]", "[\"man_made\"=\"pier\"]", "[\"public_transport\"]", "[\"highway\"=\"bus_stop\"]", "[\"building\"=\"mosque\"]", "[\"craft\"][\"name\"]"
        )
        val q = "[out:json][timeout:180];(" + f.joinToString("") { "node$b$it;way$b$it;" } + ");out center tags;"
        val arr = JSONObject(overpass(q, readSeconds = 60)).optJSONArray("elements") ?: return emptyList()
        return parsePlaces(arr)
    }

    /** Places from Overpass elements (downloaded, or built into the app). */
    fun parsePlaces(arr: JSONArray): List<MapPlace> {
        val out = ArrayList<MapPlace>()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val t = e.optJSONObject("tags") ?: continue
            val lat = if (e.has("lat")) e.optDouble("lat") else e.optJSONObject("center")?.optDouble("lat") ?: continue
            val lon = if (e.has("lon")) e.optDouble("lon") else e.optJSONObject("center")?.optDouble("lon") ?: continue
            val cat = categoryFromTags(t)
            val type = tagType(t)
            var name = t.optString("name:en").ifBlank { t.optString("name") }
            // Unnamed places are kept only when useful for blind travellers (toilets, ATM, bus stop, pier ...).
            if (name.isBlank()) {
                if (type in setOf("toilets", "atm", "bus stop", "pier", "ferry terminal", "pharmacy", "drinking water", "bench")) name = type.replaceFirstChar { it.uppercase() }
                else continue
            }
            if (type == "parking" || type == "waste basket" || type == "bench" && t.optString("name").isBlank()) continue
            var addr = listOf("addr:housename", "addr:street", "addr:suburb", "addr:place", "addr:city", "addr:island", "is_in")
                .map { t.optString(it) }.filter { it.isNotBlank() }.distinct().joinToString(", ")
            if (cat == "bus") {
                // Bus stop details, read with the place: "with shelter and bench".
                val have = listOfNotNull("shelter".takeIf { t.optString("shelter") == "yes" }, "bench".takeIf { t.optString("bench") == "yes" })
                if (have.isNotEmpty()) addr = listOf(addr, "with " + have.joinToString(" and ")).filter { it.isNotBlank() }.joinToString(", ")
            }
            val key = name.lowercase() + String.format(java.util.Locale.US, "%.5f%.5f", lat, lon)
            if (seen.add(key)) out.add(MapPlace(name, lat, lon, cat, type, addr))
        }
        return out
    }

    private fun categoryFromTags(t: JSONObject): String {
        val amenity = t.optString("amenity"); val shop = t.optString("shop"); val tourism = t.optString("tourism")
        return when {
            (amenity == "place_of_worship" && t.optString("religion") == "muslim") || t.optString("building") == "mosque" -> "mosque"
            t.optString("aeroway") in setOf("aerodrome", "terminal") -> "airport"
            amenity in setOf("hospital", "clinic", "doctors", "pharmacy", "dentist") || t.has("healthcare") -> "health"
            amenity in setOf("school", "college", "kindergarten", "university") -> "school"
            shop.isNotBlank() || amenity == "marketplace" -> "shop"
            amenity in setOf("restaurant", "cafe", "fast_food", "food_court") -> "food"
            isBusStop(t) -> "bus"
            amenity == "ferry_terminal" || t.optString("man_made") == "pier" || t.has("harbour") || t.has("public_transport") -> "ferry"
            amenity in setOf("bank", "atm", "bureau_de_change") -> "bank"
            amenity == "police" -> "police"
            tourism in setOf("hotel", "guest_house", "apartment", "hostel", "resort") -> "hotel"
            else -> "other"
        }
    }

    /** All roads and paths inside a box (with geometry), for offline road names, intersections and routing. */
    fun roadsInBox(south: Double, west: Double, north: Double, east: Double): List<DownloadedRoad> {
        val q = "[out:json][timeout:180];way($south,$west,$north,$east)[\"highway\"~\"^(primary|secondary|tertiary|unclassified|residential|living_street|service|pedestrian|footway|path|track|road|steps|cycleway)$\"];out geom tags;"
        val arr = JSONObject(overpass(q, readSeconds = 60)).optJSONArray("elements") ?: return emptyList()
        return parseRoads(arr)
    }

    /** Roads from Overpass elements (downloaded, or built into the app). */
    fun parseRoads(arr: JSONArray): List<DownloadedRoad> {
        val out = ArrayList<DownloadedRoad>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val geom = e.optJSONArray("geometry") ?: continue
            val pts = (0 until geom.length()).map { j -> val g = geom.getJSONObject(j); doubleArrayOf(g.getDouble("lat"), g.getDouble("lon")) }
            if (pts.size < 2) continue
            val tags = e.optJSONObject("tags")
            val name = tags?.optString("name:en").orEmpty().ifBlank { tags?.optString("name").orEmpty() }.ifBlank { tags?.optString("ref").orEmpty() }
            out.add(DownloadedRoad(name, pts, tags?.optString("highway").orEmpty(), tags?.optString("oneway") == "yes"))
        }
        return out
    }
}
