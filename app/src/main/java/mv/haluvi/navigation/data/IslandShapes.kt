package mv.haluvi.navigation.data

import android.content.Context
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.core.LatLon
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Island outlines (coastlines) from OpenStreetMap, downloaded once in the app and kept offline.
 *  - Several inhabited islands can share one outline when they are joined (Addu, Hulhumeedhoo).
 *  - Wrong island positions in the built-in data are corrected from the matched outline.
 *  - Other named islands (resorts, airports, uninhabited) are kept as extra islands.
 */
class IslandShapes(context: Context, private val islands: IslandRepository) {
    private val file = File(context.filesDir, "island_shapes.json")

    @Volatile private var rings: Map<String, List<LatLon>> = emptyMap()
    @Volatile private var islandRing: Map<String, String> = emptyMap()
    @Volatile var extras: List<Island> = emptyList(); private set
    @Volatile private var osmNames: Map<String, String> = emptyMap()
    /** How each inhabited island was matched: "name", "inside" or "position". */
    @Volatile private var methods: Map<String, String> = emptyMap()
    /** Corrected positions (middle of the matched outline) for islands whose built-in position was wrong. */
    @Volatile private var corrected: Map<String, LatLon> = emptyMap()
    @Volatile private var correctedBy: Map<String, Double> = emptyMap()
    @Volatile private var warnings: Map<String, String> = emptyMap()
    @Volatile var atollsDone: Set<String> = emptySet(); private set
    @Volatile var notOnMap: Set<String> = emptySet(); private set
    /** File format: 3 = matched with the 2.4 rules. Older downloads should be done again. */
    @Volatile var formatVersion: Int = 0; private set

    /** Built-in positions, kept so corrections can be re-applied or undone. */
    private val original: Map<String, LatLon> = islands.allIslands.associate { it.key to LatLon(it.lat, it.lon) }

    init { load(); applyCorrections() }

    val count: Int get() = islands.allIslands.count { has(it.key) }
    val isEmpty: Boolean get() = islandRing.isEmpty()
    fun has(key: String) = islandRing.containsKey(key)
    fun ringId(key: String): String? = islandRing[key]
    fun ring(key: String): List<LatLon>? = islandRing[key]?.let { rings[it] }
    fun osmName(key: String): String? = osmNames[key]
    fun method(key: String): String? = methods[key]
    fun correction(key: String): Double? = correctedBy[key]
    fun warning(key: String): String? = warnings[key]
    fun originalPosition(key: String): LatLon? = original[key]

    // ------------------------------------------------------------------ storage

    @Synchronized
    fun load() {
        if (!file.exists()) return
        runCatching {
            val o = JSONObject(file.readText())
            val r = HashMap<String, List<LatLon>>()
            val ir = HashMap<String, String>()
            if (!o.has("v")) {
                o.keys().forEach { k -> r[k] = readRing(o.getJSONArray(k)); ir[k] = k }
                rings = r; islandRing = ir; formatVersion = 1
                return@runCatching
            }
            formatVersion = o.optInt("v", 2)
            if (formatVersion >= 3) {
                val rj = o.getJSONObject("rings"); rj.keys().forEach { k -> r[k] = readRing(rj.getJSONArray(k)) }
                val ij = o.getJSONObject("islandRing"); ij.keys().forEach { k -> ir[k] = ij.getString(k) }
            } else {
                val sh = o.getJSONObject("shapes"); sh.keys().forEach { k -> r[k] = readRing(sh.getJSONArray(k)); ir[k] = k }
            }
            val ex = ArrayList<Island>()
            o.optJSONArray("extras")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    val key = e.getString("key")
                    ex.add(extraIsland(key, e.getString("name"), e.getDouble("lat"), e.getDouble("lon"), e.optString("type", "island")))
                    if (!ir.containsKey(key) && r.containsKey(key)) ir[key] = key
                }
            }
            rings = r; islandRing = ir; extras = ex
            osmNames = readMap(o.optJSONObject("osmNames"))
            methods = readMap(o.optJSONObject("methods"))
            warnings = readMap(o.optJSONObject("warnings"))
            val c = HashMap<String, LatLon>(); val cb = HashMap<String, Double>()
            o.optJSONObject("corrected")?.let { cj -> cj.keys().forEach { k -> val a = cj.getJSONArray(k); c[k] = LatLon(a.getDouble(0), a.getDouble(1)); cb[k] = a.optDouble(2, 0.0) } }
            corrected = c; correctedBy = cb
            atollsDone = jsonSet(o.optJSONArray("atollsDone"))
            notOnMap = jsonSet(o.optJSONArray("notOnMap"))
        }
    }

    private fun readRing(arr: JSONArray): List<LatLon> = (0 until arr.length()).map { i -> val p = arr.getJSONArray(i); LatLon(p.getDouble(0), p.getDouble(1)) }
    private fun readMap(o: JSONObject?): Map<String, String> { val m = HashMap<String, String>(); o?.keys()?.forEach { k -> m[k] = o.getString(k) }; return m }
    private fun jsonSet(a: JSONArray?): Set<String> = if (a == null) emptySet() else (0 until a.length()).map { a.getString(it) }.toSet()
    private fun toJson(m: Map<String, String>): JSONObject { val o = JSONObject(); m.forEach { (k, v) -> o.put(k, v) }; return o }

    private fun extraIsland(key: String, name: String, lat: Double, lon: Double, type: String): Island =
        Island(-1, -1, name, lat, lon).also { it.key = key; it.type = type }

    /** Moves islands with a corrected position to the middle of their real outline. */
    private fun applyCorrections() {
        for (isl in islands.allIslands) {
            val c = corrected[isl.key] ?: original[isl.key] ?: continue
            isl.lat = c.lat; isl.lon = c.lon
        }
    }

    /** Result of matching one atoll. */
    class AtollData(
        val code: String,
        val islandKeys: Set<String>,
        /** ringId -> ring, for rings used by the atoll's inhabited islands. */
        val rings: Map<String, List<LatLon>>,
        val islandRing: Map<String, String>,
        val osmNames: Map<String, String>,
        val methods: Map<String, String>,
        val corrected: Map<String, Pair<LatLon, Double>>,
        val warnings: Map<String, String>,
        val missing: Set<String>,
        /** Other named islands: name, type, ring. */
        val others: List<Triple<String, String, List<LatLon>>>
    )

    @Synchronized
    fun putAtoll(d: AtollData) {
        val r = HashMap(rings); val ir = HashMap(islandRing)
        val names = HashMap(osmNames); val meth = HashMap(methods); val warn = HashMap(warnings)
        val c = HashMap(corrected); val cb = HashMap(correctedBy)
        // Replace the atoll's old data.
        for (k in d.islandKeys) {
            ir.remove(k)?.let { old -> if (ir.values.none { it == old }) r.remove(old) }
            names.remove(k); meth.remove(k); warn.remove(k); c.remove(k); cb.remove(k)
        }
        r.putAll(d.rings); ir.putAll(d.islandRing); names.putAll(d.osmNames); meth.putAll(d.methods); warn.putAll(d.warnings)
        d.corrected.forEach { (k, v) -> c[k] = v.first; cb[k] = v.second }
        val ex = extras.toMutableList()
        for ((name, type, ring) in d.others) {
            val ct = centroid(ring)
            val key = "osm:" + name + "@" + String.format(Locale.US, "%.3f,%.3f", ct.lat, ct.lon)
            ex.removeAll { it.key == key }
            ex.add(extraIsland(key, name, ct.lat, ct.lon, type))
            r[key] = ring; ir[key] = key
        }
        rings = r; islandRing = ir; osmNames = names; methods = meth; warnings = warn; corrected = c; correctedBy = cb; extras = ex
        atollsDone = atollsDone + d.code
        notOnMap = (notOnMap - d.islandKeys) + d.missing
        formatVersion = 3
        applyCorrections()
        save()
    }

    /** Forgets everything so a full download starts clean. */
    @Synchronized
    fun clear() {
        rings = emptyMap(); islandRing = emptyMap(); extras = emptyList(); osmNames = emptyMap(); methods = emptyMap()
        corrected = emptyMap(); correctedBy = emptyMap(); warnings = emptyMap(); atollsDone = emptySet(); notOnMap = emptySet()
        formatVersion = 0
        applyCorrections()
        file.delete()
    }

    private fun save() {
        val o = JSONObject().put("v", 3)
        val rj = JSONObject()
        rings.forEach { (k, pts) -> val arr = JSONArray(); pts.forEach { arr.put(JSONArray().put(round6(it.lat)).put(round6(it.lon))) }; rj.put(k, arr) }
        o.put("rings", rj)
        o.put("islandRing", toJson(islandRing))
        val ex = JSONArray()
        extras.forEach { ex.put(JSONObject().put("key", it.key).put("name", it.name).put("lat", it.lat).put("lon", it.lon).put("type", it.type)) }
        o.put("extras", ex)
        o.put("osmNames", toJson(osmNames))
        o.put("methods", toJson(methods))
        o.put("warnings", toJson(warnings))
        val cj = JSONObject()
        corrected.forEach { (k, v) -> cj.put(k, JSONArray().put(round6(v.lat)).put(round6(v.lon)).put(correctedBy[k] ?: 0.0)) }
        o.put("corrected", cj)
        o.put("atollsDone", JSONArray(atollsDone.toList()))
        o.put("notOnMap", JSONArray(notOnMap.toList()))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) { file.writeText(o.toString()); tmp.delete() }
    }

    fun exportText(): String = if (file.exists()) file.readText() else "{}"

    private fun round6(v: Double) = Math.round(v * 1e6) / 1e6

    // ------------------------------------------------------------------ geometry queries

    fun inside(key: String, lat: Double, lon: Double): Boolean {
        val ring = ring(key) ?: return false
        return pointInRing(lat, lon, ring)
    }

    /** Metres from a point to an island's shore (0 on the island). Without an outline: centre distance minus 300 m. */
    fun shoreDistance(island: Island, lat: Double, lon: Double): Double {
        val ring = ring(island.key)
        if (ring == null || ring.size < 3) return maxOf(0.0, Geo.distance(lat, lon, island.lat, island.lon) - 300.0)
        if (pointInRing(lat, lon, ring)) return 0.0
        return Geo.distanceToPolyline(lat, lon, ring + ring.first())
    }

    data class ShoreHit(val island: Island, val shore: Double, val centre: Double)

    /**
     * Islands (inhabited and other named) whose centre is within [maxCentre] metres, nearest shore first.
     * Islands joined into one landmass are reported once: the one whose centre is nearest.
     */
    fun nearby(lat: Double, lon: Double, maxCentre: Double = 20_000.0): List<ShoreHit> {
        val all = ArrayList<ShoreHit>()
        islands.within(lat, lon, maxCentre).forEach { all.add(ShoreHit(it.island, shoreDistance(it.island, lat, lon), it.distance)) }
        for (e in extras) {
            val c = Geo.distance(lat, lon, e.lat, e.lon)
            if (c <= maxCentre) all.add(ShoreHit(e, shoreDistance(e, lat, lon), c))
        }
        val best = LinkedHashMap<String, ShoreHit>()
        for (h in all) {
            val group = ringId(h.island.key) ?: ("centre:" + h.island.key)
            val cur = best[group]
            if (cur == null || h.centre < cur.centre) best[group] = h
        }
        return best.values.sortedBy { it.shore }
    }

    /** The island you are on (inside its outline), or null. */
    fun islandAt(lat: Double, lon: Double): Island? =
        nearby(lat, lon, 15_000.0).filter { it.shore == 0.0 && has(it.island.key) }.minByOrNull { it.centre }?.island

    /** Search inhabited and other named islands by name (understands Dhivehi spelling differences). */
    fun search(query: String): List<Island> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val nq = normalizeName(q)
        return (islands.allIslands + extras).filter {
            it.name.lowercase().contains(q) || it.fullName.lowercase().contains(q) || (nq.length >= 3 && normalizeName(it.name).contains(nq))
        }
    }

    companion object {
        /**
         * Normalizes Dhivehi island-name spellings so variants match:
         * Dhidhoo / Dhidhdhoo, Kurinbee / Kurinbi, Kimbidhoo / Kinbidhoo, Hon'daidhoo, Male' / Malé.
         */
        fun normalizeName(name: String): String {
            var s = java.text.Normalizer.normalize(name.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("[^a-z]"), "")
            s = s.replace("ee", "i").replace("mb", "nb").replace("aa", "a").replace("oa", "o").replace("h", "")
            val sb = StringBuilder()
            for (ch in s) if (sb.isEmpty() || sb.last() != ch) sb.append(ch)
            return sb.toString()
        }

        /** Same island name allowing Dhivehi spelling variants (but "Dhidhoo" is not "Dhidhoo Finolhu"). */
        fun sameName(a: String, b: String): Boolean {
            if (a.isBlank() || b.isBlank()) return false
            val x = normalizeName(a); val y = normalizeName(b)
            if (x == y) return true
            if (x.length < 5 || y.length < 5) return false
            return editDistance(x, y) <= 1
        }

        private fun editDistance(a: String, b: String): Int {
            if (kotlin.math.abs(a.length - b.length) > 1) return 2
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]; dp[0] = i
                for (j in 1..b.length) {
                    val tmp = dp[j]
                    dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return dp[b.length]
        }

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
