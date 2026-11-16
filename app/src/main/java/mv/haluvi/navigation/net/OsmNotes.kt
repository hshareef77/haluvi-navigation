package mv.haluvi.navigation.net

import mv.haluvi.navigation.data.NamedRoad
import mv.haluvi.navigation.data.RoadNames
import org.json.JSONObject
import java.util.Locale

/**
 * Sends a road to OpenStreetMap as a public note (no account needed). Volunteer mappers check notes and add
 * the road or its name to the map, so everyone gets it in later downloads. Nothing about the user is sent.
 */
object OsmNotes {
    private fun f(v: Double) = String.format(Locale.US, "%.6f", v)

    /** The note text for a road. */
    fun text(r: NamedRoad, islandName: String): String = buildString {
        val len = RoadNames.length(r.points).toInt()
        if (r.recorded) {
            append("Missing road here")
            if (islandName.isNotBlank()) append(" on $islandName")
            append(", about $len m long")
            if (r.width.isNotBlank()) append(", ${r.width} width")
            append(".")
            if (r.name.isNotBlank() && !r.name.startsWith("Unnamed", true)) append(" Name: ${r.name}.")
            append(" Recorded by walking it with phone GPS (about 5 to 10 m accuracy).")
        } else {
            append("Road name: ${r.name}")
            if (islandName.isNotBlank()) append(", on $islandName")
            append(". This road (about $len m long) has no name on the map.")
        }
        append(" Local knowledge, shared from the Haluvi Navigation app for blind travellers.")
        // The line, so mappers can find the exact road (at most 25 points).
        val step = maxOf(1, r.points.size / 25)
        val pts = r.points.filterIndexed { i, _ -> i % step == 0 || i == r.points.size - 1 }
        append(" Line: ")
        append(pts.joinToString("; ") { "${f(it[0])},${f(it[1])}" })
    }.take(1900)

    /** Posts the note at the middle of the road. Returns the note number. */
    fun send(r: NamedRoad, islandName: String): Long {
        val mid = r.points[r.points.size / 2]
        val body = "lat=${f(mid[0])}&lon=${f(mid[1])}&text=${Http.enc(text(r, islandName))}"
        val res = Http.post("https://api.openstreetmap.org/api/0.6/notes.json", body, "application/x-www-form-urlencoded")
        return runCatching { JSONObject(res).optJSONObject("properties")?.optLong("id") ?: 0L }.getOrDefault(0L)
    }
}
