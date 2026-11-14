package mv.haluvi.navigation.nav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.core.LatLon
import mv.haluvi.navigation.data.Atoll
import mv.haluvi.navigation.data.IslandShapes

/**
 * Downloads island outlines atoll by atoll and keeps them offline.
 *  - Continues where it stopped: atolls already complete are skipped (unless [full]).
 *  - Each atoll is tried up to 3 times.
 *  - Also keeps other named islands found on the map (resorts, airports, uninhabited islands).
 */
object IslandShapeDownloader {
    @Volatile var running = false
        private set

    data class AtollResult(val atoll: Atoll, val found: Int, val failed: Boolean)

    data class Result(val withOutline: Int, val total: Int, val failedAtolls: List<String>, val missing: List<String>, val extras: Int) {
        /** Clear spoken summary with names. */
        fun message(): String {
            val parts = arrayListOf("$withOutline of $total inhabited islands have an outline.")
            if (missing.isNotEmpty()) parts.add("Not on the map: ${missing.joinToString(", ")}. These use the island centre point.")
            parts.add(if (failedAtolls.isEmpty()) "All atolls downloaded." else "Could not download: ${failedAtolls.joinToString(", ")}. Press Download to try again.")
            if (extras > 0) parts.add("$extras other named islands, such as resorts, were also added.")
            return parts.joinToString(" ")
        }
    }

    /** Islands of the 200 without an outline, as "Name, Atoll" (those not yet downloaded or not on the map). */
    fun missingNames(): List<String> {
        val g = AppGraph
        return g.islands.allIslands.filter { !g.shapes.has(it.key) }.map { "${it.name}, ${it.atollFullName}" }
    }

    fun summary(): Result {
        val g = AppGraph
        val failed = g.islands.atolls.filter { it.code !in g.shapes.atollsDone }.map { it.fullName }
        val missing = g.islands.allIslands.filter { it.key in g.shapes.notOnMap && !g.shapes.has(it.key) }.map { it.name }
        return Result(g.shapes.count, g.islands.allIslands.size, failed, missing, g.shapes.extras.size)
    }

    suspend fun download(full: Boolean, onProgress: (String) -> Unit): Result {
        val g = AppGraph
        running = true
        val failed = ArrayList<String>()
        try {
            val todo = g.islands.atolls.filter { a -> full || a.code !in g.shapes.atollsDone || a.islands.any { !g.shapes.has(it.key) && it.key !in g.shapes.notOnMap } }
            if (todo.isEmpty()) onProgress("Everything is already downloaded.")
            for ((i, atoll) in todo.withIndex()) {
                currentCoroutineContext().ensureActive()
                var ok = false
                for (attempt in 1..3) {
                    onProgress("Atoll ${i + 1} of ${todo.size}: ${atoll.fullName}" + if (attempt > 1) ", try $attempt." else ".")
                    val r = withContext(Dispatchers.IO) { runCatching { matchAtoll(atoll) } }
                    val m = r.getOrNull()
                    if (m != null) {
                        g.shapes.putAtoll(atoll.code, m.matched, m.others, m.missing, atoll.islands.map { it.key }.toSet())
                        onProgress("${atoll.fullName}: ${m.matched.size} of ${atoll.islands.size} islands found.")
                        ok = true
                        break
                    }
                    delay(4000L * attempt)
                }
                if (!ok) failed.add(atoll.fullName)
            }
        } finally {
            running = false
        }
        return summary().copy(failedAtolls = failed.ifEmpty { summary().failedAtolls })
    }

    private class AtollMatch(
        val matched: Map<String, Pair<String, List<LatLon>>>,
        val others: List<Pair<String, List<LatLon>>>,
        val missing: Set<String>
    )

    private fun matchAtoll(atoll: Atoll): AtollMatch {
        val pad = 0.05 // about 5 km around the atoll's inhabited islands
        val s = atoll.islands.minOf { it.lat } - pad
        val n = atoll.islands.maxOf { it.lat } + pad
        val w = atoll.islands.minOf { it.lon } - pad
        val e = atoll.islands.maxOf { it.lon } + pad
        val outlines = AppGraph.osm.islandOutlines(s, w, n, e)
        val matched = HashMap<String, Pair<String, List<LatLon>>>()
        val usedRings = HashSet<List<LatLon>>()
        for (island in atoll.islands) {
            var best: mv.haluvi.navigation.net.OpenStreetMap.Outline? = null
            var bestScore = Double.MAX_VALUE
            for (o in outlines) {
                val inside = IslandShapes.pointInRing(island.lat, island.lon, o.ring)
                val d = if (inside) 0.0 else Geo.distanceToPolyline(island.lat, island.lon, o.ring + o.ring.first())
                if (d > 1500) continue
                val nameMatch = o.name.isNotBlank() && (o.name.contains(island.name, true) || island.name.contains(o.name, true))
                var score = d - (if (nameMatch) 400 else 0) - (if (inside) 1000 else 0)
                if (IslandShapes.area(o.ring) < 5_000) score += 300
                if (score < bestScore) { bestScore = score; best = o }
            }
            best?.let { matched[island.key] = it.name to IslandShapes.simplify(it.ring, 6.0); usedRings.add(it.ring) }
        }
        // Other named islands (resorts, airports, uninhabited) not matched to an inhabited island.
        val others = outlines.asSequence()
            .filter { it.name.isNotBlank() && it.ring !in usedRings && IslandShapes.area(it.ring) >= 2_000 }
            .distinctBy { it.name.lowercase() + "@" + String.format(java.util.Locale.US, "%.3f,%.3f", IslandShapes.centroid(it.ring).lat, IslandShapes.centroid(it.ring).lon) }
            .map { it.name to IslandShapes.simplify(it.ring, 8.0) }
            .toList()
        val missing = atoll.islands.filter { !matched.containsKey(it.key) }.map { it.key }.toSet()
        return AtollMatch(matched, others, missing)
    }

    /** Plain-text report of every inhabited island's outline, for checking accuracy. */
    fun report(): String {
        val g = AppGraph
        val sb = StringBuilder()
        val sum = summary()
        sb.append("Haluvi Navigation island outline report\n")
        sb.append(sum.message()).append("\n\n")
        for (a in g.islands.atolls) {
            val have = a.islands.count { g.shapes.has(it.key) }
            sb.append("${a.fullName} (${a.code}): $have of ${a.islands.size}").append(if (a.code in g.shapes.atollsDone) "" else " - not downloaded").append("\n")
            for (isl in a.islands) {
                val ring = g.shapes.ring(isl.key)
                if (ring == null) {
                    sb.append("  ${isl.name}: NO OUTLINE").append(if (isl.key in g.shapes.notOnMap) " (not on map)" else "").append("\n")
                    continue
                }
                val inside = IslandShapes.pointInRing(isl.lat, isl.lon, ring)
                val off = if (inside) "centre inside" else "centre ${Geo.distanceToPolyline(isl.lat, isl.lon, ring + ring.first()).toInt()} m outside"
                val size = (IslandShapes.radius(ring) * 2).toInt()
                val name = g.shapes.osmName(isl.key)?.takeIf { it.isNotBlank() } ?: "no name"
                sb.append("  ${isl.name}: map name $name, about $size m across, $off, ${ring.size} points\n")
            }
        }
        sb.append("\nOther named islands: ${g.shapes.extras.size}\n")
        g.shapes.extras.sortedBy { it.name }.forEach { sb.append("  ${it.name}\n") }
        return sb.toString()
    }
}
