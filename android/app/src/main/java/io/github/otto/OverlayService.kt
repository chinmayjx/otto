package io.github.otto

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.Manifest
import android.content.pm.PackageManager
import io.github.otto.device.LogReader
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Keeps the second screen at hand over every app: a handle on the right edge (an overlay window, so it stays
 * above the screen too; a tap fades the screen in and out, a long press opens its tray: the lens, the assistant), and the screen itself
 * (Screen), which lives here and is shown in ScreenActivity while it's in. A foreground service, so Android keeps all of it, the browser's tabs
 * included, while other apps are in front.
 *
 * While the handle is on, Otto is connected to the box: this service keeps the tunnel up (Tunnel), and starts the
 * browser's service (BrowserService, in ":browser") as a foreground service too, so the browser stays reachable
 * with the screen closed. Both share one notification.
 */
class OverlayService : Service(), Screen.Host {
    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private var screen: Screen? = null
    private lateinit var rightLp: WindowManager.LayoutParams
    private var right: FrameLayout? = null
    private var launching = false // ScreenActivity was asked for and isn't up yet
    private var holding = false // a finger is on the handle

    override fun onCreate() {
        super.onCreate()
        running = true
        instance = this
        wm = getSystemService(WindowManager::class.java)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        Notifications.startForeground(this)
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        prefs.edit().putBoolean("on", true).apply()
        // A themed context, so the screen's dialogs and the WebViews' pickers have a theme to draw with.
        screen = Screen(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault), this)
        addRightHandle()
        Events.add(this, "handle on")
        Tunnel.start(this, "the handle")
        // Turned on from Otto's screen (the handle switch): the moment Android asks for log consent. Never ask from
        // the background: Android declines and remembers the decline for a minute.
        if (MainActivity.resumed && checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED) LogReader.restart()
        startBrowser()
    }

    /** The browser's process, as a foreground service of its own (it binds to it too, from Screen). */
    private fun startBrowser() {
        try {
            startForegroundService(Intent(this, BrowserService::class.java).setAction(BrowserService.ACTION_KEEP))
        } catch (e: Exception) {
            android.util.Log.w("OverlayService", "browser: $e")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val screen = screen ?: return START_NOT_STICKY
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.edit().putBoolean("on", false).apply()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_OPEN -> intent.getStringExtra("url")?.let { screen.openLink(it) }
            ACTION_SHOW -> screen.open()
            ACTION_RELINK -> {
                Tunnel.relink()
            }
        }
        return START_STICKY
    }

    // --- The screen ------------------------------------------------------------------------------------------

    /**
     * The screen opens in an app screen of its own (ScreenActivity), not an overlay: overlays (other apps' bubbles,
     * this handle) then draw above it, and Back, the keyboard, Home and Recents work as in any app. As soon as it
     * starts to come in, the activity is started; the screen's view moves into it, and out again when it's gone.
     */
    override fun moved(position: Float) {
        if (right == null) return // the screen's first layout, before the handle is up
        if (position > 0) bringScreen()
    }

    override fun settled(open: Boolean) {
        if (open) return
        launching = false
        ScreenActivity.current?.leave()
    }

    /**
     * ScreenActivity to the front: started the first time, after that brought back from the background, where it
     * waits between uses (much quicker than a new one, and the browser stays embedded in it).
     */
    private fun bringScreen() {
        if (launching || ScreenActivity.current?.isShown == true) return
        launching = true
        startActivity(Intent(this, ScreenActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
    }

    /** The activity is up (or gone): a launch is no longer pending. */
    fun activityChanged() {
        launching = false
    }

    // --- The handle ------------------------------------------------------------------------------------------

    /**
     * The handle's look: a pill against the right edge, a little thicker than a swipe strip, to tap. Its strip is
     * the handle's, not the Back gesture's.
     */
    private fun handleView(): FrameLayout {
        val h = FrameLayout(this)
        val pill = View(this)
        pill.background = GradientDrawable().apply {
            setColor(HANDLE)
            cornerRadius = dp(3).toFloat()
            alpha = 230
        }
        h.addView(pill, FrameLayout.LayoutParams(dp(5), dp(76), Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = dp(3) })
        h.contentDescription = "Otto"
        h.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ -> v.systemGestureExclusionRects = listOf(Rect(0, 0, r - l, b - t)) }
        return h
    }

    private fun handleLp(gravity: Int) = WindowManager.LayoutParams(dp(HANDLE_W), dp(HANDLE_H),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT).apply {
        this.gravity = Gravity.TOP or gravity
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    /**
     * The one handle, on the right edge, over the screen too: a tap or a swipe across it fades the screen in, the
     * next fades it out. A drag up or down moves it along the edge. A long press opens the tray (Tray) beside it,
     * and the same finger slides onto the lens (Lens) or the assistant (VoiceService) and lifts there to pick one.
     * With the lens or the tray open, a tap closes it.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun addRightHandle() {
        val handle = handleView()
        right = handle
        rightLp = handleLp(Gravity.END)
        rightLp.title = "Otto handle"
        rightLp.y = clampY(prefs.getInt("y", (wm.currentWindowMetrics.bounds.height() * 0.3f).roundToInt()))
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val pill = handle.getChildAt(0)
        handle.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var startY = 0
            var moving = false // a drag up or down, not a tap
            var swiped = false // a swipe across, which toggles the screen as a tap does
            var pressed = false // held still long enough: the tray, and nothing else for this touch
            var closing = false // a touch while the lens or the tray is open: it closes it, and does nothing else
            val longPress = Runnable {
                pressed = true
                pill.animate().scaleX(1f).scaleY(1f).setDuration(90)
                handle.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                holding = false
                tray(downY)
            }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val screen = screen ?: return false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        closing = Lens.current != null || Tray.current != null
                        if (closing) return true
                        downX = e.rawX
                        downY = e.rawY
                        swiped = false
                        startY = rightLp.y
                        moving = false
                        pressed = false
                        handle.postDelayed(longPress, LONG_PRESS_MS)
                        pill.animate().scaleX(1.5f).scaleY(1.12f).setDuration(90)
                        // The app screen comes up at the touch, so it's there by the time the finger lifts.
                        holding = true
                        if (!screen.isOpen) bringScreen()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (closing) return true
                        if (pressed) {
                            Tray.current?.hover(e.rawX, e.rawY)
                            return true
                        }
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!moving && !swiped && max(abs(dx), abs(dy)) > slop) {
                            // Whichever way it went first decides: across toggles the screen at once, up or down moves the handle.
                            handle.removeCallbacks(longPress)
                            if (abs(dx) > abs(dy)) {
                                swiped = true
                                toggle()
                            } else moving = true
                        }
                        if (moving) moveHandle(startY + dy.roundToInt())
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (closing) {
                            if (e.actionMasked == MotionEvent.ACTION_UP) {
                                Lens.current?.dismiss()
                                Tray.current?.dismiss()
                            }
                            return true
                        }
                        handle.removeCallbacks(longPress)
                        if (pressed) {
                            val moved = max(abs(e.rawX - downX), abs(e.rawY - downY)) > slop
                            if (e.actionMasked == MotionEvent.ACTION_UP) Tray.current?.release(e.rawX, e.rawY, moved)
                            else Tray.current?.dismiss()
                            return true
                        }
                        pill.animate().scaleX(1f).scaleY(1f).setDuration(140)
                        holding = false
                        if (moving) prefs.edit().putInt("y", rightLp.y).apply()
                        else if (!swiped && e.actionMasked == MotionEvent.ACTION_UP) toggle()
                        if (screen.isClosed) ScreenActivity.current?.leave() // moved, not opened
                        return true
                    }
                }
                return false
            }
        })
        wm.addView(handle, rightLp)
    }

    /** The tray, beside the handle at the finger's height: the lens and the assistant. */
    private fun tray(y: Float) {
        val screen = screen
        if (screen == null || screen.isClosed) ScreenActivity.current?.leave() // brought up for this touch, not shown
        Tray(this, listOf(
            Tray.Item(R.drawable.ccm_lens, "Lens") { lens() },
            Tray.Item(R.drawable.ccm_wave, "Assistant") { VoiceService.start(this) },
        )).show(y, dp(HANDLE_W))
    }

    /**
     * The lens, over whatever is on screen: the second screen too, if it's in (its pages and artifacts can be taken
     * like any app's). The lens hides the handle while it takes the screen.
     */
    private fun lens() {
        val screen = screen
        if (screen == null || screen.isClosed) ScreenActivity.current?.leave() // brought up for this touch, not shown
        Lens.open(this, { shown -> right?.visibility = if (shown) View.VISIBLE else View.INVISIBLE }) {}
    }

    private fun toggle() {
        val screen = screen ?: return
        if (screen.isOpen) screen.animateTo(0f) else screen.open()
    }

    /** The handle to height y (clamped). */
    private fun moveHandle(y: Int) {
        rightLp.y = clampY(y)
        wm.updateViewLayout(right, rightLp)
    }

    private fun clampY(y: Int): Int {
        val h = wm.currentWindowMetrics.bounds.height()
        return max(dp(80), min(y, h - dp(HANDLE_H) - dp(100)))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    override fun onDestroy() {
        running = false
        if (instance === this) instance = null
        Lens.current?.dismiss()
        Tray.current?.dismiss()
        ScreenActivity.current?.finish()
        right?.let { if (it.isAttachedToWindow) wm.removeView(it) }
        screen?.destroy()
        Events.add(this, "handle service gone" + if (wanted(this)) "" else " (turned off)")
        if (!wanted(this)) {
            Tunnel.stop()
            LogReader.stop()
        }
        // With the handle off, Otto is off: the browser (unbound by the screen above) and its link stop too.
        stopService(Intent(this, BrowserService::class.java))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        // The handle: lavender, the app's colour; the dev build's is red, to tell the two apart when both are on.
        private val HANDLE = android.graphics.Color.parseColor(if (BuildConfig.DEBUG) "#ff3b30" else "#ab9cf2")
        const val ACTION_START = "start" // the handle, the screen closed
        const val ACTION_SHOW = "show" // the screen, where it was left
        const val ACTION_OPEN = "open" // extra "url": a link from another app (LinkActivity)
        const val ACTION_STOP = "stop"
        const val ACTION_RELINK = "relink" // a new pairing: both links reconnect with it
        const val PREFS = "overlay" // "on": the handle is wanted (BootReceiver brings it back); "y": where it is
        private const val HANDLE_W = 28
        private const val HANDLE_H = 120
        private const val LONG_PRESS_MS = 450L

        @Volatile
        var running = false
            private set
        var instance: OverlayService? = null
            private set

        fun send(ctx: Context, action: String, url: String? = null) {
            val i = Intent(ctx, OverlayService::class.java).setAction(action)
            if (url != null) i.putExtra("url", url)
            ctx.startForegroundService(i)
        }

        /** The handle was on when last seen (and so should be again after a restart). */
        fun wanted(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", false)

        /** A finger is on the handle: the screen may be about to come in (ScreenActivity stays up for it). */
        fun holding() = instance?.holding == true
    }
}
