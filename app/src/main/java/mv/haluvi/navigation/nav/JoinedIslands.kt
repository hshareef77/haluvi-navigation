package mv.haluvi.navigation.nav

import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.LatLon
import mv.haluvi.navigation.data.Island
import mv.haluvi.navigation.data.IslandShapes
import mv.haluvi.navigation.data.SavedLocation

/**
 * Islands joined by land or a causeway share one outline (for example Hulhudhoo and Meedhoo). The outline
 * stays one piece (it is the real shore), but places and positions are split between the islands:
 *  1. by the island named in the place's address (Google or OpenStreetMap),
 *  2. otherwise by a dividing line between the two villages, learned from the places whose address names
 *     their island (starting at the halfway line between the village points, moved so most places fall on
 *     the right side).
 */
object JoinedIslands {
    /** Islands sharing one outline, by outline id (only groups of 2 or more). */
    private var groupsCache: Map<String, List<Island>>? = null
    private var groupsSize = -1

    fun groups(): Map<String, List<Island>> {
        val s = AppGraph.shapes
        val all = AppGraph.islands.allIslands + s.extras
        if (groupsCache != null && groupsSize == all.size) return groupsCache!!
        val m = all.filter { s.has(it.key) }.groupBy { s.ringId(it.key)!! }.filterValues { it.size >= 2 }
        groupsCache = m; groupsSize = all.size
        return m
    }

    fun groupOf(isl: Island): List<Island>? = AppGraph.shapes.ringId(isl.key)?.let { groups()[it] }

    /** The village point of an island: the official list position (where the houses are), not the outline centre. */
    private fun village(isl: Island): LatLon = AppGraph.shapes.originalPosition(isl.key) ?: LatLon(isl.lat, isl.lon)

    // ------------------------------------------------------------------ address

    /** The island of [group] named in an address ("…, Maradhoo-Feydhoo, Addu City"), the longest name winning. */
    fun fromAddress(address: String?, group: List<Island>): Island? {
        if (address.isNullOrBlank()) return null
        val segments = address.split(',', ';', '/').map { IslandShapes.normalizeName(it) }.filter { it.isNotEmpty() }
        var best: Island? = null; var bestLen = 0
        for (isl in group) {
            for (name in listOf(isl.name, isl.originalName).distinct()) {
                val n = IslandShapes.normalizeName(name)
                if (n.length < 4) continue
                val hit = segments.any { seg -> seg == n || seg.startsWith(n) || seg.endsWith(n) || seg.contains(n) }
                if (hit && n.length > bestLen) { best = isl; bestLen = n.length }
            }
        }
        return best
    }

    // ------------------------------------------------------------------ dividing line

    /** For two islands A and B: where along the line from A's village to B's the border is (metres from A). */
    private class Split(val a: Island, val b: Island, val border: Double, val labelled: Int, val correct: Int)

    private val splitCache = HashMap<String, Split>()
    private var cacheItems = -1

    private fun along(a: LatLon, b: LatLon, lat: Double, lon: Double): Pair<Double, Double> {
        val k = Math.cos(Math.toRadians(a.lat)) * 111320.0
        val bx = (b.lon - a.lon) * k; val by = (b.lat - a.lat) * 111320.0
        val px = (lon - a.lon) * k; val py = (lat - a.lat) * 111320.0
        val len = Math.sqrt(bx * bx + by * by).coerceAtLeast(1.0)
        return ((px * bx + py * by) / len) to len
    }

    @Synchronized
    private fun split(a: Island, b: Island): Split {
        val items = AppGraph.store.items
        if (cacheItems != items.size) { splitCache.clear(); cacheItems = items.size }
        val key = a.key + "|" + b.key
        splitCache[key]?.let { return it }
        val va = village(a); val vb = village(b)
        val len = along(va, vb, vb.lat, vb.lon).second
        // Places whose address names A or B, as positions along the A-B line.
        val pair = listOf(a, b)
        val ring = AppGraph.shapes.ringId(a.key)
        val labelled = ArrayList<Pair<Double, Boolean>>() // (position, isB)
        for (p in items) {
            if (p.address.isBlank()) continue
            if (ring != null && AppGraph.shapes.ringId(p.islandKey) != ring && !AppGraph.shapes.inside(a.key, p.lat, p.lon)) continue
            val named = fromAddress(p.address, pair) ?: continue
            labelled.add(along(va, vb, p.lat, p.lon).first to (named.key == b.key))
        }
        var border = len / 2
        var correct = labelled.count { (s, isB) -> (s >= border) == isB }
        if (labelled.size >= 6) {
            // Try borders between sorted positions; keep the one with fewest mistakes, nearest the halfway point.
            val sorted = labelled.map { it.first }.sorted()
            val candidates = (sorted.zipWithNext { x, y -> (x + y) / 2 } + (len / 2)).filter { it > len * 0.1 && it < len * 0.9 }
            for (c in candidates) {
                val ok = labelled.count { (s, isB) -> (s >= c) == isB }
                if (ok > correct || (ok == correct && Math.abs(c - len / 2) < Math.abs(border - len / 2))) { correct = ok; border = c }
            }
        }
        return Split(a, b, border, labelled.size, correct).also { splitCache[key] = it }
    }

    /** The island of [group] a position belongs to, by the dividing lines (each pair of islands compared). */
    fun byLine(group: List<Island>, lat: Double, lon: Double): Island {
        if (group.size == 1) return group[0]
        val wins = HashMap<String, Int>()
        for (i in group.indices) for (j in i + 1 until group.size) {
            val sp = split(group[i], group[j])
            val s = along(village(group[i]), village(group[j]), lat, lon).first
            val winner = if (s >= sp.border) group[j] else group[i]
            wins[winner.key] = (wins[winner.key] ?: 0) + 1
        }
        return group.maxByOrNull { wins[it.key] ?: 0 }!!
    }

    /** Address first, then the dividing line. Null when the position is not on a joined landmass. */
    fun islandFor(lat: Double, lon: Double, address: String?, near: Island? = null): Island? {
        val group = (near?.let { groupOf(it) }) ?: run {
            val on = AppGraph.shapes.nearby(lat, lon, 15_000.0, resolve = false).firstOrNull { it.shore <= 150 } ?: return null
            groupOf(on.island)
        } ?: return null
        return fromAddress(address, group) ?: byLine(group, lat, lon)
    }

    /**
     * For a Google search of one joined island: false when the square (centre, radius) lies clearly on the
     * other island's side, so it is not searched again.
     */
    fun mightBelong(isl: Island, lat: Double, lon: Double, radius: Double): Boolean {
        val group = groupOf(isl) ?: return true
        for (other in group) {
            if (other.key == isl.key) continue
            val (a, b) = if (group.indexOf(isl) < group.indexOf(other)) isl to other else other to isl
            val sp = split(a, b)
            val s = along(village(a), village(b), lat, lon).first
            val islIsB = isl.key == b.key
            val margin = if (islIsB) s - sp.border else sp.border - s
            if (margin < -radius) return false
        }
        return true
    }

    /** Text for Manage > Advanced: how each joined pair is split. */
    fun report(): String {
        val gs = groups().values
        if (gs.isEmpty()) return "No joined islands."
        return gs.joinToString("\n\n") { g ->
            val names = g.joinToString(" and ") { it.name }
            val lines = ArrayList<String>()
            for (i in g.indices) for (j in i + 1 until g.size) {
                val sp = split(g[i], g[j])
                lines.add(if (sp.labelled == 0) "${g[i].name} and ${g[j].name}: no places with an island in their address yet; the halfway line between the villages is used."
                else "${g[i].name} and ${g[j].name}: dividing line learned from ${sp.labelled} places with addresses; ${sp.correct * 100 / sp.labelled}% are on the right side.")
            }
            "$names share one outline.\n" + lines.joinToString("\n")
        }
    }

    /** Once: puts every saved place on a joined landmass on its own island (address first, then the line). Returns how many moved. */
    fun recheckAll(): Int {
        val g = AppGraph
        val gs = groups()
        if (gs.isEmpty()) return 0
        var moved = 0
        for ((ring, group) in gs) {
            val keys = group.map { it.key }.toSet()
            val k0 = group[0].key
            for (p in g.store.items) {
                // Places given to one of these islands, or lying on their shared landmass.
                if (p.islandKey !in keys && !g.shapes.inside(k0, p.lat, p.lon)) continue
                val isl = fromAddress(p.address, group) ?: byLine(group, p.lat, p.lon)
                if (isl.key != p.islandKey) { p.islandKey = isl.key; p.islandName = isl.fullName; moved++ }
            }
        }
        if (moved > 0) g.store.save()
        return moved
    }

    fun moveTo(p: SavedLocation, isl: Island) { p.islandKey = isl.key; p.islandName = isl.fullName; AppGraph.store.save() }
}
