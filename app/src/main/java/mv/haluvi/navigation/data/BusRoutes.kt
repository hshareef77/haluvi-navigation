package mv.haluvi.navigation.data

import mv.haluvi.navigation.core.Geo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One stop of a bus or ferry route. */
data class BusStop(
    var name: String,
    var lat: Double,
    var lon: Double,
    val osm: Long = 0,
    var shelter: String = "",
    var bench: String = "",
    /** RTL stop code. */
    val code: String = "",
    /** Dhivehi name. */
    val dv: String = "",
    /** Ferry stops: the island key ("HA:Dhidhoo"), when known. */
    val island: String = "",
    /** Times the bus leaves this stop, "06:30" (RTL timetable). */
    val times: List<String> = emptyList()
) {
    fun toJson(): JSONObject {
        val t = JSONArray(); times.forEach { t.put(it) }
        return JSONObject().put("name", name).put("lat", lat).put("lon", lon).put("osm", osm)
            .put("shelter", shelter).put("bench", bench).put("code", code).put("dv", dv).put("island", island).put("times", t)
    }

    /** "with shelter and bench", or "" when not known. */
    val details: String get() {
        val have = listOfNotNull("shelter".takeIf { shelter == "yes" }, "bench".takeIf { bench == "yes" })
        return if (have.isEmpty()) "" else "with " + have.joinToString(" and ")
    }

    companion object {
        fun fromJson(o: JSONObject): BusStop? = runCatching {
            val t = o.optJSONArray("times")
            BusStop(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"), o.optLong("osm"),
                o.optString("shelter"), o.optString("bench"), o.optString("code"), o.optString("dv"), o.optString("island"),
                if (t == null) emptyList() else (0 until t.length()).map { t.getString(it) })
        }.getOrNull()
    }
}

/**
 * A bus or ferry route with its stops in order. Built-in routes ([key]: "rtl:bus:133", "osm:12345") are refreshed
 * with each update of the app's data, unless you changed them ([edited]): your changes are kept.
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
    var edited: Boolean = false,
    /** Built-in source key, "" for routes you made. */
    val key: String = if (osmId > 0) "osm:$osmId" else "",
    /** "bus", "ferry" (island stops) or "vessel" (ferry between two terminals in Male'). */
    val kind: String = KIND_BUS,
    /** "Greater Male", "Addu City", "Zone 1" ... */
    val area: String = "",
    val fare: String = ""
) {
    val isFerry: Boolean get() = kind == KIND_FERRY
    val isBoat: Boolean get() = kind == KIND_FERRY || kind == KIND_VESSEL
    val fromOsm: Boolean get() = key.startsWith("osm:")
    val fromRtl: Boolean get() = key.startsWith("rtl:")
    val builtin: Boolean get() = key.isNotBlank()

    /** "R10 Orchid", without repeating the number. */
    val title: String get() = when {
        ref.isBlank() -> name.ifBlank { "Route" }
        name.isBlank() -> "Route $ref"
        name.startsWith(ref, true) -> name
        else -> "$ref $name"
    }

    fun toJson(): JSONObject {
        val arr = JSONArray(); stops.forEach { arr.put(it.toJson()) }
        return JSONObject().put("id", id).put("ref", ref).put("name", name).put("from", from).put("to", to)
            .put("operator", operator).put("stops", arr).put("osmId", osmId).put("edited", edited)
            .put("key", key).put("kind", kind).put("area", area).put("fare", fare)
    }

    companion object {
        const val KIND_BUS = "bus"
        const val KIND_FERRY = "ferry"
        const val KIND_VESSEL = "vessel"

        fun newId(): String = "bus_" + UUID.randomUUID().toString().replace("-", "").take(12)
        fun fromJson(o: JSONObject): BusRoute? = runCatching {
            val arr = o.getJSONArray("stops")
            val osmId = o.optLong("osmId")
            BusRoute(o.optString("id").ifBlank { newId() }, o.optString("ref"), o.optString("name"), o.optString("from"),
                o.optString("to"), o.optString("operator"),
                (0 until arr.length()).mapNotNull { BusStop.fromJson(arr.getJSONObject(it)) }.toMutableList(),
                osmId, o.optBoolean("edited"),
                o.optString("key").ifBlank { if (osmId > 0) "osm:$osmId" else "" },
                o.optString("kind", KIND_BUS).ifBlank { KIND_BUS }, o.optString("area"), o.optString("fare"))
        }.getOrNull()

        /** A route from the built-in OpenStreetMap file. */
        fun fromOsm(o: JSONObject): BusRoute? = runCatching {
            val arr = o.getJSONArray("stops")
            val osm = o.getLong("osm")
            BusRoute("osm_$osm", o.optString("ref"), o.optString("name"), o.optString("from"), o.optString("to"),
                o.optString("operator"),
                (0 until arr.length()).mapNotNull { i ->
                    val s = arr.getJSONObject(i)
                    runCatching { BusStop(s.getString("name"), s.getDouble("lat"), s.getDouble("lon"), s.optLong("osm"),
                        s.optString("shelter"), s.optString("bench")) }.getOrNull()
                }.toMutableList(), osm, area = "OpenStreetMap")
        }.getOrNull()

        /** A route from the built-in RTL file. */
        fun fromRtl(o: JSONObject): BusRoute? = runCatching {
            val kind = o.optString("kind", KIND_BUS)
            val arr = o.getJSONArray("stops")
            val stops = (0 until arr.length()).mapNotNull { i ->
                val s = arr.getJSONObject(i)
                val t = s.optJSONArray("times")
                runCatching { BusStop(s.getString("name"), s.getDouble("lat"), s.getDouble("lon"), code = s.optString("code"),
                    dv = s.optString("dv"), island = s.optString("island"),
                    times = if (t == null) emptyList() else (0 until t.length()).map { t.getString(it) }) }.getOrNull()
            }.toMutableList()
            val code = o.getString("code")
            val fare = if (o.has("fare") && !o.isNull("fare")) o.optString("fare") else ""
            BusRoute("rtl_${kind}_$code", o.optString("ref"), o.optString("name"), stops.firstOrNull()?.name ?: "",
                stops.lastOrNull()?.name ?: "", "RTL", stops, key = "rtl:$kind:$code", kind = kind, area = o.optString("area"), fare = fare)
        }.getOrNull()
    }
}

/** A stop of a route near a point. */
data class BusStopHit(val route: BusRoute, val index: Int, val distance: Double) {
    val stop: BusStop get() = route.stops[index]
}

/** All bus and ferry routes: built in (RTL, OpenStreetMap) and your own. Stored as data (bus_routes.json), never in the code. */
class BusRoutes(private val file: File) {
    private val items = ArrayList<BusRoute>()
    /** Built-in routes you deleted (their keys): they do not come back with updates. */
    private val deleted = HashSet<String>()
    /** The built-in data the routes come from (its stamp). */
    var mapStamp = ""; private set

    init { load() }

    @Synchronized fun all(): List<BusRoute> = items.sortedWith(compareBy({ refNumber(it.ref) }, { it.ref }, { it.title.lowercase() }))
    @Synchronized fun byId(id: String?): BusRoute? = items.firstOrNull { it.id == id }
    @Synchronized fun deletedCount(): Int = deleted.size

    private fun refNumber(ref: String): Int = Regex("\\d+").find(ref)?.value?.toIntOrNull() ?: Int.MAX_VALUE

    @Synchronized fun add(r: BusRoute) { items.removeAll { it.id == r.id }; items.add(r); save() }

    /** Call after changing a route: it is marked as yours, so updates keep your change. */
    @Synchronized fun changed(r: BusRoute) { r.edited = true; save() }

    @Synchronized
    fun delete(id: String) {
        val r = byId(id) ?: return
        if (r.builtin) deleted.add(r.key)
        items.remove(r); save()
    }

    /** Brings back deleted built-in routes (when [id] is null) or undoes your changes to route [id]. */
    @Synchronized
    fun restore(id: String?, osm: JSONObject?, rtl: JSONObject?) {
        if (id == null) deleted.clear() else byId(id)?.let { if (it.builtin) it.edited = false }
        loadBuiltin(osm, rtl, mapStamp, force = true)
    }

    /**
     * Takes the built-in routes: RTL first, then OpenStreetMap routes that RTL does not have (same number).
     * New ones are added, unchanged ones updated, ones no longer there removed. Routes you changed, made or deleted
     * stay as you left them.
     */
    @Synchronized
    fun loadBuiltin(osm: JSONObject?, rtl: JSONObject?, stamp: String, force: Boolean = false): Int {
        if (!force && stamp == mapStamp) return 0
        val incoming = ArrayList<BusRoute>()
        rtl?.optJSONArray("routes")?.let { arr -> for (i in 0 until arr.length()) BusRoute.fromRtl(arr.getJSONObject(i))?.let { incoming.add(it) } }
        val rtlRefs = incoming.filter { it.kind == BusRoute.KIND_BUS }.map { it.ref.lowercase() }.toSet()
        osm?.optJSONArray("routes")?.let { arr ->
            for (i in 0 until arr.length()) BusRoute.fromOsm(arr.getJSONObject(i))?.let { if (it.ref.lowercase() !in rtlRefs) incoming.add(it) }
        }
        val seen = HashSet<String>()
        var n = 0
        for (r in incoming) {
            if (r.stops.size < 2) continue
            seen.add(r.key)
            if (r.key in deleted) continue
            val old = items.firstOrNull { it.key == r.key }
            if (old != null && old.edited) continue
            items.removeAll { it.key == r.key }
            items.add(r); n++
        }
        items.removeAll { it.builtin && !it.edited && it.key !in seen }
        mapStamp = stamp
        save()
        return n
    }

    /** The nearest stop of each route within [radius] (boats: [boatRadius]) of a point. */
    @Synchronized
    fun near(lat: Double, lon: Double, radius: Double, boatRadius: Double = radius, kinds: Set<String>? = null): List<BusStopHit> {
        val out = ArrayList<BusStopHit>()
        for (r in items) {
            if (kinds != null && r.kind !in kinds) continue
            var best = -1; var bestD = Double.MAX_VALUE
            r.stops.forEachIndexed { i, s ->
                val d = Geo.distance(lat, lon, s.lat, s.lon)
                if (d < bestD) { bestD = d; best = i }
            }
            if (best >= 0 && bestD <= (if (r.isBoat) boatRadius else radius)) out.add(BusStopHit(r, best, bestD))
        }
        return out.sortedBy { it.distance }
    }

    /** All stops of a route within [radius] of a point, nearest first. */
    fun stopsNear(r: BusRoute, lat: Double, lon: Double, radius: Double): List<BusStopHit> =
        r.stops.indices.map { BusStopHit(r, it, Geo.distance(lat, lon, r.stops[it].lat, r.stops[it].lon)) }
            .filter { it.distance <= radius }.sortedBy { it.distance }

    /** Every stop of the RTL bus routes, once per stop code, for the places list. */
    @Synchronized
    fun allBusStops(): List<BusStop> {
        val seen = HashSet<String>()
        val out = ArrayList<BusStop>()
        for (r in items) {
            if (r.kind != BusRoute.KIND_BUS || !r.fromRtl) continue
            for (s in r.stops) {
                val k = s.code.ifBlank { s.name.lowercase() + "@" + String.format(java.util.Locale.US, "%.4f,%.4f", s.lat, s.lon) }
                if (seen.add(k)) out.add(s)
            }
        }
        return out
    }

    @Synchronized
    fun save() {
        val arr = JSONArray(); items.forEach { arr.put(it.toJson()) }
        val del = JSONArray(); deleted.forEach { del.put(it) }
        runCatching { file.writeText(JSONObject().put("routes", arr).put("deleted", del).put("mapStamp", mapStamp).toString()) }
    }

    /** Your own and changed routes, for backups (built-in routes come with the app). */
    @Synchronized
    fun exportJson(): JSONArray { val arr = JSONArray(); items.filter { !it.builtin || it.edited }.forEach { arr.put(it.toJson()) }; return arr }

    /** Adds routes from a backup; nothing is deleted. Returns how many were added or updated. */
    @Synchronized
    fun import(arr: JSONArray): Int {
        var n = 0
        for (i in 0 until arr.length()) {
            val r = BusRoute.fromJson(arr.getJSONObject(i)) ?: continue
            if (r.builtin) {
                if (!r.edited) continue
                items.removeAll { it.key == r.key }; items.add(r); n++
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
        items.clear(); deleted.clear()
        if (!file.exists()) return
        runCatching {
            val o = JSONObject(file.readText())
            val arr = o.getJSONArray("routes")
            for (i in 0 until arr.length()) BusRoute.fromJson(arr.getJSONObject(i))?.let { items.add(it) }
            o.optJSONArray("deleted")?.let { d -> for (i in 0 until d.length()) deleted.add(d.getString(i)) }
            o.optJSONArray("deletedOsm")?.let { d -> for (i in 0 until d.length()) deleted.add("osm:" + d.getLong(i)) }
            mapStamp = o.optString("mapStamp")
        }
    }
}
