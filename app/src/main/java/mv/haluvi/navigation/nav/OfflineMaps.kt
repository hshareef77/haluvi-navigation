package mv.haluvi.navigation.nav

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.core.LatLon
import mv.haluvi.navigation.data.Atoll
import mv.haluvi.navigation.data.Categories
import mv.haluvi.navigation.data.DownloadedRoad
import mv.haluvi.navigation.data.Island
import mv.haluvi.navigation.data.IslandShapes
import mv.haluvi.navigation.data.SavedLocation
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Offline maps: island outlines, roads and places from OpenStreetMap, for one island, one atoll or
 * the whole Maldives. Everything is stored on the phone and works without internet.
 */
object OfflineMaps {
    data class Status(val timeMs: Long, val places: Int, val roads: Int)

    @Volatile var running = false
        private set

    private fun file(): File = File(AppGraph.appContext.filesDir, "offline_maps.json")

    private var cache: MutableMap<String, Status>? = null

    @Synchronized
    fun statuses(): MutableMap<String, Status> {
        cache?.let { return it }
        val m = HashMap<String, Status>()
        runCatching {
            val f = file()
            if (f.exists()) {
                val o = JSONObject(f.readText())
                o.keys().forEach { k -> val s = o.getJSONObject(k); m[k] = Status(s.getLong("t"), s.getInt("p"), s.getInt("r")) }
            }
        }
        cache = m
        return m
    }

    @Synchronized
    private fun saveStatuses() {
        val o = JSONObject()
        statuses().forEach { (k, s) -> o.put(k, JSONObject().put("t", s.timeMs).put("p", s.places).put("r", s.roads)) }
        file().writeText(o.toString())
    }

    fun status(key: String): Status? = statuses()[key]

    fun dateText(ms: Long): String = SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(ms))

    /** "Faafu: 5 of 5 islands, downloaded 14 Nov 2026" or "Faafu: not downloaded". */
    fun atollText(a: Atoll): String {
        val have = a.islands.mapNotNull { status(it.key) }
        if (have.isEmpty()) return "${a.fullName}: not downloaded"
        val latest = have.maxOf { it.timeMs }
        return "${a.fullName}: ${have.size} of ${a.islands.size} islands, downloaded ${dateText(latest)}"
    }

    fun islandText(i: Island): String {
        val s = status(i.key) ?: return "${i.name}: not downloaded"
        return "${i.name}: ${s.places} places, ${s.roads} roads, downloaded ${dateText(s.timeMs)}"
    }

    /** The island you are on: inside its outline, or the nearest inhabited island within 3 km. */
    fun myIsland(): Island? {
        val g = AppGraph
        val f = g.location.fix ?: return null
        g.shapes.islandAt(f.lat, f.lon)?.takeIf { it.atollIndex >= 0 }?.let { return it }
        return g.islands.nearest(f.lat, f.lon)?.takeIf { it.distance <= 3000 }?.island
    }

    /**
     * Downloads outlines, places and roads for [islands] (inhabited islands), atoll by atoll.
     * Returns a spoken summary.
     */
    suspend fun download(islands: List<Island>, onProgress: (String) -> Unit): String {
        val g = AppGraph
        running = true
        var places = 0; var roads = 0; var done = 0
        val failed = ArrayList<String>()
        try {
            val byAtoll = islands.filter { it.atollIndex >= 0 }.groupBy { it.atollIndex }
            var step = 0
            for ((ai, group) in byAtoll) {
                currentCoroutineContext().ensureActive()
                step++
                val atoll = g.islands.atolls[ai]
                val prefix = if (byAtoll.size > 1) "Atoll $step of ${byAtoll.size}: " else ""
                // 1. Island outlines for this atoll (needed to put places and roads on the right island).
                // Outlines are replaced only when the new download succeeds (old ones keep working meanwhile).
                if (atoll.code !in g.shapes.atollsDone) IslandShapeDownloader.ensureAtoll(atoll, prefix, onProgress)
                // 2. Places and roads in one request each, for the box around the chosen islands.
                val what = if (group.size == 1) group[0].name else atoll.fullName
                val box = boxFor(group)
                val main = android.os.Handler(android.os.Looper.getMainLooper())
                var head = ""
                g.osm.onStatus = { msg -> main.post { onProgress("$head $msg") } }
                val result = try {
                    withRetry { attempt ->
                        val tryText = if (attempt > 1) ", try $attempt of 3" else ""
                        head = prefix + "$what: places$tryText."
                        onProgress(head)
                        val pl = withContext(Dispatchers.IO) { g.osm.placesInBox(box[0], box[1], box[2], box[3]) }
                        head = prefix + "$what: ${pl.size} places received. Roads$tryText."
                        onProgress(head)
                        val rd = withContext(Dispatchers.IO) { g.osm.roadsInBox(box[0], box[1], box[2], box[3]) }
                        onProgress(prefix + "$what: ${pl.size} places and ${rd.size} roads received. Saving.")
                        pl to rd
                    }
                } finally { g.osm.onStatus = null }
                if (result == null) { failed.add(atoll.fullName); continue }
                val (placeList, roadList) = result
                // 3. Put each place and road on its island.
                val placesBy = HashMap<String, MutableList<SavedLocation>>()
                for (p in placeList) {
                    val isl = islandFor(group, p.lat, p.lon) ?: continue
                    placesBy.getOrPut(isl.key) { ArrayList() }.add(
                        SavedLocation(SavedLocation.newId(), p.name, p.lat, p.lon, 0.0, source = SavedLocation.SOURCE_OSM_MAP,
                            categoryId = p.categoryId.takeIf { it != "other" } ?: Categories.guess(p.name + " " + p.typeText),
                            islandKey = isl.key, islandName = isl.fullName, address = p.address)
                    )
                }
                val roadsBy = HashMap<String, MutableList<DownloadedRoad>>()
                for (r in roadList) {
                    val mid = r.points[r.points.size / 2]
                    val isl = islandFor(group, mid[0], mid[1]) ?: islandFor(group, r.points[0][0], r.points[0][1]) ?: continue
                    roadsBy.getOrPut(isl.key) { ArrayList() }.add(r)
                }
                // 4. Replace each island's old map data and save.
                withContext(Dispatchers.IO) {
                    for (isl in group) {
                        g.store.deleteDownloadedForIsland(isl.key)
                        val pl = placesBy[isl.key].orEmpty()
                        var added = 0
                        pl.forEach { if (g.store.add(it, persist = false)) added++ }
                        val rl = roadsBy[isl.key].orEmpty()
                        g.store.saveRoads(isl.key, rl)
                        statuses()[isl.key] = Status(System.currentTimeMillis(), added, rl.size)
                        places += added; roads += rl.size; done++
                    }
                    g.store.save()
                    saveStatuses()
                }
                onProgress("${atoll.fullName}: done.")
            }
        } finally {
            running = false
        }
        val detail = if (islands.size == 1 && done == 1) {
            val isl = islands[0]
            val rs = g.store.loadRoads(isl.key)
            var km = 0.0
            rs.forEach { r -> for (i in 0 until r.points.size - 1) km += Geo.distance(r.points[i][0], r.points[i][1], r.points[i + 1][0], r.points[i + 1][1]) / 1000 }
            val (parts, share) = connectivity(rs)
            val conn = when {
                rs.isEmpty() -> "no roads on the map"
                parts == 1 -> "all connected"
                else -> "$parts separate parts, the largest is ${(share * 100).toInt()} percent"
            }
            "Done. ${isl.name}: $places places, $roads roads, ${String.format(Locale.US, "%.1f", km)} kilometers of roads, $conn."
        } else "Done. $done islands: $places places and $roads roads saved for offline use."
        return detail + if (failed.isNotEmpty()) " Failed, map server busy or no internet: ${failed.joinToString(", ")}. Press the download again to retry." else ""
    }

    private suspend fun <T> withRetry(block: suspend (attempt: Int) -> T): T? {
        for (attempt in 1..3) {
            try { return block(attempt) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                currentCoroutineContext().ensureActive()
                delay(5000L * attempt)
            }
        }
        return null
    }

    /** South, west, north, east around the islands (outline extents when known, else 3 km around the centre). */
    private fun boxFor(group: List<Island>): DoubleArray {
        val g = AppGraph
        var s = 90.0; var w = 180.0; var n = -90.0; var e = -180.0
        for (isl in group) {
            val ring = g.shapes.ring(isl.key)
            if (ring != null) {
                ring.forEach { s = minOf(s, it.lat); n = maxOf(n, it.lat); w = minOf(w, it.lon); e = maxOf(e, it.lon) }
            } else {
                s = minOf(s, isl.lat - 0.03); n = maxOf(n, isl.lat + 0.03); w = minOf(w, isl.lon - 0.03); e = maxOf(e, isl.lon + 0.03)
            }
        }
        val pad = 0.004 // about 400 m: harbours and piers just outside the coastline
        return doubleArrayOf(s - pad, w - pad, n + pad, e + pad)
    }

    /** The chosen island a point belongs to: inside its outline, or the nearest one within 2.5 km. */
    private fun islandFor(group: List<Island>, lat: Double, lon: Double): Island? {
        val g = AppGraph
        val inside = group.filter { g.shapes.inside(it.key, lat, lon) }
        if (inside.size == 1) return inside[0]
        val pool = inside.ifEmpty { group }
        // Joined islands share one outline: the nearest island centre decides.
        val best = pool.minByOrNull { Geo.distance(lat, lon, it.lat, it.lon) } ?: return null
        if (inside.isEmpty()) {
            // Outside every outline (harbour, pier, reef): only if close to the shore of a chosen island.
            val shore = g.shapes.shoreDistance(best, lat, lon)
            if (shore > 500) return null
        }
        return best
    }

    /** Removes downloaded places and roads of [islands]. */
    fun delete(islands: List<Island>): Int {
        val g = AppGraph
        var n = 0
        islands.forEach { n += g.store.deleteDownloadedForIsland(it.key); statuses().remove(it.key) }
        saveStatuses()
        return n
    }

    /** Plain-text report of downloaded islands: places per category and road quality for routing. */
    fun report(): String {
        val g = AppGraph
        val sb = StringBuilder("Haluvi Navigation offline maps report\n\n")
        val keys = statuses().keys
        if (keys.isEmpty()) return sb.append("Nothing downloaded yet.").toString()
        for (a in g.islands.atolls) for (isl in a.islands) {
            val st = status(isl.key) ?: continue
            sb.append("${isl.fullName} (${a.code}), downloaded ${dateText(st.timeMs)}\n")
            val pl = g.store.items.filter { it.isDownloadedMapPlace && it.islandKey == isl.key }
            val cats = pl.groupBy { it.categoryName }.map { "${it.key} ${it.value.size}" }.sorted()
            sb.append("  Places: ${pl.size}" + if (cats.isNotEmpty()) " (" + cats.joinToString(", ") + ")" else "").append("\n")
            val rs = g.store.loadRoads(isl.key)
            val named = rs.count { it.name.isNotBlank() }
            var lengthM = 0.0
            rs.forEach { r -> for (i in 0 until r.points.size - 1) lengthM += Geo.distance(r.points[i][0], r.points[i][1], r.points[i + 1][0], r.points[i + 1][1]) }
            val kinds = rs.groupBy { it.kind.ifBlank { "other" } }.map { "${it.key} ${it.value.size}" }.sorted()
            sb.append("  Roads: ${rs.size}, named $named, total ${String.format(Locale.US, "%.1f", lengthM / 1000)} km (${kinds.joinToString(", ")})\n")
            val (parts, largestShare) = connectivity(rs)
            sb.append("  Road network: $parts connected part" + (if (parts == 1) "" else "s") + ", largest part ${(largestShare * 100).toInt()}% of road length\n")
            g.shapes.ring(isl.key)?.let { sb.append("  Outline: yes, about ${(IslandShapes.radius(it) * 2).toInt()} m across\n") } ?: sb.append("  Outline: no\n")
        }
        return sb.toString()
    }

    /** How well roads connect (routing needs one connected network): number of parts and share of the biggest. */
    fun connectivity(roads: List<DownloadedRoad>): Pair<Int, Double> {
        if (roads.isEmpty()) return 0 to 0.0
        val parent = IntArray(roads.size) { it }
        fun find(x: Int): Int { var a = x; while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }; return a }
        val byNode = HashMap<String, Int>()
        roads.forEachIndexed { i, r ->
            r.points.forEach { p ->
                val k = String.format(Locale.US, "%.6f,%.6f", p[0], p[1])
                val other = byNode.putIfAbsent(k, i)
                if (other != null) { val ra = find(i); val rb = find(other); if (ra != rb) parent[ra] = rb }
            }
        }
        val len = DoubleArray(roads.size)
        roads.forEachIndexed { i, r -> for (j in 0 until r.points.size - 1) len[i] += Geo.distance(r.points[j][0], r.points[j][1], r.points[j + 1][0], r.points[j + 1][1]) }
        val groups = HashMap<Int, Double>()
        roads.indices.forEach { groups[find(it)] = (groups[find(it)] ?: 0.0) + len[it] }
        val total = len.sum().takeIf { it > 0 } ?: 1.0
        return groups.size to (groups.values.maxOrNull() ?: 0.0) / total
    }

    @Suppress("unused")
    private fun centre(r: List<LatLon>) = IslandShapes.centroid(r)
}
