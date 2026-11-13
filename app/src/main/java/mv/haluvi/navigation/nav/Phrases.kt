package mv.haluvi.navigation.nav

import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.Fmt
import mv.haluvi.navigation.core.Geo
import java.util.Locale
import kotlin.math.roundToInt

/** Short, consistent spoken phrases that follow the user's settings (clock or compass, metres or miles). */
object Phrases {
    /** "120 meters" or "1.5 kilometers"; adds miles when the user asked for them. */
    fun distance(m: Double?): String {
        if (m == null) return "unknown distance"
        val base = Fmt.meters(m)
        if (!AppGraph.settings.milesToo) return base
        val miles = m / 1609.344
        return "$base, " + if (miles < 0.1) "${(m * 1.09361).roundToInt()} yards" else String.format(Locale.US, "%.1f miles", miles)
    }

    /** Where a target is from the user: "at 2 o'clock" (relative to [heading]) or "north east, 45 degrees" (absolute). */
    fun where(heading: Double, bearing: Double): String =
        if (AppGraph.settings.clockDirections) "at ${Geo.clockPosition(heading, bearing)}"
        else "${Geo.directionName(bearing)}, ${Geo.degreesInt(bearing)} degrees"

    /** "Pharmacy, 2 o'clock, 40 meters". */
    fun place(name: String, heading: Double, bearing: Double, meters: Double): String =
        "$name, ${where(heading, bearing).removePrefix("at ")}, ${distance(meters)}"

    fun compass(heading: Double): String = "Facing ${Geo.directionName(heading)}, ${Geo.degreesInt(heading)} degrees."
}
