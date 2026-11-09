package io.github.otto.device

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.otto.BrowserService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * "Otto is using your phone": while the box drives the phone (Commands: taps, typing, swipes, keys, launches), a
 * slowly turning lavender border runs round the screen's edge with a faint wash, each of the agent's touches leaves a
 * small ripple, and a chip at the top says so, with a Stop button. Only the chip takes touches: the phone can be used
 * as usual under the rest. It stays while commands keep coming and fades out a few seconds after the last one.
 *
 * Stop puts it away and refuses the agent's input for ten minutes (reads, like the screen and its tree, still work);
 * after that, the next command brings it back. It's left out of what the agent sees: screenshots are taken without
 * it ([hidden]) and its windows are left out of the UI tree ([isOurs]).
 *
 * Its windows are accessibility overlays when Otto's accessibility service is on (above everything, the system's bars
 * and the keyboard too), app overlays otherwise.
 */
object AgentOverlay {
    private const val STOP_MS = 10 * 60 * 1000L
    private const val LINGER_MS = 8000L
    private const val FADE_MS = 300L
    private const val RIPPLE_MS = 900L
    /** The class name its windows' views give the accessibility tree, to tell them apart. */
    private const val MARK = "io.github.otto.AgentOverlay"
    private val COLORS = intArrayOf(0xffab9cf2.toInt(), 0xff7aa2ff.toInt(), 0xfff29cd8.toInt(), 0xffab9cf2.toInt(),
        0xff7aa2ff.toInt(), 0xfff29cd8.toInt(), 0xffab9cf2.toInt())
    private val STOP = Color.parseColor("#ff6b81")
    private val ON_STOP = Color.parseColor("#1a0b0e")

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stoppedUntil = 0L
    private var wm: WindowManager? = null
    private var edge: Edge? = null
    private var chip: View? = null
    private var chipLp: WindowManager.LayoutParams? = null
    private val fade = Runnable { hide() }

    /**
     * An input command is about to run: refused if the user stopped the agent, else the overlay shows (or stays).
     * Called on the command's thread.
     */
    fun driving(ctx: Context) {
        if (System.currentTimeMillis() < stoppedUntil) throw Exception("The user stopped the agent from using the phone. Ask them before trying again.")
        main.post {
            main.removeCallbacks(fade)
            show(ctx.applicationContext)
        }
    }

    /** An input command is over: the overlay goes a while after the last one. */
    fun done() = main.post {
        main.removeCallbacks(fade)
        if (edge != null) main.postDelayed(fade, LINGER_MS)
    }.let {}

    /**
     * Runs [gesture], the agent's touch from (x1, y1) to (x2, y2) on the screen: a ripple where it starts, and the chip
     * lets touches through meanwhile if the gesture lands on it (the agent doesn't see the chip, so it may aim under it).
     */
    fun <T> touching(x1: Float, y1: Float, x2: Float, y2: Float, gesture: () -> T): T {
        val onChip = onMain {
            edge?.ripple(x1, y1)
            val c = chip
            val lp = chipLp
            val r = Rect()
            if (c == null || lp == null || !c.isAttachedToWindow || !c.getGlobalVisibleRect(r)) return@onMain false
            val loc = IntArray(2)
            c.getLocationOnScreen(loc)
            r.set(loc[0], loc[1], loc[0] + c.width, loc[1] + c.height)
            if (!r.contains(x1.toInt(), y1.toInt()) && !r.contains(x2.toInt(), y2.toInt())) return@onMain false
            touchable(false)
            true
        } ?: false
        try {
            if (onChip) Thread.sleep(30) // the change reaches input dispatch
            return gesture()
        } finally {
            if (onChip) main.post { touchable(true) }
        }
    }

    /** Runs [f] (a screenshot) with the overlay out of sight, then brings it back. Not on the main thread. */
    fun <T> hidden(f: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return f()
        val done = CountDownLatch(1)
        var hid = false
        main.post {
            val views = listOfNotNull(edge, chip).filter { it.isAttachedToWindow }
            if (views.isEmpty()) return@post done.countDown()
            hid = true
            var left = views.size
            for (v in views) {
                v.animate().cancel()
                v.alpha = 0f
                v.viewTreeObserver.registerFrameCommitCallback { if (--left == 0) done.countDown() }
                v.invalidate()
            }
        }
        done.await(400, TimeUnit.MILLISECONDS)
        if (hid) Thread.sleep(32) // the frame without it on the display
        try {
            return f()
        } finally {
            if (hid) main.post { listOfNotNull(edge, chip).forEach { it.alpha = 1f } }
        }
    }

    /** The window is one of the overlay's (for the UI tree, which leaves them out). */
    fun isOurs(w: AccessibilityWindowInfo): Boolean =
        w.title?.toString()?.startsWith("Otto agent") == true || w.root?.className?.toString() == MARK

    private fun <T> onMain(f: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return f()
        var r: T? = null
        val done = CountDownLatch(1)
        main.post {
            try {
                r = f()
            } finally {
                done.countDown()
            }
        }
        done.await(1, TimeUnit.SECONDS)
        return r
    }

    private fun show(app: Context) {
        if (edge != null) return
        // Its windows: accessibility overlays from the service if it's on, else app overlays if Otto may draw them.
        val a11y = AgentAccessibility.instance
        val ctx: Context = a11y ?: app
        val type = if (a11y != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else if (Settings.canDrawOverlays(app)) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else return
        val wm = ctx.getSystemService(WindowManager::class.java)
        this.wm = wm
        val d = ctx.resources.displayMetrics.density
        val display = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val corner = display?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: (24 * d)

        // The edge and the wash: the whole screen, taking no touches.
        val e = Edge(ctx, corner)
        e.alpha = 0f
        wm.addView(e, WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            title = "Otto agent edge"
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        })
        edge = e
        e.animate().alpha(1f).setDuration(FADE_MS)

        // The chip, below the status bar.
        val c = chip(ctx, d)
        c.alpha = 0f
        val top = wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = top
            title = "Otto agent"
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        wm.addView(c, lp)
        chip = c
        chipLp = lp
        c.animate().alpha(1f).setDuration(FADE_MS)
    }

    /** Fades out and takes the windows away. */
    private fun hide() {
        main.removeCallbacks(fade)
        val wm = wm ?: return
        for (v in listOfNotNull(edge, chip)) {
            v.animate().alpha(0f).setDuration(FADE_MS).withEndAction { if (v.isAttachedToWindow) wm.removeView(v) }
        }
        edge = null
        chip = null
        chipLp = null
    }

    /** Stop: the agent may not use the phone for a while. */
    private fun stop() {
        stoppedUntil = System.currentTimeMillis() + STOP_MS
        CommandLog.add("the user stopped the agent (input refused for ten minutes)")
        hide()
    }

    private fun touchable(on: Boolean) {
        val c = chip ?: return
        val lp = chipLp ?: return
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (c.isAttachedToWindow) wm?.updateViewLayout(c, lp)
    }

    /** The chip: a pulsing dot, what's going on, and Stop; in a frame with room for its shadow. */
    private fun chip(ctx: Context, d: Float): View {
        fun dp(v: Float) = (v * d).roundToInt()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(6f), dp(6f), dp(6f))
            background = GradientDrawable().apply { setColor(BrowserService.FIELD); cornerRadius = dp(20f).toFloat() }
            elevation = dp(8f).toFloat()
        }
        row.addView(Dot(ctx), LinearLayout.LayoutParams(dp(20f), dp(20f)))
        row.addView(TextView(ctx).apply {
            text = "Otto is using your phone"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(BrowserService.TEXT)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6f) })
        row.addView(TextView(ctx).apply {
            text = "Stop"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(ON_STOP)
            setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
            background = GradientDrawable().apply { setColor(STOP); cornerRadius = dp(14f).toFloat() }
            contentDescription = "Stop Otto from using your phone"
            setOnClickListener { stop() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10f) })
        return object : FrameLayout(ctx) {
            override fun getAccessibilityClassName(): CharSequence = MARK
        }.apply {
            clipToPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES // the window's root, with MARK as its class
            setPadding(dp(10f), dp(8f), dp(10f), dp(12f))
            addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    /** The chip's dot: lavender, with a ring going out from it every 1.4 s. */
    private class Dot(ctx: Context) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BrowserService.ACCENT }
        private val t0 = System.nanoTime()

        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val u = ((System.nanoTime() - t0) / 1e6f % 1400f) / 1400f
            val k = min(1f, u / 0.7f)
            paint.alpha = ((1 - k) * 0.6f * 255).roundToInt()
            c.drawCircle(cx, cy, 4 * d + 6 * d * k, paint)
            paint.alpha = 255
            c.drawCircle(cx, cy, 4 * d, paint)
            if (isAttachedToWindow) postInvalidateOnAnimation()
        }
    }

    /**
     * The screen's edge: a faint wash over it all, a soft inner glow that breathes (2.4 s), a thin border of lavender,
     * blue and pink that turns once every 3.2 s, following the display's rounded corners, and the agent's touches.
     */
    private class Edge(ctx: Context, private val corner: Float) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val t0 = System.nanoTime()
        private val wash = Color.argb(26, 28, 22, 60)
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = BrowserService.ACCENT }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; color = BrowserService.ACCENT }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BrowserService.ACCENT }
        private val turn = Matrix()
        private val r = RectF()
        private var sweep: SweepGradient? = null
        private val ripples = ArrayList<FloatArray>() // x, y (in the view), start (ms)

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        override fun getAccessibilityClassName(): CharSequence = MARK

        /** A touch of the agent's at (x, y) on the screen. */
        fun ripple(x: Float, y: Float) {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            ripples += floatArrayOf(x - loc[0], y - loc[1], now())
            invalidate()
        }

        private fun now() = ((System.nanoTime() - t0) / 1e6).toFloat()

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            sweep = SweepGradient(w / 2f, h / 2f, COLORS, null)
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val ms = now()
            c.drawColor(wash)
            // The glow: concentric strokes fading inwards, from 26 dp deep at .55 to 14 dp at .35 and back.
            val b = (1 - cos(2 * PI * ms / 2400.0).toFloat()) / 2
            val depth = (26 - 12 * b) * d
            val strength = 0.55f - 0.2f * b
            val n = 10
            glow.strokeWidth = depth / n + 1
            for (k in 0 until n) {
                val inset = (k + 0.5f) * depth / n
                glow.alpha = (255 * strength * (1 - k / n.toFloat()).pow(2)).roundToInt()
                r.set(inset, inset, w - inset, h - inset)
                val rad = max(0f, corner - inset)
                c.drawRoundRect(r, rad, rad, glow)
            }
            // The border, turning.
            sweep?.let {
                turn.setRotate(360f * (ms % 3200f) / 3200f, w / 2, h / 2)
                it.setLocalMatrix(turn)
                border.shader = it
            }
            val half = 1.5f * d
            r.set(half, half, w - half, h - half)
            c.drawRoundRect(r, max(0f, corner - half), max(0f, corner - half), border)
            // The agent's touches: a ring growing and fading.
            val it = ripples.iterator()
            while (it.hasNext()) {
                val p = it.next()
                val u = (ms - p[2]) / RIPPLE_MS
                if (u >= 1) {
                    it.remove()
                    continue
                }
                val e = 1 - (1 - u).pow(3)
                val rad = 15 * d * (0.3f + 1.1f * e)
                val a = 1 - e
                dot.alpha = (255 * 0.25f * a).roundToInt()
                ring.alpha = (255 * a).roundToInt()
                c.drawCircle(p[0], p[1], rad, dot)
                c.drawCircle(p[0], p[1], rad, ring)
            }
            if (isAttachedToWindow) postInvalidateOnAnimation()
        }
    }
}
