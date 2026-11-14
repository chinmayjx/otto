package io.github.otto

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.OverScroller
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where the browser's pages are, and Desktop site's laptop screen (the same idea as the box's VNC viewer,
 * web/desktop.js).
 *
 * A tab in desktop mode is laid out on a fixed laptop screen, SCREEN_W × SCREEN_H CSS px, rather than at a desktop
 * width squeezed into the phone's width (tiny text). Its WebView is that screen: as tall as the free area, and as
 * wide as the screen's shape makes it, so the page's own fit-to-width (overview) mode gives it the fit-the-height
 * scale and the page sees a 1536×864 window, 100vh and all. The phone shows a slice of it, panned sideways:
 *   one-finger drag up or down   the page's own scrolling, as ever
 *   one-finger drag sideways     pans the screen (with a fling), unless the page itself scrolls that way; decided
 *                                by the first movement past the touch slop, and only before a long-press, which
 *                                stays the page's (selecting text)
 *   pinch                        off: the zoom is the screen's; the zoom-out button shows the whole screen instead
 * Under it, a strip: a picture of the whole screen with the slice in view marked (tap to bring the slice there,
 * centred; drag to move it), and the zoom-out button, which shows the whole screen fitted to the phone's width;
 * tapped again, back to fit-the-height at the same place.
 * The keyboard doesn't change the zoom (the screen keeps the height it has without it): the screen moves up
 * instead, so the field being typed into stays above the keyboard. The strip steps aside meanwhile.
 *
 * Tabs in mobile mode fill it as before. Every tab's WebView is in here, stacked (see BrowserService.live), each
 * laid out as its own mode wants; the strip follows the current tab.
 */
@SuppressLint("ViewConstructor")
class DesktopStage(ctx: Context, private val tabs: () -> List<BrowserService.Tab>, private val current: () -> BrowserService.Tab?) :
    FrameLayout(ctx) {
    /** The strip under the screen: goes in the same parent, at its bottom (BrowserService.build). */
    val strip: View = Strip(ctx)

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val longPress = ViewConfiguration.getLongPressTimeout()

    private var fullH = 0 // the free area's height without the keyboard: what the zoom is fitted to (fitH in desktop.js)
    private var lastW = 0
    private var shrunkAt = 0L // when the area last got shorter without the keyboard showing in the insets (yet)
    private var keyboard = false
    private var whole = false // zoomed out: the whole screen, fitted to the width
    private var lift = 0f // keyboard up: how far the screen is moved up, to keep the field typed into above it
    private var viewW = 0 // the part of the phone the screen shows in
    private var viewH = 0
    private var stripH = 0
    private var screenH = 0 // a desktop tab's WebView, in device px (the whole laptop screen)
    private var screenW = 0

    private fun desk(): BrowserService.Tab? = current()?.takeIf { it.desktop && it.web != null }

    /**
     * Lay the tabs out for the area as it is now (the pages' size, the keyboard, the current tab's mode). Called
     * whenever one of those changes.
     */
    fun arrange() {
        val root = parent as? View ?: return
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0) return
        // The keyboard: the insets say so, though they may come a moment after the area shrinks for it; until then
        // a shorter area of the same width is taken as the keyboard, so the zoom doesn't jump meanwhile.
        val ime = root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        val now = SystemClock.uptimeMillis()
        val shrunk = w == lastW && fullH > 0 && h < fullH - dp(120)
        if (shrunk && !ime && shrunkAt == 0L) {
            shrunkAt = now
            postDelayed({ arrange() }, SETTLE_MS + 50)
        }
        if (!shrunk) shrunkAt = 0L
        val was = keyboard
        keyboard = ime || shrunk && now - shrunkAt < SETTLE_MS
        if (!keyboard || w != lastW) fullH = h
        lastW = w

        val tab = desk()
        val showStrip = tab != null && !keyboard
        stripH = stripHeight(w)
        strip.visibility = if (showStrip) VISIBLE else GONE
        strip.layoutParams?.let { if (it.height != stripH) { it.height = stripH; strip.layoutParams = it } }
        val lp = layoutParams as LayoutParams
        val bottom = if (showStrip) stripH else 0
        if (lp.bottomMargin != bottom) {
            lp.bottomMargin = bottom
            layoutParams = lp
        }
        viewW = w
        viewH = h - bottom
        screenH = max(1, fullH - stripH)
        screenW = (screenH.toFloat() * SCREEN_W / SCREEN_H).roundToInt()
        for (t in tabs()) {
            val web = t.web ?: continue
            val p = web.layoutParams as LayoutParams
            val pw = if (t.desktop) screenW else ViewGroup.LayoutParams.MATCH_PARENT
            val ph = if (t.desktop) screenH else ViewGroup.LayoutParams.MATCH_PARENT
            if (p.width != pw || p.height != ph) {
                p.width = pw
                p.height = ph
                web.layoutParams = p
            }
            if (!t.desktop) {
                web.translationX = 0f
                web.translationY = 0f
                web.scaleX = 1f
                web.scaleY = 1f
            } else if (t !== tab) place(t) // stacked under the current one: kept where its pan has it
            web.setOnScrollChangeListener { _, _, _, _, _ -> if (t === current()) pictureSoon() }
        }
        if (keyboard && !was) follow()
        if (!keyboard) lift = 0f
        tab?.let { place(it) }
        if (showStrip) pictureSoon()
    }

    /** The strip's height for a pages area w px wide: the picture keeps the screen's shape, beside the button. */
    private fun stripHeight(w: Int): Int {
        val pic = (w - dp(BUTTON_DP) - dp(PAD_DP) * 2) * SCREEN_H / SCREEN_W
        return min(pic, (fullH * 0.24f).roundToInt()) + dp(PAD_DP) * 2
    }

    private fun maxPan() = max(0f, (screenW - viewW).toFloat())

    /** Put a desktop tab's screen where its pan, the zoom and the keyboard have it. */
    private fun place(t: BrowserService.Tab) {
        val web = t.web ?: return
        t.pan = t.pan.coerceIn(0f, maxPan())
        val k = if (whole) min(1f, viewW.toFloat() / screenW) else 1f
        web.pivotX = 0f
        web.pivotY = 0f
        web.scaleX = k
        web.scaleY = k
        web.translationX = if (whole || maxPan() == 0f) (viewW - screenW * k) / 2 else -t.pan
        web.translationY = top(k) - lift
        if (t === current()) strip.invalidate()
    }

    /** Where the screen's top is with the keyboard down: zoomed out, centred in the area. */
    private fun top(k: Float) = if (whole) max(0f, (fullH - stripH - screenH * k) / 2) else 0f

    private fun panTo(px: Float) {
        val t = desk() ?: return
        t.pan = px.coerceIn(0f, maxPan())
        place(t)
    }

    // The keyboard: keep the field being typed into above it.

    private val follow = Runnable { follow() }

    /** Ask the page where its focused element is, and lift the screen just enough to show it above the keyboard. */
    private fun follow() {
        val t = desk() ?: return
        val web = t.web ?: return
        web.evaluateJavascript(
            "(() => { const e = document.activeElement; if (!e || e === document.body) return null;" +
                " const r = e.getBoundingClientRect(); return [r.top, r.bottom]; })()",
        ) { v ->
            if (!keyboard || desk() !== t) return@evaluateJavascript
            val r = try {
                JSONArray(v)
            } catch (_: Exception) {
                return@evaluateJavascript
            }
            val k = if (whole) min(1f, viewW.toFloat() / screenW) else 1f
            val s = screenH.toFloat() / SCREEN_H * k // device px per CSS px, as shown
            val top = top(k)
            val fieldTop = top + r.optDouble(0).toFloat() * s
            val fieldBottom = top + r.optDouble(1).toFloat() * s
            var to = max(0f, fieldBottom - (viewH - dp(16)))
            if (fieldTop - to < dp(8)) to = fieldTop - dp(8) // taller than the room left: its top shows
            lift = to.coerceIn(0f, max(0f, top + screenH * k - viewH))
            place(t)
        }
    }

    // Gestures: one-finger sideways drags pan; everything else is the page's.

    private class Gesture(val x0: Float, val y0: Float, val t0: Long) {
        var x = x0
        var panning = false
        var page = false // decided: the page's
    }

    private var g: Gesture? = null
    private var velocity: VelocityTracker? = null
    private val scroller = OverScroller(ctx)
    private val flinging = object : Runnable {
        override fun run() {
            if (!scroller.computeScrollOffset()) return
            panTo(scroller.currX.toFloat())
            postOnAnimation(this)
        }
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        val tab = desk()
        val web = tab?.web
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            scroller.forceFinished(true)
            velocity?.recycle()
            velocity = null
            g = if (web != null && !whole && maxPan() > 0f) Gesture(e.x, e.y, e.eventTime) else null
            if (g != null) velocity = VelocityTracker.obtain()
        }
        val g = g
        if (g == null || web == null) {
            if (keyboard && e.actionMasked == MotionEvent.ACTION_UP) postDelayed(follow, FOLLOW_MS)
            return super.dispatchTouchEvent(e)
        }
        velocity?.addMovement(e)
        if (g.panning) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    panTo(tab.pan + g.x - e.x)
                    g.x = e.x
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val v = velocity
                    if (e.actionMasked == MotionEvent.ACTION_UP && v != null) {
                        v.computeCurrentVelocity(1000)
                        scroller.fling(tab.pan.roundToInt(), 0, -v.xVelocity.roundToInt(), 0, 0, maxPan().roundToInt(), 0, 0)
                        postOnAnimation(flinging)
                    }
                    this.g = null
                    pictureSoon()
                }
            }
            return true
        }
        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) g.page = true
        if (!g.page && e.actionMasked == MotionEvent.ACTION_MOVE) {
            val dx = e.x - g.x0
            val dy = e.y - g.y0
            if (hypot(dx, dy) > slop) {
                // Sideways, and early: a long-press and then a drag is the page's (selecting text). A page that
                // scrolls sideways itself (wider than the screen) keeps its drags.
                if (abs(dx) > abs(dy) && e.eventTime - g.t0 < longPress && !web.canScrollHorizontally(if (dx < 0) 1 else -1)) {
                    g.panning = true
                    g.x = e.x
                    val cancel = MotionEvent.obtain(e)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel) // the page lets go of the touch
                    cancel.recycle()
                    return true
                }
                g.page = true
            }
        }
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            this.g = null
            // A tap with the keyboard up may have moved the focus to another field.
            if (keyboard && e.actionMasked == MotionEvent.ACTION_UP) postDelayed(follow, FOLLOW_MS)
        }
        return super.dispatchTouchEvent(e)
    }

    // The minimap's picture: the current desktop tab's whole screen, drawn small once scrolling or loading settles.

    private var picture: Bitmap? = null
    private var pictureOf: WebView? = null
    private var pictureAt = 0L
    private val takePicture = Runnable { takePicture() }

    /** Redraw the picture soon: a moment after the last change, and not more than once a second. */
    fun pictureSoon() {
        removeCallbacks(takePicture)
        val wait = max(SETTLE_PICTURE_MS, PICTURE_EVERY_MS - (SystemClock.uptimeMillis() - pictureAt))
        postDelayed(takePicture, wait)
    }

    private fun takePicture() {
        val web = desk()?.web ?: return
        if (strip.visibility != VISIBLE || g != null || !scroller.isFinished || screenW == 0) return
        val r = pictureRect()
        val w = r.width().roundToInt()
        val h = r.height().roundToInt()
        if (w <= 0 || h <= 0) return
        pictureAt = SystemClock.uptimeMillis()
        val b = picture?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(BrowserService.FIELD)
        c.scale(w.toFloat() / web.width, h.toFloat() / web.height)
        // A software canvas: WebView draws the whole view into it, the parts panned off the phone included.
        web.draw(c)
        picture = b
        pictureOf = web
        if (BuildConfig.DEBUG) { // TEMP: is the part panned off the phone drawn? (non-blank samples, left third / right third)
            fun inked(x0: Int, x1: Int): Int { var n = 0; for (x in x0 until x1 step 4) for (y in 0 until h step 4) if (b.getPixel(x, y) != BrowserService.FIELD) n++; return n }
            android.util.Log.d("DesktopStage", "picture ${w}x$h pan=${desk()?.pan} view=$viewW screen=${screenW}x$screenH inked left=${inked(0, w / 3)} right=${inked(w * 2 / 3, w)}")
        }
        strip.invalidate()
        // Pages change by themselves too (feeds, clocks, video): look again now and then while it's in view.
        postDelayed(takePicture, PICTURE_IDLE_MS)
    }

    /** Where the picture goes in the strip. */
    private fun pictureRect(): RectF {
        val pad = dp(PAD_DP).toFloat()
        val h = stripH - pad * 2
        val w = h * SCREEN_W / SCREEN_H
        val left = pad + max(0f, (viewW - dp(BUTTON_DP) - pad * 2 - w) / 2)
        return RectF(left, pad, left + w, pad + h)
    }

    // The strip.

    private inner class Strip(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var onButton = false

        init {
            setBackgroundColor(BrowserService.BG)
            contentDescription = "Desktop screen: the part in view, and zoom out"
            visibility = GONE // until a desktop tab is in view
        }

        override fun onDraw(c: Canvas) {
            val tab = desk() ?: return
            val r = pictureRect()
            val round = dp(4).toFloat()
            paint.style = Paint.Style.FILL
            paint.color = BrowserService.FIELD
            c.drawRoundRect(r, round, round, paint)
            val pic = picture
            if (pic != null && pictureOf === tab.web) c.drawBitmap(pic, null, r, paint)
            // The slice in view.
            val k = r.width() / max(1, screenW)
            val box = if (whole) RectF(r) else RectF(r.left + tab.pan * k, r.top, r.left + min(screenW.toFloat(), tab.pan + viewW) * k, r.bottom)
            paint.color = 0x66000000
            c.drawRect(r.left, r.top, box.left, r.bottom, paint)
            c.drawRect(box.right, r.top, r.right, r.bottom, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            paint.color = BrowserService.ACCENT
            val inset = paint.strokeWidth / 2
            c.drawRoundRect(box.left + inset, box.top + inset, box.right - inset, box.bottom - inset, round, round, paint)
            // The zoom button: a magnifier, minus to zoom out, plus to come back in.
            val cx = width - dp(BUTTON_DP) / 2f
            val cy = height / 2f
            val rad = dp(7).toFloat()
            paint.color = BrowserService.STEEL
            paint.strokeWidth = 1.6f * density
            paint.strokeCap = Paint.Cap.ROUND
            val mx = cx - dp(2)
            val my = cy - dp(2)
            c.drawCircle(mx, my, rad, paint)
            c.drawLine(mx + rad * 0.72f, my + rad * 0.72f, mx + rad * 1.55f, my + rad * 1.55f, paint)
            c.drawLine(mx - rad * 0.5f, my, mx + rad * 0.5f, my, paint)
            if (whole) c.drawLine(mx, my - rad * 0.5f, mx, my + rad * 0.5f, paint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (desk() == null) return false
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onButton = e.x >= width - dp(BUTTON_DP)
            if (onButton) {
                if (e.actionMasked == MotionEvent.ACTION_UP && e.x >= width - dp(BUTTON_DP)) zoom()
                return true
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    if (whole) zoom() // a place picked on the picture: back to the slice there
                    scroller.forceFinished(true)
                    val r = pictureRect()
                    val at = (e.x - r.left) / r.width() * screenW // screen px under the finger
                    panTo(at - viewW / 2f)
                }
                MotionEvent.ACTION_UP -> pictureSoon()
            }
            return true
        }
    }

    /** Zoom out to the whole screen, or back in to the slice it was at. */
    private fun zoom() {
        whole = !whole
        lift = 0f
        desk()?.let { place(it) }
        if (keyboard) follow()
        strip.invalidate()
    }

    companion object {
        /** The laptop screen desktop sites are laid out on, in CSS px: 16:9, the most common laptop viewport (a
         * 1920×1080 display at 125%). */
        const val SCREEN_W = 1536
        const val SCREEN_H = 864
        private const val BUTTON_DP = 48 // the zoom button, at the strip's end
        private const val PAD_DP = 6
        private const val SETTLE_MS = 600L // a shorter area with no keyboard in the insets by then: not the keyboard
        private const val FOLLOW_MS = 350L // after a tap with the keyboard up: the focus has moved by then
        private const val SETTLE_PICTURE_MS = 400L
        private const val PICTURE_EVERY_MS = 1000L
        private const val PICTURE_IDLE_MS = 4000L
    }
}
