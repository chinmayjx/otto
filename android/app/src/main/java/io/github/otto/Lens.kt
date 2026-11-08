package io.github.otto

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import io.github.otto.device.AgentAccessibility
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The lens: a long press on the handle freezes the screen (a screenshot through the accessibility service) in an
 * overlay of its own, where parts of it are selected with a finger and sent to the box as a clip (Clips), with a
 * note, for an agent to read with `otto clip <id>`.
 *
 * Drag on the image for a selection; drag its corners or edges to adjust it, inside it to move it. Each one is a chip
 * in the collector (Collector), floating over the image, whose grip drags it up and down so what's under it can be
 * selected too. Copy sends the selections (or the whole screen when there are none) and the text, then puts a
 * reference to the clip on the clipboard. Until a Copy goes through, what was collected is a draft (Drafts, "lens"):
 * closing the lens with Back or Home keeps it, and the next lens starts with its text and images.
 *
 * An overlay window rather than an app screen, so it's up the moment the screenshot is: a focusable one, so the text
 * takes the keyboard and Back comes here. It lays itself out under the system bars and keeps the collector clear of
 * them and of the keyboard (the image never moves).
 */
class Lens private constructor(base: Context, private val shot: Bitmap, private val app: String?, private val done: () -> Unit) {
    private val ctx = ContextThemeWrapper(base, android.R.style.Theme_DeviceDefault)
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val sels = mutableListOf<RectF>() // in the screenshot's pixels, which are the screen's
    private var active = -1
    private var stills = mutableListOf<Bitmap>() // the draft's images, from an earlier lens
    private val root = Root(ctx)
    private val image = Frozen(ctx)
    private val collector = Collector(ctx, docked = false)
    private var lift = 0f // how far the collector's bottom is dragged up from the screen's
    private var liftAtDown = 0f
    private var topInset = 0
    private var bottomInset = 0 // the navigation bar's, or the keyboard's when it's up
    private var sent = false
    private var gone = false

    /** Home or Recents: the lens goes, as an app would. */
    private val systemDialogs = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val why = i.getStringExtra("reason")
            if (why == "homekey" || why == "recentapps") dismiss()
        }
    }

    init {
        root.setBackgroundColor(Color.BLACK)
        root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        collector.onCopy = { copy() }
        collector.onRemove = { i -> remove(i) }
        collector.onTapPiece = { i ->
            if (i >= stills.size) {
                active = i - stills.size
                chipsChanged()
            }
        }
        collector.onDrag = { dy ->
            if (dy == null) liftAtDown = max(lift, bottomInset.toFloat())
            else {
                lift = (liftAtDown - dy).coerceIn(0f, maxLift())
                place()
            }
        }
        root.addView(collector, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        // Dragged up, it stays where it was put as it grows, but never past the top.
        collector.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }

        // Laid out under the system bars and the keyboard: the image stays put, the collector keeps clear of them.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            collector.setPadding(b.left, 0, b.right, 0)
            topInset = b.top
            bottomInset = max(b.bottom, ime.bottom)
            place()
            WindowInsets.CONSUMED
        }
        chipsChanged()
    }

    private fun maxLift() = max(0f, (root.height - topInset - collector.height).toFloat())

    /** Where it was dragged to, but always above the navigation bar and the keyboard. */
    private fun place() {
        lift = min(lift, maxLift())
        collector.translationY = -max(lift, bottomInset.toFloat())
    }

    /** The draft from the last lens that wasn't sent: its text and images. */
    private fun restore(d: Drafts.Draft) {
        stills = d.images.toMutableList()
        collector.text = d.text
        chipsChanged()
    }

    private fun show() {
        val lp = WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "Otto lens"
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        }
        wm.addView(root, lp)
        ctx.registerReceiver(systemDialogs, IntentFilter(@Suppress("DEPRECATION") Intent.ACTION_CLOSE_SYSTEM_DIALOGS), Context.RECEIVER_EXPORTED)
        current = this
    }

    /** Out (Back, Home, or after a Copy); what wasn't sent stays as the draft. */
    fun dismiss() {
        if (gone) return
        gone = true
        if (current === this) current = null
        try {
            ctx.unregisterReceiver(systemDialogs)
        } catch (_: Exception) {
        }
        collector.hideKeyboard()
        if (!sent) {
            val text = collector.text
            val images = stills + regions(whole = false).map { crop(it) }
            val app = ctx.applicationContext
            io.execute { Drafts.save(app, DRAFT, text, images) }
        }
        if (root.isAttachedToWindow) wm.removeViewImmediate(root)
        done()
    }

    // --- Copy ------------------------------------------------------------------------------------------------------

    /** The selections' pixels; with none (and no images from the draft), the whole screen if [whole]. */
    private fun regions(whole: Boolean): List<Rect> {
        val all = Rect(0, 0, shot.width, shot.height)
        val r = sels.map { s ->
            Rect(s.left.roundToInt(), s.top.roundToInt(), s.right.roundToInt(), s.bottom.roundToInt()).apply { intersect(all) }
        }.filter { it.width() > 0 && it.height() > 0 }
        return if (whole && r.isEmpty() && stills.isEmpty()) listOf(all) else r
    }

    private fun copy() {
        collector.sending()
        val parts = regions(whole = true)
        val text = collector.text.trim()
        val source = JSONObject().put("kind", "screen")
        if (app != null) {
            source.put("app", app)
            label(app)?.let { source.put("label", it) }
        }
        val before = stills.toList()
        io.execute {
            try {
                val id = Clips.upload(text, before + parts.map { crop(it) }, source)
                Drafts.clear(ctx, DRAFT)
                main.post {
                    sent = true
                    Clips.copyReference(ctx, id)
                    collector.sent()
                    Toast.makeText(ctx, "Copied clip $id", Toast.LENGTH_SHORT).show()
                    main.postDelayed({ dismiss() }, 550)
                }
            } catch (e: Exception) {
                android.util.Log.w("Lens", "copy: $e")
                main.post { collector.failed(e.message ?: e.toString()) }
            }
        }
    }

    private fun label(pkg: String): String? = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        null
    }

    private fun crop(r: Rect): Bitmap =
        if (r.left == 0 && r.top == 0 && r.width() == shot.width && r.height() == shot.height) shot
        else Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height())

    // --- Selections ----------------------------------------------------------------------------------------------

    /** Selections came or went, or another became the active one: the chips again (the draft's first). */
    private fun chipsChanged() {
        val pieces = stills.map { Collector.Still(it) } + sels.indices.map { Live(it) }
        collector.piecesChanged(pieces, if (active >= 0) stills.size + active else -1)
        image.invalidate()
    }

    private fun remove(i: Int) {
        if (i < stills.size) stills.removeAt(i)
        else {
            val j = i - stills.size
            sels.removeAt(j)
            if (active == j) active = -1 else if (active > j) active--
        }
        chipsChanged()
    }

    /** A selection's chip, drawn live from the screenshot. */
    private inner class Live(private val i: Int) : Collector.Piece {
        private val src = Rect()
        private fun rect(): Rect? {
            val s = sels.getOrNull(i) ?: return null
            s.round(src)
            return if (src.intersect(0, 0, shot.width, shot.height)) src else null
        }

        override val width get() = rect()?.width() ?: 0
        override val height get() = rect()?.height() ?: 0
        override val active get() = i == this@Lens.active
        override fun draw(c: Canvas, dst: RectF, paint: Paint) {
            rect()?.let { c.drawBitmap(shot, it, dst, paint) }
        }
    }

    /** The frozen screen: draws the screenshot where it was taken, dims what isn't selected, and takes the drags. */
    @SuppressLint("ViewConstructor")
    private inner class Frozen(c: Context) : View(c) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
        private val knobRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.BLACK
        }
        private val loc = IntArray(2)
        private val slop = ViewConfiguration.get(c).scaledTouchSlop
        private val reach = dp(24).toFloat() // how near a corner or an edge a finger must land to take it
        private val least = dp(16).toFloat() // a selection's smallest side
        private var mode = NONE
        private var edges = 0 // the sides a resize moves (LEFT, TOP, RIGHT, BOTTOM)
        private var downX = 0f
        private var downY = 0f
        private val start = RectF()

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            getLocationOnScreen(loc)
        }

        override fun onDraw(c: Canvas) {
            c.save()
            c.translate(-loc[0].toFloat(), -loc[1].toFloat()) // the screenshot's pixels are the screen's
            c.drawBitmap(shot, 0f, 0f, paint)
            c.save()
            for (s in sels) c.clipOutRect(s)
            c.drawColor(DIM)
            c.restore()
            sels.forEachIndexed { i, s -> if (i != active) outline(c, s, false) }
            sels.getOrNull(active)?.let { outline(c, it, true) }
            c.restore()
        }

        private fun outline(c: Canvas, s: RectF, on: Boolean) {
            line.color = if (on) BrowserService.ACCENT else STEEL
            line.strokeWidth = if (on) dp(2).toFloat() else dp(1.5f)
            c.drawRect(s, line)
            if (!on) return
            knob.color = BrowserService.ACCENT
            knobRim.strokeWidth = dp(1.5f)
            val r = dp(5).toFloat()
            for (x in floatArrayOf(s.left, s.right)) for (y in floatArrayOf(s.top, s.bottom)) {
                c.drawCircle(x, y, r, knob)
                c.drawCircle(x, y, r, knobRim)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val x = e.rawX
            val y = e.rawY
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (collector.typing) collector.hideKeyboard()
                    downX = x
                    downY = y
                    mode = NONE
                    sels.getOrNull(active)?.let { s ->
                        edges = edgesAt(s, x, y)
                        if (edges != 0) mode = RESIZE
                    }
                    if (mode == NONE) {
                        val i = sels.indexOfLast { it.contains(x, y) }
                        if (i >= 0) {
                            if (i != active) {
                                active = i
                                chipsChanged()
                            }
                            mode = MOVE
                        } else mode = PENDING
                    }
                    sels.getOrNull(active)?.let { start.set(it) }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = x - downX
                    val dy = y - downY
                    when (mode) {
                        PENDING -> if (max(abs(dx), abs(dy)) > slop) {
                            sels.add(RectF(downX, downY, downX, downY))
                            active = sels.size - 1
                            start.set(downX, downY, downX, downY)
                            mode = DRAW
                            chipsChanged()
                        }
                        DRAW -> sels[active].set(min(downX, x).coerceAtLeast(0f), min(downY, y).coerceAtLeast(0f),
                            max(downX, x).coerceAtMost(shot.width.toFloat()), max(downY, y).coerceAtMost(shot.height.toFloat()))
                        RESIZE -> resize(sels[active], dx, dy)
                        MOVE -> {
                            val s = sels[active]
                            val mx = dx.coerceIn(-start.left, shot.width - start.right)
                            val my = dy.coerceIn(-start.top, shot.height - start.bottom)
                            s.set(start)
                            s.offset(mx, my)
                        }
                    }
                    if (mode != NONE && mode != PENDING) {
                        invalidate()
                        collector.redraw(stills.size + active)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    when (mode) {
                        PENDING -> if (active >= 0) { // a tap on nothing: no active selection
                            active = -1
                            chipsChanged()
                        }
                        DRAW -> {
                            val s = sels[active]
                            if (s.width() < least || s.height() < least) {
                                sels.removeAt(active)
                                active = -1
                            }
                            chipsChanged()
                        }
                        RESIZE, MOVE -> chipsChanged() // the chip's shape
                    }
                    mode = NONE
                    return true
                }
            }
            return false
        }

        /** The sides of s a finger at (x, y) takes: a corner gives two, an edge one, none if it's far from all. */
        private fun edgesAt(s: RectF, x: Float, y: Float): Int {
            val inX = x > s.left - reach && x < s.right + reach
            val inY = y > s.top - reach && y < s.bottom + reach
            if (!inX || !inY) return 0
            var e = 0
            // The nearer side wins when the selection is thinner than two reaches.
            if (abs(x - s.left) < reach && abs(x - s.left) <= abs(x - s.right)) e = e or LEFT
            else if (abs(x - s.right) < reach) e = e or RIGHT
            if (abs(y - s.top) < reach && abs(y - s.top) <= abs(y - s.bottom)) e = e or TOP
            else if (abs(y - s.bottom) < reach) e = e or BOTTOM
            return e
        }

        private fun resize(s: RectF, dx: Float, dy: Float) {
            s.set(start)
            val w = shot.width.toFloat()
            val h = shot.height.toFloat()
            if (edges and LEFT != 0) s.left = (start.left + dx).coerceIn(0f, start.right - least)
            if (edges and RIGHT != 0) s.right = (start.right + dx).coerceIn(start.left + least, w)
            if (edges and TOP != 0) s.top = (start.top + dy).coerceIn(0f, start.bottom - least)
            if (edges and BOTTOM != 0) s.bottom = (start.bottom + dy).coerceIn(start.top + least, h)
        }
    }

    /** The window's view: takes Back. */
    private inner class Root(c: Context) : FrameLayout(c) {
        override fun dispatchKeyEvent(e: KeyEvent): Boolean {
            if (e.keyCode == KeyEvent.KEYCODE_BACK) {
                if (e.action != KeyEvent.ACTION_UP || e.isCanceled) return true
                if (rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) collector.hideKeyboard() // the keyboard goes first
                else dismiss()
                return true
            }
            return super.dispatchKeyEvent(e)
        }
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()
    private fun dp(v: Float) = v * ctx.resources.displayMetrics.density

    companion object {
        private val STEEL = Color.parseColor("#aeb4bc") // the selections that aren't the active one
        private const val DIM = 0x66000000 // over what isn't selected
        private const val DRAFT = "lens"
        private const val NONE = 0
        private const val PENDING = 1 // down on nothing: a new selection once it moves
        private const val DRAW = 2
        private const val RESIZE = 3
        private const val MOVE = 4
        private const val LEFT = 1
        private const val TOP = 2
        private const val RIGHT = 4
        private const val BOTTOM = 8

        /** Uploads, drafts and the screenshot's copy work off the main thread, in order. */
        private val io = Executors.newSingleThreadExecutor { Thread(it, "lens") }

        var current: Lens? = null
            private set
        private var starting = false

        /**
         * Freezes the screen and opens the lens over it, with the last unsent draft. Whatever shouldn't be in the
         * picture (the handle, the second screen) has been asked to go already: the screenshot waits a few frames
         * for that to reach the display. [done] runs when the lens is gone, or failed to open.
         */
        fun open(ctx: Context, done: () -> Unit) {
            if (current != null || starting) return
            val agent = AgentAccessibility.instance
            if (agent == null) {
                Toast.makeText(ctx, "The lens needs Otto's accessibility service turned on", Toast.LENGTH_LONG).show()
                done()
                return
            }
            starting = true
            val app = try {
                agent.rootInActiveWindow?.packageName?.toString()
            } catch (_: Exception) {
                null
            }
            val main = Handler(Looper.getMainLooper())
            var frames = 0
            val shoot = object : Choreographer.FrameCallback {
                override fun doFrame(t: Long) {
                    if (++frames < 3) {
                        Choreographer.getInstance().postFrameCallback(this)
                        return
                    }
                    thread(name = "lens-shot") {
                        val r = try {
                            agent.screenshot(io)
                        } catch (e: Exception) {
                            e
                        }
                        val draft = if (r is Bitmap) Drafts.load(ctx, DRAFT) else null
                        main.post {
                            starting = false
                            if (r is Bitmap) Lens(ctx, r, app?.takeIf { it != ctx.packageName }, done).apply { draft?.let { restore(it) } }.show()
                            else {
                                val why = (r as Exception).message ?: r.toString()
                                Toast.makeText(ctx, "The lens couldn't take the screen: $why", Toast.LENGTH_LONG).show()
                                done()
                            }
                        }
                    }
                }
            }
            Choreographer.getInstance().postFrameCallback(shoot)
        }
    }
}
