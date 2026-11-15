package mv.haluvi.navigation.data

import mv.haluvi.navigation.core.Geo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A road name the user gave to a downloaded road. The road's shape is kept so the name survives map updates. */
data class NamedRoad(val id: String, val islandKey: String, var name: String, val points: List<DoubleArray>, val savedMs: Long) {
    val lengthMeters: Double get() = RoadNames.length(points)
}

/**
 * The user's own road names. They are stored separately from the downloaded roads and put on top of them
 * every time roads are loaded: a downloaded road gets the name when most of it lies along the named road.
 * So the names are kept when the offline map is updated or downloaded again.
 */
class RoadNames(private val file: File) {
    private val items = ArrayList<NamedRoad>()

    init { load() }

    @Synchronized
    fun all(): List<NamedRoad> = items.toList()

    @Synchronized
    fun forIsland(key: String): List<NamedRoad> = items.filter { it.islandKey == key }

    /** Adds a name; a named road covering mostly the same line is replaced. */
    @Synchronized
    fun add(islandKey: String, name: String, points: List<DoubleArray>): NamedRoad {
        items.removeAll { it.islandKey == islandKey && matches(points, it.points) && matches(it.points, points) }
        val n = NamedRoad(java.util.UUID.randomUUID().toString(), islandKey, name.trim(), points, System.currentTimeMillis())
        items.add(n)
        save()
        return n
    }

    @Synchronized
    fun rename(id: String, name: String) { items.firstOrNull { it.id == id }?.name = name.trim(); save() }

    @Synchronized
    fun delete(id: String) { items.removeAll { it.id == id }; save() }

    /** Puts the user's names on downloaded roads. */
    @Synchronized
    fun apply(islandKey: String, roads: List<DownloadedRoad>): List<DownloadedRoad> {
        val mine = items.filter { it.islandKey == islandKey }
        if (mine.isEmpty()) return roads
        return roads.map { r ->
            val n = mine.firstOrNull { matches(r.points, it.points) }
            if (n != null) r.copy(name = n.name, userNamed = true) else r
        }
    }

    private fun load() {
        items.clear()
        if (!file.exists()) return
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pts = o.getJSONArray("points")
                items.add(NamedRoad(o.getString("id"), o.getString("island"), o.getString("name"),
                    (0 until pts.length()).map { j -> val p = pts.getJSONArray(j); doubleArrayOf(p.getDouble(0), p.getDouble(1)) },
                    o.optLong("saved")))
            }
        }
    }

    private fun save() {
        val arr = JSONArray()
        items.forEach { n ->
            val pts = JSONArray()
            n.points.forEach { p -> pts.put(JSONArray().put(p[0]).put(p[1])) }
            arr.put(JSONObject().put("id", n.id).put("island", n.islandKey).put("name", n.name).put("points", pts).put("saved", n.savedMs))
        }
        runCatching { file.writeText(arr.toString()) }
    }

    companion object {
        /** Within this distance a road counts as lying along the named line. */
        private const val NEAR_M = 12.0

        fun length(pts: List<DoubleArray>): Double {
            var s = 0.0
            for (i in 0 until pts.size - 1) s += Geo.distance(pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1])
            return s
        }

        /** Points every [step] metres along a line (so long straight segments count fairly). */
        fun sample(pts: List<DoubleArray>, step: Double = 8.0): List<DoubleArray> {
            if (pts.size < 2) return pts
            val out = ArrayList<DoubleArray>()
            for (i in 0 until pts.size - 1) {
                val a = pts[i]; val b = pts[i + 1]
                val d = Geo.distance(a[0], a[1], b[0], b[1])
                val n = maxOf(1, (d / step).toInt())
                for (k in 0 until n) { val t = k.toDouble() / n; out.add(doubleArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)) }
            }
            out.add(pts.last())
            return out
        }

        fun distanceToLine(lat: Double, lon: Double, line: List<DoubleArray>): Double {
            if (line.isEmpty()) return Double.MAX_VALUE
            if (line.size == 1) return Geo.distance(lat, lon, line[0][0], line[0][1])
            var best = Double.MAX_VALUE
            for (i in 0 until line.size - 1) {
                val d = Geo.distanceToSegment(lat, lon, line[i][0], line[i][1], line[i + 1][0], line[i + 1][1])
                if (d < best) best = d
            }
            return best
        }

        /** True when at least 70 % of road [a] lies within 12 m of line [b]. */
        fun matches(a: List<DoubleArray>, b: List<DoubleArray>): Boolean {
            if (a.size < 2 || b.size < 2) return false
            // Quick box check first.
            val pad = 0.0003
            val bMinLat = b.minOf { it[0] } - pad; val bMaxLat = b.maxOf { it[0] } + pad
            val bMinLon = b.minOf { it[1] } - pad; val bMaxLon = b.maxOf { it[1] } + pad
            if (a.none { it[0] in bMinLat..bMaxLat && it[1] in bMinLon..bMaxLon }) return false
            val s = sample(a)
            val near = s.count { distanceToLine(it[0], it[1], b) <= NEAR_M }
            return near >= s.size * 0.7
        }
    }
}
