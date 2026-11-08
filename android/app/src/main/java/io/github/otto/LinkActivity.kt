package io.github.otto

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

/**
 * Where a link tapped in any app lands. Invisible: a link to the paired box's hub or the artifact hub goes to the
 * second screen, which slides in over the app it was tapped in; any other link (the manifest claims every
 * *.ts.net name on those routes, and not every one is the paired box's) goes on to the browser. Without the
 * overlay permission it opens the setup screen instead.
 */
class LinkActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val url = intent.dataString
        when {
            url == null -> {}
            !Links.isOurs(this, Uri.parse(url)) -> Links.openElsewhere(this, url)
            Settings.canDrawOverlays(this) -> OverlayService.send(this, OverlayService.ACTION_OPEN, url)
            else -> startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
