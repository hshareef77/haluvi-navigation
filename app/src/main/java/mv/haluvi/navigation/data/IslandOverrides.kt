package mv.haluvi.navigation.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The user's own changes to islands: a different name (spelling), a different type (inhabited, uninhabited,
 * resort, airport) and whether it has an airport. For when the government changes an island's status:
 * no app update is needed. The original values are kept and can be restored.
 */
object IslandOverrides {
    data class Change(var name: String? = null, var type: String? = null, var hasAirport: Boolean? = null)

    private var file: File? = null
    private val changes = HashMap<String, Change>()
    /** Original values, saved the first time an island is changed. */
    private val originals = HashMap<String, Triple<String, String, Boolean>>()

    fun init(context: Context) {
        file = File(context.filesDir, "island_overrides.json")
        load()
    }

    @Synchronized fun changeOf(key: String): Change? = changes[key]
    @Synchronized fun hasChange(key: String): Boolean = changes.containsKey(key)

    /** Applies the changes to these islands (called whenever islands are loaded or rebuilt). */
    @Synchronized
    fun apply(list: List<Island>) {
        if (changes.isEmpty()) return
        for (isl in list) {
            val c = changes[isl.key] ?: continue
            originals.getOrPut(isl.key) { Triple(isl.originalName, isl.type, isl.hasAirport) }
            c.name?.let { isl.name = it }
            c.type?.let { isl.type = it }
            c.hasAirport?.let { isl.hasAirport = it }
        }
    }

    @Synchronized
    fun set(isl: Island, name: String? = null, type: String? = null, hasAirport: Boolean? = null) {
        originals.getOrPut(isl.key) { Triple(isl.originalName, isl.type, isl.hasAirport) }
        val c = changes.getOrPut(isl.key) { Change() }
        if (name != null) { c.name = name; isl.name = name }
        if (type != null) { c.type = type; isl.type = type }
        if (hasAirport != null) { c.hasAirport = hasAirport; isl.hasAirport = hasAirport }
        save()
    }

    /** Back to the app's own name, type and airport. */
    @Synchronized
    fun restore(isl: Island) {
        changes.remove(isl.key)
        originals.remove(isl.key)?.let { (n, t, a) -> isl.name = n; isl.type = t; isl.hasAirport = a } ?: run { isl.name = isl.originalName }
        save()
    }

    private fun load() {
        changes.clear()
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            o.keys().forEach { k ->
                val c = o.getJSONObject(k)
                changes[k] = Change(
                    c.optString("name").ifBlank { null },
                    c.optString("type").ifBlank { null },
                    if (c.has("airport")) c.getBoolean("airport") else null)
            }
        }
    }

    private fun save() {
        val o = JSONObject()
        changes.forEach { (k, c) ->
            val j = JSONObject()
            c.name?.let { j.put("name", it) }
            c.type?.let { j.put("type", it) }
            c.hasAirport?.let { j.put("airport", it) }
            o.put(k, j)
        }
        runCatching { file?.writeText(o.toString()) }
    }
}
