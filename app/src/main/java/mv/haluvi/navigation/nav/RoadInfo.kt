package mv.haluvi.navigation.nav

import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.data.DownloadedRoad
import mv.haluvi.navigation.data.IslandShapes
import mv.haluvi.navigation.data.RoadNames
import mv.haluvi.navigation.data.SavedLocation

/**
 * Roads for places and search: the road of a place (from its address, else the nearest named road within
 * 25 m), road search by name, and the places on a road. Offline; uses the downloaded and built-in roads and
 * your own road names.
 */
object RoadInfo {
    private val ROAD = Regex("([A-Z][\\w'’.-]*(?: [A-Z][\\w'’.-]*)* (?:Magu|Hingun|Higun|Goalhi|Road|Rd|Street|St|Avenue|Highway))")
    private val roadsCache = HashMap<String, List<DownloadedRoad>>()
    private val placeRoad = HashMap<String, String>()
    private var cacheVersion = -1

    @Synchronized
    private fun roads(islandKey: String): List<DownloadedRoad> {
        if (cacheVersion != RoadDetector.version) { roadsCache.clear(); placeRoad.clear(); cacheVersion = RoadDetector.version }
        return roadsCache.getOrPut(islandKey) { AppGraph.store.loadRoads(islandKey) }
    }

    /** "Majeedhee Magu Rd" and "Majeedhee Magu" are the same road. */
    fun clean(name: String): String = name.trim().replace(Regex("\\s+(Rd|Road)$"), "").let { if (it.isBlank()) name.trim() else it }

    fun fromAddress(address: String?): String? = address?.let { ROAD.find(it)?.groupValues?.get(1) }?.let { clean(it) }

    /** Spelling-tolerant form for comparing ("Majeedhee" finds "Majidi Magu"). */
    fun norm(s: String): String = IslandShapes.normalizeName(s)

    /** The nearest named road within [maxD] metres. */
    fun nearestNamed(lat: Double, lon: Double, islandKey: String?, maxD: Double = 25.0): String? {
        val key = islandKey?.takeIf { it.isNotBlank() } ?: RoadDetector.islandKeyAt(lat, lon) ?: return null
        var best: String? = null; var bd = maxD
        for (r in roads(key)) {
            if (r.name.isBlank()) continue
            val d = RoadNames.distanceToLine(lat, lon, r.points)
            if (d < bd) { bd = d; best = r.name }
        }
        return best?.let { clean(it) }
    }

    /** The road of a saved place: from its address, else the nearest named road. */
    fun of(loc: SavedLocation): String? {
        synchronized(this) { if (cacheVersion == RoadDetector.version) placeRoad[loc.id]?.let { return it.ifBlank { null } } }
        val r = fromAddress(loc.address) ?: nearestNamed(loc.lat, loc.lon, loc.islandKey)
        synchronized(this) { placeRoad[loc.id] = r.orEmpty() }
        return r
    }

    /** A road found by search: all its pieces on one island, and the point nearest to you. */
    class RoadHit(val name: String, val islandKey: String, val pieces: List<List<DoubleArray>>, val nearLat: Double, val nearLon: Double, val distance: Double?)

    /** Roads whose name matches [query], on [islandKey] or on the islands within 5 km of you. */
    fun search(query: String, lat: Double?, lon: Double?, islandKey: String?): List<RoadHit> {
        val q = query.trim()
        if (q.length < 3) return emptyList()
        val nq = norm(q)
        val keys = if (islandKey != null) listOf(islandKey) else if (lat != null && lon != null)
            (AppGraph.islands.within(lat, lon, 5000.0).map { it.island.key } + AppGraph.shapes.extras.filter { Geo.distance(lat, lon, it.lat, it.lon) <= 5000 }.map { it.key }).distinct()
        else emptyList()
        val out = ArrayList<RoadHit>()
        for (k in keys) {
            val byName = roads(k).filter { it.name.isNotBlank() && (it.name.contains(q, true) || (nq.length >= 3 && norm(it.name).contains(nq))) }
                .groupBy { norm(clean(it.name)) }
            for ((_, pieces) in byName) {
                var best = pieces[0].points[0]; var bd = Double.MAX_VALUE
                if (lat != null && lon != null) for (p in pieces) for (i in 0 until p.points.size - 1) {
                    val pr = RoadNames.project(doubleArrayOf(lat, lon), p.points[i], p.points[i + 1])
                    val d = Geo.distance(lat, lon, pr[0], pr[1]); if (d < bd) { bd = d; best = pr }
                }
                out.add(RoadHit(clean(pieces[0].name), k, pieces.map { it.points }, best[0], best[1], if (bd < Double.MAX_VALUE) bd else null))
            }
        }
        return out.sortedBy { it.distance ?: 0.0 }.take(10)
    }

    /** True when a place is on this road: its address names it, or it is within 30 m of the road's line. */
    fun isOn(loc: SavedLocation, road: RoadHit): Boolean {
        if (loc.islandKey.isNotBlank() && loc.islandKey != road.islandKey && Geo.distance(loc.lat, loc.lon, road.nearLat, road.nearLon) > 3000) return false
        val n = norm(road.name)
        if (loc.address.isNotBlank() && norm(loc.address).contains(n)) return true
        return road.pieces.any { RoadNames.distanceToLine(loc.lat, loc.lon, it) <= 30 }
    }

    /**
     * Position along the road (metres from the point nearest to you, walking away along the road) and the
     * side of the road ("left" or "right" while walking that way).
     */
    fun alongAndSide(loc: SavedLocation, road: RoadHit): Pair<Double, String> {
        var bestD = Double.MAX_VALUE; var a = doubleArrayOf(0.0, 0.0); var b = a; var pr = a
        for (p in road.pieces) for (i in 0 until p.size - 1) {
            val x = RoadNames.project(doubleArrayOf(loc.lat, loc.lon), p[i], p[i + 1])
            val d = Geo.distance(loc.lat, loc.lon, x[0], x[1])
            if (d < bestD) { bestD = d; a = p[i]; b = p[i + 1]; pr = x }
        }
        val along = Geo.distance(road.nearLat, road.nearLon, pr[0], pr[1])
        // Walking away from you: the segment direction pointing away from the start point.
        var dir = Geo.bearing(a[0], a[1], b[0], b[1])
        if (Geo.angleBetween(dir, Geo.bearing(road.nearLat, road.nearLon, pr[0], pr[1])) > 90 && along > 2) dir = Geo.normalize(dir + 180)
        val diff = Geo.headingDifference(dir, Geo.bearing(pr[0], pr[1], loc.lat, loc.lon)) // positive = left
        return along to (if (bestD < 2) "on the road" else if (diff > 0) "left side" else "right side")
    }

    /** The road name most places near this line give in their address (for naming a road). */
    fun suggestName(line: List<DoubleArray>): String? {
        val names = AppGraph.store.items.asSequence()
            .filter { it.address.isNotBlank() }
            .filter { Geo.distance(it.lat, it.lon, line[line.size / 2][0], line[line.size / 2][1]) <= RoadNames.length(line) + 100 }
            .filter { RoadNames.distanceToLine(it.lat, it.lon, line) <= 30 }
            .mapNotNull { fromAddress(it.address) }
            .groupingBy { it }.eachCount()
        return names.maxByOrNull { it.value }?.key
    }
}
