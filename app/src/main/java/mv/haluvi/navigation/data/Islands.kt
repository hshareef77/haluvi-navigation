package mv.haluvi.navigation.data

import android.content.Context
import mv.haluvi.navigation.core.Geo
import org.json.JSONArray

data class Atoll(val index: Int, val name: String, val code: String, val fullName: String, val islands: List<Island>) {
    val label: String get() = "$fullName ($code), $name"
}

data class Island(val atollIndex: Int, val index: Int, val name: String, var lat: Double, var lon: Double) {
    /**
     * "inhabited" (only the official island list), "resort", "airport" or "island" (uninhabited).
     */
    var type: String = "inhabited"

    /** An inhabited island that also has an airport (e.g. Hanimaadhoo). */
    var hasAirport: Boolean = false

    /** Name with its kind for announcements: "Kandooma, resort", "Hanimaadhoo, with airport". */
    val spokenName: String get() = when (type) {
        "resort" -> "$name, resort"
        "airport" -> "$name, airport"
        "island" -> "$name, uninhabited island"
        else -> if (hasAirport) "$name, with airport" else name
    }

    /** Stable key, independent of list order: "HA:Dhidhoo". */
    var key: String = ""
        internal set
    var atollFullName: String = ""
        internal set

    /** Full spoken name, e.g. "Haa Alifu Dhidhoo" (same as the Lua islandFullName). */
    val fullName: String get() = if (atollFullName.isEmpty()) name else "$atollFullName $name"
}

data class IslandHit(val island: Island, val distance: Double, val bearing: Double)

/** Maldives atolls and inhabited islands, loaded from assets/maldives_islands.json (extracted from the Lua data). */
class IslandRepository(context: Context) {
    val atolls: List<Atoll>
    val allIslands: List<Island>
    private val byKey: Map<String, Island>

    init {
        val text = context.assets.open("maldives_islands.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(text)
        val list = ArrayList<Atoll>()
        for (a in 0 until arr.length()) {
            val o = arr.getJSONObject(a)
            val code = o.optString("code")
            val full = o.optString("fullname")
            val isl = o.getJSONArray("islands")
            val islands = ArrayList<Island>()
            for (i in 0 until isl.length()) {
                val io = isl.getJSONObject(i)
                val island = Island(a, i, io.getString("n"), io.getDouble("lat"), io.getDouble("lon"))
                island.key = "$code:${island.name}"
                island.atollFullName = full
                islands.add(island)
            }
            list.add(Atoll(a, o.optString("name"), code, full, islands))
        }
        atolls = list
        allIslands = list.flatMap { it.islands }
        byKey = allIslands.associateBy { it.key }
    }

    fun byKey(key: String?): Island? = if (key.isNullOrBlank()) null else byKey[key]

    fun atollOf(island: Island): Atoll = atolls[island.atollIndex]

    fun nearest(lat: Double, lon: Double): IslandHit? {
        var best: Island? = null
        var bestD = Double.MAX_VALUE
        for (i in allIslands) {
            val d = Geo.distance(lat, lon, i.lat, i.lon)
            if (d < bestD) { bestD = d; best = i }
        }
        return best?.let { IslandHit(it, bestD, Geo.bearing(lat, lon, it.lat, it.lon)) }
    }

    fun within(lat: Double, lon: Double, maxMeters: Double): List<IslandHit> =
        allIslands.map { IslandHit(it, Geo.distance(lat, lon, it.lat, it.lon), Geo.bearing(lat, lon, it.lat, it.lon)) }
            .filter { it.distance <= maxMeters }
            .sortedBy { it.distance }

    /** Islands within [maxMeters] in front of the user (within 90 degrees either side of [heading]). */
    fun inFront(lat: Double, lon: Double, maxMeters: Double, heading: Double): List<IslandHit> =
        within(lat, lon, maxMeters).filter { Geo.angleBetween(heading, it.bearing) <= 90.0 }

    fun search(query: String): List<Island> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return allIslands.filter { it.name.lowercase().contains(q) || it.fullName.lowercase().contains(q) }
    }
}
