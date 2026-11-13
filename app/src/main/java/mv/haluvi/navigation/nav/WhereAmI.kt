package mv.haluvi.navigation.nav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.Fmt
import mv.haluvi.navigation.core.Geo

/** "Where am I": nearest named place, road and island. Google first (Lua v101), offline fallback. */
object WhereAmI {
    suspend fun describe(): String {
        val g = AppGraph
        val f = g.location.freshFix(60_000) ?: return if (!g.location.isGpsEnabled()) "Location is turned off. Please turn on location."
            else "GPS is not ready yet. Please wait a moment in an open area and try again."

        val islandHit = g.islands.nearest(f.lat, f.lon)
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
            if (geo != null && geo.road.isNotBlank()) parts.add("On ${geo.road}.")
            val island = geo?.island?.takeIf { it.isNotBlank() } ?: islandHit?.island?.fullName
            if (!island.isNullOrBlank()) parts.add("$island.")
        }

        if (parts.isEmpty()) {
            if (saved != null && saved.distance <= 150) {
                val where = Phrases.where(g.compass.heading, saved.bearing)
                parts.add(if (saved.distance <= 15) "At ${saved.loc.name}." else "Near ${saved.loc.name}, ${Phrases.distance(saved.distance)} $where.")
            }
            if (islandHit != null) parts.add("${islandHit.island.fullName}.")
            if (parts.isEmpty()) parts.add("Your GPS position is available, but no place name was found.")
        }
        if (f.accuracy > 40) parts.add("GPS is weak, about ${Fmt.meters(f.accuracy)}.")
        return parts.joinToString(" ")
    }
}
