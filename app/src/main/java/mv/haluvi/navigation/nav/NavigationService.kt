package mv.haluvi.navigation.nav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.R
import mv.haluvi.navigation.ui.MainActivity

/**
 * Foreground service that keeps GPS, compass, focus vibration and voice guidance running
 * when the screen is off or another app is open. The Lua app stopped guiding in that case.
 */
class NavigationService : Service() {
    companion object {
        private const val CHANNEL = "navigation"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "mv.haluvi.navigation.STOP"
        @Volatile var running = false; private set

        fun ensureRunning(context: Context) {
            if (running) return
            if (!AppGraph.location.hasPermission()) return
            runCatching {
                val i = Intent(context, NavigationService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            }
        }

        fun updateOrStop(context: Context) {
            val g = AppGraph
            if (!g.nav.isActive && !g.explore.active && !g.settings.islandAlertsOn && !g.routeTrip.active && !g.routeRecorder.active) {
                if (running) context.stopService(Intent(context, NavigationService::class.java))
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifyMs = 0L
    private var lastText = ""
    private val changeListener: () -> Unit = { syncResources(); updateNotification(false) }

    /** Fast GPS while navigating, exploring or at sea; slow GPS for background island alerts on land (saves battery). */
    private val fixListener: (mv.haluvi.navigation.sensors.Fix) -> Unit = { f ->
        val g = AppGraph
        val ms = when {
            g.uiVisible || g.nav.isActive || g.explore.active || g.routeTrip.active || g.routeRecorder.active -> 1000L
            g.islandWatcher.recentlyAtSea() || (f.speedMps ?: 0.0) >= 2.0 -> 2000L
            else -> 20_000L
        }
        g.location.setInterval(ms)
        updateNotification(false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppGraph.nav.stop()
            AppGraph.explore.stop()
            AppGraph.routeTrip.stop(silent = true)
            AppGraph.routeRecorder.finish()?.let { AppGraph.routes.add(it) }
            AppGraph.settings.islandAlertsOn = false
            stopSelf()
            return START_NOT_STICKY
        }
        val n = buildNotification(currentText())
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIFICATION_ID, n)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            AppGraph.location.acquire()
            AppGraph.nav.addListener(changeListener)
            AppGraph.explore.addListener(changeListener)
            AppGraph.routeTrip.addListener(changeListener)
            AppGraph.routeRecorder.addListener(changeListener)
            AppGraph.location.addListener(fixListener)
        }
        syncResources()
        return START_NOT_STICKY
    }

    private var heavyHeld = false

    /**
     * Compass and a CPU wake lock are needed only while navigating or exploring (pointing vibration,
     * timers with the screen off). Background island alerts alone run on GPS only, to save battery.
     */
    private fun syncResources() {
        val heavy = AppGraph.nav.isActive || AppGraph.explore.active
        if (heavy && !heavyHeld) {
            heavyHeld = true
            AppGraph.compass.acquire()
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Haluvi:navigation").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        } else if (!heavy && heavyHeld) {
            heavyHeld = false
            AppGraph.compass.release()
            AppGraph.haptics.stopFocus()
            runCatching { wakeLock?.release() }
            wakeLock = null
        }
    }

    override fun onDestroy() {
        if (running) {
            running = false
            AppGraph.nav.removeListener(changeListener)
            AppGraph.explore.removeListener(changeListener)
            AppGraph.routeTrip.removeListener(changeListener)
            AppGraph.routeRecorder.removeListener(changeListener)
            AppGraph.location.removeListener(fixListener)
            AppGraph.location.setInterval(1000L)
            AppGraph.location.release()
            if (heavyHeld) { heavyHeld = false; AppGraph.compass.release() }
            AppGraph.haptics.stopFocus()
        }
        runCatching { wakeLock?.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep guiding even if the app is swiped away from recents; the notification has a Stop button.
        super.onTaskRemoved(rootIntent)
    }

    private fun currentText(): String {
        val nav = AppGraph.nav
        val explore = AppGraph.explore
        return when {
            nav.isActive -> nav.notificationText().ifBlank { "Navigating" }
            AppGraph.routeTrip.active -> AppGraph.routeTrip.repeatText()
            AppGraph.routeRecorder.active -> "Recording route ${AppGraph.routeRecorder.name}. ${AppGraph.routeRecorder.status}"
            explore.active -> explore.statusText + (if (explore.roadText.isNotBlank()) " " + explore.roadText else "")
            AppGraph.settings.islandAlertsOn -> AppGraph.islandWatcher.lastMessage.ifBlank { "Watching for islands when you travel by sea." }
            else -> "Ready"
        }
    }

    private fun updateNotification(force: Boolean) {
        if (!running) return
        val text = currentText()
        val now = SystemClock.elapsedRealtime()
        if (!force && (text == lastText || now - lastNotifyMs < 3000)) return
        lastText = text; lastNotifyMs = now
        runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Navigation guidance", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Shows while Haluvi Navigation is guiding you"
            ch.setShowBadge(false)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(when {
                AppGraph.nav.isActive -> "Going to ${AppGraph.nav.target?.name}"
                AppGraph.explore.active -> "Explore on"
                AppGraph.settings.islandAlertsOn -> "Island alerts on"
                else -> getString(R.string.app_name)
            })
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Stop", stop).build())
            .build()
    }
}
