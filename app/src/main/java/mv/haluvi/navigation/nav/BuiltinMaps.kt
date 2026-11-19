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

    /** The last problem reading a built-in map (shown when installing fails). */
    @Volatile var lastError = ""

    /**
     * Reads one atoll's file. The Android packager may unpack files ending in .gz and drop the ".gz", so
     * both names are tried, and the content is read packed or plain.
     */
    private fun readText(atollCode: String): String {
        val am = AppGraph.appContext.assets
        var last: Exception? = null
        for (name in listOf("osm/$atollCode.json.gz", "osm/$atollCode.json")) {
            try {
                val raw = java.io.BufferedInputStream(am.open(name))
                raw.mark(4)
                val b1 = raw.read(); val b2 = raw.read()
                raw.reset()
                val stream = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(raw) else raw
                return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (e: Exception) { last = e }
        }
        throw last ?: java.io.FileNotFoundException("osm/$atollCode")
    }

    /** Places and roads of one atoll. */
    fun load(atollCode: String): Pair<List<OpenStreetMap.MapPlace>, List<DownloadedRoad>> {
        val text = try { readText(atollCode) } catch (e: Exception) { lastError = "file not found or unreadable (${e.javaClass.simpleName})"; throw e }
        val o = try { JSONObject(text) } catch (e: Exception) { lastError = "file damaged"; throw e }
        val osm = AppGraph.osm
        return osm.parsePlaces(o.getJSONArray("places")) to osm.parseRoads(o.getJSONArray("roads"))
    }
}
