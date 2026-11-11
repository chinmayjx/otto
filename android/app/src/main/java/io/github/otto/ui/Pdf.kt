package io.github.otto.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** PdfRenderer allows one page open at a time: all of it runs on one thread. */
private val pdfThread = Executors.newSingleThreadExecutor { Thread(it, "pdf") }.asCoroutineDispatcher()

/** A PDF opened with Android's PdfRenderer. Page sizes in points. */
class PdfDoc private constructor(private val pfd: ParcelFileDescriptor, private val renderer: PdfRenderer, val sizes: List<Pair<Float, Float>>) {
    @Volatile
    private var closed = false

    /**
     * Part of page i: the page drawn scale pixels per point, the region from (dx, dy) of w × h pixels, on white.
     * Null once closed.
     */
    suspend fun render(i: Int, scale: Float, dx: Int, dy: Int, w: Int, h: Int): Bitmap? = withContext(pdfThread) {
        if (closed || w <= 0 || h <= 0) return@withContext null
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bm.eraseColor(android.graphics.Color.WHITE)
        val page = renderer.openPage(i)
        try {
            val m = Matrix()
            m.setScale(scale, scale)
            m.postTranslate(-dx.toFloat(), -dy.toFloat())
            page.render(bm, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        } finally {
            page.close()
        }
        bm
    }

    fun close() {
        CoroutineScope(pdfThread).launch {
            if (closed) return@launch
            closed = true
            renderer.close()
            pfd.close()
        }
    }

    companion object {
        suspend fun open(file: File): PdfDoc = withContext(pdfThread) {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val r = PdfRenderer(pfd)
                val sizes = (0 until r.pageCount).map { i ->
                    val p = r.openPage(i)
                    try {
                        p.width.toFloat() to p.height.toFloat()
                    } finally {
                        p.close()
                    }
                }
                PdfDoc(pfd, r, sizes)
            } catch (e: Exception) {
                pfd.close()
                throw e
            }
        }
    }
}

/** A sharp rendering of the part of page `page` that was on screen, at zoom `zoom`: rect in the page's pixels. */
private class Tile(val zoom: Float, val rect: IntRect, val image: ImageBitmap)

/**
 * The pages of a PDF, one under the other, lazily rendered at the screen's density as they come into view.
 *
 * Zoom: pinch, or double tap (2.5x and back). During a pinch only a transform changes; when the fingers lift the
 * pages are laid out again at the new size, and the part on screen is rendered again at full resolution (Tile),
 * so text stays sharp at any zoom while each page's full rendering stays at screen width (memory stays small).
 * Zoomed in, one finger pans both ways, with a fling.
 *
 * sizes: page sizes (points), known before the document is (the artifact hub says them), so the layout is there at
 * once; doc: null until the file is here.
 */
@Composable
fun PdfPages(doc: PdfDoc?, sizes: List<Pair<Float, Float>>, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val cs = MaterialTheme.colorScheme
    val touchSlop = LocalViewConfiguration.current.touchSlop
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds().background(cs.surfaceContainerHighest)) {
        val vw = constraints.maxWidth
        val vh = constraints.maxHeight
        val margin0 = with(density) { 6.dp.toPx() }
        val gap0 = with(density) { 8.dp.toPx() }
        val baseW = max(1, (vw - 2 * margin0).roundToInt()) // a page's width at zoom 1: its base rendering's width

        var zoom by remember { mutableFloatStateOf(1f) }
        var panX by remember { mutableFloatStateOf(0f) }
        // The live pinch: scale s about the origin, then a translation.
        var gs by remember { mutableFloatStateOf(1f) }
        var gt by remember { mutableStateOf(Offset.Zero) }
        var gesturing by remember { mutableStateOf(false) }
        val lazy = rememberLazyListState()
        val scope = rememberCoroutineScope()

        // The geometry at this zoom, in whole pixels.
        class Geo(val contentW: Int, val m: Int, val pw: Int, val g: Int, val ph: IntArray, val tops: IntArray) {
            val total get() = if (ph.isEmpty()) 0 else tops.last() + ph.last() + g
        }
        val geo = remember(zoom, vw, sizes) {
            val contentW = (vw * zoom).roundToInt()
            val m = (margin0 * zoom).roundToInt()
            val pw = contentW - 2 * m
            val g = (gap0 * zoom).roundToInt()
            val ph = IntArray(sizes.size) { (pw * sizes[it].second / sizes[it].first).roundToInt() }
            val tops = IntArray(sizes.size)
            var y = 0
            for (i in sizes.indices) {
                tops[i] = y
                y += ph[i] + g
            }
            Geo(contentW, m, pw, g, ph, tops)
        }
        // The geometry as of now, for what outlives this composition (the gesture handler, the page number): it's
        // made once, so it would otherwise keep 1x's geometry and never pan sideways.
        val now by rememberUpdatedState(geo)

        val cache = remember(baseW, doc) { object : LruCache<Int, ImageBitmap>(48 shl 20) {
            override fun sizeOf(key: Int, value: ImageBitmap) = value.width * value.height * 4
        } }
        val tiles = remember { mutableStateMapOf<Int, Tile>() }

        fun scrollY(): Int {
            val i = lazy.firstVisibleItemIndex
            return if (i < now.tops.size) now.tops[i] + lazy.firstVisibleItemScrollOffset else 0
        }

        fun maxPanX() = max(0, now.contentW - vw).toFloat()

        // Zoom to z, keeping the content under c (viewport pixels, before) under c2 (after). All in one frame: the
        // new layout, the new scroll and the end of the transform.
        fun commit(z0: Float, c: Offset, c2: Offset) {
            val z = z0.coerceIn(1f, 5f)
            val s = z / zoom
            val nx = (panX + c.x) * s - c2.x
            val ny = (scrollY() + c.y) * s - c2.y
            zoom = z
            // The new geometry, computed here as remember will (the same arithmetic).
            val contentW = (vw * z).roundToInt()
            val m = (margin0 * z).roundToInt()
            val pw = contentW - 2 * m
            val g = (gap0 * z).roundToInt()
            panX = nx.coerceIn(0f, max(0, contentW - vw).toFloat())
            var y = 0
            var target = 0
            var off = 0
            val wantY = max(0f, ny).roundToInt()
            for (i in sizes.indices) {
                val h = (pw * sizes[i].second / sizes[i].first).roundToInt() + g
                if (wantY < y + h || i == sizes.lastIndex) {
                    target = i
                    off = wantY - y
                    break
                }
                y += h
            }
            lazy.requestScrollToItem(target, max(0, off))
            gs = 1f
            gt = Offset.Zero
            tiles.clear()
        }

        // The sharp part: once still and zoomed in, each visible page's part on screen, at full resolution.
        LaunchedEffect(doc, geo) {
            tiles.clear()
            if (doc == null) return@LaunchedEffect
            snapshotFlow { Triple(gesturing || lazy.isScrollInProgress, scrollY(), panX) }.collectLatest { (moving, sy, px) ->
                if (moving || zoom < 1.05f) return@collectLatest
                delay(90)
                val left = px.roundToInt()
                for (i in sizes.indices) {
                    val top = geo.tops[i] + geo.g
                    val y0 = max(sy, top)
                    val y1 = min(sy + vh, top + geo.ph[i])
                    val x0 = max(left, geo.m)
                    val x1 = min(left + vw, geo.m + geo.pw)
                    if (y1 <= y0 || x1 <= x0) continue
                    val rect = IntRect(x0 - geo.m, y0 - top, x1 - geo.m, y1 - top)
                    val t = tiles[i]
                    if (t != null && t.zoom == zoom && t.rect.contains(rect)) continue
                    val bm = doc.render(i, geo.pw / sizes[i].first, rect.left, rect.top, rect.width, rect.height) ?: return@collectLatest
                    tiles[i] = Tile(zoom, rect, bm.asImageBitmap())
                }
            }
        }

        val page by remember { derivedStateOf {
            val line = scrollY() + vh / 4
            var n = sizes.size
            for (i in sizes.indices) if (now.tops[i] + now.ph[i] + now.g > line) { n = i + 1; break }
            n
        } }

        var flings by remember { mutableStateOf<List<Job>>(emptyList()) }

        Box(
            Modifier.fillMaxSize()
                .pointerInput(sizes, vw) {
                    var lastTap = 0L
                    var lastTapAt = Offset.Zero
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        flings.forEach { it.cancel() }
                        flings = emptyList()
                        if (down.uptimeMillis - lastTap < 300 && (down.position - lastTapAt).getDistance() < touchSlop * 3) {
                            // A double tap: 2.5x there, or back to 1x.
                            lastTap = 0
                            down.consume()
                            val p = down.position
                            commit(if (zoom > 1.2f) 1f else 2.5f, p, p)
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                e.changes.forEach { it.consume() }
                                if (e.changes.none { it.pressed }) break
                            }
                            return@awaitEachGesture
                        }
                        var pinch = false
                        var d0 = 1f
                        var c0 = Offset.Zero
                        var c2 = Offset.Zero
                        var panning = false
                        var moved = false
                        val vt = VelocityTracker()
                        vt.addPosition(down.uptimeMillis, down.position)
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = e.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                val a = pressed[0].position
                                val b = pressed[1].position
                                val c = (a + b) / 2f
                                val d = max(1f, (a - b).getDistance())
                                if (!pinch) {
                                    pinch = true
                                    gesturing = true
                                    d0 = d / gs
                                    c0 = c
                                }
                                c2 = c
                                val s = (zoom * d / d0).coerceIn(0.6f, 6f) / zoom
                                gs = s
                                gt = c2 - c0 * s
                                e.changes.forEach { it.consume() }
                            } else if (pinch) {
                                e.changes.forEach { it.consume() }
                                if (pressed.isEmpty()) {
                                    gesturing = false
                                    commit(zoom * gs, c0, c2)
                                    break
                                }
                            } else {
                                if (pressed.isEmpty()) {
                                    if (!moved) {
                                        lastTap = e.changes.first().uptimeMillis
                                        lastTapAt = down.position
                                    }
                                    if (panning) {
                                        gesturing = false
                                        val v = vt.calculateVelocity()
                                        val decay = exponentialDecay<Float>(frictionMultiplier = 1.5f)
                                        flings = listOf(
                                            scope.launch {
                                                var last = 0f
                                                AnimationState(0f, -v.x).animateDecay(decay) {
                                                    panX = (panX + value - last).coerceIn(0f, maxPanX())
                                                    last = value
                                                }
                                            },
                                            scope.launch {
                                                lazy.scroll {
                                                    var last = 0f
                                                    AnimationState(0f, -v.y).animateDecay(decay) {
                                                        scrollBy(value - last)
                                                        last = value
                                                    }
                                                }
                                            },
                                        )
                                    }
                                    break
                                }
                                val ch = pressed[0]
                                vt.addPosition(ch.uptimeMillis, ch.position)
                                if (!moved && (ch.position - down.position).getDistance() > touchSlop) moved = true
                                // Zoomed in, one finger pans both ways (at 1x the list scrolls itself).
                                if (zoom > 1.01f && moved) {
                                    if (!panning) {
                                        panning = true
                                        gesturing = true
                                    }
                                    val delta = ch.positionChange()
                                    panX = (panX - delta.x).coerceIn(0f, maxPanX())
                                    lazy.dispatchRawDelta(-delta.y)
                                    ch.consume()
                                }
                            }
                        }
                    }
                },
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                scaleX = gs
                scaleY = gs
                translationX = gt.x
                translationY = gt.y
                transformOrigin = TransformOrigin(0f, 0f)
            }) {
                LazyColumn(
                    state = lazy,
                    userScrollEnabled = zoom <= 1.01f,
                    modifier = Modifier.layout { m, _ ->
                        val p = m.measure(Constraints.fixed(geo.contentW, vh))
                        layout(vw, vh) { p.place(-panX.roundToInt(), 0) }
                    },
                ) {
                    items(sizes.size, key = { it }) { i ->
                        val ph = geo.ph[i]
                        val bm by produceState(cache.get(i), i, doc, baseW) {
                            if (value != null || doc == null) return@produceState
                            val s = sizes[i]
                            val h = (baseW * s.second / s.first).roundToInt()
                            val b = doc.render(i, baseW / s.first, 0, 0, baseW, h)?.asImageBitmap() ?: return@produceState
                            cache.put(i, b)
                            value = b
                        }
                        val tile = tiles[i]?.takeIf { it.zoom == zoom }
                        Canvas(Modifier.layout { m, _ ->
                            val p = m.measure(Constraints.fixed(geo.contentW, ph + geo.g))
                            layout(geo.contentW, ph + geo.g) { p.place(0, 0) }
                        }) {
                            val at = IntOffset(geo.m, geo.g)
                            val size = IntSize(geo.pw, ph)
                            drawRect(Color.White, topLeft = Offset(at.x.toFloat(), at.y.toFloat()),
                                size = androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()))
                            bm?.let { drawImage(it, dstOffset = at, dstSize = size, filterQuality = FilterQuality.Medium) }
                            tile?.let {
                                drawImage(it.image, dstOffset = at + it.rect.topLeft, dstSize = it.rect.size, filterQuality = FilterQuality.None)
                            }
                        }
                    }
                }
            }
            if (sizes.size > 1) {
                Surface(
                    Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = cs.inverseSurface.copy(alpha = 0.85f),
                ) {
                    val z = zoom * gs
                    Text((if (abs(z - 1f) > 0.04f) "${(z * 100).roundToInt()}% · " else "") + "$page / ${sizes.size}",
                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium, color = cs.inverseOnSurface)
                }
            }
        }
        DisposableEffect(doc) { onDispose { cache.evictAll() } }
    }
}

private fun IntRect.contains(o: IntRect) = left <= o.left && top <= o.top && right >= o.right && bottom >= o.bottom
