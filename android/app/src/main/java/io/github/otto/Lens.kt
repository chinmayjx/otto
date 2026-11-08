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
import android.view.accessibility.AccessibilityWindowInfo
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
 * The lens: a long press on the handle opens the collector (Collector) as a panel floating over the live screen,
 * to gather a clip (Clips) for an agent to read with `otto clip <id>`: a note, typed or dictated, and parts of the
 * screen.
 *
 * Live, the panel is a window of its own at the bottom, full width, dragged up and down by its grip; everything
 * outside it stays the app's (FLAG_NOT_TOUCH_MODAL), so the user can scroll, switch apps, and use other apps'
 * overlays, such as a dictation bubble, while the note has the focus. It takes the focus when touched and gives it
 * back when the app is (FLAG_WATCH_OUTSIDE_TOUCH), so Back and the keyboard go where the user last was.
 *
 * It opens frozen, as the long press found the screen; its freeze button (Done while frozen) goes back to the
 * live screen and freezes it again. Freezing takes a screenshot (through the accessibility service, with the panel
 * and handle out of it) and the window grows over the whole screen to show it: drag on it for a selection; drag its corners or edges to
 * adjust it, inside it to move it. Each selection is a chip. Done (the same button), Back or Home unfreeze: the
 * selections are cut out and kept as images, and the live screen is back. Whole screen takes all of it, frozen or
 * live. This can be repeated across apps to gather many parts into one clip.
 *
 * Copy sends the text and the images taken to the box and puts a reference to the clip on the clipboard; with no
 * images it puts the text itself on the clipboard and sends nothing (never an image the user didn't take). Until a
 * Copy goes through, what was collected is a draft (Drafts, "lens"): closing the collector (Back, or a tap on the
 * handle) keeps it, and the next one starts with it.
 */
class Lens private constructor(base: Context, private val handle: (Boolean) -> Unit, private val done: () -> Unit) {
    private val ctx = ContextThemeWrapper(base, android.R.style.Theme_DeviceDefault)
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var still: Bitmap? = null // the frozen screen, while frozen
    private val shot: Bitmap get() = still!!
    private val sels = mutableListOf<RectF>() // on the frozen screen, in its pixels, which are the screen's
    private var active = -1
    private var stills = mutableListOf<Bitmap>() // the parts taken so far (and the draft's)
    private var app: String? = null // the app last frozen
    private val root = Root(ctx)
    private val image = Frozen(ctx)
    private val collector = Collector(ctx, docked = false)
    private val lp = WindowManager.LayoutParams()
    private var lift = 0f // how far the collector is dragged up from its place at the bottom
    private var liftAtDown = 0f
    private var topInset = 0
    private var bottomInset = 0 // frozen: the navigation bar's, or the keyboard's when it's up
    private var busy = false
    private var sent = false
    private var gone = false

    /** Home or Recents while frozen: back to the live screen, where the user is going. */
    private val systemDialogs = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val why = i.getStringExtra("reason")
            if ((why == "homekey" || why == "recentapps") && still != null) unfreeze()
        }
    }

    init {
        image.visibility = View.GONE
        root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        collector.onCopy = { copy() }
        collector.onRemove = { i -> remove(i) }
        collector.onTapPiece = { i ->
            if (i >= stills.size) {
                active = i - stills.size
                chipsChanged()
            }
        }
        collector.onCapture = { if (still == null) freeze() else unfreeze() }
        collector.onWhole = { whole() }
        collector.onDrag = { dy ->
            if (dy == null) liftAtDown = lift
            else {
                lift = (liftAtDown - dy).coerceIn(0f, maxLift())
                place()
            }
        }
        root.addView(collector, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        collector.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }

        // Frozen, the window lies under the system bars and the keyboard: the image stays put, the collector keeps
        // clear of them. Live, the window is laid out clear of them already (fitInsetsTypes).
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

    /** Live, the system's bars (and the keyboard's) around the window, for how far up it may go. */
    private fun screenInsets() = wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())

    private fun maxLift(): Float {
        val h = collector.height
        if (still != null) return max(0f, (root.height - topInset - bottomInset - h).toFloat())
        val i = screenInsets()
        return max(0f, (wm.currentWindowMetrics.bounds.height() - i.top - i.bottom - h).toFloat())
    }

    /** Where it was dragged to: frozen, by moving it in the window; live, by moving the window. */
    private fun place() {
        lift = min(lift, maxLift())
        if (still != null) {
            collector.translationY = -(bottomInset + lift)
        } else {
            collector.translationY = 0f
            val y = lift.roundToInt()
            if (lp.y != y && root.isAttachedToWindow) {
                lp.y = y
                wm.updateViewLayout(root, lp)
            }
        }
    }

    /** The draft from the last collector that wasn't sent: its text and images. */
    private fun restore(d: Drafts.Draft) {
        stills = d.images.toMutableList()
        collector.text = d.text
        chipsChanged()
    }

    // --- The window ------------------------------------------------------------------------------------------------

    private fun liveLayout() {
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        // Outside touches go to the app beneath; a touch outside is reported, to hand it the focus back.
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        lp.format = PixelFormat.TRANSLUCENT
        lp.gravity = Gravity.BOTTOM or Gravity.START
        lp.x = 0
        lp.y = lift.roundToInt()
        lp.title = "Otto collector"
        // Laid out clear of the system bars and of the keyboard, which so never covers it.
        lp.fitInsetsTypes = WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout()
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        root.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun frozenLayout() {
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        lp.flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        lp.gravity = Gravity.TOP or Gravity.START
        lp.y = 0
        lp.fitInsetsTypes = 0
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        root.setBackgroundColor(Color.BLACK)
    }

    /** In, frozen on [bm]. */
    private fun show(bm: Bitmap) {
        liveLayout()
        enterFrozen(bm)
        wm.addView(root, lp)
        ctx.registerReceiver(systemDialogs, IntentFilter(@Suppress("DEPRECATION") Intent.ACTION_CLOSE_SYSTEM_DIALOGS), Context.RECEIVER_EXPORTED)
        current = this
    }

    /** Live: the panel takes the focus (for the keyboard, and Back) or hands it back to the app. */
    private fun focusable(on: Boolean) {
        if (still != null || !root.isAttachedToWindow) return
        val was = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0
        if (was == on) return
        if (!on) collector.hideKeyboard()
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        wm.updateViewLayout(root, lp)
    }

    /** Out (Back, a tap on the handle, or after a Copy); what wasn't sent stays as the draft. */
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
            val images = stills + if (still != null) regions().map { crop(it) } else emptyList()
            val app = ctx.applicationContext
            io.execute { Drafts.save(app, DRAFT, text, images) }
        }
        if (root.isAttachedToWindow) wm.removeViewImmediate(root)
        handle(true)
        done()
    }

    // --- Freezing --------------------------------------------------------------------------------------------------

    /**
     * The live screen as it is without the panel and the handle: they go out of the picture first, and the shot
     * waits a few frames for that to reach the display. Then [then] (on the main thread) with it, or null.
     */
    private fun shoot(then: (Bitmap?) -> Unit) {
        val agent = AgentAccessibility.instance
        if (agent == null) {
            Toast.makeText(ctx, "Taking the screen needs Otto's accessibility service turned on", Toast.LENGTH_LONG).show()
            return then(null)
        }
        collector.hideKeyboard()
        root.visibility = View.INVISIBLE
        handle(false)
        var frames = 0
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(t: Long) {
                if (++frames < 3) return Choreographer.getInstance().postFrameCallback(this)
                app = foreground(agent) ?: app
                thread(name = "lens-shot") {
                    val r = try {
                        agent.screenshot(io)
                    } catch (e: Exception) {
                        e
                    }
                    main.post {
                        if (gone) return@post
                        root.visibility = View.VISIBLE
                        handle(true)
                        if (r is Bitmap) then(r)
                        else {
                            Toast.makeText(ctx, "Couldn't take the screen: ${(r as Exception).message ?: r}", Toast.LENGTH_LONG).show()
                            then(null)
                        }
                    }
                }
            }
        })
    }

    /** The app on screen (not Otto): the topmost application window's. */
    private fun foreground(agent: AgentAccessibility): String? = try {
        agent.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .firstNotNullOfOrNull { w -> w.root?.packageName?.toString()?.takeIf { it != ctx.packageName } }
    } catch (_: Exception) {
        null
    }

    private fun freeze() {
        if (busy || still != null) return
        busy = true
        shoot { bm ->
            busy = false
            if (bm == null) return@shoot
            enterFrozen(bm)
            wm.updateViewLayout(root, lp)
        }
    }

    /** The frozen screen in, over the whole screen, to select on. */
    private fun enterFrozen(bm: Bitmap) {
        still = bm
        sels.clear()
        active = -1
        frozenLayout()
        image.visibility = View.VISIBLE
        collector.frozen = true
        chipsChanged()
    }

    /** Back to the live screen; the selections are kept as images. */
    private fun unfreeze() {
        if (still == null) return
        stills.addAll(regions().map { crop(it) })
        leaveFrozen()
    }

    /** The whole screen as one image: the frozen one, or (live) the screen as it is now. */
    private fun whole() {
        if (busy) return
        val bm = still
        if (bm != null) {
            stills.add(bm)
            chipsChanged()
            return
        }
        busy = true
        shoot { shot ->
            busy = false
            if (shot != null) {
                stills.add(shot)
                chipsChanged()
            }
        }
    }

    private fun leaveFrozen() {
        collector.hideKeyboard()
        sels.clear()
        active = -1
        still = null
        image.visibility = View.GONE
        liveLayout()
        if (root.isAttachedToWindow) wm.updateViewLayout(root, lp)
        collector.frozen = false
        chipsChanged()
        place()
    }

    // --- Copy ------------------------------------------------------------------------------------------------------

    /** The selections' pixels on the frozen screen. */
    private fun regions(): List<Rect> {
        val bm = still ?: return emptyList()
        val all = Rect(0, 0, bm.width, bm.height)
        return sels.map { s ->
            Rect(s.left.roundToInt(), s.top.roundToInt(), s.right.roundToInt(), s.bottom.roundToInt()).apply { intersect(all) }
        }.filter { it.width() > 0 && it.height() > 0 }
    }

    /** What was taken (the selections on a frozen screen too) and the text; never an image nobody asked for. */
    private fun copy() {
        if (busy) return
        collector.sending()
        unfreeze()
        if (stills.isEmpty()) { // no images: the text itself goes on the clipboard, and nothing to the box
            Clips.copyText(ctx, collector.text.trim())
            io.execute { Drafts.clear(ctx, DRAFT) }
            sent = true
            collector.sent()
            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
            main.postDelayed({ dismiss() }, 550)
        } else send(stills.toList())
    }

    private fun send(images: List<Bitmap>) {
        val text = collector.text.trim()
        val source = JSONObject().put("kind", "screen")
        app?.let { a ->
            source.put("app", a)
            label(a)?.let { source.put("label", it) }
        }
        busy = true
        io.execute {
            try {
                val id = Clips.upload(text, images, source)
                Drafts.clear(ctx, DRAFT)
                main.post {
                    busy = false
                    sent = true
                    Clips.copyReference(ctx, id)
                    collector.sent()
                    Toast.makeText(ctx, "Copied clip $id", Toast.LENGTH_SHORT).show()
                    main.postDelayed({ dismiss() }, 550)
                }
            } catch (e: Exception) {
                android.util.Log.w("Lens", "copy: $e")
                main.post {
                    busy = false
                    collector.failed(e.message ?: e.toString())
                }
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

    /** Selections came or went, or another became the active one: the chips again (the parts taken first). */
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

    /** A selection's chip, drawn live from the frozen screen. */
    private inner class Live(private val i: Int) : Collector.Piece {
        private val src = Rect()
        private fun rect(): Rect? {
            val bm = still ?: return null
            val s = sels.getOrNull(i) ?: return null
            s.round(src)
            return if (src.intersect(0, 0, bm.width, bm.height)) src else null
        }

        override val width get() = rect()?.width() ?: 0
        override val height get() = rect()?.height() ?: 0
        override val active get() = i == this@Lens.active
        override fun draw(c: Canvas, dst: RectF, paint: Paint) {
            val bm = still ?: return
            rect()?.let { c.drawBitmap(bm, it, dst, paint) }
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
            if (still == null) return
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
            if (still == null) return false
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

    /** The window's view: takes Back, and (live) gives the focus to the panel or back to the app. */
    private inner class Root(c: Context) : FrameLayout(c) {
        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_OUTSIDE -> {
                    focusable(false)
                    return false
                }
                MotionEvent.ACTION_DOWN -> focusable(true)
            }
            return super.dispatchTouchEvent(e)
        }

        // The panel took the focus on the touch that focused the note: the keyboard can come only now.
        override fun onWindowFocusChanged(has: Boolean) {
            super.onWindowFocusChanged(has)
            if (has && collector.typing) post { collector.showKeyboard() }
        }

        override fun dispatchKeyEvent(e: KeyEvent): Boolean {
            if (e.keyCode == KeyEvent.KEYCODE_BACK) {
                if (e.action != KeyEvent.ACTION_UP || e.isCanceled) return true
                when {
                    rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true -> collector.hideKeyboard() // the keyboard goes first
                    still != null -> unfreeze()
                    else -> dismiss()
                }
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
         * Freezes the screen and opens the collector over it, with the last unsent draft. Whatever shouldn't be in
         * the picture (the second screen) has been asked to go already, and [handle] hides the handle: the shot waits
         * a few frames for that to reach the display. [handle] shows it again after; [done] runs when the collector
         * is gone, or failed to open.
         */
        fun open(ctx: Context, handle: (Boolean) -> Unit, done: () -> Unit) {
            if (current != null || starting) return
            val agent = AgentAccessibility.instance
            if (agent == null) {
                Toast.makeText(ctx, "The lens needs Otto's accessibility service turned on", Toast.LENGTH_LONG).show()
                done()
                return
            }
            starting = true
            io.execute { java.io.File(ctx.cacheDir, "lens").deleteRecursively() } // the images of the lens before clips
            handle(false)
            val main = Handler(Looper.getMainLooper())
            var frames = 0
            Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
                override fun doFrame(t: Long) {
                    if (++frames < 3) return Choreographer.getInstance().postFrameCallback(this)
                    thread(name = "lens-shot") {
                        val r = try {
                            agent.screenshot(io)
                        } catch (e: Exception) {
                            e
                        }
                        val draft = if (r is Bitmap) Drafts.load(ctx, DRAFT) else null
                        main.post {
                            starting = false
                            handle(true)
                            if (r is Bitmap) Lens(ctx, handle, done).apply {
                                app = foreground(agent)
                                draft?.let { restore(it) }
                                show(r)
                            } else {
                                Toast.makeText(ctx, "The lens couldn't take the screen: ${(r as Exception).message ?: r}", Toast.LENGTH_LONG).show()
                                done()
                            }
                        }
                    }
                }
            })
        }
    }
}
