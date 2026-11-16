package mv.haluvi.navigation.nav

import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.core.Geo
import mv.haluvi.navigation.sensors.Fix

/**
 * Records a missing road by walking it: good GPS fixes only (15 m or better), a point every 3 m of movement
 * (standing still adds nothing, so stops do not spoil the line), then smoothed into one centre line.
 */
object RoadRecorder {
    var active = false; private set
    private val raw = ArrayList<DoubleArray>()
    var meters = 0.0; private set
    var weakGps = false; private set
    private val listener: (Fix) -> Unit = { f -> runCatching { onFix(f) } }

    fun start() {
        if (active) return
        active = true; raw.clear(); meters = 0.0; weakGps = false
        AppGraph.location.addListener(listener)
        AppGraph.location.acquire()
        NavigationService.ensureRunning(AppGraph.appContext)
    }

    fun cancel() { stopListening(); raw.clear() }

    /** Ends recording and returns the smoothed line (empty when too short). */
    fun finish(): List<DoubleArray> {
        stopListening()
        if (raw.size < 3 || meters < 20) return emptyList()
        // Moving average over 5 points, keeping the real start and end.
        val sm = ArrayList<DoubleArray>()
        for (i in raw.indices) {
            if (i == 0 || i == raw.size - 1) { sm.add(raw[i]); continue }
            val from = maxOf(0, i - 2); val to = minOf(raw.size - 1, i + 2)
            var la = 0.0; var lo = 0.0
            for (j in from..to) { la += raw[j][0]; lo += raw[j][1] }
            val n = (to - from + 1).toDouble()
            sm.add(doubleArrayOf(la / n, lo / n))
        }
        // Keep a point every 5 m at least.
        val out = arrayListOf(sm.first())
        for (p in sm.drop(1)) if (Geo.distance(out.last()[0], out.last()[1], p[0], p[1]) >= 5) out.add(p)
        if (out.last() !== sm.last()) out.add(sm.last())
        return out
    }

    private fun stopListening() {
        if (!active) return
        active = false
        AppGraph.location.removeListener(listener)
        AppGraph.location.release()
        NavigationService.updateOrStop(AppGraph.appContext)
    }

    private fun onFix(f: Fix) {
        if (f.accuracy > 15) { weakGps = true; return }
        weakGps = false
        val last = raw.lastOrNull()
        if (last == null) { raw.add(doubleArrayOf(f.lat, f.lon)); return }
        val d = Geo.distance(last[0], last[1], f.lat, f.lon)
        if (d < 3) return
        if (d > 60) return // a GPS jump
        raw.add(doubleArrayOf(f.lat, f.lon)); meters += d
    }
}
