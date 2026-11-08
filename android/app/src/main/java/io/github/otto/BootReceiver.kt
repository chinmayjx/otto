package io.github.otto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Brings the handle (and with it the tunnel to the box) back after a restart or an app update, if it was on.
 * An update through Otto itself ends with MY_PACKAGE_REPLACED here; the accessibility service, which Android
 * binds again after an update, is the second way back (AgentAccessibility.onServiceConnected).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val wanted = OverlayService.wanted(ctx)
        Events.add(ctx, "${intent.action?.substringAfterLast('.')}: version ${BuildConfig.VERSION_CODE}, handle ${if (wanted) "wanted" else "off"}")
        if (wanted) restore(ctx, "receiver")
    }

    companion object {
        /** Starts the handle's service, if it's wanted and isn't running; says why not in the events log. */
        fun restore(ctx: Context, from: String) {
            if (!OverlayService.wanted(ctx) || OverlayService.running) return
            if (!Settings.canDrawOverlays(ctx)) return Events.add(ctx, "$from: can't draw over other apps; the handle stays off")
            try {
                OverlayService.send(ctx, OverlayService.ACTION_START)
                Events.add(ctx, "$from: started the handle")
            } catch (e: Exception) {
                Events.add(ctx, "$from: couldn't start the handle: $e")
            }
        }
    }
}
