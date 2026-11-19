package mv.haluvi.navigation.nav

import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.data.DownloadedRoad
import mv.haluvi.navigation.data.RoadNames
import mv.haluvi.navigation.sensors.Fix

/**
 * Finds the downloaded road you are on, carefully enough to name it:
 *  - you must have moved at least 30 m, all of it along the same road (within 15 m),
 *  - in the direction of the road,
 *  - and not next to a junction or another road (GPS could put you on the wrong one).
 * Also gives the road direction at a position (used for "destination on your right").
 */
object RoadDetector {
    private const val NEED_METERS = 30.0
    private const val NEAR_ROAD_M = 15.0

    private val history = ArrayDeque<Fix>()
    private var cacheKey = ""
    private var cacheVersion = -1
    private var roads: List<DownloadedRoad> = emptyList()
    /** Increased when road names change, so the cached roads are reloaded. */
    @Volatile var version = 0

    /** Called for every GPS fix (registered once in AppGraph). Keeps the last 2 minutes of good fixes. */
    fun onFix(f: Fix) {
        if (f.accuracy > 25) return
        val last = history.lastOrNull()
        if (last != null && last.elapsedMs == f.elapsedMs) return
        history.addLast(f)
        while (history.isNotEmpty() && f.elapsedMs - history.first().elapsedMs > 120_000) history.removeFirst()
        while (history.size > 200) history.removeFirst()
    }

    fun islandKeyAt(lat: Double, lon: Double): String? = AppGraph.islands.nearest(lat, lon)?.takeIf { it.distance <= 5000 }?.island?.key

    fun roadsAt(lat: Double, lon: Double): Pair<String, List<DownloadedRoad>>? {
        val key = islandKeyAt(lat, lon) ?: return null
        if (key != cacheKey || cacheVersion != version) {
            roads = AppGraph.store.loadRoads(key); cacheKey = key; cacheVersion = version
        }
        return key to roads
    }

    private data class Near(val road: Int, val seg: Int, val dist: Double)

    private fun nearest(lat: Double, lon: Double, rs: List<DownloadedRoad>, skip: Set<Int> = emptySet()): Near? {
        var best: Near? = null
        rs.forEachIndexed { ri, r ->
            if (ri in skip) return@forEachIndexed
            for (i in 0 until r.points.size - 1) {
                val a = r.points[i]; val b = r.points[i + 1]
                val d = Geo.distanceToSegment(lat, lon, a[0], a[1], b[0], b[1])
                if (best == null || d < best!!.dist) best = Near(ri, i, d)
            }
        }
        return best
    }

    /** "Check this road": which road you are on, and whether it is on the map, named by you or recorded by you. */
    fun describeHere(): String {
        val f = AppGraph.location.fix ?: return "Waiting for GPS."
        if (f.ageMs > 15_000) return "Waiting for GPS."
        val (_, rs) = roadsAt(f.lat, f.lon) ?: return "Not near an island."
        if (rs.isEmpty()) return "No roads downloaded for this island. Download it in Offline maps, or record the road."
        val n = nearest(f.lat, f.lon, rs)
        val weak = if (f.accuracy > 20) " GPS is weak, about ${Phrases.distance(f.accuracy)}." else ""
        if (n == null || n.dist > NEAR_ROAD_M) return "No road here on the map. You can record it.$weak"
        val r = rs[n.road]
        val other = nearest(f.lat, f.lon, rs, setOf(n.road))
        val near = if (other != null && other.dist <= NEAR_ROAD_M) " Another road is close, so this may be a junction." else ""
        return when {
            r.recorded -> "You are on your recorded road${if (r.name.isNotBlank()) ": ${r.name}" else ""}."
            r.userNamed -> "You are on ${r.name}, your name for this map road."
            r.name.isNotBlank() -> "You are on ${r.name}, from the map."
            else -> "You are on an unnamed map road. You can name it."
        } + near + weak
    }

    /** Name of the downloaded road you are on (your own name or the map's), within 15 m; null if unnamed or none. */
    fun roadNameAt(lat: Double, lon: Double): String? {
        val (_, rs) = roadsAt(lat, lon) ?: return null
        val n = nearest(lat, lon, rs) ?: return null
        if (n.dist > NEAR_ROAD_M) return null
        return rs[n.road].name.takeIf { it.isNotBlank() }
    }

    /** Direction of the nearest downloaded road (one of its two directions), when you are within 15 m of it. */
    fun roadDirectionAt(f: Fix): Double? {
        val (_, rs) = roadsAt(f.lat, f.lon) ?: return null
        if (rs.isEmpty()) return null
        val n = nearest(f.lat, f.lon, rs) ?: return null
        if (n.dist > maxOf(NEAR_ROAD_M, minOf(f.accuracy, 25.0))) return null
        val p = rs[n.road].points
        return Geo.bearing(p[n.seg][0], p[n.seg][1], p[n.seg + 1][0], p[n.seg + 1][1])
    }

    /**
     * The whole road: the found piece plus the pieces that continue it straight on with the same name
     * (OpenStreetMap often stores one street in several pieces).
     */
    fun chain(start: Int, rs: List<DownloadedRoad>): List<Int> {
        val used = linkedSetOf(start)
        fun dir(p: List<DoubleArray>, atEnd: Boolean): Double =
            if (atEnd) Geo.bearing(p[p.size - 2][0], p[p.size - 2][1], p.last()[0], p.last()[1])
            else Geo.bearing(p[1][0], p[1][1], p[0][0], p[0][1])
        // Walk outwards from both ends of the start piece.
        for (startAtEnd in listOf(true, false)) {
            var cur = start; var atEnd = startAtEnd
            for (step in 0 until 30) {
                val p = rs[cur].points
                val end = if (atEnd) p.last() else p.first()
                val out = dir(p, atEnd)
                var best = -1; var bestAngle = 36.0; var bestAtEnd = true
                rs.forEachIndexed { ri, r ->
                    if (ri in used || !r.name.equals(rs[start].name, true)) return@forEachIndexed
                    val q = r.points
                    for (qEnd in listOf(false, true)) {
                        val e = if (qEnd) q.last() else q.first()
                        if (Geo.distance(end[0], end[1], e[0], e[1]) > 3) continue
                        // Direction going away from the shared point.
                        val away = if (qEnd) Geo.bearing(q.last()[0], q.last()[1], q[q.size - 2][0], q[q.size - 2][1])
                        else Geo.bearing(q[0][0], q[0][1], q[1][0], q[1][1])
                        val a = Geo.angleBetween(out, away)
                        if (a < bestAngle) { bestAngle = a; best = ri; bestAtEnd = !qEnd }
                    }
                }
                if (best < 0) {
                    // Through a bend: when exactly one road piece continues here (no junction), it is the same road.
                    val touching = ArrayList<Pair<Int, Boolean>>()
                    rs.forEachIndexed { ri, r ->
                        if (ri == cur) return@forEachIndexed
                        val q = r.points
                        if (Geo.distance(end[0], end[1], q.first()[0], q.first()[1]) <= 3) touching.add(ri to false)
                        else if (Geo.distance(end[0], end[1], q.last()[0], q.last()[1]) <= 3) touching.add(ri to true)
                        else for (i in 1 until q.size - 1) if (Geo.distance(end[0], end[1], q[i][0], q[i][1]) <= 3) { touching.add(ri to false); touching.add(ri to true); break }
                    }
                    val only = touching.singleOrNull()
                    if (only != null && only.first !in used && rs[only.first].name.equals(rs[start].name, true)) { best = only.first; bestAtEnd = !only.second }
                }
                if (best < 0) break
                used.add(best); cur = best; atEnd = bestAtEnd
            }
        }
        return used.toList()
    }

    /** Joins the pieces into one line (in order along the road). */
    fun joined(ids: List<Int>, rs: List<DownloadedRoad>): List<DoubleArray> {
        if (ids.size == 1) return rs[ids[0]].points
        val pieces = ids.map { rs[it].points.toMutableList() }.toMutableList()
        val line = pieces.removeAt(0)
        while (pieces.isNotEmpty()) {
            var bi = -1; var mode = 0; var bd = Double.MAX_VALUE
            pieces.forEachIndexed { i, q ->
                val c = listOf(
                    Geo.distance(line.last()[0], line.last()[1], q.first()[0], q.first()[1]),
                    Geo.distance(line.last()[0], line.last()[1], q.last()[0], q.last()[1]),
                    Geo.distance(line.first()[0], line.first()[1], q.last()[0], q.last()[1]),
                    Geo.distance(line.first()[0], line.first()[1], q.first()[0], q.first()[1]))
                c.forEachIndexed { m, d -> if (d < bd) { bd = d; bi = i; mode = m } }
            }
            val q = pieces.removeAt(bi)
            when (mode) {
                0 -> line.addAll(q.drop(1))
                1 -> line.addAll(q.reversed().drop(1))
                2 -> line.addAll(0, q.dropLast(1))
                else -> line.addAll(0, q.reversed().dropLast(1))
            }
        }
        return line
    }

    /** An unnamed road near you (the whole street), described so you can tell which one it is. */
    class NearbyRoad(val islandKey: String, val points: List<DoubleArray>, val length: Double, val distance: Double, val description: String)

    /** Unnamed roads within [radius] metres, nearest first. */
    fun unnamedNear(lat: Double, lon: Double, radius: Double = 600.0): List<NearbyRoad> {
        val (key, rs) = roadsAt(lat, lon) ?: return emptyList()
        val done = HashSet<Int>()
        val out = ArrayList<NearbyRoad>()
        for (i in rs.indices) {
            if (i in done || rs[i].name.isNotBlank()) continue
            val ids = chain(i, rs); done.addAll(ids)
            val line = joined(ids, rs)
            val d = RoadNames.distanceToLine(lat, lon, line)
            if (d > radius) continue
            val len = RoadNames.length(line)
            if (len < 15) continue
            out.add(NearbyRoad(key, line, len, d, describe(line, rs, ids.toSet(), lat, lon, d)))
        }
        return out.sortedBy { it.distance }
    }

    /** "180 meters long, 40 meters away at 2 o'clock, from near Feeali Mosque to near the harbour, joins Main Road". */
    fun describe(line: List<DoubleArray>, rs: List<DownloadedRoad>, own: Set<Int>, lat: Double, lon: Double, dist: Double): String {
        fun near(p: DoubleArray): String? = AppGraph.store.nearest(p[0], p[1]).firstOrNull()?.takeIf { it.distance <= 40 }?.loc?.name
        val a = line.first(); val b = line.last()
        val joins = rs.withIndex().filter { (i, r) -> i !in own && r.name.isNotBlank() &&
            r.points.any { q -> Geo.distance(q[0], q[1], a[0], a[1]) <= 5 || Geo.distance(q[0], q[1], b[0], b[1]) <= 5 } }
            .map { it.value.name }.distinct().take(2)
        var best = line[0]; var bd = Double.MAX_VALUE
        for (p in line) { val dd = Geo.distance(lat, lon, p[0], p[1]); if (dd < bd) { bd = dd; best = p } }
        return buildString {
            append("${Phrases.distance(RoadNames.length(line))} long, ${Phrases.distance(dist)} away ")
            append(Phrases.where(AppGraph.compass.heading, Geo.bearing(lat, lon, best[0], best[1])))
            val na = near(a); val nb = near(b)
            if (na != null || nb != null) append(", from ${na?.let { "near $it" } ?: "a road end"} to ${nb?.let { "near $it" } ?: "a road end"}")
            if (joins.isNotEmpty()) append(", joins ${joins.joinToString(" and ")}")
            append(".")
        }
    }

    sealed class State {
        object NoGps : State()
        object NoRoads : State()
        object NotOnRoad : State()
        data class Walk(val meters: Int) : State()
        object NearJunction : State()
        object WrongDirection : State()
        data class Ready(val islandKey: String, val points: List<DoubleArray>, val currentName: String, val userNamed: Boolean, val description: String) : State()
    }

    fun message(s: State): String = when (s) {
        State.NoGps -> "Waiting for good GPS. Go outside, away from tall buildings."
        State.NoRoads -> "No roads downloaded for this island. Download it in Offline maps first."
        State.NotOnRoad -> "You are not on a downloaded road. Walk on the road you want to name."
        is State.Walk -> "Walk along the road. ${s.meters} of 30 meters done."
        State.NearJunction -> "You are near a junction or another road. Walk a little further along the road."
        State.WrongDirection -> "Walk straight along the road, not across it."
        is State.Ready -> s.description
    }

    /** Checks the last fixes. */
    fun evaluate(): State {
        val now = AppGraph.location.fix ?: return State.NoGps
        if (now.ageMs > 10_000 || now.accuracy > 25) return State.NoGps
        val (key, rs) = roadsAt(now.lat, now.lon) ?: return State.NoRoads
        if (rs.isEmpty()) return State.NoRoads
        val n = nearest(now.lat, now.lon, rs) ?: return State.NoRoads
        if (n.dist > NEAR_ROAD_M) return State.NotOnRoad
        val ids = chain(n.road, rs)
        val line = joined(ids, rs)

        // Recent fixes along this road, newest first, until 30 m of movement.
        val recent = history.toList().asReversed().filter { now.elapsedMs - it.elapsedMs <= 90_000 }
        var meters = 0.0
        var oldest = recent.firstOrNull() ?: now
        var prev = oldest
        for (f in recent) {
            if (RoadNames.distanceToLine(f.lat, f.lon, line) > NEAR_ROAD_M) break
            meters += Geo.distance(prev.lat, prev.lon, f.lat, f.lon)
            prev = f; oldest = f
            if (meters >= NEED_METERS) break
        }
        val straight = Geo.distance(oldest.lat, oldest.lon, now.lat, now.lon)
        if (meters < NEED_METERS || straight < NEED_METERS * 0.7) return State.Walk(minOf(straight, meters).toInt().coerceIn(0, 29))

        // Moving along the road, not across it.
        val p = rs[n.road].points
        val roadB = Geo.bearing(p[n.seg][0], p[n.seg][1], p[n.seg + 1][0], p[n.seg + 1][1])
        val moveB = Geo.bearing(oldest.lat, oldest.lon, now.lat, now.lon)
        val diff = Geo.angleBetween(moveB, roadB)
        if (minOf(diff, 180 - diff) > 30) return State.WrongDirection

        // Another road close by: GPS could be on either one.
        val other = nearest(now.lat, now.lon, rs, ids.toSet())
        if (other != null && other.dist <= minOf(NEAR_ROAD_M, n.dist + 10)) return State.NearJunction

        val r = rs[n.road]
        val len = RoadNames.length(line)
        val place = AppGraph.store.items
            .filter { Geo.distance(now.lat, now.lon, it.lat, it.lon) < len + 200 }
            .map { it to RoadNames.distanceToLine(it.lat, it.lon, line) }
            .filter { it.second <= 40 }
            .minByOrNull { it.second }?.first
        val desc = buildString {
            append(if (r.name.isBlank()) "Unnamed road" else (if (r.userNamed) "Your name: ${r.name}" else "Road called ${r.name}"))
            append(", ${Phrases.distance(len)} long")
            if (place != null) append(", near ${place.name}")
            append(".")
        }
        return State.Ready(key, line, r.name, r.userNamed, desc)
    }
}
