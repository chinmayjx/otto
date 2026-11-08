package io.github.otto

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
 * The collector: what goes into a clip (Clips), gathered in one full-width bar. A row of image chips (each with an
 * ×), a text field that grows from one line to four, and one button, Copy. The same component in two places:
 *
 * - floating over the lens's frozen screen (Lens), with a grip on its top edge to drag it up and down;
 * - docked under the artifact viewer (ui/Viewer, in an AndroidView), with a capture button that freezes the
 *   viewer to take a part of it, and a place button that starts a note tagged with where the reader is ("p.4").
 *
 * It holds no clip logic of its own: its host says what the chips are (Piece), and what Copy, ×, capture and the
 * place button do.
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
    var onRemove: (Int) -> Unit = {}
    var onTapPiece: (Int) -> Unit = {}
    var onTextChanged: (String) -> Unit = {}
    /** Docked: freeze the viewer to take a part of it. */
    var onCapture: () -> Unit = {}
    /** The grip's drag (lens): by how much the finger moved down, then null when it's let go. */
    var onDrag: (Float?) -> Unit = {}

    var pieces: List<Piece> = emptyList()
        private set

    /** Where the reader is (docked): the place button starts a note with it. Null: nowhere in particular. */
    var place: String? = null
        set(v) {
            field = v
            placeButton.text = "+ " + (v ?: "Note")
        }

    var text: String
        get() = note.text.toString()
        set(v) {
            if (v != note.text.toString()) note.setText(v)
        }

    val grip = FrameLayout(ctx)
    private val chips = LinearLayout(ctx)
    private val chipScroll = HorizontalScrollView(ctx)
    private val hint = TextView(ctx)
    private val status = TextView(ctx)
    private val note = EditText(ctx)
    private val copy: TextView
    private val placeButton: TextView
    private val capture = ImageView(ctx)
    private var busy = false

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply { setColor(BrowserService.BG) }
        isClickable = true // nothing falls through to what's under it
        isFocusable = false // nor does it take the focus (and its grey highlight) when the text lets go of it
        defaultFocusHighlightEnabled = false

        if (!docked) {
            // The grip: a full-width strip on the top edge, with a short bar in the middle, dragged up and down.
            val bar = View(ctx)
            bar.background = GradientDrawable().apply {
                setColor(BrowserService.MUTED)
                cornerRadius = dp(2).toFloat()
            }
            grip.addView(bar, FrameLayout.LayoutParams(dp(36), dp(4), Gravity.CENTER))
            grip.contentDescription = "Drag to move"
            grip.setOnTouchListener(GripTouch())
            addView(grip, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)))
        }
        val hairline = View(ctx)
        hairline.setBackgroundColor(BrowserService.LINE)
        addView(hairline, 0, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max(1, dp(1) / 2 + 1)))

        // The chips (or a hint), then, docked, the place and capture buttons at the right end.
        val top = LinearLayout(ctx)
        top.gravity = Gravity.CENTER_VERTICAL
        val strip = FrameLayout(ctx)
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        hint.setTextColor(BrowserService.MUTED)
        hint.gravity = Gravity.CENTER_VERTICAL
        hint.maxLines = 2
        hint.ellipsize = TextUtils.TruncateAt.END
        strip.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        chips.orientation = HORIZONTAL
        chips.gravity = Gravity.CENTER_VERTICAL
        chipScroll.isHorizontalScrollBarEnabled = false
        chipScroll.addView(chips, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        strip.addView(chipScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        top.addView(strip, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        placeButton = button("+ Note", BrowserService.TEXT, field(BrowserService.FIELD, BrowserService.LINE))
        placeButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        placeButton.minWidth = 0
        placeButton.setPadding(dp(10), 0, dp(10), 0)
        placeButton.contentDescription = "Add a note here"
        placeButton.setOnClickListener { addNote() }
        capture.setImageResource(R.drawable.ccm_capture)
        capture.setColorFilter(BrowserService.TEXT)
        capture.scaleType = ImageView.ScaleType.CENTER
        capture.background = field(BrowserService.FIELD, BrowserService.LINE)
        capture.contentDescription = "Take a part of the page"
        capture.setOnClickListener {
            hideKeyboard()
            onCapture()
        }
        if (docked) {
            top.addView(placeButton, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(8) })
            top.addView(capture, LayoutParams(dp(40), dp(36)).apply { marginStart = dp(6) })
        }
        addView(top, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(CHIP_ROW)).apply {
            topMargin = dp(if (docked) 6 else 0)
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

        // The text, then Copy.
        val row = LinearLayout(ctx)
        row.gravity = Gravity.BOTTOM
        note.hint = if (docked) "Notes for your agent" else "What should your agent see here?"
        note.setHintTextColor(BrowserService.MUTED)
        note.setTextColor(BrowserService.TEXT)
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
        note.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        note.isSingleLine = false
        note.minLines = 1
        note.maxLines = 4
        note.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        note.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
        note.minHeight = dp(44)
        note.setPadding(dp(12), dp(9), dp(12), dp(9))
        note.background = field(BrowserService.FIELD, BrowserService.LINE)
        note.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = onTextChanged(s?.toString() ?: "")
        })
        row.addView(note, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        copy = button("Copy", BrowserService.ON_ACCENT, field(BrowserService.ACCENT, BrowserService.ACCENT))
        copy.contentDescription = "Copy: send to the box, and a reference to the clipboard"
        copy.setOnClickListener {
            if (busy) return@setOnClickListener
            hideKeyboard()
            onCopy()
        }
        row.addView(copy, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginStart = dp(8) })
        addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
            leftMargin = dp(12)
            rightMargin = dp(12)
            bottomMargin = dp(10)
        })
        hint.text = if (docked) "Notes, and parts of the page: + takes one." else "Drag to select. Nothing selected sends the whole screen."
        piecesChanged(emptyList())
    }

    fun setHint(text: String) {
        hint.text = text
    }

    /** New chips (and, if one is given, that one scrolled into view). */
    fun piecesChanged(list: List<Piece>, show: Int = -1) {
        pieces = list
        chips.removeAllViews()
        for (i in list.indices) chips.addView(chip(i))
        hint.visibility = if (list.isEmpty()) VISIBLE else GONE
        chipScroll.visibility = if (list.isEmpty()) GONE else VISIBLE
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
        copy.text = "…"
    }

    fun sent() {
        copy.text = "Copied"
    }

    /** Copy failed: the draft stays, and the reason shows. */
    fun failed(why: String) {
        busy = false
        copy.text = "Copy"
        status.text = "Not sent: $why"
        status.visibility = VISIBLE
    }

    /** Ready for another clip (docked, after one was sent). */
    fun reset() {
        busy = false
        copy.text = "Copy"
        status.visibility = GONE
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
        note.requestFocus()
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

        override fun onDraw(c: Canvas) {
            val p = pieces.getOrNull(i) ?: return
            if (p.width <= 0 || p.height <= 0) return
            // Fitted into the chip, centred: a live piece may have changed shape since the chip was made.
            val k = min(width / p.width.toFloat(), height / p.height.toFloat())
            val w = p.width * k
            val h = p.height * k
            dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            c.drawColor(BrowserService.FIELD)
            p.draw(c, dst, paint)
            val on = p.active
            outline.color = if (on) BrowserService.ACCENT else BrowserService.LINE
            outline.strokeWidth = if (on) dp(2).toFloat() else max(1, dp(1)).toFloat()
            val half = outline.strokeWidth / 2
            c.drawRect(half, half, width - half, height - half, outline)
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

    private fun field(color: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(10).toFloat()
        setStroke(max(1, dp(1)), stroke)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val CHIP_ROW = 58

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
