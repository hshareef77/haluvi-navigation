package mv.haluvi.navigation.net

import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.core.LatLon
import mv.haluvi.navigation.data.TravelMode
import org.json.JSONArray
import org.json.JSONObject

/** A place found online (Google Maps or OpenStreetMap). */
data class OnlinePlace(
    val name: String,
    val lat: Double,
    val lon: Double,
    val address: String = "",
    val typeText: String = "",
    val source: String,
    var distance: Double? = null,
    var bearing: Double? = null,
    /** Google place ID (may be kept permanently under Google's terms; positions must be refreshed). */
    val placeId: String = ""
) {
    val key: String get() = name.lowercase() + "@" + String.format(java.util.Locale.US, "%.5f,%.5f", lat, lon)
}

data class RouteStep(
    val instruction: String,
    /** "left" or "right" when Google says "Destination will be on the left/right" in this step. */
    val destinationSide: String = "",
    /** Ready-made spoken action for offline routes, e.g. "take the second road on the right after the harbour". */
    val spokenAction: String = "",
    val maneuver: String,
    val roadName: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val start: LatLon?,
    val end: LatLon?,
    val polyline: List<LatLon>,
    /** Side roads passed on this step without turning (offline routes). */
    val sideRoads: Int = 0
)

data class Route(
    val steps: List<RouteStep>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val summary: String,
    val source: String,
    /** "easiest", "shortest", "fewest turns", "another way" (offline alternatives). */
    val label: String = "",
    val turns: Int = 0,
    /** Main roads crossed on the way. */
    val crossings: Int = 0,
    /** The road most of the route is on. */
    val mainRoad: String = "",
    /** Lower is easier (offline routes). */
    val score: Double = 0.0
)

data class ReverseGeocode(val road: String, val island: String, val address: String)

/**
 * Google Maps Platform client.
 * Improvement over the Lua version: uses the current "Places API (New)" first (the legacy Places API
 * cannot be enabled on new Google Cloud projects), falling back to the legacy endpoints automatically.
 */
class GoogleMaps(private val keyProvider: () -> String) {
    private val key: String get() = keyProvider().trim()

    // ---------------- Places ----------------

    fun searchText(query: String, lat: Double?, lon: Double?, radius: Int = 5000): List<OnlinePlace> {
        return try {
            searchTextNew(query, lat, lon, radius)
        } catch (e: Exception) {
            try { searchTextLegacy(query, lat, lon, radius) } catch (_: Exception) { throw e }
        }
    }

    private fun searchTextNew(query: String, lat: Double?, lon: Double?, radius: Int): List<OnlinePlace> {
        val body = JSONObject().put("textQuery", query).put("regionCode", "MV").put("languageCode", "en").put("pageSize", 20)
        if (lat != null && lon != null) {
            body.put("locationBias", JSONObject().put("circle", JSONObject()
                .put("center", JSONObject().put("latitude", lat).put("longitude", lon))
                .put("radius", radius.coerceIn(100, 50000).toDouble())))
        }
        val text = Http.post("https://places.googleapis.com/v1/places:searchText", body.toString(), "application/json",
            mapOf("X-Goog-Api-Key" to key, "X-Goog-FieldMask" to "places.id,places.displayName,places.formattedAddress,places.location,places.types,places.primaryTypeDisplayName"))
        return parsePlacesNew(text)
    }

    private fun parsePlacesNew(text: String): List<OnlinePlace> {
        val arr = JSONObject(text).optJSONArray("places") ?: return emptyList()
        val out = ArrayList<OnlinePlace>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val loc = p.optJSONObject("location") ?: continue
            val name = p.optJSONObject("displayName")?.optString("text").orEmpty().ifBlank { "Place" }
            val type = p.optJSONObject("primaryTypeDisplayName")?.optString("text").orEmpty()
                .ifBlank { typesText(p.optJSONArray("types")) }
            out.add(OnlinePlace(name, loc.getDouble("latitude"), loc.getDouble("longitude"), p.optString("formattedAddress"), type, "google", placeId = p.optString("id")))
        }
        return out
    }

    private fun searchTextLegacy(query: String, lat: Double?, lon: Double?, radius: Int): List<OnlinePlace> {
        var url = "https://maps.googleapis.com/maps/api/place/textsearch/json?query=${Http.enc(query)}&region=mv&key=${Http.enc(key)}"
        if (lat != null && lon != null) url += "&location=$lat,$lon&radius=${radius.coerceIn(100, 50000)}"
        val o = JSONObject(Http.get(url))
        checkLegacyStatus(o)
        val arr = o.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val r = arr.getJSONObject(i)
            val l = r.optJSONObject("geometry")?.optJSONObject("location") ?: return@mapNotNull null
            OnlinePlace(r.optString("name", "Place"), l.getDouble("lat"), l.getDouble("lng"), r.optString("formatted_address"), typesText(r.optJSONArray("types")), "google", placeId = r.optString("place_id"))
        }
    }

    /** Places around a point, nearest first (Places API (New) searchNearby, legacy fallback). */
    fun nearby(lat: Double, lon: Double, radius: Int): List<OnlinePlace> {
        val list = try {
            val body = JSONObject().put("maxResultCount", 20).put("rankPreference", "DISTANCE").put("languageCode", "en")
                .put("locationRestriction", JSONObject().put("circle", JSONObject()
                    .put("center", JSONObject().put("latitude", lat).put("longitude", lon)).put("radius", radius.coerceIn(20, 50000).toDouble())))
            parsePlacesNew(Http.post("https://places.googleapis.com/v1/places:searchNearby", body.toString(), "application/json",
                mapOf("X-Goog-Api-Key" to key, "X-Goog-FieldMask" to "places.id,places.displayName,places.formattedAddress,places.location,places.types,places.primaryTypeDisplayName")))
        } catch (e: Exception) {
            val o = try {
                JSONObject(Http.get("https://maps.googleapis.com/maps/api/place/nearbysearch/json?location=$lat,$lon&rankby=distance&type=point_of_interest&key=${Http.enc(key)}"))
            } catch (_: Exception) { throw e }
            checkLegacyStatus(o)
            val arr = o.optJSONArray("results") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val r = arr.getJSONObject(i)
                val l = r.optJSONObject("geometry")?.optJSONObject("location") ?: return@mapNotNull null
                OnlinePlace(r.optString("name", "Place"), l.getDouble("lat"), l.getDouble("lng"), r.optString("vicinity"), typesText(r.optJSONArray("types")), "google", placeId = r.optString("place_id"))
            }
        }
        list.forEach { it.distance = Geo.distance(lat, lon, it.lat, it.lon); it.bearing = Geo.bearing(lat, lon, it.lat, it.lon) }
        return list.filter { (it.distance ?: 0.0) <= radius * 1.2 }.sortedBy { it.distance }
    }

    /**
     * All places of every kind strictly inside a circle (at most 20, Google's limit per request).
     * Used to cover a whole island circle by circle; a full answer (20) means there may be more,
     * so the caller splits that circle into smaller ones.
     */
    fun placesInCircle(lat: Double, lon: Double, radius: Double): List<OnlinePlace> {
        return try {
            val body = JSONObject().put("maxResultCount", 20).put("rankPreference", "DISTANCE").put("languageCode", "en")
                .put("locationRestriction", JSONObject().put("circle", JSONObject()
                    .put("center", JSONObject().put("latitude", lat).put("longitude", lon)).put("radius", radius.coerceIn(10.0, 50000.0))))
            parsePlacesNew(Http.post("https://places.googleapis.com/v1/places:searchNearby", body.toString(), "application/json",
                mapOf("X-Goog-Api-Key" to key, "X-Goog-FieldMask" to "places.id,places.displayName,places.formattedAddress,places.location,places.types,places.primaryTypeDisplayName")))
        } catch (e: Exception) {
            val o = try {
                JSONObject(Http.get("https://maps.googleapis.com/maps/api/place/nearbysearch/json?location=$lat,$lon&radius=${radius.toInt().coerceIn(10, 50000)}&key=${Http.enc(key)}"))
            } catch (_: Exception) { throw e }
            checkLegacyStatus(o)
            val arr = o.optJSONArray("results") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val r = arr.getJSONObject(i)
                val l = r.optJSONObject("geometry")?.optJSONObject("location") ?: return@mapNotNull null
                OnlinePlace(r.optString("name", "Place"), l.getDouble("lat"), l.getDouble("lng"), r.optString("vicinity"), typesText(r.optJSONArray("types")), "google", placeId = r.optString("place_id"))
            }
        }
    }

    /** Nearest named place to a point (for "Where am I"). */
    fun nearestPlace(lat: Double, lon: Double): OnlinePlace? = nearby(lat, lon, 300).firstOrNull()

    /** Current position of a Google place (used to refresh saved Google places, as Google's terms require). */
    fun placeLocation(placeId: String): LatLon? {
        val text = Http.get("https://places.googleapis.com/v1/places/${Http.enc(placeId)}",
            mapOf("X-Goog-Api-Key" to key, "X-Goog-FieldMask" to "location"))
        val loc = JSONObject(text).optJSONObject("location") ?: return null
        return LatLon(loc.getDouble("latitude"), loc.getDouble("longitude"))
    }

    // ---------------- Roads ----------------

    private val roadNames = HashMap<String, String>()
    @Volatile var roadsApiWorks = true
        private set

    /**
     * Name of the road the recent GPS points are on, using the Roads API (snaps the track to the road).
     * Returns null when not known. If the Roads API is not enabled for the key, it is not asked again.
     */
    fun snappedRoadName(track: List<LatLon>): String? {
        if (!roadsApiWorks || track.isEmpty()) return null
        val path = track.takeLast(5).joinToString("|") { String.format(java.util.Locale.US, "%.6f,%.6f", it.lat, it.lon) }
        val o = try {
            JSONObject(Http.get("https://roads.googleapis.com/v1/snapToRoads?path=${Http.enc(path)}&interpolate=false&key=${Http.enc(key)}"))
        } catch (e: HttpException) {
            if (e.code == 403 || e.code == 400) roadsApiWorks = false
            throw e
        }
        val arr = o.optJSONArray("snappedPoints") ?: return null
        if (arr.length() == 0) return null
        val pid = arr.getJSONObject(arr.length() - 1).optString("placeId")
        if (pid.isBlank()) return null
        roadNames[pid]?.let { return it.ifBlank { null } }
        val g = JSONObject(Http.get("https://maps.googleapis.com/maps/api/geocode/json?place_id=${Http.enc(pid)}&language=en&key=${Http.enc(key)}"))
        var name = ""
        val res = g.optJSONArray("results")
        if (res != null && res.length() > 0) {
            val comps = res.getJSONObject(0).optJSONArray("address_components")
            if (comps != null) for (c in 0 until comps.length()) {
                val comp = comps.getJSONObject(c)
                if (comp.optJSONArray("types")?.toString().orEmpty().contains("\"route\"")) { name = comp.optString("long_name"); break }
            }
        }
        if (name.contains("Unnamed", true)) name = ""
        roadNames[pid] = name
        return name.ifBlank { null }
    }

    // ---------------- Geocoding ----------------

    fun reverseGeocode(lat: Double, lon: Double): ReverseGeocode {
        val o = JSONObject(Http.get("https://maps.googleapis.com/maps/api/geocode/json?latlng=$lat,$lon&region=mv&language=en&key=${Http.enc(key)}"))
        checkLegacyStatus(o)
        val results = o.optJSONArray("results") ?: JSONArray()
        var road = ""; var island = ""; var address = ""
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val types = r.optJSONArray("types")?.toString().orEmpty()
            if (address.isEmpty() && !types.contains("plus_code")) address = r.optString("formatted_address")
            val comps = r.optJSONArray("address_components") ?: continue
            for (c in 0 until comps.length()) {
                val comp = comps.getJSONObject(c)
                val ct = comp.optJSONArray("types")?.toString().orEmpty()
                val name = comp.optString("long_name")
                if (road.isEmpty() && ct.contains("\"route\"") && !name.contains("Unnamed", true)) road = name
                if (island.isEmpty() && (ct.contains("\"locality\"") || ct.contains("sublocality"))) island = name
            }
        }
        return ReverseGeocode(road, island, address)
    }

    // ---------------- Routes ----------------

    /** Google Routes API v2 first, legacy Directions API as fallback (same as Lua v98). */
    fun route(oLat: Double, oLon: Double, dLat: Double, dLon: Double, mode: TravelMode): Route {
        val first = runCatching { routesV2(oLat, oLon, dLat, dLon, mode) }
        first.getOrNull()?.takeIf { it.steps.isNotEmpty() }?.let { return it }
        val legacy = runCatching { directionsLegacy(oLat, oLon, dLat, dLon, mode) }
        legacy.getOrNull()?.takeIf { it.steps.isNotEmpty() }?.let { return it }
        throw (first.exceptionOrNull() ?: legacy.exceptionOrNull() ?: IllegalStateException("No route found"))
    }

    private fun routesV2(oLat: Double, oLon: Double, dLat: Double, dLon: Double, mode: TravelMode): Route {
        fun wp(lat: Double, lon: Double) = JSONObject().put("location", JSONObject().put("latLng", JSONObject().put("latitude", lat).put("longitude", lon)))
        val body = JSONObject().put("origin", wp(oLat, oLon)).put("destination", wp(dLat, dLon))
            .put("travelMode", mode.routesApi).put("computeAlternativeRoutes", false)
            .put("languageCode", "en").put("regionCode", "MV").put("units", "METRIC")
        val mask = listOf(
            "routes.duration", "routes.distanceMeters", "routes.description",
            "routes.legs.steps.distanceMeters", "routes.legs.steps.staticDuration",
            "routes.legs.steps.navigationInstruction", "routes.legs.steps.startLocation",
            "routes.legs.steps.endLocation", "routes.legs.steps.polyline.encodedPolyline"
        ).joinToString(",")
        val text = Http.post("https://routes.googleapis.com/directions/v2:computeRoutes", body.toString(), "application/json",
            mapOf("X-Goog-Api-Key" to key, "X-Goog-FieldMask" to mask), readMs = 25000)
        val root = JSONObject(text)
        root.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message", "Google Routes API error")) }
        val routes = root.optJSONArray("routes") ?: throw IllegalStateException("No Google route found")
        if (routes.length() == 0) throw IllegalStateException("No Google route found")
        val r = routes.getJSONObject(0)
        val steps = ArrayList<RouteStep>()
        val legs = r.optJSONArray("legs") ?: JSONArray()
        for (l in 0 until legs.length()) {
            val arr = legs.getJSONObject(l).optJSONArray("steps") ?: continue
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val nav = s.optJSONObject("navigationInstruction")
                val (inst, side) = splitDestinationSide(cleanInstruction(nav?.optString("instructions").orEmpty()))
                val maneuver = nav?.optString("maneuver").orEmpty()
                val poly = Geo.decodePolyline(s.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty())
                steps.add(RouteStep(
                    instruction = inst.ifBlank { "Continue" }, destinationSide = side, maneuver = maneuver, roadName = roadFromInstruction(inst),
                    distanceMeters = s.optDouble("distanceMeters", 0.0), durationSeconds = parseDuration(s.optString("staticDuration")),
                    start = latLng(s.optJSONObject("startLocation")), end = latLng(s.optJSONObject("endLocation")), polyline = poly
                ))
            }
        }
        return Route(steps, r.optDouble("distanceMeters", steps.sumOf { it.distanceMeters }), parseDuration(r.optString("duration")),
            r.optString("description"), "Google Routes")
    }

    private fun directionsLegacy(oLat: Double, oLon: Double, dLat: Double, dLon: Double, mode: TravelMode): Route {
        val url = "https://maps.googleapis.com/maps/api/directions/json?origin=$oLat,$oLon&destination=$dLat,$dLon" +
            "&mode=${mode.directionsApi}&alternatives=false&region=mv&language=en&key=${Http.enc(key)}"
        val o = JSONObject(Http.get(url, readMs = 25000))
        checkLegacyStatus(o)
        val routes = o.optJSONArray("routes") ?: throw IllegalStateException("No Google route found")
        if (routes.length() == 0) throw IllegalStateException("No Google route found")
        val r = routes.getJSONObject(0)
        val leg = r.getJSONArray("legs").getJSONObject(0)
        val arr = leg.optJSONArray("steps") ?: JSONArray()
        val steps = (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            val (inst, side) = splitDestinationSide(cleanInstruction(s.optString("html_instructions")))
            fun ll(name: String) = s.optJSONObject(name)?.let { LatLon(it.getDouble("lat"), it.getDouble("lng")) }
            RouteStep(instruction = inst.ifBlank { "Continue" }, destinationSide = side, maneuver = s.optString("maneuver"),
                roadName = roadFromInstruction(inst),
                distanceMeters = s.optJSONObject("distance")?.optDouble("value", 0.0) ?: 0.0,
                durationSeconds = s.optJSONObject("duration")?.optDouble("value", 0.0) ?: 0.0,
                start = ll("start_location"), end = ll("end_location"),
                polyline = Geo.decodePolyline(s.optJSONObject("polyline")?.optString("points").orEmpty()))
        }
        return Route(steps, leg.optJSONObject("distance")?.optDouble("value", 0.0) ?: 0.0,
            leg.optJSONObject("duration")?.optDouble("value", 0.0) ?: 0.0, r.optString("summary"), "Google Directions")
    }

    // ---------------- Key check ----------------

    /** Tests each Google service used by the app. Returns (anyWorking, spoken summary). */
    fun checkKey(): Pair<Boolean, String> {
        val lat = 4.1755; val lon = 73.5093 // Malé
        val tests = linkedMapOf<String, () -> Unit>(
            "Places" to { searchText("mosque", lat, lon, 1000) },
            "Routes" to { route(lat, lon, 4.1780, 73.5130, TravelMode.WALKING) },
            "Geocoding" to { reverseGeocode(lat, lon) }
        )
        val lines = ArrayList<String>()
        var ok = 0
        for ((name, test) in tests) {
            try { test(); ok++; lines.add("$name: working.") } catch (e: Exception) { lines.add("$name: ${describeError(e)}") }
        }
        val summary = when (ok) {
            tests.size -> "API key accepted by all Google services. Billing appears active."
            0 -> "Google rejected all tested services. Check billing, enabled APIs, and key restrictions."
            else -> "$ok of ${tests.size} Google services are working. One or more APIs may be disabled, restricted, or over quota."
        }
        return (ok > 0) to (summary + " " + lines.joinToString(" "))
    }

    fun describeError(e: Throwable): String {
        if (e is HttpException) {
            val msg = runCatching { JSONObject(e.body).optJSONObject("error")?.optString("message") }.getOrNull()
            if (!msg.isNullOrBlank()) return shorten(msg)
        }
        return shorten(Http.friendly(e, "Google Maps"))
    }

    private fun shorten(s: String) = if (s.length > 180) s.take(180) + "." else s

    private fun checkLegacyStatus(o: JSONObject) {
        val status = o.optString("status", "OK")
        if (status != "OK" && status != "ZERO_RESULTS") {
            val msg = o.optString("error_message").ifBlank { status }
            throw IllegalStateException(msg)
        }
    }

    private fun typesText(arr: JSONArray?): String {
        if (arr == null) return ""
        val skip = setOf("point_of_interest", "establishment", "premise", "street_address")
        return (0 until arr.length()).map { arr.optString(it) }.firstOrNull { it !in skip }?.replace('_', ' ').orEmpty()
    }

    private fun latLng(o: JSONObject?): LatLon? {
        val ll = o?.optJSONObject("latLng") ?: return null
        return LatLon(ll.optDouble("latitude"), ll.optDouble("longitude"))
    }

    private fun parseDuration(s: String?): Double = s?.removeSuffix("s")?.toDoubleOrNull() ?: 0.0

    companion object {
        fun cleanInstruction(raw: String): String {
            var s = raw.replace(Regex("<div[^>]*>"), ". ").replace(Regex("<[^>]+>"), "")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("\n", ". ")
            s = s.replace(Regex("\\s+"), " ").replace(Regex("\\s*\\.\\s*\\.+"), ".").trim()
            return s.trimEnd('.').trim()
        }

        private val DEST_SIDE = Regex("\\.?\\s*(?:Your )?destination (?:will be|is) on the (left|right)\\.?", RegexOption.IGNORE_CASE)

        /** Removes "Destination will be on the left/right" from an instruction and returns the side separately. */
        fun splitDestinationSide(inst: String): Pair<String, String> {
            val m = DEST_SIDE.find(inst) ?: return inst to ""
            return inst.replace(m.value, "").trim().trimEnd('.') to m.groupValues[1].lowercase()
        }

        private val INVALID_ROAD = setOf("left", "right", "straight", "destination", "the left", "the right", "your left", "your right")

        fun roadFromInstruction(inst: String): String {
            val patterns = listOf(
                Regex("\\bonto\\s+(.+?)(?:\\.|,|\\s+Destination|\\s+toward|$)", RegexOption.IGNORE_CASE),
                Regex("\\bon\\s+(.+?)(?:\\.|,|\\s+Destination|\\s+toward|$)", RegexOption.IGNORE_CASE)
            )
            for (p in patterns) {
                val m = p.find(inst) ?: continue
                val road = m.groupValues[1].trim()
                if (road.isNotEmpty() && road.lowercase() !in INVALID_ROAD && !road.lowercase().startsWith("the left") && !road.lowercase().startsWith("the right")) return road
            }
            return ""
        }
    }
}
