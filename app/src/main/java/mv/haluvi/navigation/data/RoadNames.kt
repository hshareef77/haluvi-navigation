package mv.haluvi.navigation.data

import mv.haluvi.navigation.core.Geo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A road name the user gave to a downloaded road, or a road the user recorded by walking it ([recorded]).
 * The road's shape is kept, so names survive map updates.
 */
data class NamedRoad(
    val id: String, val islandKey: String, var name: String, val points: List<DoubleArray>, val savedMs: Long,
    val recorded: Boolean = false,
    /** For recorded roads: "narrow", "normal" or "wide". */
    val width: String = "",
    /** When it was shared with OpenStreetMap as a note (0 = not shared). */
    var sharedMs: Long = 0
) {
    val lengthMeters: Double get() = RoadNames.length(points)
}

/**
 * The user's own roads: names for downloaded roads and roads recorded by walking. They are stored separately
 * from the downloaded roads and put on top of them every time roads are loaded, so they are kept when the
 * offline map is updated or downloaded again.
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
        items.removeAll { !it.recorded && it.islandKey == islandKey && matches(points, it.points) && matches(it.points, points) }
        val n = NamedRoad(java.util.UUID.randomUUID().toString(), islandKey, name.trim(), points, System.currentTimeMillis())
        items.add(n)
        save()
        return n
    }

    /** Adds a road recorded by walking. */
    @Synchronized
    fun addRecorded(islandKey: String, name: String, width: String, points: List<DoubleArray>): NamedRoad {
        val n = NamedRoad(java.util.UUID.randomUUID().toString(), islandKey, name.trim(), points, System.currentTimeMillis(), recorded = true, width = width)
        items.add(n)
        save()
        return n
    }

    @Synchronized
    fun rename(id: String, name: String) { items.firstOrNull { it.id == id }?.name = name.trim(); save() }

    @Synchronized
    fun markShared(id: String) { items.firstOrNull { it.id == id }?.sharedMs = System.currentTimeMillis(); save() }

    @Synchronized
    fun delete(id: String) { items.removeAll { it.id == id }; save() }

    /**
     * Puts the user's names on downloaded roads, and adds recorded roads (joined to roads within 20 m of
     * their ends, so offline routes can use them). A recorded road that the map now has too is not doubled.
     */
    @Synchronized
    fun apply(islandKey: String, roads: List<DownloadedRoad>): List<DownloadedRoad> {
        val mine = items.filter { it.islandKey == islandKey }
        if (mine.isEmpty()) return roads
        val named = mine.filter { !it.recorded }
        val out = roads.map { r ->
            val n = named.firstOrNull { matches(r.points, it.points) }
            if (n != null) r.copy(name = n.name, userNamed = true) else r
        }.toMutableList()
        for (rec in mine.filter { it.recorded }) {
            // Already on the map (mappers added it): just give the map road the name.
            val onMap = out.indexOfFirst { !it.recorded && matches(rec.points, it.points) }
            if (onMap >= 0) {
                if (rec.name.isNotBlank() && out[onMap].name.isBlank()) out[onMap] = out[onMap].copy(name = rec.name, userNamed = true)
                continue
            }
            val pts = rec.points.toMutableList()
            for (atEnd in listOf(false, true)) {
                val e = if (atEnd) pts.last() else pts.first()
                var bi = -1; var bs = -1; var bd = 20.0; var bp: DoubleArray? = null
                out.forEachIndexed { ri, r ->
                    for (i in 0 until r.points.size - 1) {
                        val a = r.points[i]; val b = r.points[i + 1]
                        val d = Geo.distanceToSegment(e[0], e[1], a[0], a[1], b[0], b[1])
                        if (d < bd) { bd = d; bi = ri; bs = i; bp = project(e, a, b) }
                    }
                }
                val p = bp ?: continue
                // Same point on both roads, so they share a junction.
                val target = out[bi]
                val tp = target.points.toMutableList().apply { add(bs + 1, p) }
                out[bi] = target.copy(points = tp)
                if (atEnd) pts.add(p) else pts.add(0, p)
            }
            val kind = when (rec.width) { "narrow" -> "footway"; "wide" -> "tertiary"; else -> "residential" }
            out.add(DownloadedRoad(rec.name, pts, kind, false, userNamed = rec.name.isNotBlank(), recorded = true))
        }
        return out
    }

    /** For the backup file. */
    @Synchronized
    fun exportJson(): JSONArray {
        val arr = JSONArray()
        items.forEach { n ->
            val pts = JSONArray(); n.points.forEach { p -> pts.put(JSONArray().put(p[0]).put(p[1])) }
            arr.put(JSONObject().put("island", n.islandKey).put("name", n.name).put("points", pts)
                .put("recorded", n.recorded).put("width", n.width).put("shared", n.sharedMs))
        }
        return arr
    }

    /** From a backup file: each name is added (replacing a name on the same road). Returns how many. */
    @Synchronized
    fun importJson(arr: JSONArray): Int {
        var n = 0
        for (i in 0 until arr.length()) runCatching {
            val o = arr.getJSONObject(i)
            val pts = o.getJSONArray("points")
            val points = (0 until pts.length()).map { j -> val p = pts.getJSONArray(j); doubleArrayOf(p.getDouble(0), p.getDouble(1)) }
            if (points.size >= 2) {
                val island = o.getString("island")
                val recorded = o.optBoolean("recorded")
                items.removeAll { it.islandKey == island && it.recorded == recorded && matches(points, it.points) && matches(it.points, points) }
                items.add(NamedRoad(java.util.UUID.randomUUID().toString(), island, o.getString("name"), points, System.currentTimeMillis(),
                    recorded, o.optString("width"), o.optLong("shared")))
                n++
            }
        }
        if (n > 0) save()
        return n
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
                    o.optLong("saved"), o.optBoolean("recorded"), o.optString("width"), o.optLong("shared")))
            }
        }
    }

    private fun save() {
        val arr = JSONArray()
        items.forEach { n ->
            val pts = JSONArray()
            n.points.forEach { p -> pts.put(JSONArray().put(p[0]).put(p[1])) }
            arr.put(JSONObject().put("id", n.id).put("island", n.islandKey).put("name", n.name).put("points", pts).put("saved", n.savedMs)
                .put("recorded", n.recorded).put("width", n.width).put("shared", n.sharedMs))
        }
        runCatching { file.writeText(arr.toString()) }
    }

    companion object {
        /** Within this distance a road counts as lying along the named line. */
        private const val NEAR_M = 12.0

        /** The closest point to [p] on segment a-b. */
        fun project(p: DoubleArray, a: DoubleArray, b: DoubleArray): DoubleArray {
            val k = Math.cos(Math.toRadians(p[0]))
            val ax = a[1] * k; val ay = a[0]; val bx = b[1] * k; val by = b[0]; val px = p[1] * k; val py = p[0]
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
            return doubleArrayOf(a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]))
        }

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
