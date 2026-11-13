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

    var voiceOn by bool("voiceOn", true)
    var speechRate by float("speechRate", 1.0f)
    var vibrationOn by bool("vibrationOn", true)
    var directionVoiceHintsOn by bool("directionVoiceHintsOn", false)
    var keepAwake by bool("keepAwake", true)
    var autoIslandDetectionOn by bool("autoIslandDetectionOn", true)
    var islandArrivalIdentityOn by bool("islandArrivalIdentityOn", true)

    /** Half-width of the direction focus window in degrees (Lua v22+: 1 degree). */
    var focusAccuracy by int("focusAccuracy", 1)
    var focusVibration by string("focusVibration", VibrationPatterns.CONTINUOUS)
    var arrivalVibration by string("arrivalVibration", "Strong confirmation")
    var correctionIntervalSec by int("correctionInterval", 4)

    var arrivalDistance by int("arrivalDistance", 5)
    var islandArrivalDistance by int("islandArrivalDistance", 1000)
    var nearestLocationDistance by int("nearestLocationDistance", 100)
    var nearestIslandArea by int("nearestIslandArea", 10000)
    var turnAnnouncementsOn by bool("turnAnnouncementsOn", true)

    var travelModeName by string("travelMode", TravelMode.WALKING.name)
    var travelMode: TravelMode
        get() = runCatching { TravelMode.valueOf(travelModeName) }.getOrDefault(TravelMode.WALKING)
        set(v) { travelModeName = v.name }

    var googleEnabled by bool("googleMapsEnabled", false)
    var googleApiKey by string("googleMapsApiKey", "")
    var googleLastStatus by string("googleMapsLastStatus", "")
    var onlineProvider by string("onlineSearchProvider", "Google Maps")

    var exploreCategory by string("exploreCategory", "All")
    var exploreRadius by int("exploreRadiusMeters", 500)

    var homeTab by string("homeTab", "Home")
    var manualCurrentPlaceId by string("manualCurrentPlaceId", "")
    var manualCurrentIslandKey by string("manualCurrentIslandKey", "")
    var mapDataIslandKey by string("mapDataIslandKey", "")

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
