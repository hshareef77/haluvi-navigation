package mv.haluvi.navigation.nav

import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.data.DownloadedRoad
import mv.haluvi.navigation.net.OpenStreetMap
import org.json.JSONObject
import java.util.zip.GZIPInputStream

/**
 * OpenStreetMap places and roads built into the app, one file per atoll (assets/osm/<code>.json.gz),
 * prepared with the same queries as the Offline maps download. Installing them needs no internet.
 * Map data (c) OpenStreetMap contributors, ODbL.
 */
object BuiltinMaps {
    private var index: JSONObject? = null

    private fun index(): JSONObject? {
        index?.let { return it }
        return runCatching {
            JSONObject(AppGraph.appContext.assets.open("osm/index.json").bufferedReader().use { it.readText() })
        }.getOrNull()?.also { index = it }
    }

    /** Date the built-in maps were prepared, or null when the app has none. */
    fun date(): String? = index()?.optString("date")?.ifBlank { null }

    fun has(atollCode: String): Boolean = index()?.optJSONObject("atolls")?.has(atollCode) == true

    /** Places and roads of one atoll. */
    fun load(atollCode: String): Pair<List<OpenStreetMap.MapPlace>, List<DownloadedRoad>> {
        val text = GZIPInputStream(AppGraph.appContext.assets.open("osm/$atollCode.json.gz")).bufferedReader().use { it.readText() }
        val o = JSONObject(text)
        val osm = AppGraph.osm
        return osm.parsePlaces(o.getJSONArray("places")) to osm.parseRoads(o.getJSONArray("roads"))
    }
}
