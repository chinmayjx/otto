package io.github.otto

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/**
 * The second screen while it's in: a transparent app screen holding Screen's view, over the app it was pulled in
 * over. An app screen rather than an overlay, so other apps' overlays (and the handle) draw above it, and Back,
 * the keyboard, Home and Recents treat it as an app. Screen moves it in and out; the activity only hosts it.
 *
 * It's started once and then kept: when the screen is out it waits in the background (its own task, out of
 * Recents) and OverlayService brings it back to the front, at the first touch on the handle, which is much
 * quicker than starting it anew and keeps the browser embedded in it. Brought up for a touch, it stays unseen (its
 * window fully transparent) until the screen starts to come in ([reveal]): a swipe or a long press on the handle
 * (the tray, the lens, the assistant) brings it up and sends it back without anything showing.
 */
class ScreenActivity : Activity() {
    private lateinit var frame: FrameLayout

    /** In front, from onResume until leave(). */
    var isShown = false
        private set

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.setDecorFitsSystemWindows(false)
        frame = FrameLayout(this)
        setContentView(frame)
        val screen = Screen.current
        if (screen == null) {
            finish()
            return
        }
        current = this
        seen(!screen.isClosed)
        screen.attach(frame)
    }

    override fun onStart() {
        super.onStart()
        // Back to the front for a touch on the handle: unseen until the screen moves.
        seen(Screen.current?.isClosed == false)
    }

    /** The screen is coming in: its window shows. */
    fun reveal() = seen(true)

    /** The whole window, the browser's surface in it included, shown or fully transparent. */
    private fun seen(on: Boolean) {
        val a = if (on) 1f else 0f
        val lp = window.attributes
        if (lp.alpha == a) return
        lp.alpha = a
        window.attributes = lp
    }

    override fun onResume() {
        super.onResume()
        isShown = true
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        OverlayService.instance?.activityChanged()
        // Brought up for a pull that has since ended without opening the screen (or a screen let go already).
        if (Screen.current?.isClosed == true && !OverlayService.holding()) leave()
    }

    /** To the background, at once and without the system's animation: Screen has already faded the screen out. */
    fun leave() {
        if (!isShown) return
        isShown = false
        seen(false)
        moveTaskToBack(true)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onStop() {
        super.onStop()
        // Home, Recents, or another app in front: the screen is put away, as an app would be.
        if (isShown) {
            isShown = false
            Screen.current?.closeNow()
        }
    }

    @Deprecated("Back that no view took (the screen's Root takes it first)")
    override fun onBackPressed() {
        val screen = Screen.current
        if (screen != null) screen.back() else leave()
    }

    override fun onDestroy() {
        if (::frame.isInitialized) Screen.current?.detach(frame)
        if (current === this) current = null
        OverlayService.instance?.activityChanged()
        super.onDestroy()
    }

    companion object {
        var current: ScreenActivity? = null
            private set
    }
}
