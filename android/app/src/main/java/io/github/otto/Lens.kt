package io.github.otto

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import io.github.otto.device.AgentAccessibility
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The lens: a long press on the handle freezes the screen (a screenshot through the accessibility service) in an
 * overlay of its own, where parts of it are selected with a finger and copied or shared, with a note.
 *
 * Drag on the image for a selection; drag its corners or edges to adjust it, inside it to move it. Each one is a chip
 * in the bottom bar, whose × removes it. Copy puts one image on the clipboard (the selections one under another, or
 * the whole screen when there are none) and the note as a second item; Share hands each selection to another app as
 * a PNG of its own, the note as the text. The images go through the app's FileProvider, from cache/lens/, and are
 * cleared out after a day.
 *
 * An overlay window rather than an app screen, so it's up the moment the screenshot is: a focusable one, so the note
 * takes the keyboard and Back comes here. It lays itself out under the system bars and keeps the bar clear of them
 * and of the keyboard itself (the image never moves).
 */
class Lens private constructor(base: Context, private val shot: Bitmap, private val done: () -> Unit) {
    private val ctx = ContextThemeWrapper(base, android.R.style.Theme_DeviceDefault)
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val sels = mutableListOf<RectF>() // in the screenshot's pixels, which are the screen's
    private var active = -1
    private val root = Root(ctx)
    private val image = Frozen(ctx)
    private val bar = LinearLayout(ctx)
    private val chips = LinearLayout(ctx)
    private val chipScroll = HorizontalScrollView(ctx)
    private val hint = TextView(ctx)
    private val note = EditText(ctx)
    private val share: TextView
    private val copy: TextView
    private val close = ImageView(ctx)
    private var busy = false
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

        close.setImageResource(R.drawable.ccm_close)
        close.setColorFilter(STEEL)
        close.scaleType = ImageView.ScaleType.CENTER
        close.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xcc000000.toInt())
            setStroke(max(1, dp(1)), BrowserService.LINE)
        }
        close.contentDescription = "Close the lens"
        close.setOnClickListener { dismiss() }
        root.addView(close, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.START))

        // The bottom bar: the selections' chips (or a hint), then the note and the two buttons.
        bar.orientation = LinearLayout.VERTICAL
        bar.background = GradientDrawable().apply { setColor(BrowserService.BG) }
        bar.isClickable = true // nothing falls through to the image
        val hairline = View(ctx)
        hairline.setBackgroundColor(HAIRLINE)
        bar.addView(hairline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max(1, dp(1) / 2 + 1)))

        val top = FrameLayout(ctx)
        hint.text = "Drag to select. Nothing selected sends the whole screen."
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        hint.setTextColor(BrowserService.MUTED)
        hint.gravity = Gravity.CENTER_VERTICAL
        hint.isSingleLine = true
        hint.ellipsize = android.text.TextUtils.TruncateAt.END
        top.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        chips.orientation = LinearLayout.HORIZONTAL
        chips.gravity = Gravity.CENTER_VERTICAL
        chipScroll.isHorizontalScrollBarEnabled = false
        chipScroll.addView(chips, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        top.addView(chipScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bar.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(CHIP_ROW)).apply {
            topMargin = dp(6)
            leftMargin = dp(12)
            rightMargin = dp(12)
        })

        val row = LinearLayout(ctx)
        row.gravity = Gravity.CENTER_VERTICAL
        note.hint = "Add a note"
        note.setHintTextColor(BrowserService.MUTED)
        note.setTextColor(BrowserService.TEXT)
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
        note.isSingleLine = true
        note.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        note.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
        note.setPadding(dp(12), 0, dp(12), 0)
        note.background = field(BrowserService.FIELD, BrowserService.LINE)
        note.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) hideKeyboard()
            action == EditorInfo.IME_ACTION_DONE
        }
        row.addView(note, LinearLayout.LayoutParams(0, dp(44), 1f))
        share = button("Share", BrowserService.TEXT, field(BrowserService.FIELD, BrowserService.LINE))
        share.setOnClickListener { share() }
        row.addView(share, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginStart = dp(8) })
        copy = button("Copy", Color.WHITE, field(BrowserService.ACCENT, BrowserService.ACCENT))
        copy.setOnClickListener { copy() }
        row.addView(copy, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginStart = dp(8) })
        bar.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
            leftMargin = dp(12)
            rightMargin = dp(12)
            bottomMargin = dp(10)
        })
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // Laid out under the system bars and the keyboard: the image stays put, the bar and × keep clear of them.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            bar.setPadding(b.left, 0, b.right, max(b.bottom, ime.bottom))
            (close.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = b.top + dp(10)
                leftMargin = b.left + dp(12)
            }
            close.requestLayout()
            WindowInsets.CONSUMED
        }
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

    /** Out, without doing anything. */
    fun dismiss() {
        if (gone) return
        gone = true
        if (current === this) current = null
        try {
            ctx.unregisterReceiver(systemDialogs)
        } catch (_: Exception) {
        }
        hideKeyboard()
        if (root.isAttachedToWindow) wm.removeViewImmediate(root)
        done()
    }

    private fun hideKeyboard() {
        ctx.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
        note.clearFocus()
    }

    // --- Copy and Share ----------------------------------------------------------------------------------------

    /** The selections' pixels, or the whole screen when there are none. */
    private fun regions(): List<Rect> {
        val all = Rect(0, 0, shot.width, shot.height)
        val r = sels.map { s ->
            Rect(s.left.roundToInt(), s.top.roundToInt(), s.right.roundToInt(), s.bottom.roundToInt()).apply { intersect(all) }
        }.filter { it.width() > 0 && it.height() > 0 }
        return r.ifEmpty { listOf(all) }
    }

    private fun copy() {
        if (busy) return
        busy = true
        hideKeyboard()
        val parts = regions()
        val text = note.text.toString().trim()
        copy.text = "…"
        io.execute {
            try {
                val img = if (parts.size == 1) crop(parts[0]) else stack(parts)
                val uri = save(img, stamp())
                main.post {
                    val types = if (text.isEmpty()) arrayOf("image/png") else arrayOf("image/png", ClipDescription.MIMETYPE_TEXT_PLAIN)
                    val clip = ClipData(ClipDescription("Otto lens", types), ClipData.Item(uri))
                    if (text.isNotEmpty()) clip.addItem(ClipData.Item(text))
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                    copy.text = "Copied"
                    main.postDelayed({ dismiss() }, 550)
                }
            } catch (e: Exception) {
                main.post { failed("copy", e) }
            }
        }
    }

    private fun share() {
        if (busy) return
        busy = true
        hideKeyboard()
        val parts = regions()
        val text = note.text.toString().trim()
        share.text = "…"
        io.execute {
            try {
                val base = stamp()
                val uris = parts.mapIndexed { i, r -> save(crop(r), if (parts.size == 1) base else "$base-${i + 1}") }
                main.post {
                    val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    send.type = "image/png"
                    if (text.isNotEmpty()) send.putExtra(Intent.EXTRA_TEXT, text)
                    // The ClipData carries the grant to whichever app is picked (and the chooser's previews).
                    val clip = ClipData(ClipDescription("Otto lens", arrayOf("image/png")), ClipData.Item(uris[0]))
                    for (u in uris.drop(1)) clip.addItem(ClipData.Item(u))
                    send.clipData = clip
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        ctx.startActivity(Intent.createChooser(send, null)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        dismiss()
                    } catch (e: Exception) {
                        failed("share", e)
                    }
                }
            } catch (e: Exception) {
                main.post { failed("share", e) }
            }
        }
    }

    private fun failed(what: String, e: Exception) {
        android.util.Log.w("Lens", "$what: $e")
        Toast.makeText(ctx, "Couldn't $what: ${e.message ?: e}", Toast.LENGTH_LONG).show()
        busy = false
        copy.text = "Copy"
        share.text = "Share"
    }

    private fun crop(r: Rect): Bitmap =
        if (r.left == 0 && r.top == 0 && r.width() == shot.width && r.height() == shot.height) shot
        else Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height())

    /** The selections one under another, each centred, with a small black gap between. */
    private fun stack(parts: List<Rect>): Bitmap {
        val gap = max(8, dp(6))
        val w = parts.maxOf { it.width() }
        val h = parts.sumOf { it.height() } + gap * (parts.size - 1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        var y = 0
        for (r in parts) {
            val x = (w - r.width()) / 2
            c.drawBitmap(shot, r, Rect(x, y, x + r.width(), y + r.height()), null)
            y += r.height() + gap
        }
        return out
    }

    private fun save(b: Bitmap, name: String): Uri {
        val f = File(dir(ctx), "otto-lens-$name.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())

    // --- Selections ----------------------------------------------------------------------------------------------

    /** Selections came or went, or another became the active one: the chips again. */
    private fun chipsChanged() {
        chips.removeAllViews()
        for (i in sels.indices) chips.addView(chip(i))
        hint.visibility = if (sels.isEmpty()) View.VISIBLE else View.GONE
        chipScroll.visibility = if (sels.isEmpty()) View.GONE else View.VISIBLE
        if (active >= 0) chipScroll.post { // the active one's chip into view
            chips.getChildAt(active)?.let { chipScroll.requestChildRectangleOnScreen(chips, Rect(it.left, 0, it.right, it.height), false) }
        }
        image.invalidate()
    }

    private fun remove(i: Int) {
        sels.removeAt(i)
        if (active == i) active = -1 else if (active > i) active--
        chipsChanged()
    }

    /** A selection's chip: its thumbnail, drawn live from the screenshot, and an × that removes it. */
    private fun chip(i: Int): View {
        val s = sels[i]
        val h = dp(CHIP_ROW - 14)
        val w = (h * s.width() / max(1f, s.height())).roundToInt().coerceIn(dp(28), dp(96))
        val f = FrameLayout(ctx)
        val thumb = Thumb(ctx, i)
        thumb.contentDescription = "Selection ${i + 1}"
        thumb.setOnClickListener {
            active = i
            chipsChanged()
        }
        f.addView(thumb, FrameLayout.LayoutParams(w, h, Gravity.BOTTOM or Gravity.START).apply { topMargin = dp(8); rightMargin = dp(8) })
        val x = ImageView(ctx)
        x.setImageResource(R.drawable.ccm_close)
        x.setColorFilter(BrowserService.TEXT)
        x.scaleType = ImageView.ScaleType.FIT_CENTER
        x.setPadding(dp(4), dp(4), dp(4), dp(4))
        x.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(BrowserService.FIELD)
            setStroke(max(1, dp(1)), BrowserService.LINE)
        }
        x.contentDescription = "Remove selection ${i + 1}"
        x.setOnClickListener { remove(i) }
        f.addView(x, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END))
        f.layoutParams = LinearLayout.LayoutParams(w + dp(8), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(6) }
        return f
    }

    private inner class Thumb(c: Context, private val i: Int) : View(c) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val src = Rect()
        private val dst = RectF()

        override fun onDraw(c: Canvas) {
            val s = sels.getOrNull(i) ?: return
            s.round(src)
            if (!src.intersect(0, 0, shot.width, shot.height)) return
            // Fitted into the chip, centred: the selection may have changed shape since the chip was made.
            val k = min(width / src.width().toFloat(), height / src.height().toFloat())
            val w = src.width() * k
            val h = src.height() * k
            dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            c.drawColor(BrowserService.FIELD)
            c.drawBitmap(shot, src, dst, paint)
            val on = i == active
            outline.color = if (on) BrowserService.ACCENT else BrowserService.LINE
            outline.strokeWidth = if (on) dp(2).toFloat() else max(1, dp(1)).toFloat()
            val half = outline.strokeWidth / 2
            c.drawRect(half, half, width - half, height - half, outline)
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
                    if (note.hasFocus()) hideKeyboard()
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
                        chips.getChildAt(active)?.let { (it as ViewGroup).getChildAt(0).invalidate() }
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
                if (rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) hideKeyboard() // the keyboard goes first
                else dismiss()
                return true
            }
            return super.dispatchKeyEvent(e)
        }
    }

    // --- Helpers -------------------------------------------------------------------------------------------------

    private fun button(text: String, color: Int, bg: GradientDrawable) = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(color)
        gravity = Gravity.CENTER
        minWidth = dp(72)
        setPadding(dp(14), 0, dp(14), 0)
        background = bg
        isClickable = true
        isFocusable = false // a tap leaves the note's focus (and the keyboard) where it is until the button acts
    }

    private fun field(color: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(10).toFloat()
        setStroke(max(1, dp(1)), stroke)
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()
    private fun dp(v: Float) = v * ctx.resources.displayMetrics.density

    companion object {
        private val STEEL = Color.parseColor("#aeb4bc")
        private val HAIRLINE = Color.parseColor("#26262a")
        private const val DIM = 0x66000000 // over what isn't selected
        private const val CHIP_ROW = 58
        private const val NONE = 0
        private const val PENDING = 1 // down on nothing: a new selection once it moves
        private const val DRAW = 2
        private const val RESIZE = 3
        private const val MOVE = 4
        private const val LEFT = 1
        private const val TOP = 2
        private const val RIGHT = 4
        private const val BOTTOM = 8
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        /** Files and the screenshot's copy work off the main thread, in order. */
        private val io = Executors.newSingleThreadExecutor { Thread(it, "lens") }

        var current: Lens? = null
            private set
        private var starting = false

        private fun dir(ctx: Context) = File(ctx.cacheDir, "lens").apply { mkdirs() }

        /**
         * Freezes the screen and opens the lens over it. Whatever shouldn't be in the picture (the handle, the second
         * screen) has been asked to go already: the screenshot waits a few frames for that to reach the display.
         * [done] runs when the lens is gone, or failed to open.
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
            io.execute { // last day's images out
                val old = System.currentTimeMillis() - DAY_MS
                dir(ctx).listFiles()?.forEach { if (it.lastModified() < old) it.delete() }
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
                        main.post {
                            starting = false
                            if (r is Bitmap) Lens(ctx, r, done).show()
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
