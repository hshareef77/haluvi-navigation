package mv.haluvi.navigation.data

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

enum class TravelMode(val label: String, val routesApi: String, val directionsApi: String) {
    WALKING("Walking", "WALK", "walking"),
    DRIVING("Driving", "DRIVE", "driving");

    val description: String
        get() = if (this == WALKING) "Walking mode uses Google walking route guidance when Google API is available."
        else "Driving mode uses Google driving route guidance when Google API is available."
}

/** All user settings, persisted in SharedPreferences. Defaults match the final Lua version (v130). */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("haluvi_settings", Context.MODE_PRIVATE)

    // Voice and vibration
    var voiceOn by bool("voiceOn", true)
    var speechRate by float("speechRate", 1.0f)
    var vibrationOn by bool("vibrationOn", true)
    var focusVibration by string("focusVibration", VibrationPatterns.CONTINUOUS)
    var arrivalVibration by string("arrivalVibration", "Strong confirmation")

    // Navigation
    /** Half-width of the pointing window in degrees (vibrates when the phone points at the destination). */
    var focusAccuracy by int("focusAccuracy", 3)
    var directionVoiceHintsOn by bool("directionVoiceHintsOn", false)
    var turnAnnouncementsOn by bool("turnAnnouncementsOn", true)
    var keepAwake by bool("keepAwake", false)
    var arrivalDistance by int("arrivalDistance", 10)
    var islandArrivalDistance by int("islandArrivalDistance", 1000)
    /** Routes: announce every stop (true) or only your own stop (false). */
    var routeAnnounceAll by bool("routeAnnounceAll", true)
    /** Routes: "Your stop is coming" this many metres before a place stop. */
    var routeWarnMeters by int("routeWarnMeters", 200)
    /** "Approaching / Arrived at / Passing" island announcements during island trips and Explore at sea. */
    var islandStopAnnouncementsOn by bool("islandStopAnnouncementsOn", true)
    /** Background island alerts: announcements and notifications at sea without opening the app. */
    var islandAlertsOn by bool("islandAlertsOn", false)
    /** Island trips: false = sea (ferry, boat), true = air (plane, seaplane). */
    var islandTravelAir by bool("islandTravelAir", false)
    /** Places "From": saved place id, or empty for my GPS location. */
    var placeFromId by string("placeFromId", "")
    /** Islands "From": island key, or empty for the GPS island. */
    var islandFromKey by string("islandFromKey", "")

    // How directions and distances are spoken
    /** true: "2 o'clock". false: "north east, 45 degrees". */
    var clockDirections by bool("clockDirections", true)
    var milesToo by bool("milesToo", false)

    var travelModeName by string("travelMode", TravelMode.WALKING.name)
    var travelMode: TravelMode
        get() = runCatching { TravelMode.valueOf(travelModeName) }.getOrDefault(TravelMode.WALKING)
        set(v) { travelModeName = v.name }

    // Google Maps
    var googleEnabled by bool("googleMapsEnabled", false)
    var googleApiKey by string("googleMapsApiKey", "")
    var googleLastStatus by string("googleMapsLastStatus", "")

    // Map data and "What's around me"
    var mapDataIslandKey by string("mapDataIslandKey", "")
    var aroundCategory by string("aroundCategory", "all")

    val googleReady: Boolean get() = googleEnabled && googleApiKey.isNotBlank()

    private fun bool(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    }

    private fun int(key: String, def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getInt(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { prefs.edit().putInt(key, value).apply() }
    }

    private fun float(key: String, def: Float) = object : ReadWriteProperty<Any?, Float> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getFloat(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) { prefs.edit().putFloat(key, value).apply() }
    }

    private fun string(key: String, def: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) { prefs.edit().putString(key, value).apply() }
    }
}

object VibrationPatterns {
    const val CONTINUOUS = "Continuous"

    /** Same patterns as the Lua app (milliseconds: wait, vibrate, pause, ...). */
    val patterns: LinkedHashMap<String, LongArray> = linkedMapOf(
        "Single short" to longArrayOf(0, 80, 400),
        "Short pulse" to longArrayOf(0, 120, 500),
        "Long pulse" to longArrayOf(0, 400, 600),
        "Double pulse" to longArrayOf(0, 120, 120, 120, 600),
        "Triple pulse" to longArrayOf(0, 80, 80, 80, 80, 80, 600),
        "Long short pause" to longArrayOf(0, 350, 150, 100, 700),
        "Heartbeat" to longArrayOf(0, 80, 100, 180, 700),
        "Double heartbeat" to longArrayOf(0, 80, 100, 180, 100, 80, 700),
        "Strong confirmation" to longArrayOf(0, 600, 700),
        "Two long" to longArrayOf(0, 400, 200, 400, 700),
        "Rapid three" to longArrayOf(0, 60, 60, 60, 60, 60, 600)
    )

    val names: List<String> get() = patterns.keys.toList()
}
