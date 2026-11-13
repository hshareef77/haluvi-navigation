package mv.haluvi.navigation.nav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.data.Categories
import mv.haluvi.navigation.data.SavedLocation
import java.util.Locale

/** A nearby place with its distance and direction from the user. */
data class NearbyItem(
    val name: String,
    val lat: Double,
    val lon: Double,
    val categoryId: String,
    val saved: SavedLocation?,
    val distance: Double,
    val bearing: Double
) {
    val key: String get() = name.lowercase() + "@" + String.format(Locale.US, "%.4f,%.4f", lat, lon)
    fun toSavedLocation(): SavedLocation = saved ?: SavedLocation(SavedLocation.newId(), name, lat, lon, source = SavedLocation.SOURCE_GOOGLE, categoryId = categoryId)
}

/** "What's around me" and "What's in this direction". */
object Surroundings {
    /** Categories offered in "What's around me" and Search. */
    val categories = listOf(
        "all" to "All places", "mosque" to "Mosques", "health" to "Health and pharmacy", "shop" to "Shops",
        "food" to "Food and cafes", "ferry" to "Ferry and harbor", "school" to "Schools", "bank" to "Bank and ATM",
        "hotel" to "Hotels", "police" to "Police"
    )

    fun categoryLabel(id: String) = categories.firstOrNull { it.first == id }?.second ?: "All places"

    /** Nearest places (saved, downloaded and Google), at most [limit], within [radius] metres. */
    suspend fun aroundMe(category: String, radius: Int = 500, limit: Int = 8): Pair<List<NearbyItem>, String?> {
        val g = AppGraph
        val f = g.location.freshFix(30_000) ?: return emptyList<NearbyItem>() to "Waiting for GPS."
        val out = LinkedHashMap<String, NearbyItem>()
        g.store.nearest(f.lat, f.lon).asSequence()
            .filter { it.distance <= radius && (category == "all" || it.loc.categoryId == category) }
            .forEach { val n = NearbyItem(it.loc.name, it.loc.lat, it.loc.lon, it.loc.categoryId, it.loc, it.distance, it.bearing); out.putIfAbsent(n.key, n) }
        var note: String? = null
        if (g.settings.googleReady) {
            val online = withContext(Dispatchers.IO) { runCatching { g.google.nearby(f.lat, f.lon, radius) } }
            online.onSuccess { list ->
                list.forEach { p ->
                    val cat = Categories.guess(p.name + " " + p.typeText)
                    if (category != "all" && cat != category) return@forEach
                    val n = NearbyItem(p.name, p.lat, p.lon, cat, null, p.distance ?: Geo.distance(f.lat, f.lon, p.lat, p.lon), p.bearing ?: Geo.bearing(f.lat, f.lon, p.lat, p.lon))
                    // Skip Google duplicates of places already saved within 25 m with a similar name.
                    val dup = out.values.any { it.name.equals(n.name, true) || (Geo.distance(it.lat, it.lon, n.lat, n.lon) < 8 && it.name.take(5).equals(n.name.take(5), true)) }
                    if (!dup) out.putIfAbsent(n.key, n)
                }
            }.onFailure { note = "Online places unavailable." }
        }
        val list = out.values.sortedBy { it.distance }.take(limit)
        if (f.accuracy > 30) note = listOfNotNull(note, "GPS is weak.").joinToString(" ")
        return list to note
    }

    /** Spoken summary of [items] relative to the phone's heading. */
    fun speakList(items: List<NearbyItem>, heading: Double, note: String?): String {
        if (items.isEmpty()) return listOfNotNull("Nothing found nearby.", note).joinToString(" ")
        val parts = items.take(5).map { Phrases.place(it.name, heading, it.bearing, it.distance) }
        return (parts.joinToString(". ") + ".") + (note?.let { " $it" } ?: "")
    }

    /**
     * Names what the phone is pointing at: saved places within 2 km, then islands within 60 km,
     * in a narrow window around the phone's heading.
     */
    fun inDirection(): String {
        val g = AppGraph
        val f = g.location.freshFix(60_000) ?: return "Waiting for GPS."
        val heading = g.compass.heading
        val window = maxOf(8.0, g.settings.focusAccuracy * 2.0)
        val places = g.store.nearest(f.lat, f.lon).asSequence()
            .filter { it.distance in 5.0..2000.0 && Geo.angleBetween(heading, it.bearing) <= window }
            .take(3).map { "${it.loc.name}, ${Phrases.distance(it.distance)}" }.toList()
        val here = g.islands.nearest(f.lat, f.lon)?.island?.key
        val isl = g.islands.within(f.lat, f.lon, 60_000.0)
            .filter { it.island.key != here && Geo.angleBetween(heading, it.bearing) <= window }
            .take(3).map { "${it.island.name}, ${Phrases.distance(it.distance)}" }
        val parts = places + isl
        val dir = Geo.directionName(heading)
        return if (parts.isEmpty()) "Facing $dir. Nothing found in this direction."
        else "Facing $dir. " + parts.joinToString(". ") + "."
    }
}
