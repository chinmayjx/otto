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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The handle's tray: what a long press on the handle opens, beside it (the lens, the assistant). One gesture: the
 * finger that pressed slides onto an item (it lights up) and lifts there to pick it; lifted anywhere else, the tray
 * closes. Lifted without moving, the tray stays, to tap an item (or outside, to close it), as the second screen's
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
    private lateinit var root: LinearLayout

    /** Opens it left of the handle ([handleWidth] px wide, on the right edge), centred on [y] on the screen. */
    @SuppressLint("ClickableViewAccessibility")
    fun show(y: Float, handleWidth: Int) {
        current?.dismiss()
        current = this
        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(BrowserService.FIELD)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), BrowserService.LINE)
            }
            elevation = dp(8).toFloat()
        }
        items.forEachIndexed { i, item ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(18), 0)
                minimumHeight = dp(52)
                isClickable = true
                contentDescription = item.label
                setOnClickListener { pick(i) }
            }
            row.addView(ImageView(ctx).apply {
                setImageResource(item.icon)
                imageTintList = ColorStateList.valueOf(BrowserService.TEXT)
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) })
            row.addView(TextView(ctx).apply {
                text = item.label
                textSize = 16f
                setTextColor(BrowserService.TEXT)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            rows += row
            root.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        }
        root.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) dismiss() // a tap elsewhere, with the tray left open
            false
        }
        val height = items.size * dp(52) + dp(12)
        val screenH = wm.currentWindowMetrics.bounds.height()
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END
            x = handleWidth + dp(4)
            this.y = max(dp(40), min((y - height / 2f).roundToInt(), screenH - height - dp(40)))
            title = "Otto tray"
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        root.alpha = 0f
        root.translationX = dp(24).toFloat()
        wm.addView(root, lp)
        root.animate().alpha(1f).translationX(0f).setDuration(120)
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
            i >= 0 -> pick(i)
            moved -> dismiss()
            else -> if (hot >= 0) { light(hot, false); hot = -1 }
        }
    }

    private fun pick(i: Int) {
        root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        dismiss()
        items[i].run()
    }

    private fun light(i: Int, on: Boolean) {
        val row = rows[i]
        row.background = if (on) GradientDrawable().apply {
            setColor(BrowserService.ACCENT)
            cornerRadius = dp(13).toFloat()
        } else null
        val color = if (on) BrowserService.ON_ACCENT else BrowserService.TEXT
        ((row as LinearLayout).getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(color)
        (row.getChildAt(1) as TextView).setTextColor(color)
    }

    private fun itemAt(x: Float, y: Float): Int {
        val loc = IntArray(2)
        rows.forEachIndexed { i, v ->
            v.getLocationOnScreen(loc)
            // A little give to the left and right: the finger comes in from the handle, at the tray's edge.
            if (x >= loc[0] - dp(8) && x < loc[0] + v.width + dp(40) && y >= loc[1] && y < loc[1] + v.height) return i
        }
        return -1
    }

    fun dismiss() {
        if (current === this) current = null
        if (::root.isInitialized && root.isAttachedToWindow) wm.removeView(root)
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()

    companion object {
        var current: Tray? = null
            private set
    }
}
