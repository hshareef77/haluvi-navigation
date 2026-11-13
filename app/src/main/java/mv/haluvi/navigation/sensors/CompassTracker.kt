package mv.haluvi.navigation.sensors

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import mv.haluvi.navigation.core.Geo
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Compass heading in degrees from TRUE north (the Lua version used magnetic north, which is a few
 * degrees off and matters for a 1-degree focus window).
 *
 * Improvements over the Lua version:
 *  - magnetic declination correction from the current GPS position;
 *  - works with the phone held flat OR upright (camera pointing forward);
 *  - accounts for screen rotation;
 *  - reports when the compass needs calibration.
 */
class CompassTracker(private val context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val listeners = CopyOnWriteArrayList<(Double) -> Unit>()
    private var users = 0
    private var registered = false

    private val rot = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)
    private var gravity: FloatArray? = null
    private var geomagnetic: FloatArray? = null
    private var smooth = -1.0
    private var lastNotifyMs = 0L

    @Volatile var heading: Double = 0.0
        private set
    @Volatile var available: Boolean = false
        private set
    @Volatile var lastMoveMs: Long = 0L
        private set
    @Volatile var needsCalibration: Boolean = false
        private set

    private var declination = 0.0
    var onCalibrationNeeded: (() -> Unit)? = null

    fun addListener(l: (Double) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Double) -> Unit) { listeners.remove(l) }

    @Synchronized
    fun acquire() { users++; start() }

    @Synchronized
    fun release() { users = (users - 1).coerceAtLeast(0); if (users == 0) stop() }

    fun updateDeclination(lat: Double, lon: Double, altitude: Double?) {
        declination = runCatching {
            GeomagneticField(lat.toFloat(), lon.toFloat(), (altitude ?: 0.0).toFloat(), System.currentTimeMillis()).declination.toDouble()
        }.getOrDefault(declination)
    }

    private fun start() {
        if (registered) return
        val rv = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        registered = if (rv != null) {
            sm.registerListener(this, rv, SensorManager.SENSOR_DELAY_GAME)
        } else {
            val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            if (acc != null && mag != null) {
                sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
                sm.registerListener(this, mag, SensorManager.SENSOR_DELAY_GAME)
            } else false
        }
        // Magnetometer is still registered for its accuracy reports (calibration warning).
        sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { if (rv != null) sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        available = registered
    }

    private fun stop() {
        if (!registered) return
        sm.unregisterListener(this)
        registered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                runCatching { SensorManager.getRotationMatrixFromVector(rot, event.values) }.onFailure { return }
                computeHeading()
            }
            Sensor.TYPE_ACCELEROMETER -> { gravity = event.values.clone(); fromAccMag() }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                if (!usingRotationVector()) { geomagnetic = event.values.clone(); fromAccMag() }
            }
        }
    }

    private fun usingRotationVector() = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null ||
        sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) != null

    private fun fromAccMag() {
        val g = gravity ?: return
        val m = geomagnetic ?: return
        if (SensorManager.getRotationMatrix(rot, null, g, m)) computeHeading()
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = runCatching {
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }.getOrDefault(Surface.ROTATION_0)

    private fun computeHeading() {
        // rot[8] is the z component of the device z-axis in world coordinates: ~1 when flat, ~0 when upright.
        val upright = abs(rot[8]) < 0.6f
        // The activity is portrait-locked; landscape is handled for devices that ignore the lock.
        val ok = when (displayRotation()) {
            Surface.ROTATION_90 -> if (upright) SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_Z, SensorManager.AXIS_MINUS_X, remapped)
                else SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped)
            Surface.ROTATION_270 -> if (upright) SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_MINUS_Z, SensorManager.AXIS_X, remapped)
                else SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped)
            // Phone held upright: heading is the direction the back of the phone faces.
            else -> if (upright) SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
                else { System.arraycopy(rot, 0, remapped, 0, 9); true }
        }
        if (!ok) return
        SensorManager.getOrientation(remapped, orientation)
        val raw = Geo.normalize(Math.toDegrees(orientation[0].toDouble()) + declination)

        smooth = if (smooth < 0) raw else {
            var delta = raw - smooth
            if (delta > 180) delta -= 360 else if (delta < -180) delta += 360
            Geo.normalize(smooth + 0.25 * delta)
        }
        val now = SystemClock.elapsedRealtime()
        if (Geo.angleBetween(smooth, heading) > 2.0) lastMoveMs = now
        heading = smooth
        if (now - lastNotifyMs >= 50) {
            lastNotifyMs = now
            listeners.forEach { runCatching { it(smooth) } }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return
        val bad = accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE || accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
        if (bad && !needsCalibration) onCalibrationNeeded?.invoke()
        needsCalibration = bad
    }

    /** True while the phone has not turned more than 2 degrees for [ms]. */
    fun isStable(ms: Long = 900): Boolean = SystemClock.elapsedRealtime() - lastMoveMs >= ms
}
