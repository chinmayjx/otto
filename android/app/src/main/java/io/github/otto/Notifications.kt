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
            .setContentText("Tap the handle on the right edge (swipe it in for the lens and the assistant), or tap here")
            .setContentIntent(show)
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .setOngoing(true)
            .build()
    }

    private const val MESSAGES = "assistant"
    private var next = 100

    /**
     * Something the assistant said with no call on (the box's device command "notify"): a notification that pops
     * up, its whole text when expanded. Throws when notifications are off, so the assistant knows it wasn't seen.
     */
    fun message(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) throw IllegalStateException("notifications are off for Otto on the phone")
        if (nm.getNotificationChannel(MESSAGES) == null) {
            nm.createNotificationChannel(NotificationChannel(MESSAGES, "Assistant", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "What your assistant says when no call is on"
            })
        }
        nm.notify(next++, Notification.Builder(ctx, MESSAGES)
            .setSmallIcon(R.drawable.ccm_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build())
    }

    fun startForeground(service: Service) {
        service.startForeground(ID, build(service), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }
}
