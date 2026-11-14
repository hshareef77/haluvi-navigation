package mv.haluvi.navigation.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import mv.haluvi.navigation.core.Geo
import java.util.concurrent.CopyOnWriteArrayList

/** One accepted GPS fix. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val accuracy: Double,
    val speedMps: Double?,
    val bearing: Double?,
    val altitude: Double?,
    val altitudeIsMsl: Boolean,
    val provider: String,
    val elapsedMs: Long
) {
    val ageMs: Long get() = SystemClock.elapsedRealtime() - elapsedMs
}

/**
 * GPS + network location with the improvements over the Lua version:
 *  - real continuous updates (1 s GPS), not only last-known polling;
 *  - a weak network fix never replaces a recent good GPS fix;
 *  - GPS jump ("spike") rejection;
 *  - movement speed and travel direction computed from positions when the chip gives none;
 *  - sea-level altitude on Android 14+.
 */
class LocationTracker(private val context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val listeners = CopyOnWriteArrayList<(Fix) -> Unit>()
    private var users = 0
    private var running = false
    /** GPS update interval. 1 s for navigation; longer for background island alerts on land to save battery. */
    private var intervalMs = 1000L

    /** Changes the GPS update interval (re-registers updates when it changes). */
    @Synchronized
    fun setInterval(ms: Long) {
        if (ms == intervalMs) return
        intervalMs = ms
        if (running) { stop(); start() }
    }

    @Volatile var fix: Fix? = null
        private set

    private var motionSpeed: Double? = null
    private var motionBearing: Double? = null
    private var motionMs = 0L
    private var lastMotionFix: Fix? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = accept(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isGpsEnabled(): Boolean = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)

    fun addListener(l: (Fix) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Fix) -> Unit) { listeners.remove(l) }

    /** Reference-counted start: every screen/service that needs GPS calls acquire() and later release(). */
    @Synchronized
    fun acquire() {
        users++
        start()
    }

    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) stop()
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        if (!hasPermission()) return
        if (running) return
        readLastKnown()
        runCatching {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, listener, Looper.getMainLooper())
        }
        runCatching {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, maxOf(3000L, intervalMs), 0f, listener, Looper.getMainLooper())
        }
        running = true
    }

    /** Re-registers updates, e.g. after the user turns on location or presses Refresh GPS. */
    @Synchronized
    fun restart() {
        stop()
        start()
    }

    @Synchronized
    private fun stop() {
        runCatching { lm.removeUpdates(listener) }
        running = false
    }

    @SuppressLint("MissingPermission")
    fun readLastKnown() {
        if (!hasPermission()) return
        var best: Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            val l = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null || l.time > best.time + 30_000 || (kotlin.math.abs(l.time - best.time) <= 30_000 && l.accuracy < best.accuracy)) best = l
        }
        best?.let { if (fix == null) accept(it) }
    }

    private fun accept(loc: Location) {
        val now = SystemClock.elapsedRealtime()
        val locElapsed = loc.elapsedRealtimeNanos / 1_000_000
        val acc = if (loc.hasAccuracy() && loc.accuracy > 0) loc.accuracy.toDouble() else 999.0
        val prev = fix

        if (prev != null) {
            // Never let a coarse network fix replace a fresh, better GPS fix.
            if (loc.provider != LocationManager.GPS_PROVIDER && prev.provider == LocationManager.GPS_PROVIDER &&
                prev.ageMs < 8000 && acc > prev.accuracy) return
            // Reject impossible jumps (more than 70 m/s, allowing for both accuracies).
            val dt = ((locElapsed - prev.elapsedMs).coerceAtLeast(1)) / 1000.0
            val d = Geo.distance(prev.lat, prev.lon, loc.latitude, loc.longitude)
            if (dt < 30 && d > 70 * dt + acc + prev.accuracy && acc > 15) return
        }

        var speed: Double? = if (loc.hasSpeed() && loc.speed >= 0 && loc.speed < 120) loc.speed.toDouble() else null
        var bearing: Double? = if (loc.hasBearing() && (speed ?: 0.0) >= 0.6) loc.bearing.toDouble() else null

        // Position-based movement estimate (used when the chip gives no speed or bearing).
        val last = lastMotionFix
        if (last == null) {
            lastMotionFix = Fix(loc.latitude, loc.longitude, acc, null, null, null, false, loc.provider ?: "", locElapsed)
        } else {
            val dt = (locElapsed - last.elapsedMs) / 1000.0
            if (dt >= 2.0) {
                val d = Geo.distance(last.lat, last.lon, loc.latitude, loc.longitude)
                if (d > maxOf(acc, last.accuracy) * 0.5 || dt >= 6) {
                    val sp = d / dt
                    if (sp < 120) {
                        motionSpeed = if (motionSpeed == null) sp else motionSpeed!! * 0.5 + sp * 0.5
                        if (d >= 3) motionBearing = Geo.bearing(last.lat, last.lon, loc.latitude, loc.longitude)
                        motionMs = now
                    }
                    lastMotionFix = Fix(loc.latitude, loc.longitude, acc, null, null, null, false, loc.provider ?: "", locElapsed)
                }
            }
        }
        if (speed == null && now - motionMs < 8000) speed = motionSpeed
        if (bearing == null && now - motionMs < 15000 && (motionSpeed ?: 0.0) >= 0.45) bearing = motionBearing

        var altitude: Double? = null
        var msl = false
        if (Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude()) { altitude = loc.mslAltitudeMeters; msl = true }
        else if (loc.hasAltitude()) altitude = loc.altitude

        val f = Fix(loc.latitude, loc.longitude, acc, speed, bearing, altitude, msl, loc.provider ?: "", if (locElapsed > 0) locElapsed else now)
        fix = f
        listeners.forEach { runCatching { it(f) } }
    }

    /** A fix that is recent enough to navigate with. */
    fun freshFix(maxAgeMs: Long = 20_000): Fix? = fix?.takeIf { it.ageMs <= maxAgeMs }
}
