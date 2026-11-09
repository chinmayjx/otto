package io.github.otto

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The assistant's pill: a dark pill floating over every app while a voice call is on (VoiceService). On its left a
 * small glass sphere with coloured light moving inside it, then a waveform and what the call is doing in words; on
 * its right a × that ends the call. The light and the bars follow the microphone's level while it listens and the
 * voice's while it speaks, and run along while Codex thinks. A tap mutes and unmutes (and after an error, tries
 * again). It can be dragged anywhere.
 */
class VoicePill(private val ctx: Context, private val tap: () -> Unit, private val close: () -> Unit) {
    /** What the pill shows, each with its words and the three colours of the sphere's light. */
    enum class Look(val label: String, val palette: IntArray) {
        CONNECTING("Connecting", colors("#8e949c", "#ab9cf2", "#6b7280")),
        LISTENING("Listening", colors("#6aa0ff", "#7ee0f0", "#ab9cf2")),
        THINKING("Thinking", colors("#ab9cf2", "#6aa0ff", "#f29cd8")),
        SPEAKING("Speaking", colors("#ab9cf2", "#f29cd8", "#d6c8ff")),
        MUTED("Muted", colors("#5b6068", "#4a4e55", "#5b6068")),
        ERROR("Tap to retry", colors("#ff6b81", "#c2485b", "#ff6b81")),
    }

    /** The call it shows, read each frame; null while there's none (after an error). */
    var call: VoiceCall? = null
    /** Set after a failure: the pill says so, with a retry. */
    var error: String? = null
        set(v) {
            field = v
            pill.invalidate()
        }
    /** What Codex is doing, in a few words, shown for a while after "Thinking". */
    private var note: String? = null
    private var noteUntil = 0L

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val pill = PillView(ctx)
    private val root = FrameLayout(ctx)
    private lateinit var lp: WindowManager.LayoutParams
    private var look: Look? = null

    fun note(text: String) {
        note = text
        noteUntil = System.currentTimeMillis() + 6000
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        root.addView(pill, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        // The ×, inside the pill on its right.
        val end = ImageView(ctx).apply {
            setImageResource(R.drawable.ccm_close)
            imageTintList = ColorStateList.valueOf(BrowserService.STEEL)
            scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(BrowserService.LINE)
            }
            contentDescription = "End the assistant"
            setOnClickListener { close() }
        }
        root.addView(end, FrameLayout.LayoutParams(dp(END), dp(END), Gravity.CENTER_VERTICAL or Gravity.END).apply {
            rightMargin = dp(ROOM) + dp((H - END) / 2)
        })

        val bounds = wm.currentWindowMetrics.bounds
        lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            // Placed by its middle, so it stays where it was put as its words get longer or shorter.
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = (bounds.height() * 0.7f).roundToInt()
            title = "Otto assistant"
        }

        // A tap: mute (or retry); a drag: the pill moves.
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        pill.setOnClickListener { tap() } // for accessibility; touches are handled below
        pill.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var dragging = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragging = false
                        root.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80)
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
                        root.animate().scaleX(1f).scaleY(1f).setDuration(120)
                        if (!dragging && e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                    }
                }
                return true
            }
        })
        wm.addView(root, lp)
        pill.postInvalidateOnAnimation()
    }

    fun dismiss() {
        if (root.isAttachedToWindow) wm.removeView(root)
    }

    /** What the pill shows now, from the call: an error first, then connecting, muted, speaking, thinking, listening. */
    private fun lookNow(): Look {
        val c = call
        return when {
            error != null || c == null -> Look.ERROR
            !c.connected || c.boxState == "connecting" -> Look.CONNECTING
            c.muted -> Look.MUTED
            c.outLevel > 0.08f || c.boxState == "speaking" -> Look.SPEAKING
            c.working -> Look.THINKING
            else -> Look.LISTENING
        }
    }

    /** Its words: the state, and while Codex thinks, what it's doing if that's fresh. */
    private fun words(l: Look): String {
        val n = note
        return if (l == Look.THINKING && n != null && System.currentTimeMillis() < noteUntil) "${l.label} · $n" else l.label
    }

    private fun setLook(l: Look) {
        if (l == look) return
        look = l
        pill.contentDescription = when (l) {
            Look.ERROR -> "Assistant: ${error ?: "stopped"}. Tap to retry"
            Look.MUTED -> "Assistant: muted. Tap to unmute"
            else -> "Assistant: ${l.label.lowercase()}. Tap to mute"
        }
    }

    /**
     * The pill, drawn each frame while it's on screen, with room round it for the sphere's halo and the shadow. In dp:
     * 46 tall, the sphere's centre 23 in, seven bars from 50 (8 apart), the words from 112, then the ×.
     */
    private inner class PillView(ctx: Context) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12.5f, ctx.resources.displayMetrics)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            color = BrowserService.TEXT
        }
        private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BrowserService.FIELD
            setShadowLayer(7 * d, 0f, 3 * d, Color.argb(90, 0, 0, 0))
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS } // "lighter": added
        private val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.DST_IN }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val path = Path()
        private val r = RectF()
        private val turn = Matrix()
        private val t0 = System.nanoTime()
        private var level = 0f
        private var shown = ""
        private var shownWidth = 0f

        /** The words as they fit in the pill at its widest, ellipsized. */
        private fun fit(s: String): String {
            val most = (MAX_W - TEXT_X - GAP - END - (H - END) / 2) * d
            return TextUtils.ellipsize(s, text, most, TextUtils.TruncateAt.END).toString()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = (TEXT_X + GAP + END + (H - END) / 2) * d + shownWidth
            setMeasuredDimension((w + 2 * ROOM * d).roundToInt(), ((H + 2 * ROOM) * d).roundToInt())
        }

        override fun onDraw(c: Canvas) {
            val l = lookNow()
            setLook(l)
            val words = fit(words(l))
            if (words != shown) {
                shown = words
                val w = text.measureText(words)
                if (w != shownWidth) {
                    shownWidth = w
                    requestLayout()
                }
            }
            val call = call
            val target = when (l) {
                Look.LISTENING -> call?.micLevel ?: 0f
                Look.SPEAKING -> call?.outLevel ?: 0f
                else -> 0f
            }
            level += (target - level) * 0.25f
            val t = (System.nanoTime() - t0) / 1e9f
            val left = ROOM * d
            val top = ROOM * d
            val cy = top + H / 2f * d
            // The pill.
            r.set(left, top, width - ROOM * d, top + H * d)
            c.drawRoundRect(r, H / 2f * d, H / 2f * d, body)
            sphere(c, left + 23 * d, cy, 15 * d, l, t, level)
            // The waveform: the level while listening or speaking, a travelling wave while thinking, flat when still.
            fill.shader = null
            fill.color = when (l) {
                Look.ERROR -> ERROR
                Look.MUTED -> STILL_BARS
                else -> BrowserService.ACCENT
            }
            val bx = left + 50 * d
            for (i in 0 until 7) {
                val h = d * when (l) {
                    Look.THINKING -> 4 + 6 * max(0f, sin(t * 6 - i * 0.6f))
                    Look.LISTENING, Look.SPEAKING -> 3 + 22 * level * (0.45f + 0.55f * abs(sin(t * 9 + i * 1.3f)))
                    Look.CONNECTING -> 3 + 3 * max(0f, sin(t * 3 - i * 0.5f))
                    else -> 3f
                }
                r.set(bx + i * 8 * d, cy - h / 2, bx + i * 8 * d + 3.5f * d, cy + h / 2)
                c.drawRoundRect(r, 2 * d, 2 * d, fill)
            }
            // The words.
            val fm = text.fontMetrics
            c.drawText(shown, left + TEXT_X * d, cy - (fm.ascent + fm.descent) / 2, text)
            if (isAttachedToWindow) postInvalidateOnAnimation()
        }

        /**
         * The sphere: dark glass with three soft blobs of coloured light inside, added together, their edges wobbling;
         * a highlight top left and a faint rim; round it, by state, a halo, a ring, a dotted ring or a running arc.
         */
        private fun sphere(c: Canvas, x: Float, y: Float, radius: Float, l: Look, t: Float, lv: Float) {
            val p = l.palette
            val still = l == Look.MUTED || l == Look.ERROR
            val live = when (l) {
                Look.LISTENING, Look.SPEAKING -> lv
                Look.THINKING -> 0.35f
                Look.CONNECTING -> 0.12f + 0.08f * sin(t * 2)
                else -> 0f
            }
            val rs = radius * (1 + 0.12f * live)
            // The halo.
            if (!still) {
                val out = rs * (1.7f + live * 0.6f)
                fill.shader = RadialGradient(x, y, out, intArrayOf(alpha(p[0], 0x66), alpha(p[0], 0x66), alpha(p[0], 0)),
                    floatArrayOf(0f, rs * 0.8f / out, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(x, y, out, fill)
            }
            line.shader = null
            if (l == Look.SPEAKING) {
                line.color = alpha(p[1], (80 * lv + 20).roundToInt())
                line.strokeWidth = 1.5f * d
                c.drawCircle(x, y, rs + 5 * d + lv * 9 * d, line)
            }
            // The glass, in a layer of its own: the light is added onto it, then everything outside the circle cut away.
            val box = rs + d
            c.saveLayer(x - box, y - box, x + box, y + box, null)
            fill.shader = null
            fill.color = GLASS
            c.drawCircle(x, y, rs, fill)
            val spin = if (l == Look.THINKING) t * 2.4f else t * 0.5f
            val tt = if (still) 0f else t
            for (i in 0 until 3) {
                val a = spin + i * 2.09f
                val off = rs * (0.28f + 0.12f * live)
                val bx = x + cos(a) * off
                val by = y + sin(a) * off * 0.8f + rs * 0.08f
                val br = rs * (if (still) 0.55f else 0.58f + 0.3f * live)
                light.shader = RadialGradient(bx, by, br * 1.2f,
                    intArrayOf(alpha(p[i], if (still) 0x88 else 0xee), alpha(p[i], if (still) 0x33 else 0x77), alpha(p[i], 0)),
                    floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
                blob(bx, by, br, tt, i * 1.7f, if (still) 0f else 0.08f + 0.18f * live)
                c.drawPath(path, light)
            }
            val hx = x - rs * 0.35f
            val hy = y - rs * 0.45f
            fill.shader = RadialGradient(hx, hy, rs * 0.7f, Color.argb(89, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
            c.drawRect(x - rs, y - rs, x + rs, y + rs, fill)
            fill.shader = null
            c.drawCircle(x, y, rs, cut)
            c.restore()
            // The rim: red after an error.
            line.strokeWidth = 1.2f * d
            line.color = if (l == Look.ERROR) ERROR else Color.argb(46, 255, 255, 255)
            c.drawCircle(x, y, rs, line)
            if (l == Look.CONNECTING) {
                // A dotted ring, turning.
                line.strokeWidth = 1.5f * d
                line.color = alpha(BrowserService.ACCENT, 0xaa)
                val phase = ((-t * 18 * d) % (7 * d) + 7 * d) % (7 * d)
                line.pathEffect = DashPathEffect(floatArrayOf(2 * d, 5 * d), phase)
                c.drawCircle(x, y, rs + 6 * d, line)
                line.pathEffect = null
            }
            if (l == Look.THINKING) {
                // An arc running round, fading out towards its tail.
                val deg = Math.toDegrees((t * 4.2f).toDouble()).toFloat() % 360
                val sweep = SweepGradient(x, y,
                    intArrayOf(alpha(BrowserService.ACCENT, 0), BrowserService.ACCENT, alpha(BrowserService.ACCENT, 0), alpha(BrowserService.ACCENT, 0)),
                    floatArrayOf(0f, 0.3f, 0.31f, 1f))
                turn.setRotate(deg, x, y)
                sweep.setLocalMatrix(turn)
                line.shader = sweep
                line.strokeWidth = 2.5f * d
                r.set(x - rs - 6 * d, y - rs - 6 * d, x + rs + 6 * d, y + rs + 6 * d)
                c.drawArc(r, deg, Math.toDegrees(1.9).toFloat(), false, line)
                line.shader = null
            }
            // Muted: a microphone struck through; after an error, a retry arrow.
            val s = radius / 26
            line.color = BrowserService.TEXT
            line.strokeWidth = 1.5f * d
            if (l == Look.MUTED) {
                r.set(x - 4 * s, y - 9 * s, x + 4 * s, y + 4 * s)
                c.drawRoundRect(r, 4 * s, 4 * s, line)
                r.set(x - 8 * s, y - 9 * s, x + 8 * s, y + 7 * s)
                c.drawArc(r, 27f, 126f, false, line)
                c.drawLine(x - 10 * s, y - 11 * s, x + 10 * s, y + 9 * s, line)
            }
            if (l == Look.ERROR) {
                r.set(x - 8 * s, y - 8 * s, x + 8 * s, y + 8 * s)
                c.drawArc(r, -17.2f, 296.2f, false, line)
                path.reset()
                path.moveTo(x + 8 * s, y - 7 * s)
                path.lineTo(x + 8.5f * s, y - 1.5f * s)
                path.lineTo(x + 3 * s, y - 2.5f * s)
                c.drawPath(path, line)
            }
        }

        /** A blob of light: a circle whose edge wobbles with time, by up to [wob] of its radius. */
        private fun blob(x: Float, y: Float, radius: Float, t: Float, k: Float, wob: Float) {
            path.reset()
            for (i in 0..48) {
                val a = i / 48f * 2 * PI.toFloat()
                val rr = radius * (1 + wob * (0.5f * sin(3 * a + t * 1.7f + k) + 0.3f * sin(5 * a - t * 2.3f + 2 * k) + 0.2f * sin(2 * a + t * 0.9f)))
                val px = x + cos(a) * rr
                val py = y + sin(a) * rr
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
        }
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()

    companion object {
        private const val H = 46 // the pill's height
        private const val TEXT_X = 112 // where the words start
        private const val GAP = 10 // between the words and the ×
        private const val END = 28 // the ×
        private const val MAX_W = 260 // the pill at its widest
        private const val ROOM = 16 // round the pill, for the halo and the shadow
        private val ERROR = Color.parseColor("#ff6b81")
        private val GLASS = Color.parseColor("#101014")
        private val STILL_BARS = Color.parseColor("#5b6068")

        private fun colors(vararg hex: String) = hex.map { Color.parseColor(it) }.toIntArray()

        /** [color] with alpha [a] (0 to 255). */
        private fun alpha(color: Int, a: Int) = (color and 0xffffff) or (a.coerceIn(0, 255) shl 24)
    }
}
