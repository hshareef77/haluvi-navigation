package mv.haluvi.navigation.nav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.core.LatLon
import mv.haluvi.navigation.data.Atoll
import mv.haluvi.navigation.data.IslandShapes

/** Downloads the coastline outline of every inhabited island, atoll by atoll, and stores them offline. */
object IslandShapeDownloader {
    @Volatile var running = false
        private set

    data class Result(val matched: Int, val total: Int, val failedAtolls: Int)

    suspend fun downloadAll(onProgress: (String) -> Unit): Result {
        val g = AppGraph
        running = true
        var matched = 0
        var failed = 0
        try {
            val atolls = g.islands.atolls
            for ((i, atoll) in atolls.withIndex()) {
                currentCoroutineContext().ensureActive()
                onProgress("Atoll ${i + 1} of ${atolls.size}: ${atoll.fullName}.")
                val found = withContext(Dispatchers.IO) { runCatching { matchAtoll(atoll) } }
                found.onSuccess { m -> g.shapes.putAll(m); matched += m.size }.onFailure { failed++ }
            }
        } finally {
            running = false
        }
        return Result(g.shapes.count.coerceAtLeast(matched), g.islands.allIslands.size, failed)
    }

    private fun matchAtoll(atoll: Atoll): Map<String, List<LatLon>> {
        val pad = 0.03 // about 3 km around the atoll's inhabited islands
        val s = atoll.islands.minOf { it.lat } - pad
        val n = atoll.islands.maxOf { it.lat } + pad
        val w = atoll.islands.minOf { it.lon } - pad
        val e = atoll.islands.maxOf { it.lon } + pad
        val outlines = AppGraph.osm.islandOutlines(s, w, n, e)
        val result = HashMap<String, List<LatLon>>()
        for (island in atoll.islands) {
            var best: List<LatLon>? = null
            var bestScore = Double.MAX_VALUE
            for (o in outlines) {
                val inside = IslandShapes.pointInRing(island.lat, island.lon, o.ring)
                val d = if (inside) 0.0 else Geo.distanceToPolyline(island.lat, island.lon, o.ring + o.ring.first())
                if (d > 1500) continue
                val nameMatch = o.name.isNotBlank() && (o.name.contains(island.name, true) || island.name.contains(o.name, true))
                // Inside the outline is best, then a matching name, then the nearest; small islets are less likely.
                var score = d - (if (nameMatch) 400 else 0) - (if (inside) 1000 else 0)
                if (IslandShapes.area(o.ring) < 5_000) score += 300
                if (score < bestScore) { bestScore = score; best = o.ring }
            }
            best?.let { result[island.key] = IslandShapes.simplify(it, 6.0) }
        }
        return result
    }
}
