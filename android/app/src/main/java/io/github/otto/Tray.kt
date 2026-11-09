package io.github.otto

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The handle's tray: what a swipe in on the handle opens, one dark slab that unrolls leftward out of the handle
 * (=|: the lens next to the handle, then the assistant). The handle shrinks to the slab's height as it opens and is
 * its lavender right edge, so the two read as one shape; the slab draws its own copy of that edge, being a window of
 * its own above the handle's. One gesture: the finger that swiped goes on left onto an item (it lights up, with its
 * name above it) and lifts there to pick it; lifted anywhere else (a flick), the tray stays, to tap an item (or
 * outside, to close it), as the second screen's menus do (Screen.onPress).
 *
 * The handle's touch listener keeps the gesture (Android sends the moves to the window the finger came down on) and
 * passes them here: [hover] and [release]. [closed] runs once the slab has rolled back into the handle.
 */
class Tray(private val ctx: Context, private val items: List<Item>, private val edge: Int, private val closed: () -> Unit) {
    class Item(val icon: Int, val label: String, val run: () -> Unit)

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val cells = mutableListOf<ImageView>()
    private var hot = -1
    private var closing = false
    private lateinit var root: FrameLayout
    private lateinit var slab: Slab
    private lateinit var tip: TextView

    /** Opens it with the slab's top at [top] on the screen (the handle centres its shrunk pill there). */
    @SuppressLint("ClickableViewAccessibility")
    fun show(top: Int) {
        current?.dismiss(false)
        current = this
        root = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
        // The name of the item under the finger, above it.
        tip = TextView(ctx).apply {
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(BrowserService.TEXT)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply { setColor(BrowserService.FIELD); cornerRadius = dp(8).toFloat() }
            elevation = dp(6).toFloat()
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(tip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        slab = Slab(ctx)
        // Right to left from the handle: the first item is the nearest.
        items.forEachIndexed { i, item ->
            val b = ImageView(ctx).apply {
                setImageResource(item.icon)
                scaleType = ImageView.ScaleType.CENTER
                imageTintList = ColorStateList.valueOf(BrowserService.STEEL)
                isClickable = true
                contentDescription = item.label
                setOnClickListener { pick(i) }
            }
            cells += b
            slab.addView(b, FrameLayout.LayoutParams(dp(CELL_W), dp(SLAB_H), Gravity.TOP or Gravity.END).apply {
                rightMargin = dp(EDGE_PAD) + i * dp(CELL_W)
            })
        }
        root.addView(slab, FrameLayout.LayoutParams(dp(EDGE_PAD) + items.size * dp(CELL_W), dp(SLAB_H), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(TIP_H)
            rightMargin = dp(EDGE_INSET)
        })
        // Room for a name wider than its cell left of the slab, and for the slab's shadow below it.
        root.setPadding(dp(TIP_ROOM), 0, 0, dp(SHADOW))
        root.setOnTouchListener { _, e ->
            // A tap elsewhere, or on the tray but off its items, with the tray left open.
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE || e.actionMasked == MotionEvent.ACTION_DOWN) dismiss()
            true
        }
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            // Against the right edge, as the handle is, so the slab's edge lands on the handle's pill.
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = top - dp(TIP_H)
            title = "Otto tray"
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        wm.addView(root, lp)
        // Unrolled from the right: a clip, not a scale, so the icons don't squash.
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = OPEN_MS
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { slab.reveal = it.animatedValue as Float }
            start()
        }
    }

    /** The finger at a point on the screen: the item under it lights up (with a tick as it changes). */
    fun hover(x: Float, y: Float) {
        if (closing) return
        val i = itemAt(x, y)
        if (i == hot) return
        hot = i
        light(i)
        if (i >= 0) root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** The finger lifted: on an item, it's picked; anywhere else, the tray stays, to tap. */
    fun release(x: Float, y: Float) {
        val i = itemAt(x, y)
        if (i >= 0) pick(i)
        else if (hot >= 0) { hot = -1; light(-1) }
    }

    private fun pick(i: Int) {
        if (closing) return
        root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        dismiss { items[i].run() }
    }

    /** Item [i] lit (a lavender highlight, its icon dark, its name above it), the others dark; -1 for none. */
    private fun light(i: Int) {
        slab.hot = i
        cells.forEachIndexed { k, b -> b.imageTintList = ColorStateList.valueOf(if (k == i) BrowserService.ON_ACCENT else BrowserService.STEEL) }
        if (i < 0) {
            tip.animate().alpha(0f).setDuration(FADE_MS)
            return
        }
        tip.text = items[i].label
        // Centred over the cell, 8 dp above the slab, inside the tray's window.
        tip.post {
            val b = cells[i]
            val centre = slab.left + b.left + b.width / 2f
            tip.x = (centre - tip.width / 2f).coerceIn(0f, (root.width - tip.width).toFloat().coerceAtLeast(0f))
            tip.y = slab.top - dp(TIP_GAP) - tip.height.toFloat()
            if (tip.alpha < 0.5f) tip.translationY = dp(4).toFloat()
            tip.animate().alpha(1f).translationY(0f).setDuration(FADE_MS)
        }
    }

    private fun itemAt(x: Float, y: Float): Int {
        val loc = IntArray(2)
        cells.forEachIndexed { i, v ->
            v.getLocationOnScreen(loc)
            // The slab is dragged along sideways; plenty of give up and down (the finger came down anywhere on the
            // handle's strip, which is taller than the slab).
            if (x >= loc[0] && x < loc[0] + v.width && y >= loc[1] - dp(56) && y < loc[1] + v.height + dp(56)) return i
        }
        // Between the nearest item and the handle, or past the last: nothing.
        return -1
    }

    /** Rolls the slab back into the handle, then takes the window away and runs [then]; at once if not [animate]. */
    fun dismiss(animate: Boolean = true, then: (() -> Unit)? = null) {
        if (current === this) current = null
        if (!::root.isInitialized || !root.isAttachedToWindow || closing) return
        closing = true
        val gone: () -> Unit = {
            if (root.isAttachedToWindow) wm.removeView(root)
            closed()
            then?.invoke()
        }
        if (!animate) return gone()
        tip.animate().alpha(0f).setDuration(CLOSE_MS / 2)
        ValueAnimator.ofFloat(slab.reveal, 0f).apply {
            duration = CLOSE_MS
            interpolator = AccelerateInterpolator(1.5f)
            addUpdateListener { slab.reveal = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) = gone()
            })
            start()
        }
    }

    /**
     * The slab: dark, rounded on the left only, square against the handle; [reveal] of it shows, from the right.
     * The lit item's highlight (lavender, inset 4 dp) fades in under its icon, and the handle's edge is on the right.
     */
    private inner class Slab(ctx: Context) : FrameLayout(ctx) {
        var reveal = 0f
            set(v) { field = v; invalidate() }
        var hot = -1
            set(v) { field = v; invalidate() }
        private val lit = FloatArray(items.size)
        private var lastFrame = 0L
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BrowserService.FIELD
            setShadowLayer(dp(10).toFloat(), 0f, dp(2).toFloat(), Color.argb(90, 0, 0, 0))
        }
        private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BrowserService.ACCENT }
        private val edgePaint = Paint().apply { color = edge; alpha = 230 }
        private val shape = Path()
        private val r = RectF()

        init {
            setWillNotDraw(false)
        }

        override fun dispatchDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            // The highlight fades from item to item in about 120 ms.
            val now = System.nanoTime()
            val step = if (lastFrame == 0L) 1f else min(1f, (now - lastFrame) / 1e6f / FADE_MS)
            lastFrame = now
            var moving = false
            for (i in lit.indices) {
                val want = if (i == hot) 1f else 0f
                lit[i] = if (lit[i] < want) min(want, lit[i] + step) else max(want, lit[i] - step)
                if (lit[i] != want) moving = true
            }
            if (!moving) lastFrame = 0L
            if (reveal > 0f) {
                val left = w * (1 - reveal)
                val rad = min(dp(SLAB_H / 2).toFloat(), (w - left) / 2)
                shape.reset()
                r.set(left, 0f, w, h)
                shape.addRoundRect(r, floatArrayOf(rad, rad, 0f, 0f, 0f, 0f, rad, rad), Path.Direction.CW)
                c.drawPath(shape, fill)
                c.save()
                c.clipPath(shape)
                for (i in lit.indices) {
                    if (lit[i] <= 0f) continue
                    val b = cells[i]
                    val s = 0.85f + 0.15f * lit[i]
                    val cx = b.left + b.width / 2f
                    val cy = b.top + b.height / 2f
                    val hw = (b.width / 2f - dp(4)) * s
                    val hh = (b.height / 2f - dp(4)) * s
                    r.set(cx - hw, cy - hh, cx + hw, cy + hh)
                    accent.alpha = (255 * lit[i]).roundToInt()
                    c.drawRoundRect(r, dp(18) * s, dp(18) * s, accent)
                }
                super.dispatchDraw(c)
                c.restore()
            }
            // The handle's edge: its pill, shrunk to the slab's height and squared off (the real one is under it).
            c.drawRect(w - dp(PILL_W), 0f, w, h, edgePaint)
            if (moving) postInvalidateOnAnimation()
        }
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()

    companion object {
        const val SLAB_H = 44
        private const val CELL_W = 48
        private const val PILL_W = 5 // the handle's pill, as OverlayService draws it
        private const val EDGE_INSET = 3 // the pill's distance from the screen's edge (OverlayService)
        private const val EDGE_PAD = 9 // the slab's right padding: the pill and a 4 dp gap
        private const val TIP_GAP = 8 // between the name and the slab
        private const val TIP_H = 44 // the name's room above the slab
        private const val TIP_ROOM = 40 // and to its left, for a name wider than its cell
        private const val SHADOW = 14 // the slab's shadow's room below it
        private const val OPEN_MS = 170L
        private const val CLOSE_MS = 120L
        private const val FADE_MS = 120L

        var current: Tray? = null
            private set
    }
}
