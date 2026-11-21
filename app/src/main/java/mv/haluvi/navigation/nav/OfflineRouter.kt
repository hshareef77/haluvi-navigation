package mv.haluvi.navigation.nav

import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.core.LatLon
import mv.haluvi.navigation.data.DownloadedRoad
import mv.haluvi.navigation.data.TravelMode
import mv.haluvi.navigation.net.Route
import mv.haluvi.navigation.net.RouteStep
import java.util.Locale
import java.util.PriorityQueue
import kotlin.math.abs

/**
 * Turn-by-turn routes calculated on the phone from downloaded OpenStreetMap roads (no internet).
 * Instructions work without road names: "In 40 meters, turn left", "take the second road on the right",
 * "turn right after the harbour" (landmarks from saved and downloaded places).
 */
object OfflineRouter {
    private const val WALK_MPS = 1.3
    private const val DRIVE_MPS = 7.0
    private val NOT_FOR_DRIVING = setOf("footway", "path", "steps", "pedestrian", "cycleway")

    private class Edge(val to: Int, val length: Double, val road: Int)
    private val MAJOR = setOf("primary", "secondary", "tertiary")
    private val MINOR = setOf("footway", "path", "steps", "track", "service", "cycleway")

    private class Graph {
        val nodes = ArrayList<LatLon>()
        val index = HashMap<String, Int>()
        val adj = ArrayList<MutableList<Edge>>()
        fun node(p: LatLon): Int {
            val k = String.format(Locale.US, "%.6f,%.6f", p.lat, p.lon)
            return index.getOrPut(k) { nodes.add(p); adj.add(ArrayList()); nodes.size - 1 }
        }
        fun addEdge(a: Int, b: Int, road: Int, oneway: Boolean) {
            val d = Geo.distance(nodes[a].lat, nodes[a].lon, nodes[b].lat, nodes[b].lon)
            adj[a].add(Edge(b, d, road))
            if (!oneway) adj[b].add(Edge(a, d, road))
        }
    }

    /** Roads of the islands at both ends (the same island, or islands joined by a causeway). */
    private fun roadsFor(from: LatLon, to: LatLon): List<DownloadedRoad> {
        val g = AppGraph
        val keys = LinkedHashSet<String>()
        for (p in listOf(from, to)) {
            (g.shapes.islandAt(p.lat, p.lon) ?: g.islands.nearest(p.lat, p.lon)?.takeIf { it.distance <= 3000 }?.island)?.let { isl ->
                keys.add(isl.key)
                // Joined islands share one outline: include all of them.
                g.shapes.ringId(isl.key)?.let { rid -> g.islands.allIslands.filter { g.shapes.ringId(it.key) == rid }.forEach { keys.add(it.key) } }
            }
        }
        return keys.flatMap { g.store.loadRoads(it) }
    }

    /** True when roads are downloaded for the island at this point. */
    fun available(p: LatLon): Boolean = roadsFor(p, p).isNotEmpty()

    /**
     * Calculates a route, or returns null when no roads are downloaded or the points cannot be connected.
     * [destinationName] is used in the final instruction.
     */
    fun route(from: LatLon, to: LatLon, mode: TravelMode): Route? = routes(from, to, mode, 1).firstOrNull()

    /**
     * Up to [max] clearly different routes, easiest first: fewest turns, named and main roads rather than small lanes,
     * fewest main roads to cross. Alternatives are found by making the roads of earlier routes more costly.
     */
    fun routes(from: LatLon, to: LatLon, mode: TravelMode, max: Int = 3): List<Route> {
        val roads = roadsFor(from, to).filter { mode == TravelMode.WALKING || it.kind !in NOT_FOR_DRIVING }
        if (roads.isEmpty()) return emptyList()
        val g = Graph()
        roads.forEachIndexed { ri, r ->
            var prev = -1
            for (p in r.points) {
                val n = g.node(LatLon(p[0], p[1]))
                if (prev >= 0 && prev != n) g.addEdge(prev, n, ri, mode == TravelMode.DRIVING && r.oneway)
                prev = n
            }
        }
        val start = snap(g, roads, from) ?: return emptyList()
        val end = snap(g, roads, to) ?: return emptyList()
        val maxSnap = if (mode == TravelMode.WALKING) 150.0 else 250.0
        if (start.second > maxSnap || end.second > maxSnap) return emptyList()

        fun ease(e: Edge): Double {
            val r = roads.getOrNull(e.road) ?: return 1.0
            return when {
                r.kind in MINOR -> 1.35
                r.name.isBlank() -> 1.15
                else -> 1.0
            }
        }
        val penalized = HashSet<Long>()
        fun key(a: Int, b: Int) = minOf(a, b).toLong() * 10_000_000L + maxOf(a, b)
        val paths = ArrayList<List<Int>>()
        fun accept(p: List<Int>?) {
            if (p == null || p.size < 2) return
            val edges = (0 until p.size - 1).map { key(p[it], p[it + 1]) }.toSet()
            // A clearly different route: less than 80 percent shared with any route found so far.
            val same = paths.any { q ->
                val qe = (0 until q.size - 1).map { key(q[it], q[it + 1]) }.toSet()
                edges.intersect(qe).size >= 0.8 * minOf(edges.size, qe.size)
            }
            if (!same) paths.add(p)
            edges.forEach { penalized.add(it) }
        }
        accept(astar(g, start.first, end.first) { _, e -> e.length })
        accept(astar(g, start.first, end.first) { _, e -> e.length * ease(e) })
        if (max > 1) repeat(4) {
            accept(astar(g, start.first, end.first) { u, e -> e.length * ease(e) * (if (key(u, e.to) in penalized) 1.8 else 1.0) })
        }
        if (paths.isEmpty()) return emptyList()
        val built = paths.map { buildRoute(g, roads, it, from, to, start.second, end.second, mode) }
        val shortest = built.minOf { it.distanceMeters }
        val ok = built.filter { it.distanceMeters <= shortest * 1.5 + 100 }.sortedBy { it.score }.take(max)
        if (ok.size == 1) return listOf(ok[0].copy(label = "only route"))
        val minTurns = ok.minOf { it.turns }
        return ok.mapIndexed { i, r ->
            r.copy(label = when {
                i == 0 -> "easiest"
                r.distanceMeters <= shortest + 1 -> "shortest"
                r.turns == minTurns -> "fewest turns"
                else -> "another way"
            })
        }
    }

    /** Adds the closest point on any road as a node (splitting that road piece). Returns node and distance. */
    private fun snap(g: Graph, roads: List<DownloadedRoad>, p: LatLon): Pair<Int, Double>? {
        var best: Triple<Int, Int, Double>? = null // road index, segment index, distance
        roads.forEachIndexed { ri, r ->
            for (i in 0 until r.points.size - 1) {
                val a = r.points[i]; val b = r.points[i + 1]
                val d = Geo.distanceToSegment(p.lat, p.lon, a[0], a[1], b[0], b[1])
                if (best == null || d < best!!.third) best = Triple(ri, i, d)
            }
        }
        val (ri, si, d) = best ?: return null
        val r = roads[ri]
        val a = r.points[si]; val b = r.points[si + 1]
        // Projection of p on the segment.
        val k = kotlin.math.cos(Math.toRadians(p.lat))
        val ax = a[1] * k; val ay = a[0]; val bx = b[1] * k; val by = b[0]; val px = p.lon * k; val py = p.lat
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val proj = LatLon(a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]))
        val n = g.node(proj)
        val na = g.node(LatLon(a[0], a[1])); val nb = g.node(LatLon(b[0], b[1]))
        if (n != na) g.addEdge(n, na, ri, false)
        if (n != nb) g.addEdge(n, nb, ri, false)
        return n to d
    }

    private fun astar(g: Graph, s: Int, t: Int, weight: (Int, Edge) -> Double): List<Int>? {
        val dist = DoubleArray(g.nodes.size) { Double.MAX_VALUE }
        val prev = IntArray(g.nodes.size) { -1 }
        val goal = g.nodes[t]
        fun h(n: Int) = Geo.distance(g.nodes[n].lat, g.nodes[n].lon, goal.lat, goal.lon)
        val pq = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        dist[s] = 0.0
        pq.add(h(s) to s)
        while (pq.isNotEmpty()) {
            val (_, u) = pq.poll()!!
            if (u == t) break
            for (e in g.adj[u]) {
                val nd = dist[u] + weight(u, e)
                if (nd < dist[e.to]) { dist[e.to] = nd; prev[e.to] = u; pq.add((nd + h(e.to)) to e.to) }
            }
        }
        if (dist[t] == Double.MAX_VALUE) return null
        val path = ArrayList<Int>()
        var c = t
        while (c != -1) { path.add(c); c = prev[c] }
        return path.reversed()
    }

    private fun bearing(a: LatLon, b: LatLon) = Geo.bearing(a.lat, a.lon, b.lat, b.lon)

    /** Bearing leaving/entering node [i] of [pts], measured over about 10 m to smooth small kinks. */
    private fun bearingOut(pts: List<LatLon>, i: Int): Double {
        var j = i + 1
        while (j < pts.size - 1 && Geo.distance(pts[i].lat, pts[i].lon, pts[j].lat, pts[j].lon) < 10) j++
        return bearing(pts[i], pts[minOf(j, pts.size - 1)])
    }
    private fun bearingIn(pts: List<LatLon>, i: Int): Double {
        var j = i - 1
        while (j > 0 && Geo.distance(pts[i].lat, pts[i].lon, pts[j].lat, pts[j].lon) < 10) j--
        return bearing(pts[maxOf(j, 0)], pts[i])
    }

    private fun turnWord(angle: Double): String? {
        // angle: signed change of direction, positive = right.
        val a = abs(angle)
        val side = if (angle > 0) "right" else "left"
        return when {
            a < 30 -> null
            a < 60 -> "keep slightly $side"
            a < 135 -> "turn $side"
            a < 165 -> "turn sharp $side"
            else -> "make a U-turn"
        }
    }

    private fun ordinal(n: Int) = when (n) { 1 -> "first"; 2 -> "second"; 3 -> "third"; 4 -> "fourth"; else -> "${n}th" }

    /** A saved or downloaded place within 25 m of a point, to use as a landmark. */
    private fun landmark(p: LatLon): String? {
        val hit = AppGraph.store.nearest(p.lat, p.lon).firstOrNull() ?: return null
        return hit.loc.name.takeIf { hit.distance <= 25 && !it.startsWith("Unnamed") }
    }

    private fun buildRoute(g: Graph, roads: List<DownloadedRoad>, path: List<Int>, from: LatLon, to: LatLon,
                           startSnap: Double, endSnap: Double, mode: TravelMode): Route {
        val pts = path.map { g.nodes[it] }
        val speed = if (mode == TravelMode.WALKING) WALK_MPS else DRIVE_MPS
        // Road of each path segment.
        val segRoad = (0 until path.size - 1).map { i -> g.adj[path[i]].firstOrNull { it.to == path[i + 1] }?.road ?: -1 }

        // Find maneuver points.
        data class Turn(val at: Int, val action: String, val sideRoads: Int)
        val turns = ArrayList<Turn>()
        var sideCountLeft = 0; var sideCountRight = 0
        var crossings = 0
        for (i in 1 until pts.size - 1) {
            val inB = bearingIn(pts, i); val outB = bearingOut(pts, i)
            val angle = -Geo.headingDifference(inB, outB) // positive = right turn
            val word = turnWord(angle)
            // Side roads at this node that we do not take (for "second road on the right").
            val node = path[i]
            val others = g.adj[node].filter { it.to != path[i - 1] && it.to != path[i + 1] }
            if (word == null) {
                val ourName = segRoad.getOrNull(i)?.let { roads.getOrNull(it)?.name }.orEmpty()
                if (others.any { e -> roads.getOrNull(e.road)?.let { it.kind in MAJOR && (it.name.isBlank() || it.name != ourName) } == true &&
                        (roads.getOrNull(segRoad.getOrNull(i) ?: -1)?.kind ?: "") !in MAJOR }) crossings++
                for (e in others) {
                    val ob = bearing(pts[i], g.nodes[e.to])
                    val rel = -Geo.headingDifference(inB, ob)
                    if (rel in 30.0..150.0) sideCountRight++ else if (rel in -150.0..-30.0) sideCountLeft++
                }
                continue
            }
            var action: String = word
            val isLeft = angle < 0
            val passed = if (isLeft) sideCountLeft else sideCountRight
            if ((word == "turn left" || word == "turn right") && passed >= 1) {
                action = "take the ${ordinal(passed + 1)} road on the ${if (isLeft) "left" else "right"}"
            }
            val roadName = segRoad.getOrNull(i)?.let { roads.getOrNull(it)?.name }.orEmpty()
            if (roadName.isNotBlank() && !action.startsWith("make")) action += if (action.startsWith("take")) ", $roadName" else " onto $roadName"
            landmark(pts[i])?.let { action += " at $it" }
            turns.add(Turn(i, action, sideCountLeft + sideCountRight))
            sideCountLeft = 0; sideCountRight = 0
        }

        val restSide = sideCountLeft + sideCountRight
        // Steps: departure, one per turn, arrival.
        val steps = ArrayList<RouteStep>()
        val cut = listOf(0) + turns.map { it.at } + listOf(pts.size - 1)
        for (s in 0 until cut.size - 1) {
            val a = cut[s]; val b = cut[s + 1]
            val poly = pts.subList(a, b + 1)
            var len = 0.0
            for (i in 0 until poly.size - 1) len += Geo.distance(poly[i].lat, poly[i].lon, poly[i + 1].lat, poly[i + 1].lon)
            if (s == 0) len += startSnap
            val roadName = segRoad.getOrNull(a)?.let { roads.getOrNull(it)?.name }.orEmpty()
            val action = if (s == 0) "head ${Geo.directionName(bearingOut(pts, 0))}" else turns[s - 1].action
            val maneuver = when {
                s == 0 -> "DEPART"
                action.contains("U-turn") -> "UTURN"
                action.contains("left") -> if (action.contains("slightly")) "TURN_SLIGHT_LEFT" else if (action.contains("sharp")) "TURN_SHARP_LEFT" else "TURN_LEFT"
                action.contains("right") -> if (action.contains("slightly")) "TURN_SLIGHT_RIGHT" else if (action.contains("sharp")) "TURN_SHARP_RIGHT" else "TURN_RIGHT"
                else -> "STRAIGHT"
            }
            steps.add(RouteStep(
                instruction = action.replaceFirstChar { it.uppercase() } + if (roadName.isNotBlank() && s == 0) " on $roadName" else "",
                spokenAction = action, maneuver = maneuver, roadName = roadName,
                distanceMeters = len, durationSeconds = len / speed,
                start = poly.first(), end = poly.last(), polyline = poly,
                sideRoads = if (s < turns.size) turns[s].sideRoads else restSide
            ))
        }
        // Final step: from the road to the destination itself.
        val endSide = run {
            val last = pts.last()
            val inB = bearingIn(pts, pts.size - 1)
            val toB = bearing(last, to)
            val rel = -Geo.headingDifference(inB, toB)
            when { endSnap < 5 -> ""; rel > 20 && rel < 160 -> "right"; rel < -20 && rel > -160 -> "left"; else -> "" }
        }
        steps.add(RouteStep(
            instruction = "Arrive at destination" + if (endSide.isNotBlank()) ", on your $endSide" else "",
            destinationSide = endSide, spokenAction = "arrive", maneuver = "ARRIVE", roadName = "",
            distanceMeters = endSnap, durationSeconds = endSnap / speed,
            start = pts.last(), end = to, polyline = listOf(pts.last(), to)
        ))
        val total = steps.sumOf { it.distanceMeters }
        // The road most of the route is on, and how much of it is on small lanes and paths.
        val byRoad = HashMap<String, Double>()
        var minor = 0.0
        for (i in 0 until pts.size - 1) {
            val r = roads.getOrNull(segRoad[i]) ?: continue
            val d = Geo.distance(pts[i].lat, pts[i].lon, pts[i + 1].lat, pts[i + 1].lon)
            if (r.name.isNotBlank()) byRoad[r.name] = (byRoad[r.name] ?: 0.0) + d
            if (r.kind in MINOR || r.name.isBlank()) minor += d
        }
        val main = byRoad.maxByOrNull { it.value }?.takeIf { it.value >= total * 0.3 }?.key.orEmpty()
        val score = total + 40.0 * turns.size + 80.0 * crossings + 0.35 * minor
        return Route(steps, total, total / speed, "", "Offline", turns = turns.size, crossings = crossings, mainRoad = main, score = score)
    }
}
