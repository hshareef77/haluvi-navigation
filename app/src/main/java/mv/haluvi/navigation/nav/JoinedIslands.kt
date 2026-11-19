package mv.haluvi.navigation.nav

import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.LatLon
import mv.haluvi.navigation.data.Island
import mv.haluvi.navigation.data.IslandShapes
import mv.haluvi.navigation.data.SavedLocation
import org.json.JSONObject
import java.io.File

/**
 * Islands joined by land or a causeway share one outline (Hulhudhoo and Meedhoo, Maradhoo and
 * Maradhoofeydhoo, Hulhumalé and Hulhulé). The outline stays one piece (it is the real shore), but places and
 * positions are split between the islands, most reliable first:
 *  1. the island named in the place's address (Google or OpenStreetMap);
 *  2. the border you set yourself (standing there, or at a place such as a school at the joining point);
 *  3. otherwise a dividing line between the two villages, learned from the places whose address names their
 *     island or has its postcode (starting at the halfway line between the village points).
 * The same splitting is used for areas inside an island (Hulhumalé Phase 1 and Phase 2), see [Areas].
 */
object JoinedIslands {
    // ------------------------------------------------------------------ groups

    private var groupsCache: Map<String, List<Island>>? = null
    private var groupsSize = -1

    /** Islands sharing one outline, by outline id (only groups of 2 or more). */
    fun groups(): Map<String, List<Island>> {
        val s = AppGraph.shapes
        val all = AppGraph.islands.allIslands + s.extras
        groupsCache?.let { if (groupsSize == all.size) return it }
        val m = all.filter { s.has(it.key) }.groupBy { s.ringId(it.key)!! }.filterValues { it.size >= 2 }
        groupsCache = m; groupsSize = all.size
        return m
    }

    fun groupOf(isl: Island): List<Island>? = AppGraph.shapes.ringId(isl.key)?.let { groups()[it] }

    /** The village point of an island: the official list position (where the houses are), not the outline centre. */
    private fun village(isl: Island): LatLon = AppGraph.shapes.originalPosition(isl.key) ?: LatLon(isl.lat, isl.lon)

    // ------------------------------------------------------------------ address

    /** Island postcodes seen in Google addresses (Addu City). Used only to learn dividing lines, never alone. */
    private val POSTCODES = mapOf(
        "19020" to "S:Hithadhoo", "19030" to "S:Maradhoo", "19040" to "S:Feydhoo",
        "19050" to "S:Maradhoofeydhoo", "19060" to "S:Hulhudhoo", "19010" to "S:Meedhoo"
    )

    /** The island of [group] named in an address ("…, Maradhoo-Feydhoo, Addu City"), the longest name winning. */
    fun fromAddress(address: String?, group: List<Island>): Island? {
        if (address.isNullOrBlank()) return null
        val segments = address.split(',', ';', '/').map { IslandShapes.normalizeName(it) }.filter { it.isNotEmpty() }
        var best: Island? = null; var bestLen = 0
        for (isl in group) {
            val names = (listOf(isl.name, isl.originalName) + (if (isl.airportName.isNotBlank()) listOf(isl.airportName) else emptyList())).distinct()
            for (name in names) {
                val n = IslandShapes.normalizeName(name)
                if (n.length < 4) continue
                if (segments.any { it.contains(n) } && n.length > bestLen) { best = isl; bestLen = n.length }
            }
        }
        return best
    }

    /** For learning a line: the island named in the address, else the island of its postcode. */
    private fun labelOf(address: String, group: List<Island>): Island? =
        fromAddress(address, group) ?: Regex("\\b(\\d{5})\\b").findAll(address).mapNotNull { POSTCODES[it.groupValues[1]] }
            .firstNotNullOfOrNull { k -> group.firstOrNull { it.key == k } }

    // ------------------------------------------------------------------ borders you set

    private var bordersFile: File? = null
    private val borders = HashMap<String, LatLon>()
    @Volatile var version = 0; private set

    private fun ensureBorders() {
        if (bordersFile != null) return
        val f = File(AppGraph.appContext.filesDir, "borders.json"); bordersFile = f
        if (!f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            o.keys().forEach { k -> val p = o.getJSONObject(k); borders[k] = LatLon(p.getDouble("lat"), p.getDouble("lon")) }
        }
    }

    private fun pairKey(a: String, b: String) = if (a < b) "$a|$b" else "$b|$a"

    @Synchronized fun borderOf(a: String, b: String): LatLon? { ensureBorders(); return borders[pairKey(a, b)] }

    /** Sets (or with null clears) the border between two islands or areas: the line goes through this point. */
    @Synchronized
    fun setBorder(a: String, b: String, p: LatLon?) {
        ensureBorders()
        if (p == null) borders.remove(pairKey(a, b)) else borders[pairKey(a, b)] = p
        val o = JSONObject(); borders.forEach { (k, v) -> o.put(k, JSONObject().put("lat", v.lat).put("lon", v.lon)) }
        runCatching { bordersFile?.writeText(o.toString()) }
        version++; splitCache.clear()
    }

    // ------------------------------------------------------------------ dividing lines

    /** A side of a dividing line: an island or an area, with its reference point. */
    class Zone(val key: String, val name: String, val point: LatLon)

    class Split(val border: Double, val labelled: Int, val correct: Int, val userSet: Boolean)

    private val splitCache = HashMap<String, Split>()
    private var cacheItems = -1

    /** Position of (lat, lon) along the line from a to b, in metres from a, and the line's length. */
    fun along(a: LatLon, b: LatLon, lat: Double, lon: Double): Pair<Double, Double> {
        val k = Math.cos(Math.toRadians(a.lat)) * 111320.0
        val bx = (b.lon - a.lon) * k; val by = (b.lat - a.lat) * 111320.0
        val px = (lon - a.lon) * k; val py = (lat - a.lat) * 111320.0
        val len = Math.sqrt(bx * bx + by * by).coerceAtLeast(1.0)
        return ((px * bx + py * by) / len) to len
    }

    /**
     * The border between zones a and b along the a-b line: your border if set; otherwise learned from labelled
     * positions (true = b), starting at the halfway point.
     */
    @Synchronized
    fun split(a: Zone, b: Zone, labels: () -> List<Pair<LatLon, Boolean>>): Split {
        val items = AppGraph.store.items.size
        if (cacheItems != items) { splitCache.clear(); cacheItems = items }
        val key = a.key + "|" + b.key
        splitCache[key]?.let { return it }
        val len = along(a.point, b.point, b.point.lat, b.point.lon).second
        val lab = labels().map { (p, isB) -> along(a.point, b.point, p.lat, p.lon).first to isB }
        val user = borderOf(a.key, b.key)
        var border = user?.let { along(a.point, b.point, it.lat, it.lon).first } ?: (len / 2)
        var correct = lab.count { (s, isB) -> (s >= border) == isB }
        if (user == null && lab.size >= 6) {
            val sorted = lab.map { it.first }.sorted()
            val candidates = (sorted.zipWithNext { x, y -> (x + y) / 2 } + (len / 2)).filter { it > len * 0.1 && it < len * 0.9 }
            for (c in candidates) {
                val ok = lab.count { (s, isB) -> (s >= c) == isB }
                if (ok > correct || (ok == correct && Math.abs(c - len / 2) < Math.abs(border - len / 2))) { correct = ok; border = c }
            }
        }
        return Split(border, lab.size, correct, user != null).also { splitCache[key] = it }
    }

    fun zoneOf(isl: Island) = Zone(isl.key, isl.name, village(isl))

    private fun islandSplit(a: Island, b: Island): Split {
        val ring = AppGraph.shapes.ringId(a.key)
        return split(zoneOf(a), zoneOf(b)) {
            val pair = listOf(a, b)
            AppGraph.store.items.mapNotNull { p ->
                if (p.address.isBlank()) return@mapNotNull null
                if (AppGraph.shapes.ringId(p.islandKey) != ring && !AppGraph.shapes.inside(a.key, p.lat, p.lon)) return@mapNotNull null
                labelOf(p.address, pair)?.let { LatLon(p.lat, p.lon) to (it.key == b.key) }
            }
        }
    }

    /** The zone a position belongs to, comparing each pair of zones with its dividing line. */
    fun <T> byZones(items: List<T>, zone: (T) -> Zone, splitOf: (T, T) -> Split, lat: Double, lon: Double): T {
        if (items.size == 1) return items[0]
        val wins = IntArray(items.size)
        for (i in items.indices) for (j in i + 1 until items.size) {
            val sp = splitOf(items[i], items[j])
            val s = along(zone(items[i]).point, zone(items[j]).point, lat, lon).first
            if (s >= sp.border) wins[j]++ else wins[i]++
        }
        return items[wins.indices.maxByOrNull { wins[it] }!!]
    }

    /** The island of [group] a position belongs to, by the dividing lines. */
    fun byLine(group: List<Island>, lat: Double, lon: Double): Island =
        byZones(group, { zoneOf(it) }, { x, y -> islandSplit(x, y) }, lat, lon)

    /** Address first, then the dividing line. Null when the position is not on a joined landmass. */
    fun islandFor(lat: Double, lon: Double, address: String?, near: Island? = null): Island? {
        val group = (near?.let { groupOf(it) }) ?: run {
            val on = AppGraph.shapes.nearby(lat, lon, 15_000.0, resolve = false).firstOrNull { it.shore <= 150 } ?: return null
            groupOf(on.island)
        } ?: return null
        return fromAddress(address, group) ?: byLine(group, lat, lon)
    }

    /** For a Google search of one joined island: false when the square lies clearly on another island's side. */
    fun mightBelong(isl: Island, lat: Double, lon: Double, radius: Double): Boolean {
        val group = groupOf(isl) ?: return true
        for (other in group) {
            if (other.key == isl.key) continue
            val (a, b) = if (group.indexOf(isl) < group.indexOf(other)) isl to other else other to isl
            val sp = islandSplit(a, b)
            val s = along(village(a), village(b), lat, lon).first
            val margin = if (isl.key == b.key) s - sp.border else sp.border - s
            if (margin < -radius) return false
        }
        return true
    }

    /** One line per joined pair, for the Joined islands screen. */
    fun pairs(): List<Pair<Island, Island>> = groups().values.flatMap { g ->
        val out = ArrayList<Pair<Island, Island>>()
        for (i in g.indices) for (j in i + 1 until g.size) out.add(g[i] to g[j])
        out
    }

    fun pairText(a: Island, b: Island): String {
        val sp = islandSplit(a, b)
        val how = when {
            sp.userSet -> "Border set by you."
            sp.labelled == 0 -> "No places with an island name or postcode in their address yet; the halfway line between the villages is used."
            else -> "Dividing line learned from ${sp.labelled} places with an island name or postcode."
        }
        val agree = if (sp.labelled > 0) " ${sp.correct * 100 / sp.labelled}% of those are on the right side." else ""
        val counts = "${a.name}: ${AppGraph.store.offlineCount(a.key)} places, ${b.name}: ${AppGraph.store.offlineCount(b.key)} places."
        return "${a.name} and ${b.name}. $how$agree $counts"
    }

    /** For the backup file. */
    @Synchronized fun exportJson(): JSONObject {
        ensureBorders()
        val o = JSONObject(); borders.forEach { (k, v) -> o.put(k, JSONObject().put("lat", v.lat).put("lon", v.lon)) }
        return o
    }

    /** From a backup file: borders are added (replacing the same pair). Returns how many. */
    @Synchronized fun importJson(o: JSONObject): Int {
        ensureBorders()
        var n = 0
        o.keys().forEach { k -> runCatching { val p = o.getJSONObject(k); borders[k] = LatLon(p.getDouble("lat"), p.getDouble("lon")); n++ } }
        val out = JSONObject(); borders.forEach { (k, v) -> out.put(k, JSONObject().put("lat", v.lat).put("lon", v.lon)) }
        runCatching { bordersFile?.writeText(out.toString()) }
        version++; splitCache.clear()
        return n
    }

    // ------------------------------------------------------------------ re-checking places

    /** Puts the saved places of one joined group on their own island (address first, then the line). Places you moved yourself are kept. */
    fun recheckGroup(group: List<Island>): Int {
        val g = AppGraph
        val keys = group.map { it.key }.toSet()
        val k0 = group[0].key
        var moved = 0
        for (p in g.store.items) {
            if (p.islandFixed) continue
            // Places given to one of these islands, on their shared land, or at its shore (a jetty) and on no other island.
            if (p.islandKey !in keys && !g.shapes.inside(k0, p.lat, p.lon) &&
                !(g.shapes.shoreDistance(group[0], p.lat, p.lon) <= 100 && g.shapes.islandAt(p.lat, p.lon) == null)) continue
            val isl = fromAddress(p.address, group) ?: byLine(group, p.lat, p.lon)
            if (isl.key != p.islandKey) { p.islandKey = isl.key; p.islandName = isl.fullName; moved++ }
        }
        if (moved > 0) g.store.save()
        return moved
    }

    fun recheckAll(): Int = groups().values.sumOf { recheckGroup(it) }

    /** "Move to another island": also keeps it there in later re-checks. */
    fun moveTo(p: SavedLocation, isl: Island) {
        p.islandKey = isl.key; p.islandName = isl.fullName; p.islandFixed = true
        AppGraph.store.save()
    }
}
