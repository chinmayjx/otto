package io.github.otto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo

/**
 * The one notification Otto shows while its handle is on. Both foreground services post it, under the same id
 * and channel: the overlay's (main process) and the browser's (":browser"), so the two show as one.
 */
object Notifications {
    const val ID = 1
    private const val CHANNEL = "otto"

    fun build(ctx: Context): Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Otto", NotificationManager.IMPORTANCE_MIN))
        }
        val show = PendingIntent.getForegroundService(ctx, 1,
            Intent(ctx, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getForegroundService(ctx, 2,
            Intent(ctx, OverlayService::class.java).setAction(OverlayService.ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_screen)
            .setContentTitle("Otto")
            .setContentText("Tap or swipe the handle on the right edge, or tap here")
            .setContentIntent(show)
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .setOngoing(true)
            .build()
    }

    fun startForeground(service: Service) {
        service.startForeground(ID, build(service), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }
}
