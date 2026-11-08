package io.github.otto

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The field takes the focus (and so the keyboard) only when a finger touches it: never on its own, as its window
 * opens or another view lets go of the focus. [onDown] runs as the finger comes down on it.
 */
@SuppressLint("ClickableViewAccessibility")
fun EditText.focusOnlyOnTouch(onDown: () -> Unit = {}) {
    isFocusable = false
    isFocusableInTouchMode = false
    setOnTouchListener { _, e ->
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            isFocusable = true
            isFocusableInTouchMode = true
            onDown()
        }
        false
    }
    setOnFocusChangeListener { _, has ->
        if (!has) {
            isFocusable = false
            isFocusableInTouchMode = false
        }
    }
}

/** The focus to a field kept by focusOnlyOnTouch, asked for by a tap elsewhere (a button that starts typing). */
fun EditText.focusNow() {
    isFocusable = true
    isFocusableInTouchMode = true
    requestFocus()
}

/**
 * The collector: what goes into a clip (Clips), gathered in one full-width bar. A row of image chips (each with an
 * ×), a text field that grows from one line to four, and one button, Copy. The same component in two places:
 *
 * - floating over the live screen (Lens), with a grip on its top edge to drag it up and down, and a freeze button
 *   that freezes the screen to take parts of it (and, frozen, becomes Done);
 * - docked under the artifact viewer (ui/Viewer, in an AndroidView), with a capture button that freezes the
 *   viewer to take a part of it, and a place button that starts a note tagged with where the reader is ("p.4").
 *
 * It holds no clip logic of its own: its host says what the chips are (Piece), and what Copy, ×, capture and the
 * action button (the place, or the lens's "Whole screen") do.
 */
@SuppressLint("ViewConstructor")
class Collector(ctx: Context, val docked: Boolean) : LinearLayout(ctx) {
    /** An image chip: drawn from wherever it lives (a bitmap, or a live selection of a screenshot). */
    interface Piece {
        val width: Int
        val height: Int
        val active: Boolean get() = false
        fun draw(c: Canvas, dst: RectF, paint: Paint)
    }

    /** A chip that's an image of its own. */
    class Still(val bitmap: Bitmap) : Piece {
        override val width get() = bitmap.width
        override val height get() = bitmap.height
        override fun draw(c: Canvas, dst: RectF, paint: Paint) = c.drawBitmap(bitmap, null, dst, paint)
    }

    var onCopy: () -> Unit = {}
    /** The chevron at the tools row's right end: put the collector away (the draft stays). */
    var onClose: () -> Unit = {}
    /** A finger came down on the note (the lens's panel takes the focus then). */
    var onNoteTouch: () -> Unit = {}
    var onRemove: (Int) -> Unit = {}
    var onTapPiece: (Int) -> Unit = {}
    var onTextChanged: (String) -> Unit = {}
    /** The freeze toggle: freeze (the viewer, or the screen) to select parts of it, or back to live. */
    var onCapture: () -> Unit = {}
    /** Take all of it (the screen, or the viewer) as one image. */
    var onWhole: () -> Unit = {}
    /** The grip's drag (lens): by how much the finger moved down, then null when it's let go. */
    var onDrag: (Float?) -> Unit = {}

    var pieces: List<Piece> = emptyList()
        private set

    /** Where the reader is (docked): the place button starts a note with it. Null: nowhere in particular. */
    var place: String? = null
        set(v) {
            field = v
            action("+ " + (v ?: "Note")) { addNote() }
        }

    /** The freeze toggle is on: what's under the collector is frozen. */
    var frozen = false
        set(v) {
            field = v
            capture.setColorFilter(if (v) BrowserService.ACCENT else ICON)
            capture.background = round(v)
            capture.contentDescription = if (v) "Unfreeze" else if (docked) "Freeze the page to select parts of it" else "Freeze the screen to select parts of it"
            changed()
        }

    var text: String
        get() = note.text.toString()
        set(v) {
            if (v != note.text.toString()) note.setText(v)
        }

    val grip = FrameLayout(ctx)
    private val chips = LinearLayout(ctx)
    private val chipScroll = HorizontalScrollView(ctx)
    private val status = TextView(ctx)
    private val note = EditText(ctx)
    private val copy = ImageView(ctx)
    private val placeButton: TextView
    private val capture = ImageView(ctx)
    private val whole = ImageView(ctx)
    private val close = ImageView(ctx)
    private var busy = false

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply { setColor(BrowserService.BG) }
        isClickable = true // nothing falls through to what's under it
        isFocusable = false // nor does it take the focus (and its grey highlight) when the text lets go of it
        defaultFocusHighlightEnabled = false

        val hairline = View(ctx)
        hairline.setBackgroundColor(HAIRLINE)
        addView(hairline, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max(1, dp(1) / 2 + 1)))
        if (!docked) {
            // The grip: a slim full-width strip on the top edge, with a short bar in the middle, dragged up and down.
            val bar = View(ctx)
            bar.background = GradientDrawable().apply {
                setColor(BrowserService.MUTED)
                cornerRadius = dp(2).toFloat()
            }
            grip.addView(bar, FrameLayout.LayoutParams(dp(32), dp(4), Gravity.CENTER))
            grip.contentDescription = "Drag to move"
            grip.setOnTouchListener(GripTouch())
            addView(grip, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16)))
        }

        // The chips, only when there are some.
        chips.orientation = HORIZONTAL
        chips.gravity = Gravity.CENTER_VERTICAL
        chipScroll.isHorizontalScrollBarEnabled = false
        chipScroll.addView(chips, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(chipScroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(CHIP_ROW)).apply {
            leftMargin = dp(12)
            rightMargin = dp(12)
        })

        // The tools: freeze, whole, (docked) the place, and at the right end the chevron that puts the collector away.
        // The icons are plain round buttons, with no box; the freeze toggle, when on, gets a soft fill and the
        // accent. The chevron's disc and the round Copy under it are the same size against the same right edge, so
        // they share a centre line.
        val bar = LinearLayout(ctx)
        bar.gravity = Gravity.CENTER_VERTICAL
        fun icon(v: ImageView, res: Int, run: () -> Unit) {
            v.setImageResource(res)
            v.setColorFilter(ICON)
            v.scaleType = ImageView.ScaleType.CENTER
            v.background = round(false)
            v.setOnClickListener {
                // Taken as it is on screen: after the keyboard has gone and the layout has settled.
                val ime = rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
                hideKeyboard()
                if (ime) postDelayed({ run() }, 450) else run()
            }
            bar.addView(v, LayoutParams(dp(BUTTON), dp(BUTTON)).apply { marginEnd = dp(4) })
        }
        icon(capture, R.drawable.ccm_freeze) { onCapture() }
        icon(whole, R.drawable.ccm_whole) { onWhole() }
        whole.contentDescription = if (docked) "Take the whole page in view" else "Take the whole screen"
        // The place: a small outlined pill.
        placeButton = button("+ Note", ICON, pill(Color.TRANSPARENT, BrowserService.LINE, dp(16)))
        placeButton.typeface = Typeface.DEFAULT
        placeButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        placeButton.minWidth = 0
        placeButton.setPadding(dp(12), 0, dp(12), 0)
        placeButton.visibility = GONE
        bar.addView(placeButton, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply { marginStart = dp(2) })
        bar.addView(View(ctx), LayoutParams(0, 1, 1f))
        close.setImageResource(R.drawable.ccm_chevron_put_away)
        close.setColorFilter(ICON)
        close.scaleType = ImageView.ScaleType.CENTER
        close.background = round(true) // a soft disc
        close.contentDescription = if (docked) "Close the collector" else "Close the lens"
        close.setOnClickListener {
            hideKeyboard()
            onClose()
        }
        bar.addView(close, LayoutParams(dp(ROUND), dp(ROUND)))
        addView(bar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(if (docked) 8 else 2)
            leftMargin = dp(12)
            rightMargin = dp(12)
        })

        // What went wrong with the last Copy, if it did.
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        status.setTextColor(BrowserService.TEXT)
        status.visibility = GONE
        addView(status, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
            leftMargin = dp(14)
            rightMargin = dp(14)
        })

        // The note: one full-width rounded strip that grows to four lines, its caret in the accent.
        note.setHintTextColor(PLACEHOLDER)
        note.setTextColor(BrowserService.TEXT)
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
        note.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        note.isSingleLine = false
        note.minLines = 1
        note.maxLines = 4
        note.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        note.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
        note.minHeight = dp(ROUND)
        note.setPadding(dp(16), dp(10), dp(16), dp(10))
        note.background = pill(NOTE_FILL, NOTE_LINE, dp(ROUND / 2))
        note.textCursorDrawable = GradientDrawable().apply {
            setColor(BrowserService.ACCENT)
            setSize(dp(2), 1)
        }
        note.focusOnlyOnTouch { onNoteTouch() }
        note.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(e: Editable?) {
                changed()
                onTextChanged(e?.toString() ?: "")
            }
        })
        // The note row: the note, and Copy at its right, round, icon only; grey (not a dimmed accent) while there's
        // nothing to send.
        val row = LinearLayout(ctx)
        row.gravity = Gravity.BOTTOM // with the note's last line as it grows
        row.addView(note, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        copy.setImageResource(R.drawable.ccm_copy)
        copy.scaleType = ImageView.ScaleType.CENTER
        copy.contentDescription = "Copy"
        copy.setOnClickListener {
            if (busy || !ready()) return@setOnClickListener
            hideKeyboard()
            onCopy()
        }
        row.addView(copy, LayoutParams(dp(ROUND), dp(ROUND)).apply { marginStart = dp(8) })
        addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
            leftMargin = dp(12)
            rightMargin = dp(12)
            bottomMargin = dp(10)
        })
        frozen = false
        piecesChanged(emptyList())
    }

    /** Something to send: text, or an image. */
    private fun ready() = note.text.isNotBlank() || pieces.isNotEmpty()

    /** The note's placeholder and Copy's state, after the text, the chips or the freeze changed. */
    private fun changed() {
        note.hint = if (frozen && pieces.isEmpty()) "Drag to select" else "Add a note"
        val on = ready() || busy
        copy.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) BrowserService.ACCENT else OFF_FILL)
        }
        copy.setColorFilter(if (on) BrowserService.ON_ACCENT else OFF_TEXT)
    }

    /** The text button left of capture: [text] runs [run]; null hides it. */
    fun action(text: String?, run: () -> Unit = {}) {
        placeButton.visibility = if (text == null) GONE else VISIBLE
        placeButton.text = text ?: ""
        placeButton.contentDescription = text
        placeButton.setOnClickListener { run() }
    }

    /** New chips (and, if one is given, that one scrolled into view). */
    fun piecesChanged(list: List<Piece>, show: Int = -1) {
        pieces = list
        chips.removeAllViews()
        for (i in list.indices) chips.addView(chip(i))
        chipScroll.visibility = if (list.isEmpty()) GONE else VISIBLE
        changed()
        if (show >= 0) chipScroll.post {
            chips.getChildAt(show)?.let { chipScroll.requestChildRectangleOnScreen(chips, Rect(it.left, 0, it.right, it.height), false) }
        }
    }

    /** A chip's picture again (its piece changed shape or place). */
    fun redraw(i: Int) {
        (chips.getChildAt(i) as? ViewGroup)?.getChildAt(0)?.invalidate()
    }

    /** Copy is under way. */
    fun sending() {
        busy = true
        status.visibility = GONE
        copy.alpha = 0.6f
    }

    /** Sent: Copy shows a check until the collector goes (or resets). */
    fun sent() {
        copy.alpha = 1f
        copy.setImageResource(R.drawable.ccm_check)
    }

    /** Copy failed: the draft stays, and the reason shows. */
    fun failed(why: String) {
        busy = false
        changed()
        copy.alpha = 1f
        status.text = "Not sent: $why"
        status.visibility = VISIBLE
    }

    /** Ready for another clip (docked, after one was sent). */
    fun reset() {
        busy = false
        changed()
        copy.alpha = 1f
        copy.setImageResource(R.drawable.ccm_copy)
        status.visibility = GONE
    }

    fun showKeyboard() {
        if (note.hasFocus()) context.getSystemService(InputMethodManager::class.java).showSoftInput(note, 0)
    }

    fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
        note.clearFocus()
    }

    val typing get() = note.hasFocus()

    /** A new line in the text, tagged with the place: "[p.4] ". */
    private fun addNote() {
        val t = note.text.toString().trimEnd()
        val tag = place?.let { "[$it] " } ?: ""
        val add = (if (t.isEmpty()) "" else "\n") + tag
        note.setText(t + add)
        note.setSelection(note.text.length)
        note.focusNow()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(note, 0)
    }

    /** A piece's chip: its thumbnail, drawn live, and an × that removes it. */
    private fun chip(i: Int): View {
        val p = pieces[i]
        val h = dp(CHIP_ROW - 14)
        val w = (h * p.width / max(1f, p.height.toFloat())).roundToInt().coerceIn(dp(28), dp(96))
        val f = FrameLayout(context)
        val thumb = Thumb(context, i)
        thumb.contentDescription = "Image ${i + 1}"
        thumb.setOnClickListener { onTapPiece(i) }
        f.addView(thumb, FrameLayout.LayoutParams(w, h, Gravity.BOTTOM or Gravity.START).apply { topMargin = dp(8); rightMargin = dp(8) })
        val x = ImageView(context)
        x.setImageResource(R.drawable.ccm_close)
        x.setColorFilter(BrowserService.TEXT)
        x.scaleType = ImageView.ScaleType.FIT_CENTER
        x.setPadding(dp(4), dp(4), dp(4), dp(4))
        x.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(BrowserService.FIELD)
            setStroke(max(1, dp(1)), BrowserService.LINE)
        }
        x.contentDescription = "Remove image ${i + 1}"
        x.setOnClickListener { onRemove(i) }
        f.addView(x, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END))
        f.layoutParams = LayoutParams(w + dp(8), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(6) }
        return f
    }

    private inner class Thumb(c: Context, private val i: Int) : View(c) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val dst = RectF()
        private val clip = Path()

        override fun onDraw(c: Canvas) {
            val p = pieces.getOrNull(i) ?: return
            if (p.width <= 0 || p.height <= 0) return
            // Fitted into the chip, centred: a live piece may have changed shape since the chip was made.
            val k = min(width / p.width.toFloat(), height / p.height.toFloat())
            val w = p.width * k
            val h = p.height * k
            dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            // A rounded chip: the picture clipped to its corners, the outline round them.
            val r = 8 * resources.displayMetrics.density
            clip.reset()
            clip.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, Path.Direction.CW)
            c.save()
            c.clipPath(clip)
            c.drawColor(BrowserService.FIELD)
            p.draw(c, dst, paint)
            c.restore()
            outline.color = if (p.active) BrowserService.ACCENT else CHIP_OFF
            outline.strokeWidth = 1.5f * resources.displayMetrics.density
            val half = outline.strokeWidth / 2
            c.drawRoundRect(half, half, width - half, height - half, r - half, r - half, outline)
        }
    }

    /** The grip: reports how far the finger has moved down since it landed (the host moves the collector). */
    private inner class GripTouch : OnTouchListener {
        private var downY = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> downY = e.rawY
                MotionEvent.ACTION_MOVE -> onDrag(e.rawY - downY)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onDrag(null)
            }
            return true
        }
    }

    private fun button(text: String, color: Int, bg: GradientDrawable) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(color)
        gravity = Gravity.CENTER
        minWidth = dp(72)
        setPadding(dp(14), 0, dp(14), 0)
        background = bg
        isClickable = true
        isFocusable = false // a tap leaves the text's focus (and the keyboard) where it is until the button acts
    }

    /** A pill (or any rounded box) of [fill], with a 1dp [stroke] border. */
    private fun pill(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
        setStroke(max(1, dp(1)), stroke)
    }

    /** A round icon button's background: a ripple, over a soft fill when [on]. */
    private fun round(on: Boolean): RippleDrawable {
        val disc = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) ON_FILL else Color.TRANSPARENT)
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(RIPPLE), disc, mask)
    }

    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(10).toFloat()
        setStroke(max(1, dp(1)), stroke)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val CHIP_ROW = 56
        private const val BUTTON = 40
        private const val ROUND = 44 // the chevron's disc and Copy, one over the other on a centre line

        // The toolbar's own greys (the theme's are in BrowserService): icons, the empty Copy, the note.
        private val ICON = Color.parseColor("#c3c8cf") // the icon buttons, light steel
        private const val ON_FILL = 0x1AFFFFFF // an icon button that's on: white at 10%
        private const val RIPPLE = 0x33FFFFFF
        private val OFF_FILL = Color.parseColor("#1e1e22") // Copy, with nothing to send
        private val OFF_TEXT = Color.parseColor("#5f646b")
        private val NOTE_FILL = Color.parseColor("#121214")
        private val NOTE_LINE = Color.parseColor("#26262a")
        private val PLACEHOLDER = Color.parseColor("#6f757d")
        private val HAIRLINE = Color.parseColor("#1f1f23")
        /** A chip, or a selection's box, that isn't the active one. */
        val CHIP_OFF = Color.parseColor("#3a3a40")

        /** The notes in a docked collector's text: lines starting with a place in brackets, "[p.4] …". */
        private val NOTE = Regex("^\\[([^\\]]+)]\\s*(.*)$")

        /** The text split into what isn't a note, and the notes: (where, text). */
        fun notes(text: String): Pair<String, List<Pair<String, String>>> {
            val rest = mutableListOf<String>()
            val notes = mutableListOf<Pair<String, String>>()
            for (line in text.lines()) {
                val m = NOTE.find(line.trim())
                if (m != null && m.groupValues[2].isNotBlank()) notes += m.groupValues[1] to m.groupValues[2].trim()
                else if (m == null) rest += line
            }
            return rest.joinToString("\n").trim() to notes
        }
    }
}
