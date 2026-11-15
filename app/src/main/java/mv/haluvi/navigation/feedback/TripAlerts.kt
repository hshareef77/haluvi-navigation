package mv.haluvi.navigation.feedback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import mv.haluvi.navigation.AppGraph
import mv.haluvi.navigation.R
import mv.haluvi.navigation.ui.MainActivity

/**
 * Trip alerts: the important moments of any trip (place arrival or passed, island approaching, arrived
 * or passing, route stops) are shown as a notification when the app is not on screen, the same way for
 * places, islands and routes. Turn-by-turn instructions are voice only.
 */
object TripAlerts {
    private const val CHANNEL = "trip_alerts"
    private var id = 100

    /** Shows [text] as a notification when the app is not visible (voice is spoken by the caller). */
    fun alert(context: Context, text: String) {
        if (AppGraph.uiVisible) return
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Trip alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Arrived, passed, island and route stop announcements"
                    setSound(null, null)
                })
            }
            val open = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(context, CHANNEL)
            else @Suppress("DEPRECATION") android.app.Notification.Builder(context)
            val n = b.setSmallIcon(R.drawable.ic_notification).setContentTitle(text).setContentText("Haluvi Navigation")
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true).setWhen(System.currentTimeMillis()).setShowWhen(true).build()
            id = if (id >= 130) 100 else id + 1
            nm.notify(id, n)
        }
    }
}
