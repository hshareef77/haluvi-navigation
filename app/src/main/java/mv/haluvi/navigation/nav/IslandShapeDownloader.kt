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
            // Outlines from version 2.3 or older were matched with older rules: download them all again.
            if (full || g.shapes.formatVersion in 1..2) g.shapes.clear()
            val todo = g.islands.atolls.filter { a -> a.code !in g.shapes.atollsDone || a.islands.any { !g.shapes.has(it.key) && it.key !in g.shapes.notOnMap } }
            if (todo.isEmpty()) onProgress("Everything is already downloaded.")
            for ((i, atoll) in todo.withIndex()) {
                currentCoroutineContext().ensureActive()
                var ok = false
                for (attempt in 1..3) {
                    onProgress("Atoll ${i + 1} of ${todo.size}: ${atoll.fullName}" + if (attempt > 1) ", try $attempt." else ".")
                    val r = withContext(Dispatchers.IO) { runCatching { matchAtoll(atoll) } }
                    val m = r.getOrNull()
                    if (m != null) {
                        g.shapes.putAtoll(m)
                        onProgress("${atoll.fullName}: ${m.islandRing.size} of ${atoll.islands.size} islands found.")
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

    /**
     * Matching rules, in order, for each inhabited island:
     *  1. an outline with the island's own name within 8 km (Dhivehi spellings understood) - corrects a wrong position;
     *  2. the outline the island's position is inside (joined islands may share one outline);
     *  3. the nearest outline within 1.5 km that no other island uses and that has no other island's name;
     *  4. otherwise no outline: the island keeps its centre point and is marked in the report.
     */
    private fun matchAtoll(atoll: Atoll): IslandShapes.AtollData {
        val g = AppGraph
        val pad = 0.12 // about 13 km around the atoll's inhabited islands: covers resorts and airports of the atoll
        val pos = atoll.islands.associate { it.key to (g.shapes.originalPosition(it.key) ?: LatLon(it.lat, it.lon)) }
        val s = pos.values.minOf { it.lat } - pad
        val n = pos.values.maxOf { it.lat } + pad
        val w = pos.values.minOf { it.lon } - pad
        val e = pos.values.maxOf { it.lon } + pad
        val outlines = g.osm.islandOutlines(s, w, n, e)
        val ringIdOf = HashMap<Int, String>()
        fun ringId(i: Int) = ringIdOf.getOrPut(i) { "ring:${atoll.code}:$i" }

        val islandRing = HashMap<String, String>()
        val rings = HashMap<String, List<LatLon>>()
        val names = HashMap<String, String>()
        val methods = HashMap<String, String>()
        val corrected = HashMap<String, Pair<LatLon, Double>>()
        val warnings = HashMap<String, String>()
        val usedBy = HashMap<Int, MutableList<String>>()

        fun assign(islandKey: String, idx: Int, method: String) {
            val o = outlines[idx]
            val id = ringId(idx)
            rings[id] = IslandShapes.simplify(o.ring, 6.0)
            islandRing[islandKey] = id
            if (o.name.isNotBlank()) names[islandKey] = o.name
            methods[islandKey] = method
            usedBy.getOrPut(idx) { ArrayList() }.add(islandKey)
        }
        fun distTo(p: LatLon, idx: Int): Double {
            val r = outlines[idx].ring
            return if (IslandShapes.pointInRing(p.lat, p.lon, r)) 0.0 else Geo.distanceToPolyline(p.lat, p.lon, r + r.first())
        }

        // Pass 1: by name, then inside.
        for (island in atoll.islands) {
            val p = pos.getValue(island.key)
            val byName = outlines.indices.filter { IslandShapes.sameName(outlines[it].name, island.name) }
                .map { it to distTo(p, it) }.filter { it.second <= 8000 }.minByOrNull { it.second }
            if (byName != null) {
                assign(island.key, byName.first, "name")
                if (byName.second > 150) {
                    val c = IslandShapes.centroid(outlines[byName.first].ring)
                    corrected[island.key] = c to Geo.distance(p.lat, p.lon, c.lat, c.lon)
                }
                continue
            }
            val containing = outlines.indices.filter { IslandShapes.pointInRing(p.lat, p.lon, outlines[it].ring) }
                .minByOrNull { IslandShapes.area(outlines[it].ring) }
            if (containing != null) assign(island.key, containing, "inside")
        }
        // Pass 2: nearest unused outline without another island's name.
        val atollNames = atoll.islands.map { it.name }
        for (island in atoll.islands) {
            if (islandRing.containsKey(island.key)) continue
            val p = pos.getValue(island.key)
            val cand = outlines.indices.filter { idx ->
                usedBy[idx] == null && outlines[idx].type != "resort" && outlines[idx].type != "airport" &&
                    atollNames.none { other -> other != island.name && IslandShapes.sameName(outlines[idx].name, other) }
            }.map { it to distTo(p, it) }.filter { it.second <= 1500 }.minByOrNull { it.second }
            if (cand != null) {
                assign(island.key, cand.first, "position")
                warnings[island.key] = "matched by position only (" + (outlines[cand.first].name.ifBlank { "unnamed outline" }) + ", ${cand.second.toInt()} m away) - please check"
            }
        }
        // Shared outlines: fine for joined islands, but mark them for checking.
        for ((idx, keys) in usedBy) if (keys.size > 1) {
            val label = keys.joinToString(", ") { it.substringAfter(":") }
            for (k in keys) if (!warnings.containsKey(k)) warnings[k] = "shares one outline (${outlines[idx].name.ifBlank { "unnamed" }}) with: $label"
        }
        val missing = atoll.islands.map { it.key }.filter { !islandRing.containsKey(it) }.toSet()
        missing.forEach { warnings[it] = "no outline found - uses the island centre point" }

        // Other named islands (resorts, airports, uninhabited) not used by an inhabited island.
        val others = outlines.indices.asSequence()
            .filter { usedBy[it] == null && outlines[it].name.isNotBlank() && IslandShapes.area(outlines[it].ring) >= 2_000 }
            .map { outlines[it] }
            .distinctBy { it.name.lowercase() + "@" + String.format(java.util.Locale.US, "%.3f,%.3f", IslandShapes.centroid(it.ring).lat, IslandShapes.centroid(it.ring).lon) }
            .map { Triple(it.name, it.type, IslandShapes.simplify(it.ring, 8.0)) }
            .toList()

        return IslandShapes.AtollData(atoll.code, atoll.islands.map { it.key }.toSet(), rings, islandRing, names, methods, corrected, warnings, missing, others)
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
                val size = (IslandShapes.radius(ring) * 2).toInt()
                val name = g.shapes.osmName(isl.key)?.takeIf { it.isNotBlank() } ?: "no name"
                val method = when (g.shapes.method(isl.key)) { "name" -> "by name"; "inside" -> "position inside"; "position" -> "by position"; else -> "?" }
                val corr = g.shapes.correction(isl.key)?.let { ", position corrected by ${it.toInt()} m" } ?: ""
                val o = g.shapes.originalPosition(isl.key)
                val now = String.format(java.util.Locale.US, "%.5f,%.5f", isl.lat, isl.lon)
                val was = o?.let { String.format(java.util.Locale.US, "%.5f,%.5f", it.lat, it.lon) } ?: now
                sb.append("  ${isl.name}: map name $name, $method, about $size m across$corr, position $now").append(if (was != now) " (was $was)" else "").append("\n")
                g.shapes.warning(isl.key)?.let { sb.append("    WARNING: $it\n") }
            }
        }
        sb.append("\nOther named islands: ${g.shapes.extras.size}\n")
        g.shapes.extras.sortedWith(compareBy({ it.type }, { it.name })).forEach {
            sb.append("  ${it.name} (${it.type}) ").append(String.format(java.util.Locale.US, "%.4f,%.4f", it.lat, it.lon)).append("\n")
        }
        return sb.toString()
    }
}
