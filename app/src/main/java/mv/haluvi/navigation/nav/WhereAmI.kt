package mv.haluvi.navigation.nav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.Fmt
import mv.haluvi.navigation.core.Geo

/** "Where am I": nearest named place, road and island. Google first (Lua v101), offline fallback. */
object WhereAmI {
    /** Short offline description of where you are, for the Places screen: "near City Pharmacy, on Majeedhee Magu, Feeali". */
    fun placeLine(): String {
        val g = AppGraph
        val f = g.location.fix ?: return "Waiting for GPS."
        val parts = ArrayList<String>()
        g.store.nearest(f.lat, f.lon).firstOrNull()?.let { h ->
            if (h.distance <= 15) parts.add("at ${h.loc.name}") else if (h.distance <= 80) parts.add("near ${h.loc.name}")
        }
        RoadDetector.roadNameAt(f.lat, f.lon)?.let { parts.add("on $it") }
        val isl = g.shapes.islandAt(f.lat, f.lon) ?: g.islands.nearest(f.lat, f.lon)?.takeIf { it.distance <= 3000 }?.island
        isl?.let { parts.add(it.name) }
        val text = if (parts.isEmpty()) "position known, no place name nearby" else parts.joinToString(", ")
        return "You are " + text + "." + if (f.accuracy > 30) " GPS is weak." else ""
    }

    /** For the Islands screen: "You are on Feeali." or "At sea, 2 kilometers from Feeali." */
    fun islandLine(): String {
        val g = AppGraph
        val f = g.location.fix ?: return "Waiting for GPS."
        g.shapes.islandAt(f.lat, f.lon)?.let { return "You are on ${it.spokenName}." }
        val hit = g.shapes.nearby(f.lat, f.lon, 30_000.0).firstOrNull()
        if (hit != null && hit.shore > 50) return "At sea, ${Phrases.distance(hit.shore)} from ${hit.island.spokenName}."
        return g.islands.nearest(f.lat, f.lon)?.let { "Near ${it.island.name}." } ?: "Position known."
    }

    suspend fun describe(): String {
        val g = AppGraph
        val f = g.location.freshFix(60_000) ?: return if (!g.location.isGpsEnabled()) "Location is turned off. Please turn on location."
            else "GPS is not ready yet. Please wait a moment in an open area and try again."

        val islandHit = g.islands.nearest(f.lat, f.lon)
        // With outlines: the island you are really on (also resorts and airports), or "at sea".
        val onIsland = g.shapes.islandAt(f.lat, f.lon)
        val islandText = when {
            onIsland != null -> "On ${onIsland.spokenName}."
            !g.shapes.isEmpty && g.islandWatcher.atSea(f) -> g.shapes.nearby(f.lat, f.lon, 20_000.0).firstOrNull()?.let {
                "At sea, ${Phrases.distance(it.shore)} from ${it.island.spokenName}."
            } ?: "At sea."
            else -> null
        }
        val saved = g.store.nearest(f.lat, f.lon).firstOrNull()
        val parts = ArrayList<String>()

        if (g.settings.googleReady) {
            val (place, geo) = withContext(Dispatchers.IO) {
                runCatching { g.google.nearestPlace(f.lat, f.lon) }.getOrNull() to runCatching { g.google.reverseGeocode(f.lat, f.lon) }.getOrNull()
            }
            if (saved != null && saved.distance <= 25) {
                parts.add("At ${saved.loc.name}.")
            } else if (place != null && (place.distance ?: 9999.0) <= 300) {
                val where = Phrases.where(g.compass.heading, Geo.bearing(f.lat, f.lon, place.lat, place.lon))
                parts.add("Near ${place.name}, ${Phrases.distance(place.distance)} $where.")
            }
            val road = geo?.road?.takeIf { it.isNotBlank() } ?: RoadDetector.roadNameAt(f.lat, f.lon)
            if (road != null) parts.add("On $road.")
            val island = islandText ?: (geo?.island?.takeIf { it.isNotBlank() } ?: islandHit?.island?.fullName)?.let { "$it." }
            if (!island.isNullOrBlank()) parts.add(island)
        }

        if (parts.isEmpty()) {
            if (saved != null && saved.distance <= 150) {
                val where = Phrases.where(g.compass.heading, saved.bearing)
                parts.add(if (saved.distance <= 15) "At ${saved.loc.name}." else "Near ${saved.loc.name}, ${Phrases.distance(saved.distance)} $where.")
            }
            RoadDetector.roadNameAt(f.lat, f.lon)?.let { parts.add("On $it.") }
            if (islandText != null) parts.add(islandText) else if (islandHit != null) parts.add("${islandHit.island.fullName}.")
            if (parts.isEmpty()) parts.add("Your GPS position is available, but no place name was found.")
        }
        if (f.accuracy > 40) parts.add("GPS is weak, about ${Fmt.meters(f.accuracy)}.")
        return parts.joinToString(" ")
    }
}
