package io.github.otto

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The handle's tray: what a long press on the handle opens, a row of round icon buttons beside it (==|: the lens next
 * to the handle, then the assistant). One gesture: the finger that pressed slides left onto an item (it lights up,
 * with its name above it) and lifts there to pick it; lifted anywhere else, the tray closes. Lifted without moving, the tray stays, to tap an item (or outside, to close it), as the second screen's
 * menus do (Screen.onPress).
 *
 * The handle's touch listener keeps the gesture (Android sends the moves to the window the finger came down on) and
 * passes them here: [hover] and [release].
 */
class Tray(private val ctx: Context, private val items: List<Item>) {
    class Item(val icon: Int, val label: String, val run: () -> Unit)

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val rows = mutableListOf<View>()
    private var hot = -1
    private lateinit var root: FrameLayout
    private lateinit var tip: TextView

    /** Opens it left of the handle ([handleWidth] px wide, on the right edge), centred on [y] on the screen. */
    @SuppressLint("ClickableViewAccessibility")
    fun show(y: Float, handleWidth: Int) {
        current?.dismiss()
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
            visibility = View.INVISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(tip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(TIP_ROOM), dp(TIP_H), 0, dp(8)) // room for the name above, and the buttons' shadows
        }
        // Right to left from the handle: the first item is the nearest.
        items.forEachIndexed { i, item ->
            val b = ImageView(ctx).apply {
                setImageResource(item.icon)
                scaleType = ImageView.ScaleType.CENTER
                isClickable = true
                contentDescription = item.label
                setOnClickListener { pick(i) }
                elevation = dp(6).toFloat()
            }
            rows += b
            light(i, false)
            row.addView(b, 0, LinearLayout.LayoutParams(dp(BUTTON_W), dp(BUTTON_H)).apply { marginEnd = dp(GAP) })
        }
        root.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) dismiss() // a tap elsewhere, with the tray left open
            false
        }
        val screenH = wm.currentWindowMetrics.bounds.height()
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END
            x = handleWidth + dp(4)
            // The buttons' row centred on the press.
            this.y = max(dp(40), min((y - dp(TIP_H) - dp(BUTTON_H) / 2f).roundToInt(), screenH - dp(TIP_H + BUTTON_H + 8) - dp(40)))
            title = "Otto tray"
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        row.alpha = 0f
        row.translationX = dp(24).toFloat()
        wm.addView(root, lp)
        row.animate().alpha(1f).translationX(0f).setDuration(120)
    }

    /** The finger at a point on the screen: the item under it lights up (with a tick as it changes). */
    fun hover(x: Float, y: Float) {
        val i = itemAt(x, y)
        if (i == hot) return
        if (hot >= 0) light(hot, false)
        hot = i
        if (i >= 0) {
            light(i, true)
            root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /** The finger lifted: on an item, it's picked; elsewhere after a drag, the tray closes; without a move, it stays. */
    fun release(x: Float, y: Float, moved: Boolean) {
        val i = itemAt(x, y)
        when {
            !moved -> if (hot >= 0) { light(hot, false); hot = -1 } // a press without a drag: it stays, to tap
            i >= 0 -> pick(i)
            else -> dismiss()
        }
    }

    private fun pick(i: Int) {
        root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        dismiss()
        items[i].run()
    }

    /** An item lit (lavender, its name shown above it) or not (dark). */
    private fun light(i: Int, on: Boolean) {
        val b = rows[i] as ImageView
        b.background = GradientDrawable().apply {
            setColor(if (on) BrowserService.ACCENT else BrowserService.FIELD)
            cornerRadius = dp(BUTTON_H / 2).toFloat()
        }
        b.imageTintList = ColorStateList.valueOf(if (on) BrowserService.ON_ACCENT else BrowserService.TEXT)
        if (!on) {
            if (hot == i || hot < 0) tip.visibility = View.INVISIBLE
            return
        }
        tip.text = items[i].label
        tip.visibility = View.VISIBLE
        // Centred over the button, inside the tray's window.
        tip.post {
            val centre = (b.parent as View).left + b.left + b.width / 2f
            tip.x = (centre - tip.width / 2f).coerceIn(0f, (root.width - tip.width).toFloat().coerceAtLeast(0f))
            tip.y = 0f
        }
    }

    private fun itemAt(x: Float, y: Float): Int {
        val loc = IntArray(2)
        rows.forEachIndexed { i, v ->
            v.getLocationOnScreen(loc)
            // The row is dragged along sideways: half the gap either side, and plenty of give up and down.
            val half = dp(GAP) / 2
            if (x >= loc[0] - half && x < loc[0] + v.width + half && y >= loc[1] - dp(28) && y < loc[1] + v.height + dp(28)) return i
        }
        // Between the nearest button and the handle: nothing yet.
        return -1
    }

    fun dismiss() {
        if (current === this) current = null
        if (::root.isInitialized && root.isAttachedToWindow) wm.removeView(root)
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()

    companion object {
        private const val BUTTON_W = 48
        private const val BUTTON_H = 40
        private const val GAP = 6
        private const val TIP_H = 36 // the name's room above the buttons
        private const val TIP_ROOM = 40 // and to their left, for a name wider than its button

        var current: Tray? = null
            private set
    }
}
