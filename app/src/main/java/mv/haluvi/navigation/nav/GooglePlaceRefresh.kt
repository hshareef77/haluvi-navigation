package mv.haluvi.navigation.nav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.data.SavedLocation

/**
 * Google's terms allow keeping a place ID permanently, but positions only for a limited time.
 * Saved Google places therefore get their position refreshed from the place ID every 25 days (when online).
 */
object GooglePlaceRefresh {
    private const val MAX_AGE_S = 25L * 24 * 3600

    suspend fun run(): Int = withContext(Dispatchers.IO) {
        val g = AppGraph
        if (!g.settings.googleReady) return@withContext 0
        val nowS = System.currentTimeMillis() / 1000
        val due = g.store.items.filter { it.source == SavedLocation.SOURCE_GOOGLE && it.placeId.isNotBlank() && nowS - it.savedTime > MAX_AGE_S }.take(30)
        var n = 0
        for (loc in due) {
            val p = runCatching { g.google.placeLocation(loc.placeId) }.getOrNull() ?: continue
            loc.lat = p.lat; loc.lon = p.lon; loc.savedTime = nowS; loc.islandKey = ""
            g.store.ensureIsland(loc)
            n++
        }
        if (n > 0) g.store.save()
        n
    }
}
