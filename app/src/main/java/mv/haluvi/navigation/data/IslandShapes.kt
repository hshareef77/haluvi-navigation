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

    init { load() }

    val count: Int get() = shapes.size
    val isEmpty: Boolean get() = shapes.isEmpty()
    fun has(key: String) = shapes.containsKey(key)

    @Synchronized
    fun load() {
        if (!file.exists()) return
        runCatching {
            val o = JSONObject(file.readText())
            val m = HashMap<String, List<LatLon>>()
            o.keys().forEach { k ->
                val arr = o.getJSONArray(k)
                m[k] = (0 until arr.length()).map { i -> val p = arr.getJSONArray(i); LatLon(p.getDouble(0), p.getDouble(1)) }
            }
            shapes = m
        }
    }

    /** Adds or replaces outlines (key = island key) and saves to disk. */
    @Synchronized
    fun putAll(newShapes: Map<String, List<LatLon>>) {
        val m = HashMap(shapes)
        m.putAll(newShapes)
        shapes = m
        val o = JSONObject()
        m.forEach { (k, pts) ->
            val arr = JSONArray()
            pts.forEach { arr.put(JSONArray().put(round6(it.lat)).put(round6(it.lon))) }
            o.put(k, arr)
        }
        file.writeText(o.toString())
    }

    private fun round6(v: Double) = Math.round(v * 1e6) / 1e6

    @Synchronized
    fun clear() { shapes = emptyMap(); file.delete() }

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

    /** Islands whose centre is within [maxCentre] metres, with their shore distance, nearest shore first. */
    fun nearby(lat: Double, lon: Double, maxCentre: Double = 20_000.0): List<ShoreHit> =
        islands.within(lat, lon, maxCentre).map { ShoreHit(it.island, shoreDistance(it.island, lat, lon), it.distance) }.sortedBy { it.shore }

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
    }
}
