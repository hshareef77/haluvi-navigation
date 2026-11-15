package mv.haluvi.navigation.nav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.R
import mv.haluvi.navigation.ui.MainActivity
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.data.IslandShapes
import mv.haluvi.navigation.data.Settings
import mv.haluvi.navigation.feedback.Haptics
import mv.haluvi.navigation.feedback.Speaker
import mv.haluvi.navigation.sensors.Fix
import mv.haluvi.navigation.sensors.LocationTracker

/**
 * Island stop announcements while travelling by sea or air:
 *  - "Approaching Maafushi, 2 kilometers."  once, when heading towards the island
 *  - "Arrived at Maafushi."                 when at the island's shore and the boat has stopped (a ferry stop)
 *  - "Passing Maafushi."                    when going close by without stopping
 * Active during island navigation, and during Explore when you are at sea.
 * Uses island outlines when downloaded (exact shore distance); otherwise island centre points.
 */
class IslandWatcher(
    private val context: Context,
    private val settings: Settings,
    private val shapes: IslandShapes,
    private val location: LocationTracker,
    private val speaker: Speaker,
    private val haptics: Haptics,
    private val nav: NavigationEngine,
    private val exploreActive: () -> Boolean
) {
    private class Visit {
        var approached = false
        var arrived = false
        var passed = false
        var slowSinceMs = 0L
        var minShore = Double.MAX_VALUE
        var awayCount = 0
    }

    private data class Limits(val approach: Double, val near: Double, val passMax: Double, val stopSpeed: Double,
                              val stopMs: Long, val minMoveSpeed: Double, val search: Double)

    private val visits = HashMap<String, Visit>()
    private val now: Long get() = SystemClock.elapsedRealtime()
    var lastMessage = ""; private set

    private val listener: (Fix) -> Unit = { f -> runCatching { onFix(f) } }

    init { location.addListener(listener) }

    private fun limits(): Limits = if (settings.islandTravelAir)
        Limits(approach = 10_000.0, near = 400.0, passMax = 2_000.0, stopSpeed = 12.0, stopMs = 15_000, minMoveSpeed = 25.0, search = 40_000.0)
    else
        Limits(approach = 2_000.0, near = 150.0, passMax = 700.0, stopSpeed = 1.0, stopMs = 20_000, minMoveSpeed = 2.5, search = 15_000.0)

    /**
     * True when the position is on the sea. With outlines: more than 200 m from every shore.
     * Without outlines (centre points only) it is careful: more than 3 km from every island centre,
     * so Explore is never silenced while walking on a big island.
     */
    fun atSea(f: Fix): Boolean {
        for (h in shapes.nearby(f.lat, f.lon, 5_000.0)) {
            if (shapes.has(h.island.key)) { if (h.shore <= 200) return false }
            else if (h.centre <= 3_000) return false
        }
        return true
    }

    /** True when the background Island alerts watch is the reason to listen (no trip, no Explore). */
    fun alertsOnly(): Boolean = settings.islandAlertsOn && !nav.isActive && !exploreActive()

    private var lastSeaMs = 0L

    /** True when the last good position at sea was less than 10 minutes ago (keeps watching while the ferry docks). */
    fun recentlyAtSea(): Boolean = lastSeaMs != 0L && now - lastSeaMs < 600_000

    private fun active(f: Fix): Boolean {
        if (atSea(f)) lastSeaMs = now
        if (nav.target?.kind == NavKind.ISLAND) return settings.islandStopAnnouncementsOn
        if (!(exploreActive() && settings.islandStopAnnouncementsOn) && !settings.islandAlertsOn) return false
        // At sea, or docked at a harbour shortly after being at sea (so "Arrived at" can still be said).
        return recentlyAtSea()
    }

    private fun onFix(f: Fix) {
        if (f.ageMs > 10_000 || f.accuracy > 60) return
        if (!active(f)) { if (visits.isNotEmpty() && !nav.isActive && !exploreActive()) visits.clear(); return }
        val lim = limits()
        val speed = f.speedMps ?: 0.0
        val hits = shapes.nearby(f.lat, f.lon, lim.search)
        val destKey = nav.target?.takeIf { it.kind == NavKind.ISLAND }?.islandKey

        // Forget islands we are far away from, so a later visit is announced again.
        val keep = hits.filter { it.shore <= lim.approach + 1500 }.map { it.island.key }.toSet()
        visits.keys.retainAll(keep)

        for (h in hits) {
            if (h.shore > lim.approach) continue
            val key = h.island.key
            val isDest = key == destKey
            val name = h.island.spokenName
            // Without an outline the harbour can be far from the island centre, so allow a wider shore zone.
            val near = if (shapes.has(key)) lim.near else lim.near + 450
            val isNew = !visits.containsKey(key)
            val v = visits.getOrPut(key) { Visit() }
            // First seen while already at this island's shore: this is where the trip starts (or GPS just came back).
            // Do not announce "Arrived" until we have left and come back.
            if (isNew && h.shore <= near) { v.arrived = true; v.approached = true; v.passed = true }

            // Approaching: heading towards the island while moving.
            if (!v.approached && h.shore > near && speed >= lim.minMoveSpeed && f.bearing != null) {
                val towards = Geo.angleBetween(f.bearing, Geo.bearing(f.lat, f.lon, h.island.lat, h.island.lon)) <= 25
                if (towards) {
                    v.approached = true
                    announce(if (isDest) "Approaching your destination, ${h.island.name}, ${Phrases.distance(h.shore)}." else "Approaching $name, ${Phrases.distance(h.shore)}.", flush = false)
                }
            }

            // Arrived: at the shore and stopped for a while (or standing on the island).
            if (h.shore <= near) {
                if (speed < lim.stopSpeed) {
                    if (v.slowSinceMs == 0L) v.slowSinceMs = now
                    if (!v.arrived && now - v.slowSinceMs >= lim.stopMs) {
                        v.arrived = true
                        v.approached = true
                        if (isDest) nav.arrivedAtIsland() else { announce("Arrived at $name.", flush = true); haptics.tick() }
                    }
                } else v.slowSinceMs = 0L
            } else v.slowSinceMs = 0L

            // Passing: came close, did not stop, now moving away.
            if (!v.arrived && !v.passed && h.shore <= lim.passMax && speed >= lim.minMoveSpeed) {
                if (h.shore < v.minShore) { v.minShore = h.shore; v.awayCount = 0 }
                else if (h.shore > v.minShore + 20) v.awayCount++
                if (v.awayCount >= 2) {
                    v.passed = true
                    v.approached = true
                    announce("Passing $name.", flush = false)
                }
            }
        }
    }

    private fun announce(text: String, flush: Boolean) {
        lastMessage = text
        speaker.speak(text, flush = flush)
        mv.haluvi.navigation.feedback.TripAlerts.alert(context, text)
    }

    fun reset() { visits.clear(); lastMessage = "" }
}
