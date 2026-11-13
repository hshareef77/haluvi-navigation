package mv.haluvi.navigation.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Geographic math and spoken-friendly formatting shared by every feature. */
object Geo {
    private const val EARTH_RADIUS_M = 6371000.0

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return normalize(Math.toDegrees(atan2(y, x)))
    }

    fun normalize(deg: Double): Double {
        var h = deg % 360.0
        if (h < 0) h += 360.0
        return h
    }

    /** Signed difference current - target in range -180..180. Positive means the target is to the left. */
    fun headingDifference(current: Double, target: Double): Double =
        ((normalize(current) - normalize(target) + 540.0) % 360.0) - 180.0

    fun angleBetween(a: Double, b: Double): Double = abs(headingDifference(a, b))

    private val DIRECTIONS = arrayOf(
        "north", "north north east", "north east", "east north east",
        "east", "east south east", "south east", "south south east",
        "south", "south south west", "south west", "west south west",
        "west", "west north west", "north west", "north north west"
    )

    fun directionName(deg: Double): String {
        val idx = (floor(normalize(deg) / 22.5 + 0.5).toInt()) % 16
        return DIRECTIONS[idx]
    }

    fun degreesInt(deg: Double): Int {
        val d = normalize(deg).roundToInt()
        return if (d >= 360) 0 else d
    }

    /** "2 o'clock" style position of a target relative to the user's heading. Very natural for blind travellers. */
    fun clockPosition(heading: Double, targetBearing: Double): String {
        val rel = normalize(targetBearing - heading)
        var hour = ((rel + 15.0) / 30.0).toInt() % 12
        if (hour == 0) hour = 12
        return "$hour o'clock"
    }

    /** ahead / on your left / on your right / behind, relative to a travel heading. */
    fun relativeSide(heading: Double, targetBearing: Double): String {
        val diff = headingDifference(heading, targetBearing)
        val a = abs(diff)
        return when {
            a <= 30 -> "ahead"
            a >= 150 -> "behind"
            diff > 0 -> "on your left"
            else -> "on your right"
        }
    }

    /** Distance from point P to segment AB in metres (equirectangular projection; fine for short segments). */
    fun distanceToSegment(pLat: Double, pLon: Double, aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val k = cos(Math.toRadians(pLat)) * EARTH_RADIUS_M * PI / 180.0
        val m = EARTH_RADIUS_M * PI / 180.0
        val ax = (aLon - pLon) * k; val ay = (aLat - pLat) * m
        val bx = (bLon - pLon) * k; val by = (bLat - pLat) * m
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        return sqrt(cx * cx + cy * cy)
    }

    fun distanceToPolyline(lat: Double, lon: Double, points: List<LatLon>): Double {
        if (points.isEmpty()) return Double.MAX_VALUE
        if (points.size == 1) return distance(lat, lon, points[0].lat, points[0].lon)
        var best = Double.MAX_VALUE
        for (i in 0 until points.size - 1) {
            val d = distanceToSegment(lat, lon, points[i].lat, points[i].lon, points[i + 1].lat, points[i + 1].lon)
            if (d < best) best = d
        }
        return best
    }

    /** Decodes a Google encoded polyline. */
    fun decodePolyline(encoded: String): List<LatLon> {
        val out = ArrayList<LatLon>()
        var index = 0
        var lat = 0
        var lng = 0
        try {
            while (index < encoded.length) {
                var result = 0; var shift = 0; var b: Int
                do { b = encoded[index++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
                lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
                result = 0; shift = 0
                do { b = encoded[index++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
                lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
                out.add(LatLon(lat / 1e5, lng / 1e5))
            }
        } catch (_: Exception) {
            // Return whatever was decoded from a damaged polyline.
        }
        return out
    }
}

data class LatLon(val lat: Double, val lon: Double)

/** Spoken-friendly formatting. */
object Fmt {
    fun meters(m: Double?): String {
        if (m == null) return "unknown distance"
        if (m < 1000) {
            val v = m.roundToInt()
            return if (v == 1) "1 meter" else "$v meters"
        }
        return String.format(Locale.US, "%.1f kilometers", m / 1000.0)
    }

    fun metersWithMiles(m: Double?): String {
        if (m == null) return "unknown"
        val miles = m / 1609.344
        return if (m < 1000) "${m.roundToInt()} m / ${String.format(Locale.US, "%.2f", miles)} miles"
        else String.format(Locale.US, "%.2f km / %.2f miles", m / 1000.0, miles)
    }

    fun speed(mps: Double?): String {
        if (mps == null) return "Current speed: waiting for movement"
        val kmh = mps.coerceAtLeast(0.0) * 3.6
        val mph = kmh * 0.621371
        return if (kmh < 20) String.format(Locale.US, "Current speed: %.1f km/h, %.1f mph", kmh, mph)
        else "Current speed: ${kmh.roundToInt()} km/h, ${mph.roundToInt()} mph"
    }

    fun duration(seconds: Double?): String {
        if (seconds == null || seconds <= 0) return "waiting for movement"
        if (seconds < 60) return "less than 1 minute"
        val minutes = (seconds / 60.0).roundToInt()
        if (minutes < 60) return if (minutes == 1) "1 minute" else "$minutes minutes"
        val h = minutes / 60
        val r = minutes % 60
        val hText = if (h == 1) "1 hour" else "$h hours"
        return if (r == 0) hText else "$hText $r minutes"
    }

    fun clockAfter(seconds: Double): String =
        SimpleDateFormat("HH:mm", Locale.US).format(Date(System.currentTimeMillis() + (seconds * 1000).toLong()))

    fun eta(seconds: Double?): String {
        if (seconds == null || seconds <= 0) return "Estimated arrival: waiting for movement"
        return "Estimated arrival: ${duration(seconds)}, around ${clockAfter(seconds)}"
    }

    fun direction(bearing: Double): String = "${Geo.directionName(bearing)}, ${Geo.degreesInt(bearing)} degrees"

    fun lowerFirst(s: String): String = if (s.isEmpty()) s else s[0].lowercaseChar() + s.substring(1)
}
