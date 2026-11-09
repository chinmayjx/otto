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
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The assistant's pill: a dark pill floating over every app while a voice call is on (VoiceService): a small glass
 * sphere with coloured light moving inside it, one word for what the call is doing, and a × that ends it. The light
 * follows the microphone's level while it listens and the voice's while it speaks, and swirls while Codex thinks.
 * The pill is as wide as its word and glides to the new width when the word changes. A tap mutes and unmutes (and
 * after an error, tries again). It can be dragged anywhere; left near a side of the screen, it keeps that end where
 * it is and grows and shrinks away from it.
 */
class VoicePill(private val ctx: Context, private val tap: () -> Unit, private val close: () -> Unit) {
    /** What the pill shows, each with its word and the three colours of the sphere's light. */
    enum class Look(val label: String, val palette: IntArray) {
        CONNECTING("Connecting", colors("#8e949c", "#ab9cf2", "#6b7280")),
        LISTENING("Listening", colors("#6aa0ff", "#7ee0f0", "#ab9cf2")),
        THINKING("Thinking", colors("#ab9cf2", "#6aa0ff", "#f29cd8")),
        SPEAKING("Speaking", colors("#ab9cf2", "#f29cd8", "#d6c8ff")),
        MUTED("Muted", colors("#5b6068", "#4a4e55", "#5b6068")),
        ERROR("Tap to retry", colors("#ff6b81", "#c2485b", "#ff6b81")),
    }

    /** Which point of the pill stays put as its width changes. */
    private enum class Anchor { LEFT, MIDDLE, RIGHT }

    /** The call it shows, read each frame; null while there's none (after an error). */
    var call: VoiceCall? = null
    /** Set after a failure: the pill says so, with a retry. */
    var error: String? = null
        set(v) {
            field = v
            pill.invalidate()
        }

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val pill = PillView(ctx)
    private val end = ImageView(ctx)
    private val root = FrameLayout(ctx)
    private lateinit var lp: WindowManager.LayoutParams
    private var look: Look? = null
    private var anchor = Anchor.MIDDLE
    private var anchorX = 0 // on the screen: the pill's left end, middle or right end

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        root.addView(pill, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // The ×, inside the pill's right end, moved along with it (PillView).
        end.apply {
            setImageResource(R.drawable.ccm_close)
            imageTintList = ColorStateList.valueOf(BrowserService.STEEL)
            scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(END_BG)
            }
            contentDescription = "End the assistant"
            setOnClickListener { close() }
        }
        root.addView(end, FrameLayout.LayoutParams(dp(END), dp(END), Gravity.TOP or Gravity.START).apply {
            topMargin = dp(ROOM) + dp((H - END) / 2)
        })

        val bounds = wm.currentWindowMetrics.bounds
        anchorX = bounds.width() / 2
        lp = WindowManager.LayoutParams(0, dp(H + 2 * ROOM),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            y = (bounds.height() * 0.7f).roundToInt()
            title = "Otto assistant"
        }
        pill.start(lookNow())
        place(pill.widest())

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
                        if (dragging) settle()
                        else if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
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

    /**
     * The window [width] px of pill wide (and room round it), placed so the anchored point stays where it is. While
     * the width changes the window is as wide as the wider of the two, and the pill is drawn inside it.
     */
    private fun place(width: Float) {
        lp.width = width.roundToInt() + 2 * dp(ROOM)
        lp.x = when (anchor) {
            Anchor.LEFT -> anchorX - dp(ROOM)
            Anchor.MIDDLE -> anchorX - lp.width / 2
            Anchor.RIGHT -> anchorX + dp(ROOM) - lp.width
        }
        if (root.isAttachedToWindow) wm.updateViewLayout(root, lp)
    }

    /** Dropped after a drag: near a side of the screen, that end of the pill stays put; elsewhere, its middle. */
    private fun settle() {
        val sw = wm.currentWindowMetrics.bounds.width()
        val left = lp.x + pill.pillLeft()
        val w = pill.pillWidth()
        val mid = left + w / 2
        anchor = when {
            mid < sw / 3f -> Anchor.LEFT
            mid > sw * 2 / 3f -> Anchor.RIGHT
            else -> Anchor.MIDDLE
        }
        anchorX = when (anchor) {
            Anchor.LEFT -> left
            Anchor.MIDDLE -> mid
            Anchor.RIGHT -> left + w
        }.roundToInt()
        place(pill.widest())
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
     * The pill, drawn each frame while it's on screen, with room round it for the shadow. In dp: 46 tall, the
     * sphere's glass 28 across with its centre 23 in, the word from 47, then 10 and the × (28), 9 from the right end.
     * When the word changes, the width glides to the new one (220 ms) and the word cross-fades (120 ms out, then in).
     */
    private inner class PillView(ctx: Context) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, ctx.resources.displayMetrics)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.007f
            color = WORD
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
        // The word and the width, and the change in progress: from what, since when (ms since t0).
        private var word = ""
        private var oldWord = ""
        private var fromW = 0f
        private var toW = 0f
        private var changedAt = -1e6f
        private var fitted = true // the window is down to the new width

        /** The pill's width for [w], in px. */
        private fun widthFor(w: String) = (TEXT_X + GAP + END + EDGE) * d + text.measureText(w)

        private fun now() = (System.nanoTime() - t0) / 1e6f

        /** The first word, at once. */
        fun start(l: Look) {
            word = l.label
            oldWord = word
            fromW = widthFor(word)
            toW = fromW
        }

        /** The wider of where the width is coming from and going to: the window's width while it changes. */
        fun widest() = max(fromW, toW)

        /** The pill's width now, in px. */
        fun pillWidth(): Float {
            val u = min(1f, (now() - changedAt) / WIDTH_MS)
            val e = 1 - (1 - u) * (1 - u) * (1 - u)
            return fromW + (toW - fromW) * e
        }

        /** Where the pill's left end is in the window, by the anchor. */
        fun pillLeft(): Float {
            val w = pillWidth()
            return when (anchor) {
                Anchor.LEFT -> ROOM * d
                Anchor.MIDDLE -> (width - w) / 2
                Anchor.RIGHT -> width - ROOM * d - w
            }
        }

        private fun change(to: String) {
            if (to == word) return
            val ms = now()
            fromW = pillWidth()
            oldWord = if (ms - changedAt < FADE_MS) oldWord else word
            word = to
            toW = widthFor(to)
            changedAt = ms
            fitted = false
            post { place(widest()) }
        }

        override fun onDraw(c: Canvas) {
            val l = lookNow()
            setLook(l)
            change(l.label)
            val call = call
            val target = when (l) {
                Look.LISTENING -> call?.micLevel ?: 0f
                Look.SPEAKING -> call?.outLevel ?: 0f
                else -> 0f
            }
            level += (target - level) * 0.25f
            val ms = now()
            val t = ms / 1000f
            val w = pillWidth()
            if (!fitted && ms - changedAt >= WIDTH_MS) {
                fitted = true
                fromW = toW
                post { place(toW) }
            }
            val left = pillLeft()
            val top = ROOM * d
            val cy = top + H / 2f * d
            // The pill, and the × at its right end.
            r.set(left, top, left + w, top + H * d)
            c.drawRoundRect(r, H / 2f * d, H / 2f * d, body)
            val endX = left + w - (EDGE + END) * d
            if (end.x != endX) end.x = endX
            sphere(c, left + 23 * d, cy, 14 * d, l, t, level)
            // The word, cross-fading: the old one out, then the new one in; kept off the ×.
            val since = ms - changedAt
            val (shown, a) = if (since < FADE_MS) oldWord to 1 - since / FADE_MS else word to min(1f, (since - FADE_MS) / FADE_MS)
            if (a > 0f) {
                c.save()
                c.clipRect(left + TEXT_X * d, top, endX - GAP * d / 2, top + H * d)
                text.alpha = (255 * a).roundToInt()
                val fm = text.fontMetrics
                c.drawText(shown, left + TEXT_X * d, cy - (fm.ascent + fm.descent) / 2, text)
                c.restore()
            }
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
            // Small, in the pill: the rings and the halo stay inside it.
            val ringOff = min(6 * d, radius * 0.25f)
            // The halo.
            if (!still) {
                val out = rs * (1.3f + live * 0.4f)
                fill.shader = RadialGradient(x, y, out, intArrayOf(alpha(p[0], 0x66), alpha(p[0], 0x66), alpha(p[0], 0)),
                    floatArrayOf(0f, rs * 0.8f / out, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(x, y, out, fill)
            }
            line.shader = null
            if (l == Look.SPEAKING) {
                line.color = alpha(p[1], (80 * lv + 20).roundToInt())
                line.strokeWidth = 1.5f * d
                c.drawCircle(x, y, rs + ringOff + lv * 3 * d, line)
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
                c.drawCircle(x, y, rs + ringOff, line)
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
                r.set(x - rs - ringOff, y - rs - ringOff, x + rs + ringOff, y + rs + ringOff)
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
        private const val TEXT_X = 47 // where the word starts: 10 after the sphere's glass
        private const val GAP = 10 // between the word and the ×
        private const val END = 28 // the ×
        private const val EDGE = 9 // the ×'s distance from the right end (and the glass's from the left)
        private const val ROOM = 12 // round the pill, for the shadow
        private const val WIDTH_MS = 220f
        private const val FADE_MS = 120f
        private val ERROR = Color.parseColor("#ff6b81")
        private val GLASS = Color.parseColor("#101014")
        private val WORD = Color.parseColor("#e9eaee")
        private val END_BG = Color.parseColor("#26262c")

        private fun colors(vararg hex: String) = hex.map { Color.parseColor(it) }.toIntArray()

        /** [color] with alpha [a] (0 to 255). */
        private fun alpha(color: Int, a: Int) = (color and 0xffffff) or (a.coerceIn(0, 255) shl 24)
    }
}
