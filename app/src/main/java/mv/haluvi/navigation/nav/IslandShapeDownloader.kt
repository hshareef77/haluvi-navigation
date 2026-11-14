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

    /** Atolls that failed in the last download (map server busy or no internet). */
    @Volatile var lastFailed: List<String> = emptyList()
        private set

    data class Result(
        val withOutline: Int,
        val total: Int,
        val failedAtolls: List<String>,
        val missing: List<String>,
        val extras: Int,
        /** Atolls not downloaded yet, or downloaded with older rules and waiting for an update. */
        val notYet: List<String> = emptyList()
    ) {
        /** Clear spoken summary with names. Says "failed" only for atolls that really failed. */
        fun message(): String {
            val parts = arrayListOf("$withOutline of $total inhabited islands have an outline.")
            if (missing.isNotEmpty()) parts.add("Not on the map: ${missing.joinToString(", ")}. These use the island centre point.")
            if (failedAtolls.isNotEmpty()) parts.add("Failed, map server busy or no internet: ${failedAtolls.joinToString(", ")}. Press Download missing to try again.")
            if (notYet.isNotEmpty()) parts.add("Not downloaded yet or waiting for update: ${if (notYet.size > 6) "${notYet.size} atolls" else notYet.joinToString(", ")}.")
            if (failedAtolls.isEmpty() && notYet.isEmpty()) parts.add("All atolls downloaded.")
            if (extras > 0) parts.add("$extras other named islands, such as resorts.")
            return parts.joinToString(" ")
        }
    }

    /** Inhabited islands without an outline, as "Name, Atoll". */
    fun missingNames(): List<String> {
        val g = AppGraph
        return g.islands.allIslands.filter { !g.shapes.has(it.key) }.map { "${it.name}, ${it.atollFullName}" }
    }

    fun summary(): Result {
        val g = AppGraph
        val failed = lastFailed
        val notYet = g.islands.atolls.filter { it.code !in g.shapes.atollsDone && it.fullName !in failed }.map { it.fullName }
        val missing = g.islands.allIslands.filter { it.key in g.shapes.notOnMap && !g.shapes.has(it.key) }.map { it.name }
        return Result(g.shapes.count, g.islands.allIslands.size, failed, missing, g.shapes.extras.size, notYet)
    }

    /**
     * Downloads outlines for atolls that are missing, failed or made with older rules ([full]: all atolls).
     * Existing outlines are never deleted in advance: each atoll is replaced only when its new download succeeds.
     */
    suspend fun download(full: Boolean, onProgress: (String) -> Unit): Result {
        val g = AppGraph
        running = true
        val failed = ArrayList<String>()
        try {
            val todo = g.islands.atolls.filter { a -> full || a.code !in g.shapes.atollsDone || a.islands.any { !g.shapes.has(it.key) && it.key !in g.shapes.notOnMap } }
            if (todo.isEmpty()) onProgress("Everything is already downloaded.")
            for ((i, atoll) in todo.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (!ensureAtoll(atoll, "Atoll ${i + 1} of ${todo.size}: ", onProgress)) failed.add(atoll.fullName)
                lastFailed = failed.toList()
            }
        } finally {
            running = false
        }
        lastFailed = failed
        return summary()
    }

    /** Downloads and matches one atoll's outlines (tries up to 3 times). Returns false if it failed. */
    suspend fun ensureAtoll(atoll: Atoll, prefix: String, onProgress: (String) -> Unit): Boolean {
        val g = AppGraph
        for (attempt in 1..3) {
            val head = prefix + "${atoll.fullName} island outlines" + if (attempt > 1) ", try $attempt of 3" else ""
            onProgress("$head.")
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            g.osm.onStatus = { msg -> main.post { onProgress("$head. $msg") } }
            // From the second try, ask in two smaller halves: busy servers answer small requests more easily.
            val m = try {
                withContext(Dispatchers.IO) { runCatching { matchAtoll(atoll, split = attempt >= 2) } }.getOrNull()
            } finally { g.osm.onStatus = null }
            if (m != null) {
                g.shapes.putAtoll(m)
                onProgress("${atoll.fullName}: ${m.islandRing.size} of ${atoll.islands.size} island outlines found.")
                lastFailed = lastFailed - atoll.fullName
                return true
            }
            delay(4000L * attempt)
        }
        return false
    }

    /**
     * Matching rules, in order, for each inhabited island:
     *  1. an outline with the island's own name within 8 km (Dhivehi spellings understood) - corrects a wrong position;
     *  2. the outline the island's position is inside (joined islands may share one outline);
     *  3. the nearest outline within 1.5 km that no other island uses and that has no other island's name;
     *  4. otherwise no outline: the island keeps its centre point and is marked in the report.
     */
    private fun matchAtoll(atoll: Atoll, split: Boolean = false): IslandShapes.AtollData {
        val g = AppGraph
        val pad = 0.12 // about 13 km around the atoll's inhabited islands: covers resorts and airports of the atoll
        val pos = atoll.islands.associate { it.key to (g.shapes.originalPosition(it.key) ?: LatLon(it.lat, it.lon)) }
        val s = pos.values.minOf { it.lat } - pad
        val n = pos.values.maxOf { it.lat } + pad
        val w = pos.values.minOf { it.lon } - pad
        val e = pos.values.maxOf { it.lon } + pad
        val outlines = if (!split) g.osm.islandOutlines(s, w, n, e) else {
            // Two halves (south and north) that overlap a little, merged without duplicates.
            val mid = (s + n) / 2
            val parts = g.osm.islandOutlines(s, w, mid + 0.02, e) + g.osm.islandOutlines(mid - 0.02, w, n, e)
            val merged = ArrayList<mv.haluvi.navigation.net.OpenStreetMap.Outline>()
            for (o in parts) {
                val c = IslandShapes.centroid(o.ring)
                val dup = merged.indexOfFirst { m -> IslandShapes.centroid(m.ring).let { Geo.distance(it.lat, it.lon, c.lat, c.lon) < 30 } }
                if (dup < 0) merged.add(o) else if (o.ring.size > merged[dup].ring.size) merged[dup] = o
            }
            merged
        }
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

        // Built-in positions that are identical to another island's are wrong copies (e.g. Maathoda = Thinadhoo).
        val suspect = atoll.islands.filter { a -> atoll.islands.any { b -> b !== a && pos.getValue(a.key).let { pa -> pos.getValue(b.key).let { pb -> Geo.distance(pa.lat, pa.lon, pb.lat, pb.lon) < 20 } } } }.map { it.key }.toSet()
        for (k in suspect) warnings[k] = "built-in position is the same as another island's - " + "position taken from the map instead"

        // Names an outline answers to: its own name, each word of it ("Fares Maathodaa"), and villages inside it.
        fun exactName(idx: Int, name: String) = IslandShapes.sameName(outlines[idx].name, name)
        fun partName(idx: Int, name: String): Boolean {
            val o = outlines[idx]
            val words = o.name.split(Regex("[^\\p{L}']+")).filter { it.length >= 4 }
            return (words.size > 1 && words.any { IslandShapes.sameName(it, name) }) || o.places.any { IslandShapes.sameName(it.name, name) }
        }
        /** Best position for an island on a matched outline: its village point if the map has one, else the outline middle. */
        fun placeOn(idx: Int, name: String): LatLon {
            val o = outlines[idx]
            return o.places.firstOrNull { IslandShapes.sameName(it.name, name) }?.let { LatLon(it.lat, it.lon) } ?: IslandShapes.centroid(o.ring)
        }

        // Pass 1: by name (whole name first, then a word of a joined island's name or a village inside), then inside.
        for (island in atoll.islands) {
            val p = pos.getValue(island.key)
            val maxD = if (island.key in suspect) 60_000.0 else 8_000.0
            val byName = (outlines.indices.filter { exactName(it, island.name) }.takeIf { it.isNotEmpty() }
                ?: outlines.indices.filter { partName(it, island.name) })
                .map { it to distTo(p, it) }.filter { it.second <= maxD }.minByOrNull { it.second }
            if (byName != null) {
                assign(island.key, byName.first, "name")
                if (byName.second > 150 || island.key in suspect) {
                    val c = placeOn(byName.first, island.name)
                    corrected[island.key] = c to Geo.distance(p.lat, p.lon, c.lat, c.lon)
                }
                continue
            }
            if (island.key in suspect) continue // never trust a copied position for "inside" matching
            val containing = outlines.indices.filter { IslandShapes.pointInRing(p.lat, p.lon, outlines[it].ring) }
                .minByOrNull { IslandShapes.area(outlines[it].ring) }
            if (containing != null) assign(island.key, containing, "inside")
        }
        // Pass 2: nearest unused outline without another island's name.
        val atollNames = atoll.islands.map { it.name }
        for (island in atoll.islands) {
            if (islandRing.containsKey(island.key) || island.key in suspect) continue
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

        // Other named islands (resorts, airports, uninhabited) - not the same land as an inhabited island.
        val usedRings = usedBy.keys.map { outlines[it].ring }
        val allInhabited = g.islands.allIslands.map { g.shapes.originalPosition(it.key) ?: LatLon(it.lat, it.lon) }
        fun sameLand(r: List<LatLon>): Boolean {
            val c = IslandShapes.centroid(r)
            // Any inhabited island anywhere (neighbouring atolls too) inside this outline -> not an "other island".
            if (allInhabited.any { IslandShapes.pointInRing(it.lat, it.lon, r) }) return true
            return usedRings.any { u ->
                IslandShapes.pointInRing(c.lat, c.lon, u) ||
                    IslandShapes.centroid(u).let { cu -> IslandShapes.pointInRing(cu.lat, cu.lon, r) && IslandShapes.area(r) <= IslandShapes.area(u) * 1.6 }
            }
        }
        val candidates = outlines.indices.asSequence()
            .filter { usedBy[it] == null && outlines[it].name.isNotBlank() && IslandShapes.area(outlines[it].ring) >= 2_000 && !sameLand(outlines[it].ring) }
            .map { outlines[it] }
            .sortedByDescending { IslandShapes.area(it.ring) }
            .toList()
        // One entry per name within 3 km (keeps the biggest), so "Kaadedhdhoo" is not listed twice.
        val kept = ArrayList<mv.haluvi.navigation.net.OpenStreetMap.Outline>()
        for (o in candidates) {
            val c = IslandShapes.centroid(o.ring)
            if (kept.none { k -> IslandShapes.sameName(k.name, o.name) && IslandShapes.centroid(k.ring).let { Geo.distance(it.lat, it.lon, c.lat, c.lon) < 3_000 } }) kept.add(o)
        }
        val others = kept.map { Triple(it.name, it.type, IslandShapes.simplify(it.ring, 8.0)) }

        val airports = usedBy.filter { (idx, _) -> outlines[idx].type == "airport" }.values.flatten().toSet()
        return IslandShapes.AtollData(atoll.code, atoll.islands.map { it.key }.toSet(), rings, islandRing, names, methods, corrected, warnings, missing, others, airports)
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
