package io.github.otto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Brings the handle (and with it the links to the box) back after a restart or an app update, if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (OverlayService.wanted(ctx) && Settings.canDrawOverlays(ctx)) OverlayService.send(ctx, OverlayService.ACTION_START)
    }
}
