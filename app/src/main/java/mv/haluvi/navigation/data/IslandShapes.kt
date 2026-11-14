package mv.haluvi.navigation.data

import android.content.Context
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.core.LatLon
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Coastline outlines of the inhabited islands (from OpenStreetMap), downloaded once in the app and
 * kept offline. With outlines the app knows when you are really at an island's shore or harbour,
 * whatever the island's size. Without them it falls back to the island centre point.
 */
class IslandShapes(context: Context, private val islands: IslandRepository) {
    private val file = File(context.filesDir, "island_shapes.json")
    @Volatile private var shapes: Map<String, List<LatLon>> = emptyMap()
    /** Other named islands from OpenStreetMap (resorts, airports, uninhabited), keys start with "osm:". */
    @Volatile var extras: List<Island> = emptyList(); private set
    /** Name of the OpenStreetMap outline matched to each island (for the report). */
    @Volatile private var osmNames: Map<String, String> = emptyMap()
    /** Atoll codes downloaded completely. */
    @Volatile var atollsDone: Set<String> = emptySet(); private set
    /** Inhabited islands that were checked but have no outline on the map. */
    @Volatile var notOnMap: Set<String> = emptySet(); private set

    init { load() }

    /** Number of the 200 inhabited islands that have an outline. */
    val count: Int get() = islands.allIslands.count { shapes.containsKey(it.key) }
    val isEmpty: Boolean get() = shapes.isEmpty()
    fun has(key: String) = shapes.containsKey(key)
    fun ring(key: String): List<LatLon>? = shapes[key]
    fun osmName(key: String): String? = osmNames[key]

    @Synchronized
    fun load() {
        if (!file.exists()) return
        runCatching {
            val o = JSONObject(file.readText())
            if (!o.has("v")) {
                // Version 2.2 format: { islandKey: [[lat, lon], ...] }
                val m = HashMap<String, List<LatLon>>()
                o.keys().forEach { k -> m[k] = readRing(o.getJSONArray(k)) }
                shapes = m
                return@runCatching
            }
            val m = HashMap<String, List<LatLon>>()
            val sh = o.getJSONObject("shapes")
            sh.keys().forEach { k -> m[k] = readRing(sh.getJSONArray(k)) }
            val ex = ArrayList<Island>()
            o.optJSONArray("extras")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    ex.add(extraIsland(e.getString("key"), e.getString("name"), e.getDouble("lat"), e.getDouble("lon")))
                }
            }
            val names = HashMap<String, String>()
            o.optJSONObject("osmNames")?.let { n -> n.keys().forEach { k -> names[k] = n.getString(k) } }
            shapes = m; extras = ex; osmNames = names
            atollsDone = jsonSet(o.optJSONArray("atollsDone"))
            notOnMap = jsonSet(o.optJSONArray("notOnMap"))
        }
    }

    private fun readRing(arr: JSONArray): List<LatLon> = (0 until arr.length()).map { i -> val p = arr.getJSONArray(i); LatLon(p.getDouble(0), p.getDouble(1)) }
    private fun jsonSet(a: JSONArray?): Set<String> = if (a == null) emptySet() else (0 until a.length()).map { a.getString(it) }.toSet()

    private fun extraIsland(key: String, name: String, lat: Double, lon: Double): Island =
        Island(-1, -1, name, lat, lon).also { it.key = key }

    /**
     * Saves the result of one atoll: outlines of inhabited islands, other named islands,
     * matched map names, and which inhabited islands are not on the map.
     */
    @Synchronized
    fun putAtoll(atollCode: String, matched: Map<String, Pair<String, List<LatLon>>>, others: List<Pair<String, List<LatLon>>>, missing: Set<String>, atollIslandKeys: Set<String>) {
        val m = HashMap(shapes)
        val names = HashMap(osmNames)
        matched.forEach { (k, v) -> m[k] = v.second; if (v.first.isNotBlank()) names[k] = v.first }
        val ex = extras.filter { e -> others.none { o -> extraKey(o.first, o.second) == e.key } }.toMutableList()
        others.forEach { (name, ring) ->
            val key = extraKey(name, ring)
            val c = centroid(ring)
            m[key] = ring
            ex.add(extraIsland(key, name, c.lat, c.lon))
        }
        shapes = m; osmNames = names; extras = ex
        atollsDone = atollsDone + atollCode
        notOnMap = (notOnMap - atollIslandKeys) + missing
        save()
    }

    private fun extraKey(name: String, ring: List<LatLon>): String {
        val c = centroid(ring)
        return "osm:" + name + "@" + String.format(java.util.Locale.US, "%.3f,%.3f", c.lat, c.lon)
    }

    private fun save() {
        val o = JSONObject().put("v", 2)
        val sh = JSONObject()
        shapes.forEach { (k, pts) ->
            val arr = JSONArray()
            pts.forEach { arr.put(JSONArray().put(round6(it.lat)).put(round6(it.lon))) }
            sh.put(k, arr)
        }
        o.put("shapes", sh)
        val ex = JSONArray()
        extras.forEach { ex.put(JSONObject().put("key", it.key).put("name", it.name).put("lat", it.lat).put("lon", it.lon)) }
        o.put("extras", ex)
        val n = JSONObject(); osmNames.forEach { (k, v) -> n.put(k, v) }
        o.put("osmNames", n)
        o.put("atollsDone", JSONArray(atollsDone.toList()))
        o.put("notOnMap", JSONArray(notOnMap.toList()))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) { file.writeText(o.toString()); tmp.delete() }
    }

    /** The whole file as text (for Export). */
    fun exportText(): String = if (file.exists()) file.readText() else "{}"

    private fun round6(v: Double) = Math.round(v * 1e6) / 1e6

    @Synchronized
    fun clear() { shapes = emptyMap(); extras = emptyList(); osmNames = emptyMap(); atollsDone = emptySet(); notOnMap = emptySet(); file.delete() }

    fun inside(key: String, lat: Double, lon: Double): Boolean {
        val ring = shapes[key] ?: return false
        return pointInRing(lat, lon, ring)
    }

    /**
     * Distance in metres from a point to an island's shore (0 when on the island).
     * Without an outline: distance to the centre minus a typical island radius (300 m).
     */
    fun shoreDistance(island: Island, lat: Double, lon: Double): Double {
        val ring = shapes[island.key]
        if (ring == null || ring.size < 3) return maxOf(0.0, Geo.distance(lat, lon, island.lat, island.lon) - 300.0)
        if (pointInRing(lat, lon, ring)) return 0.0
        return Geo.distanceToPolyline(lat, lon, ring + ring.first())
    }

    data class ShoreHit(val island: Island, val shore: Double, val centre: Double)

    /** Inhabited and other named islands whose centre is within [maxCentre] metres, nearest shore first. */
    fun nearby(lat: Double, lon: Double, maxCentre: Double = 20_000.0): List<ShoreHit> {
        val list = islands.within(lat, lon, maxCentre).map { ShoreHit(it.island, shoreDistance(it.island, lat, lon), it.distance) }.toMutableList()
        for (e in extras) {
            val c = Geo.distance(lat, lon, e.lat, e.lon)
            if (c <= maxCentre) list.add(ShoreHit(e, shoreDistance(e, lat, lon), c))
        }
        return list.sortedBy { it.shore }
    }

    companion object {
        fun pointInRing(lat: Double, lon: Double, ring: List<LatLon>): Boolean {
            var inside = false
            var j = ring.size - 1
            for (i in ring.indices) {
                val a = ring[i]; val b = ring[j]
                if ((a.lat > lat) != (b.lat > lat) && lon < (b.lon - a.lon) * (lat - a.lat) / (b.lat - a.lat) + a.lon) inside = !inside
                j = i
            }
            return inside
        }

        /** Douglas-Peucker simplification with a tolerance in metres. */
        fun simplify(points: List<LatLon>, toleranceM: Double): List<LatLon> {
            if (points.size < 4) return points
            val keep = BooleanArray(points.size)
            keep[0] = true; keep[points.size - 1] = true
            val stack = ArrayDeque<Pair<Int, Int>>()
            stack.addLast(0 to points.size - 1)
            while (stack.isNotEmpty()) {
                val (s, e) = stack.removeLast()
                var maxD = 0.0; var idx = -1
                for (i in s + 1 until e) {
                    val d = Geo.distanceToSegment(points[i].lat, points[i].lon, points[s].lat, points[s].lon, points[e].lat, points[e].lon)
                    if (d > maxD) { maxD = d; idx = i }
                }
                if (idx >= 0 && maxD > toleranceM) { keep[idx] = true; stack.addLast(s to idx); stack.addLast(idx to e) }
            }
            return points.filterIndexed { i, _ -> keep[i] }
        }

        /** Approximate area in square metres (for choosing the main ring). */
        fun area(ring: List<LatLon>): Double {
            if (ring.size < 3) return 0.0
            val k = cos(Math.toRadians(ring[0].lat)) * 111_320.0
            var s = 0.0
            for (i in ring.indices) {
                val a = ring[i]; val b = ring[(i + 1) % ring.size]
                s += (a.lon * k) * (b.lat * 110_540.0) - (b.lon * k) * (a.lat * 110_540.0)
            }
            return kotlin.math.abs(s) / 2
        }

        fun radius(ring: List<LatLon>): Double = sqrt(area(ring) / Math.PI)

        fun centroid(ring: List<LatLon>): LatLon = LatLon(ring.sumOf { it.lat } / ring.size, ring.sumOf { it.lon } / ring.size)
    }
}
