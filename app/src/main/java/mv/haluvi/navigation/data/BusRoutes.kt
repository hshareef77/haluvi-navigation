package mv.haluvi.navigation.data

import mv.haluvi.navigation.core.Geo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One bus stop of a bus route. */
data class BusStop(var name: String, var lat: Double, var lon: Double, val osm: Long = 0,
                   var shelter: String = "", var bench: String = "") {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("lat", lat).put("lon", lon).put("osm", osm)
        .put("shelter", shelter).put("bench", bench)

    /** "with shelter and bench", or "" when not known. */
    val details: String get() {
        val have = listOfNotNull("shelter".takeIf { shelter == "yes" }, "bench".takeIf { bench == "yes" })
        return if (have.isEmpty()) "" else "with " + have.joinToString(" and ")
    }

    companion object {
        fun fromJson(o: JSONObject): BusStop? = runCatching {
            BusStop(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"), o.optLong("osm"),
                o.optString("shelter"), o.optString("bench"))
        }.getOrNull()
    }
}

/**
 * A bus route with its stops in order. Routes from OpenStreetMap ([osmId] > 0) are refreshed with each
 * built-in map update, unless you changed them ([edited]): your changes are kept.
 */
data class BusRoute(
    val id: String,
    var ref: String,
    var name: String,
    var from: String,
    var to: String,
    var operator: String,
    val stops: MutableList<BusStop>,
    val osmId: Long = 0,
    var edited: Boolean = false
) {
    /** "R10 Orchid Route", without repeating the number. */
    val title: String get() = when {
        ref.isBlank() -> name.ifBlank { "Bus route" }
        name.isBlank() -> "Route $ref"
        name.startsWith(ref, true) -> name
        else -> "$ref $name"
    }
    val fromOsm: Boolean get() = osmId > 0

    fun toJson(): JSONObject {
        val arr = JSONArray(); stops.forEach { arr.put(it.toJson()) }
        return JSONObject().put("id", id).put("ref", ref).put("name", name).put("from", from).put("to", to)
            .put("operator", operator).put("stops", arr).put("osmId", osmId).put("edited", edited)
    }

    companion object {
        fun newId(): String = "bus_" + UUID.randomUUID().toString().replace("-", "").take(12)
        fun fromJson(o: JSONObject): BusRoute? = runCatching {
            val arr = o.getJSONArray("stops")
            BusRoute(o.optString("id").ifBlank { newId() }, o.optString("ref"), o.optString("name"), o.optString("from"),
                o.optString("to"), o.optString("operator"),
                (0 until arr.length()).mapNotNull { BusStop.fromJson(arr.getJSONObject(it)) }.toMutableList(),
                o.optLong("osmId"), o.optBoolean("edited"))
        }.getOrNull()

        /** A route from the built-in map file (OpenStreetMap). */
        fun fromOsm(o: JSONObject): BusRoute? = runCatching {
            val arr = o.getJSONArray("stops")
            val osm = o.getLong("osm")
            BusRoute("osm_$osm", o.optString("ref"), o.optString("name"), o.optString("from"), o.optString("to"),
                o.optString("operator"),
                (0 until arr.length()).mapNotNull { i ->
                    val s = arr.getJSONObject(i)
                    runCatching { BusStop(s.getString("name"), s.getDouble("lat"), s.getDouble("lon"), s.optLong("osm"),
                        s.optString("shelter"), s.optString("bench")) }.getOrNull()
                }.toMutableList(), osm)
        }.getOrNull()
    }
}

/** A stop of a route near a point. */
data class BusStopHit(val route: BusRoute, val index: Int, val distance: Double) {
    val stop: BusStop get() = route.stops[index]
}

/** All bus routes: from the built-in maps and your own. Stored as data (bus_routes.json), never in the code. */
class BusRoutes(private val file: File) {
    private val items = ArrayList<BusRoute>()
    /** OpenStreetMap routes you deleted: they do not come back with map updates. */
    private val deletedOsm = HashSet<Long>()
    /** The built-in map data the routes come from (its stamp). */
    var mapStamp = ""; private set

    init { load() }

    @Synchronized fun all(): List<BusRoute> = items.sortedWith(compareBy({ refNumber(it.ref) }, { it.ref }, { it.title.lowercase() }))
    @Synchronized fun byId(id: String?): BusRoute? = items.firstOrNull { it.id == id }
    @Synchronized fun deletedCount(): Int = deletedOsm.size

    private fun refNumber(ref: String): Int = Regex("\\d+").find(ref)?.value?.toIntOrNull() ?: Int.MAX_VALUE

    @Synchronized fun add(r: BusRoute) { items.removeAll { it.id == r.id }; items.add(r); save() }

    /** Call after changing a route: it is marked as yours, so map updates keep your change. */
    @Synchronized fun changed(r: BusRoute) { r.edited = true; save() }

    @Synchronized
    fun delete(id: String) {
        val r = byId(id) ?: return
        if (r.fromOsm) deletedOsm.add(r.osmId)
        items.remove(r); save()
    }

    /** Brings back deleted map routes and undoes your changes to [id] (or to all map routes when null). */
    @Synchronized
    fun restoreFromMap(id: String?, builtin: JSONObject?) {
        if (id == null) deletedOsm.clear()
        else byId(id)?.let { if (it.fromOsm) it.edited = false }
        if (builtin != null) loadBuiltin(builtin, mapStamp, force = true) else save()
    }

    /**
     * Takes the bus routes of the built-in maps: new ones are added, unchanged ones updated, ones no longer in the
     * map removed. Routes you changed, made or deleted stay as you left them.
     */
    @Synchronized
    fun loadBuiltin(o: JSONObject, stamp: String, force: Boolean = false): Int {
        if (!force && stamp == mapStamp) return 0
        val arr = o.optJSONArray("routes") ?: return 0
        val seen = HashSet<Long>()
        var n = 0
        for (i in 0 until arr.length()) {
            val r = BusRoute.fromOsm(arr.getJSONObject(i)) ?: continue
            if (r.stops.size < 2) continue
            seen.add(r.osmId)
            if (r.osmId in deletedOsm) continue
            val old = items.firstOrNull { it.osmId == r.osmId }
            if (old != null && old.edited) continue
            items.removeAll { it.osmId == r.osmId }
            items.add(r); n++
        }
        items.removeAll { it.fromOsm && !it.edited && it.osmId !in seen }
        mapStamp = stamp
        save()
        return n
    }

    /** Stops of all routes within [radius] of a point: the nearest stop of each route. */
    @Synchronized
    fun near(lat: Double, lon: Double, radius: Double): List<BusStopHit> {
        val out = ArrayList<BusStopHit>()
        for (r in items) {
            var best = -1; var bestD = Double.MAX_VALUE
            r.stops.forEachIndexed { i, s ->
                val d = Geo.distance(lat, lon, s.lat, s.lon)
                if (d < bestD) { bestD = d; best = i }
            }
            if (best >= 0 && bestD <= radius) out.add(BusStopHit(r, best, bestD))
        }
        return out.sortedBy { it.distance }
    }

    /** All stops of a route within [radius] of a point, nearest first. */
    fun stopsNear(r: BusRoute, lat: Double, lon: Double, radius: Double): List<BusStopHit> =
        r.stops.indices.map { BusStopHit(r, it, Geo.distance(lat, lon, r.stops[it].lat, r.stops[it].lon)) }
            .filter { it.distance <= radius }.sortedBy { it.distance }

    /** The part of a route from stop [on] to stop [off], as a route to travel with. */
    fun trip(r: BusRoute, on: Int, off: Int): TripRoute =
        TripRoute("bus:${r.id}:$on", r.title, r.stops.subList(on, off + 1).map { RouteStop(it.name, it.lat, it.lon, RouteStop.KIND_PLACE) }.toMutableList())

    @Synchronized
    fun save() {
        val arr = JSONArray(); items.forEach { arr.put(it.toJson()) }
        val del = JSONArray(); deletedOsm.forEach { del.put(it) }
        runCatching { file.writeText(JSONObject().put("routes", arr).put("deletedOsm", del).put("mapStamp", mapStamp).toString()) }
    }

    /** Your own and changed bus routes, for backups (map routes come with the app). */
    @Synchronized
    fun exportJson(): JSONArray { val arr = JSONArray(); items.filter { !it.fromOsm || it.edited }.forEach { arr.put(it.toJson()) }; return arr }

    /** Adds bus routes from a backup; nothing is deleted. Returns how many were added or updated. */
    @Synchronized
    fun import(arr: JSONArray): Int {
        var n = 0
        for (i in 0 until arr.length()) {
            val r = BusRoute.fromJson(arr.getJSONObject(i)) ?: continue
            if (r.fromOsm) {
                if (!r.edited) continue
                items.removeAll { it.osmId == r.osmId }; items.add(r); n++
            } else {
                val same = items.any { it.title.equals(r.title, true) && it.stops.map { s -> s.name } == r.stops.map { s -> s.name } }
                if (same) continue
                items.removeAll { it.id == r.id }; items.add(r); n++
            }
        }
        save()
        return n
    }

    private fun load() {
        items.clear(); deletedOsm.clear()
        if (!file.exists()) return
        runCatching {
            val o = JSONObject(file.readText())
            val arr = o.getJSONArray("routes")
            for (i in 0 until arr.length()) BusRoute.fromJson(arr.getJSONObject(i))?.let { items.add(it) }
            o.optJSONArray("deletedOsm")?.let { d -> for (i in 0 until d.length()) deletedOsm.add(d.getLong(i)) }
            mapStamp = o.optString("mapStamp")
        }
    }
}
