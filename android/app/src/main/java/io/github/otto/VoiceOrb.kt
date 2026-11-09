package io.github.otto

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The assistant's orb: a circle floating over every app while a voice call is on (VoiceService), with what the call
 * is doing under it. It grows with the microphone's level while listening and with the voice's while it speaks;
 * an arc turns round it while Codex works. A tap mutes and unmutes (and after an error, tries again); the small ×
 * ends the call. It can be dragged anywhere.
 */
class VoiceOrb(private val ctx: Context, private val tap: () -> Unit, private val close: () -> Unit) {
    enum class Look(val label: String) {
        CONNECTING("Connecting"), LISTENING("Listening"), SPEAKING("Speaking"), WORKING("Working"),
        MUTED("Muted"), ERROR("Tap to retry"),
    }

    /** The call it shows, read each frame; null while there's none (after an error). */
    var call: VoiceCall? = null
    /** Set after a failure: the orb shows it, still, with a retry. */
    var error: String? = null
        set(v) {
            field = v
            orb.invalidate()
        }
    /** What Codex is doing, in a few words, shown for a while beside "Working". */
    private var note: String? = null
    private var noteUntil = 0L

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val orb = OrbView(ctx)
    private val label = TextView(ctx)
    private val root = LinearLayout(ctx)
    private lateinit var lp: WindowManager.LayoutParams
    private var look = Look.CONNECTING

    fun note(text: String) {
        note = text
        noteUntil = System.currentTimeMillis() + 6000
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        val top = FrameLayout(ctx)
        top.addView(orb, FrameLayout.LayoutParams(dp(ORB), dp(ORB)))
        val end = ImageView(ctx).apply {
            setImageResource(R.drawable.ccm_close)
            imageTintList = android.content.res.ColorStateList.valueOf(BrowserService.TEXT)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(BrowserService.FIELD)
                setStroke(dp(1), BrowserService.LINE)
            }
            contentDescription = "End the assistant"
            setOnClickListener { close() }
        }
        top.addView(end, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END))
        root.addView(top)
        label.apply {
            textSize = 12f
            setTextColor(BrowserService.TEXT)
            maxLines = 1
            maxWidth = dp(140)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(10), dp(3), dp(10), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.argb(220, 0, 0, 0))
                cornerRadius = dp(10).toFloat()
            }
        }
        root.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })

        val bounds = wm.currentWindowMetrics.bounds
        lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.width() - dp(ORB) - dp(40)
            y = (bounds.height() * 0.62f).roundToInt()
            title = "Otto assistant"
        }

        // A tap: mute (or retry); a drag: the orb moves.
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        orb.setOnClickListener { tap() } // for accessibility; touches are handled below
        orb.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var dragging = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragging = false
                        orb.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!dragging && max(abs(e.rawX - downX), abs(e.rawY - downY)) > slop) dragging = true
                        if (dragging) {
                            lp.x = startX + (e.rawX - downX).roundToInt()
                            lp.y = startY + (e.rawY - downY).roundToInt()
                            wm.updateViewLayout(root, lp)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        orb.animate().scaleX(1f).scaleY(1f).setDuration(120)
                        if (!dragging && e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                    }
                }
                return true
            }
        })
        wm.addView(root, lp)
        orb.postInvalidateOnAnimation()
    }

    fun dismiss() {
        if (root.isAttachedToWindow) wm.removeView(root)
    }

    /** What the orb shows now, from the call: an error first, then connecting, muted, speaking, working, listening. */
    private fun lookNow(): Look {
        val c = call
        return when {
            error != null || c == null -> Look.ERROR
            !c.connected || c.boxState == "connecting" -> Look.CONNECTING
            c.muted -> Look.MUTED
            c.outLevel > 0.08f || c.boxState == "speaking" -> Look.SPEAKING
            c.working -> Look.WORKING
            else -> Look.LISTENING
        }
    }

    private fun setLook(l: Look) {
        val text = when {
            l == Look.WORKING && note != null && System.currentTimeMillis() < noteUntil -> note!!
            else -> l.label
        }
        if (label.text != text) label.text = text
        if (l == look) return
        look = l
        orb.contentDescription = when (l) {
            Look.ERROR -> "Assistant: ${error ?: "stopped"}. Tap to retry"
            Look.MUTED -> "Assistant: muted. Tap to unmute"
            else -> "Assistant: ${l.label.lowercase()}. Tap to mute"
        }
    }

    private inner class OrbView(ctx: Context) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val icons = mutableMapOf<Int, Drawable>()
        private var level = 0f
        private val arc = RectF()

        private fun icon(res: Int, color: Int): Drawable = icons.getOrPut(res) { ctx.getDrawable(res)!!.mutate() }.also { it.setTint(color) }

        override fun onDraw(c: Canvas) {
            val l = lookNow()
            setLook(l)
            val call = call
            val target = when (l) {
                Look.LISTENING -> call?.micLevel ?: 0f
                Look.SPEAKING -> call?.outLevel ?: 0f
                else -> 0f
            }
            level += (target - level) * 0.3f
            val cx = width / 2f
            val cy = height / 2f
            val r = dp(26).toFloat()
            val t = System.currentTimeMillis()
            when (l) {
                Look.CONNECTING -> {
                    // Breathing grey: waiting on the box (it gives up within a minute, and then it's an error).
                    fill.color = BrowserService.MUTED
                    fill.alpha = (120 + 80 * sin(t / 300.0)).roundToInt()
                    c.drawCircle(cx, cy, r, fill)
                }
                Look.LISTENING, Look.SPEAKING, Look.WORKING -> {
                    val speaking = l == Look.SPEAKING
                    halo.color = BrowserService.ACCENT
                    halo.alpha = (50 + 90 * level).roundToInt()
                    c.drawCircle(cx, cy, r * (1.05f + 0.5f * level), halo)
                    fill.color = if (speaking) Color.parseColor("#d6cdff") else BrowserService.ACCENT
                    c.drawCircle(cx, cy, r * (0.92f + 0.22f * level), fill)
                    if (l == Look.WORKING) {
                        ring.color = BrowserService.TEXT
                        ring.strokeWidth = dp(3).toFloat()
                        val a = (t / 4 % 360).toFloat()
                        arc.set(cx - r - dp(6), cy - r - dp(6), cx + r + dp(6), cy + r + dp(6))
                        c.drawArc(arc, a, 80f, false, ring)
                    }
                    if (!speaking) drawIcon(c, icon(R.drawable.ccm_mic, BrowserService.ON_ACCENT), cx, cy)
                }
                Look.MUTED -> {
                    fill.color = BrowserService.FIELD
                    fill.alpha = 255
                    c.drawCircle(cx, cy, r, fill)
                    ring.color = BrowserService.LINE
                    ring.strokeWidth = dp(1).toFloat()
                    c.drawCircle(cx, cy, r, ring)
                    drawIcon(c, icon(R.drawable.ccm_mic_off, BrowserService.MUTED), cx, cy)
                }
                Look.ERROR -> {
                    fill.color = BrowserService.FIELD
                    fill.alpha = 255
                    c.drawCircle(cx, cy, r, fill)
                    ring.color = ERROR
                    ring.strokeWidth = dp(2).toFloat()
                    c.drawCircle(cx, cy, r, ring)
                    drawIcon(c, icon(R.drawable.ccm_reload, ERROR), cx, cy)
                }
            }
            fill.alpha = 255
            // Still after an error: no animation that suggests something is happening.
            if (l != Look.ERROR) postInvalidateOnAnimation()
        }

        private fun drawIcon(c: Canvas, d: Drawable, cx: Float, cy: Float) {
            val s = dp(24) / 2
            d.setBounds((cx - s).roundToInt(), (cy - s).roundToInt(), (cx + s).roundToInt(), (cy + s).roundToInt())
            d.draw(c)
        }
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()

    companion object {
        private const val ORB = 96
        private val ERROR = Color.parseColor("#ff6b81")
    }
}
