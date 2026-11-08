package io.github.otto.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.webkit.MimeTypeMap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import io.github.otto.AppWeb
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.ImageLoader
import coil.request.ImageRequest
import io.github.otto.Clips
import io.github.otto.Collector
import io.github.otto.Drafts
import io.github.otto.ScreenActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The viewer, native: an artifact (any version) or a file on the box, over the whole second screen. PDFs and
 * office files (their PDF) through PdfRenderer, images zoomable, audio and video through Media3, markdown through
 * Markwon, text and code, and web pages (HTML artifacts and files) framed in a WebView: never the web UI's viewer.
 */
@Composable
fun Viewer(target: Target, hint: ArtifactItem?) {
    val sheets = remember { Sheets() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            when (target) {
                is Target.File -> FileViewer(target, sheets)
                is Target.Clip -> ClipImageViewer(target)
                else -> ArtifactViewer(target, hint, sheets)
            }
            SheetHost(sheets)
        }
    }
}

/**
 * The viewer's bar, one line, as tall as the second screen's own: home (Up), the title (ellipsized), the version
 * right after it in the accent (it picks a version, and never shrinks: the title does), then the collector's switch,
 * download and ⋮. Loading shows as a thin line along its bottom edge.
 */
@Composable
private fun ViewerBar(title: String, label: String?, progress: Float?, onLabel: () -> Unit, onDownload: (() -> Unit)?, onMore: (() -> Unit)?,
                      clipping: Boolean? = null, onClip: () -> Unit = {}, home: String = "Artifacts home") {
    val ops = LocalOps.current
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceContainer) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            Row(Modifier.fillMaxSize().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                // Up, as a home: always the section's home (the Artifacts list, the file's folder). System Back is
                // contextual instead (ScreenOps.up, Screen.back).
                IconButton(onClick = { ops.up() }) { Icon(painterResource(io.github.otto.R.drawable.ccm_home), home, Modifier.size(22.dp)) }
                Row(Modifier.weight(1f).padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    if (label != null) {
                        Text(label, Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onLabel)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelLarge, color = cs.primary, maxLines = 1, softWrap = false)
                    }
                }
                // A tight group: 40dp targets, 22dp icons.
                val tight = Modifier.size(40.dp)
                // A toggle: a plain icon like its neighbours, on a lavender-tinted disc while the collector is open.
                if (clipping != null) IconButton(onClick = onClip, modifier = tight,
                    colors = if (clipping) androidx.compose.material3.IconButtonDefaults.iconButtonColors(
                        containerColor = cs.primaryContainer, contentColor = cs.onPrimaryContainer) else androidx.compose.material3.IconButtonDefaults.iconButtonColors()) {
                    Icon(Ic.clipboard, if (clipping) "Close the collector" else "Collect notes and parts for your agent", Modifier.size(22.dp))
                }
                if (onDownload != null) IconButton(onClick = onDownload, modifier = tight) { Icon(Ic.download, "Download", Modifier.size(22.dp)) }
                if (onMore != null) IconButton(onClick = onMore, modifier = tight) { Icon(Ic.more, "More", Modifier.size(22.dp)) }
            }
            if (progress != null) {
                val m = Modifier.fillMaxWidth().height(2.dp).align(Alignment.BottomCenter)
                if (progress < 0f) LinearProgressIndicator(m) else LinearProgressIndicator({ progress }, m)
            }
        }
    }
}

// --- Artifacts --------------------------------------------------------------------------------------------------

@Composable
private fun ArtifactViewer(start: Target, hint: ArtifactItem?, sheets: Sheets) {
    val ctx = LocalContext.current
    val ops = LocalOps.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf(start) }
    var v by remember { mutableStateOf<ArtifactVersion?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(-1f) }

    // The version: what was shown last for this link at once (a version never changes; "latest" may have moved),
    // then the hub's answer.
    LaunchedEffect(target) {
        error = null
        progress = -1f
        val key = "artifact:" + target.hash
        Store.read(ctx, key)?.let { runCatching { v = ArtifactVersion.parse(it.substringAfter('\n'), it.startsWith("1")) } }
        try {
            val (text, pinned) = ArtifactsApi.load(ctx, target)
            val fresh = ArtifactVersion.parse(text, pinned)
            Store.write(ctx, key, (if (pinned) "1" else "0") + "\n" + text)
            if (fresh != v) v = fresh
        } catch (e: HttpError) {
            if (v == null) error = if (e.code == 404) "This artifact was deleted." else "The artifact hub said ${e.code}."
        } catch (e: Exception) {
            if (v == null) error = unreachable("the artifact hub")
        }
        if (progress == -1f) progress = null
    }

    val cur = v

    val title = cur?.title ?: hint?.title ?: ""
    val behind = cur != null && cur.pinned && cur.version < cur.latest
    val label = if (cur != null && cur.versions.size > 1) "v${cur.version}${if (behind) " (not latest)" else ""} ▾" else null
    val clip = remember(cur?.artifactId) { cur?.let { ArtifactClip(ctx, it.artifactId) } }
    var clipping by remember { mutableStateOf(false) }
    var area by remember { mutableStateOf<android.graphics.Rect?>(null) } // the body, in the window's pixels
    var frozen by remember { mutableStateOf<Bitmap?>(null) } // the body, frozen to take a part of it
    LaunchedEffect(clip) { clip?.load() }
    DisposableEffect(clip) { onDispose { clip?.save() } }
    OnBack(frozen != null) { frozen = null }
    Column(Modifier.fillMaxSize()) {
        ViewerBar(title, label, progress,
            onLabel = { if (cur != null) sheets.show { VersionsSheet(cur, sheets) { target = it } } },
            onDownload = cur?.let { { ops.download("${ArtifactsApi.files(ctx, it)}/download", it.filename) } },
            onMore = cur?.let { { sheets.show { ArtifactMoreSheet(it, sheets) } } },
            clipping = if (cur != null) clipping else null,
            onClip = {
                clipping = !clipping
                if (!clipping) clip?.save()
            },
        )
        Box(Modifier.weight(1f).clipToBounds().onGloballyPositioned { c ->
            val b = c.boundsInWindow()
            area = android.graphics.Rect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt())
        }) {
            when {
                error != null -> Centered(error!!)
                cur == null -> {}
                else -> androidx.compose.runtime.key(cur.versionId) {
                    ArtifactBody(cur, onProgress = { progress = it })
                }
            }
            frozen?.let { bm ->
                Freeze(bm) { part ->
                    frozen = null
                    if (part != null) clip?.add(part)
                }
            }
        }
        if (clipping && cur != null && clip != null) {
            // Docked: part of the layout, so the artifact above shrinks rather than going under it.
            AndroidView(
                factory = { c -> Collector(c, docked = true).also { clip.attach(it) } },
                update = {
                    it.frozen = frozen != null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            LaunchedEffect(clip) {
                // The body's pixels as they are on screen.
                fun take(then: (Bitmap) -> Unit) {
                    val w = ScreenActivity.current?.window
                    val r = area
                    if (w == null || r == null || r.width() <= 0 || r.height() <= 0) return
                    val bm = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
                    PixelCopy.request(w, r, bm, { res ->
                        if (res == PixelCopy.SUCCESS) then(bm) else toast(ctx, "Couldn't take the page")
                    }, Handler(Looper.getMainLooper()))
                }
                clip.onCapture = { if (frozen != null) frozen = null else take { frozen = it } }
                clip.onWhole = { if (frozen != null) frozen?.let { clip.add(it) } else take { clip.add(it) } }
                clip.onSent = { clipping = false }
                clip.onClose = {
                    frozen = null
                    clipping = false
                    clip.save()
                }
                clip.source = {
                    JSONObject().put("kind", "artifact").put("id", cur.artifactId).put("version", cur.version).put("title", cur.title)
                        .put("url", "${artifactsBase(ctx)}/a/${cur.artifactId}/v${cur.version}")
                }
            }
        }
    }
}

/** Drafts are written off the main thread, in order. */
private val draftIo = Executors.newSingleThreadExecutor { Thread(it, "drafts") }

/**
 * An artifact's clip, while it's being collected: the docked collector's text and images, kept as a draft per
 * artifact (Drafts, "artifact-<id>") until a Copy sends them.
 */
private class ArtifactClip(private val ctx: Context, id: String) {
    private val key = "artifact-$id"
    private val main = Handler(Looper.getMainLooper())
    private val images = mutableListOf<Bitmap>()
    private var text = ""
    private var loaded = false
    private var view: Collector? = null
    var onCapture: () -> Unit = {}
    var onWhole: () -> Unit = {}
    var onSent: () -> Unit = {}
    var onClose: () -> Unit = {}
    var source: () -> JSONObject = { JSONObject() }
    private val saveSoon = Runnable { save() }

    suspend fun load() {
        val d = withContext(Dispatchers.IO) { Drafts.load(ctx, key) }
        if (d != null && !loaded) {
            images.addAll(0, d.images)
            if (text.isEmpty()) text = d.text
        }
        loaded = true
        view?.let { show(it) }
    }

    fun attach(c: Collector) {
        view = c
        c.onTextChanged = {
            text = it
            main.removeCallbacks(saveSoon)
            main.postDelayed(saveSoon, 1000)
        }
        c.onRemove = { i ->
            images.removeAt(i)
            show(c)
            save()
        }
        c.onCapture = { onCapture() }
        c.onWhole = { onWhole() }
        c.onClose = { onClose() }
        c.onCopy = { copy(c) }
        show(c)
    }

    private fun show(c: Collector) {
        c.text = text
        c.piecesChanged(images.map { Collector.Still(it) }, images.size - 1)
    }

    fun add(b: Bitmap) {
        images.add(b)
        view?.let { show(it) }
        save()
    }

    fun save() {
        if (!loaded) return
        val t = text
        val list = images.toList()
        draftIo.execute { Drafts.save(ctx, key, t, list) }
    }

    private fun copy(c: Collector) {
        c.sending()
        val (rest, notes) = Collector.notes(text)
        val src = source().put("notes", JSONArray(notes.map { (where, t) -> JSONObject().put("where", where).put("text", t) }))
        val list = images.toList()
        if (list.isEmpty()) { // no images: the same in plain text on the clipboard, and nothing to the box
            Clips.copyText(ctx, plain(rest, notes, src))
            draftIo.execute { Drafts.clear(ctx, key) }
            c.sent()
            toast(ctx, "Copied")
            text = ""
            main.postDelayed({
                show(c)
                c.reset()
                onSent()
            }, 550)
            return
        }
        draftIo.execute {
            try {
                val id = Clips.upload(rest, list, src)
                Drafts.clear(ctx, key)
                main.post {
                    Clips.copyReference(ctx, id)
                    c.sent()
                    toast(ctx, "Copied clip $id")
                    images.clear()
                    text = ""
                    main.postDelayed({
                        show(c)
                        c.reset()
                        onSent()
                    }, 550)
                }
            } catch (e: Exception) {
                main.post { c.failed(e.message ?: e.toString()) }
            }
        }
    }
}

/** A clip of the artifact without images, as text: "Feedback on <title> v<n> (<link>):", the text, the notes. */
private fun plain(rest: String, notes: List<Pair<String, String>>, src: JSONObject): String {
    val head = "Feedback on ${src.optString("title")} v${src.optInt("version")} (${src.optString("url")}):"
    val lines = mutableListOf(head)
    if (rest.isNotBlank()) lines += rest.trim()
    for ((where, t) in notes) lines += "- $where: $t"
    return lines.joinToString("\n")
}

/**
 * The viewer's body frozen (a copy of its pixels), to take a part of it: drag over it, and the part goes to the
 * collector as soon as the finger lifts; a tap leaves without one.
 */
@Composable
private fun Freeze(shot: Bitmap, done: (Bitmap?) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val least = with(LocalDensity.current) { 16.dp.toPx() }
    val image = remember(shot) { shot.asImageBitmap() }
    var from by remember { mutableStateOf<Offset?>(null) }
    var to by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize()) {
        Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Canvas(Modifier.fillMaxSize()
            .pointerInput(shot) {
                // From where the finger went down (not where a drag is first recognised); a tap leaves.
                awaitEachGesture {
                    val down = awaitFirstDown()
                    from = down.position
                    to = down.position
                    var moved = false
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        to = Offset(c.position.x.coerceIn(0f, size.width.toFloat()), c.position.y.coerceIn(0f, size.height.toFloat()))
                        if ((to - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        c.consume()
                        if (!c.pressed) break
                    }
                    val f = from ?: return@awaitEachGesture
                    from = null
                    if (!moved) return@awaitEachGesture done(null)
                    if (abs(to.x - f.x) < least || abs(to.y - f.y) < least) return@awaitEachGesture
                    // The canvas is the bitmap's size, unless the layout has moved since: then by scale.
                    val kx = shot.width / size.width.toFloat()
                    val ky = shot.height / size.height.toFloat()
                    val x = (min(f.x, to.x) * kx).roundToInt().coerceIn(0, shot.width - 1)
                    val y = (min(f.y, to.y) * ky).roundToInt().coerceIn(0, shot.height - 1)
                    val w = (abs(to.x - f.x) * kx).roundToInt().coerceIn(1, shot.width - x)
                    val h = (abs(to.y - f.y) * ky).roundToInt().coerceIn(1, shot.height - y)
                    done(Bitmap.createBitmap(shot, x, y, w, h))
                }
            }) {
            val f = from
            val sel = if (f == null) null else Rect(min(f.x, to.x), min(f.y, to.y), max(f.x, to.x), max(f.y, to.y))
            if (sel == null) drawRect(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f))
            else {
                clipRect(sel.left, sel.top, sel.right, sel.bottom, ClipOp.Difference) {
                    drawRect(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f))
                }
                drawRect(cs.primary, sel.topLeft, Size(sel.width, sel.height), style = Stroke(2.dp.toPx()))
            }
        }
        Surface(Modifier.align(Alignment.TopCenter).padding(10.dp), shape = RoundedCornerShape(12.dp),
            color = cs.inverseSurface.copy(alpha = 0.9f)) {
            Text("Drag over a part to add it.", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium, color = cs.inverseOnSurface)
        }
    }
}

// --- A clip's image ---------------------------------------------------------------------------------------------

@Composable
private fun ClipImageViewer(t: Target.Clip) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        ViewerBar("Clip ${t.id}", null, null, onLabel = {}, onDownload = null, onMore = null, home = "Clipboard")
        Box(Modifier.weight(1f).clipToBounds()) { ZoomableImage(Clips.image(t.id, t.file), "Clip ${t.id}", Clips.images(ctx)) }
    }
}

@Composable
private fun ArtifactBody(v: ArtifactVersion, onProgress: (Float?) -> Unit) {
    val ctx = LocalContext.current
    val ops = LocalOps.current
    val files = ArtifactsApi.files(ctx, v)
    var noPdf by remember(v.versionId) { mutableStateOf(false) } // no preview.pdf (yet): the hub's page images
    when (v.kind) {
        "pdf", "office" -> when {
            v.kind == "office" && v.preview == "failed" -> NoPreview(v.filename, "${files}/download", v.size, "The preview couldn't be made.")
            noPdf && !v.pages.isNullOrEmpty() -> PageImages(null, { n -> "$files/page/$n.svg" }, onProgress, v.pages)
            noPdf -> NoPreview(v.filename, "$files/download", v.size, null)
            else -> PdfBody("$files/preview.pdf", if (v.kind == "pdf") "$files/file" else null, "a-" + v.versionId, v.pages, onProgress) { noPdf = true }
        }
        "image" -> ZoomableImage("$files/file", v.title)
        "audio", "video" -> MediaPlayer("$files/file", v.kind == "audio")
        "markdown" -> TextBody("$files/file", "md-" + v.versionId, onProgress) { MarkdownText(it, ops::openInBrowser) }
        "text" -> TextBody("$files/file", "txt-" + v.versionId, onProgress) { CodeText(it, v.filename) }
        // The hub's own page in embed mode: the artifact, plus the bridge its data needs (window.hub).
        "html" -> HtmlBody("$files?embed=1", sandbox = false)
        else -> NoPreview(v.filename, "$files/download", v.size, null)
    }
}

@Composable
private fun VersionsSheet(v: ArtifactVersion, sheets: Sheets, go: (Target) -> Unit) {
    SheetTitle("Versions")
    val last = v.versions.maxByOrNull { it.version }
    SheetRow(Ic.latest, "Latest", "v${last?.version ?: v.version}, and newer ones as they come", on = !v.pinned) {
        sheets.dismiss()
        go(Target.Artifact(v.artifactId))
    }
    for (s in v.versions.sortedByDescending { it.version }) {
        SheetRow(null, s.note.ifEmpty { "Version ${s.version}" }, ago(s.created), on = v.pinned && s.version == v.version, lead = "v${s.version}") {
            sheets.dismiss()
            go(Target.Artifact(v.artifactId, s.version))
        }
    }
}

@Composable
private fun ArtifactMoreSheet(v0: ArtifactVersion, sheets: Sheets) {
    val ctx = LocalContext.current
    val ops = LocalOps.current
    val scope = rememberCoroutineScope()
    val v = v0
    val n = v.versions.size
    val pub = publicArtifactsBase(ctx)
    fun failed() = toast(ctx, "That didn't work. Is Tailscale on?")
    SheetTitle("${v.type} · v${v.version}${if (v.public) " · shared" else ""}")
    SheetRow(Ic.share, "Share", if (v.public) "Its public link" else "Makes a public link anyone can open") {
        sheets.dismiss()
        scope.launch {
            try {
                if (!v.public) ArtifactsApi.setPublic(ctx, v.artifactId, true)
                ops.share("$pub/p/${v.artifactId}", v.title)
            } catch (_: Exception) {
                failed()
            }
        }
    }
    if (v.public) SheetRow(Ic.lock, "Stop sharing", "The public link stops working") {
        sheets.dismiss()
        scope.launch {
            try {
                ArtifactsApi.setPublic(ctx, v.artifactId, false)
                toast(ctx, "No longer shared")
            } catch (_: Exception) {
                failed()
            }
        }
    }
    SheetRow(Ic.link, "Copy link", "Always opens the latest version") {
        sheets.dismiss()
        copyText(ctx, "$pub/a/${v.artifactId}", "Link copied")
    }
    SheetRow(Ic.open, "Open with…", "Another app on this phone") {
        sheets.dismiss()
        openWith(ctx, scope, ops, "${ArtifactsApi.files(ctx, v)}/download", v.filename)
    }
    SheetRow(Ic.open, "Open in Browser", "The hub's own page") {
        sheets.dismiss()
        ops.closeViewer()
        ops.openInBrowser("${artifactsBase(ctx)}/a/${v.artifactId}" + if (v.pinned) "/v${v.version}" else "")
    }
    SheetRow(Ic.trash, "Delete", if (n > 1) "All $n versions" else "For good", danger = true) {
        sheets.ask("Delete \"${v.title}\"?", if (n > 1) "All $n versions go, for good." else "It goes for good.", "Delete") {
            scope.launch {
                try {
                    ArtifactsApi.delete(ctx, v.artifactId)
                    toast(ctx, "Deleted")
                    ops.artifactsChanged()
                    ops.closeViewer()
                } catch (_: Exception) {
                    failed()
                }
            }
        }
    }
}

// --- Files on the box -------------------------------------------------------------------------------------------

@Composable
private fun FileViewer(target: Target.File, sheets: Sheets) {
    val ctx = LocalContext.current
    val ops = LocalOps.current
    val scope = rememberCoroutineScope()
    val path = target.path
    val name = path.substringAfterLast('/')
    var info by remember { mutableStateOf<FsInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(-1f) }
    LaunchedEffect(path) {
        try {
            info = FilesApi.info(ctx, path)
        } catch (e: HttpError) {
            error = if (e.code == 403) "Outside the home folder" else "Not found"
        } catch (e: Exception) {
            error = unreachable("the devbox")
        }
        progress = null
    }
    val f = info
    val raw = FilesApi.raw(ctx, path)
    val download = FilesApi.url(ctx, "raw", path, "&download=1")
    Column(Modifier.fillMaxSize()) {
        ViewerBar(name, null, progress, onLabel = {}, home = "Files",
            onDownload = { ops.download(download, name) },
            onMore = {
                sheets.show {
                    SheetTitle(path)
                    SheetRow(Ic.copy, "Copy path", "For a chat with your agent") {
                        sheets.dismiss()
                        copyText(ctx, path, "Path copied")
                    }
                    SheetRow(Ic.open, "Open with…", "Another app on this phone") {
                        sheets.dismiss()
                        openWith(ctx, scope, ops, raw, name)
                    }
                    SheetRow(Ic.open, "Open in Browser", "The file itself") {
                        sheets.dismiss()
                        ops.closeViewer()
                        ops.openInBrowser(raw)
                    }
                }
            })
        Box(Modifier.weight(1f).clipToBounds()) {
            when {
                error != null -> Centered(error!!)
                f == null -> {}
                else -> when (f.kind) {
                    "pdf" -> PdfBody(raw, null, "f-" + Net.sha1("$path:${f.mtime}:${f.size}"), null, { progress = it }, {})
                    "image" -> ZoomableImage(raw, name)
                    "audio", "video" -> MediaPlayer(raw, f.kind == "audio")
                    "markdown" -> TextBody(raw, null, { progress = it }) { MarkdownText(it, ops::openInBrowser) }
                    "text" -> {
                        val cut = f.size > 2_000_000
                        TextBody(raw, null, { progress = it }, if (cut) mapOf("Range" to "bytes=0-1999999") else emptyMap()) {
                            CodeText(it, name, if (cut) "The first 2 MB of ${size(f.size)}" else null)
                        }
                    }
                    // An HTML file is the box's, not one of Otto's pages: sandboxed, so its scripts can't reach the hub.
                    "html" -> HtmlBody(raw, sandbox = true)
                    "office" -> PageImages(FilesApi.url(ctx, "pages", path), { n -> FilesApi.url(ctx, "page", path, "&n=$n") }, { progress = it })
                    else -> NoPreview(name, download, f.size, null)
                }
            }
        }
    }
}

// --- Bodies -----------------------------------------------------------------------------------------------------

/**
 * A PDF: downloaded once into the cache (key: it never changes under that key), then rendered. sizes: the pages'
 * sizes if known before the file is here. fallback: another URL if url is missing (404). onMissing: neither is there.
 */
@Composable
private fun PdfBody(url: String, fallback: String?, key: String, sizes: List<Pair<Float, Float>>?, onProgress: (Float?) -> Unit, onMissing: () -> Unit) {
    val ctx = LocalContext.current
    var doc by remember { mutableStateOf<PdfDoc?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(url, key) {
        val dir = File(ctx.cacheDir, "pdf")
        val file = File(dir, "$key.pdf")
        try {
            if (!file.exists()) {
                onProgress(-1f)
                try {
                    Net.download(ctx, url, file) { r, t -> if (t > 0) onProgress(r.toFloat() / t) }
                } catch (e: HttpError) {
                    if (e.code != 404) throw e
                    if (fallback == null) {
                        onMissing()
                        return@LaunchedEffect
                    }
                    Net.download(ctx, fallback, file) { r, t -> if (t > 0) onProgress(r.toFloat() / t) }
                }
                Net.trim(dir, 300L shl 20)
            } else file.setLastModified(System.currentTimeMillis())
            onProgress(null)
            doc = PdfDoc.open(file)
        } catch (e: Exception) {
            onProgress(null)
            if (e is HttpError) error = "The hub said ${e.code}." else if (file.exists()) {
                file.delete()
                error = "Couldn't open this PDF."
            } else error = unreachable("the hub")
        }
    }
    DisposableEffect(Unit) { onDispose { doc?.close() } }
    val d = doc
    val shown = d?.sizes ?: sizes
    when {
        error != null -> Centered(error!!)
        shown != null && shown.isNotEmpty() -> PdfPages(d, shown)
        d != null -> Centered("This PDF has no pages.")
    }
}

/** A text file fetched (and kept under key, when its content never changes), then shown. */
@Composable
private fun TextBody(url: String, key: String?, onProgress: (Float?) -> Unit, headers: Map<String, String> = emptyMap(), show: @Composable (String) -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(key?.let { Store.read(ctx, it) }) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(url) {
        if (text != null) return@LaunchedEffect
        onProgress(-1f)
        try {
            val t = Net.text(ctx, url, headers)
            if (key != null) Store.write(ctx, key, t)
            text = t
        } catch (e: Exception) {
            error = if (e is HttpError) "The hub said ${e.code}." else unreachable("the hub")
        }
        onProgress(null)
    }
    val t = text
    when {
        t != null -> show(t)
        error != null -> Centered(error!!)
    }
}

@Composable
private fun ZoomableImage(url: String, description: String, loader: ImageLoader? = null) {
    val ctx = LocalContext.current
    ZoomableAsyncImage(
        model = ImageRequest.Builder(ctx).data(url).build(),
        contentDescription = description,
        imageLoader = loader ?: Net.images(ctx),
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun NoPreview(name: String, downloadUrl: String, size: Long, why: String?) {
    val ctx = LocalContext.current
    val ops = LocalOps.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        Icon(Ic.file, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Text((why ?: "No preview for this file.") + " " + size(size), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Row {
            androidx.compose.material3.FilledTonalButton(onClick = { ops.download(downloadUrl, name) }) { Text("Download") }
            Spacer(Modifier.width(12.dp))
            androidx.compose.material3.Button(onClick = { openWith(ctx, scope, ops, downloadUrl, name) }) { Text("Open with…") }
        }
    }
}

/**
 * A web page as the viewer's body: an artifact (the hub's embed page) or an HTML file on the box. It's framed in a
 * small page of its own, as the web viewer framed it: an artifact may use MIDI, the microphone and autoplay; a box
 * file is sandboxed (no access to the hub that serves it). Links that leave it open in the second screen's browser.
 */
@Composable
fun HtmlBody(url: String, sandbox: Boolean) {
    val frame = remember(url, sandbox) {
        val attrs = if (sandbox) "sandbox=\"allow-scripts allow-popups allow-forms allow-modals\""
        else "allow=\"midi; microphone; autoplay; fullscreen\""
        "<!doctype html><meta name=viewport content=\"width=device-width,initial-scale=1\"><style>html,body{margin:0;height:100%;" +
            "background:#000}iframe{display:block;border:0;width:100%;height:100%}</style>" +
            "<iframe $attrs src=\"${android.text.Html.escapeHtml(url)}\"></iframe>"
    }
    AndroidView(
        factory = { c -> AppWeb.frame(c) },
        update = { w ->
            if (w.tag != frame) {
                w.tag = frame
                w.loadDataWithBaseURL(null, frame, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
        modifier = Modifier.fillMaxSize().clipToBounds(), // Compose doesn't clip the views it hosts
    )
}

/**
 * Pages as images, one under the other: an office file on the box (the hub renders its pages, numbered from 1, and
 * says their sizes at [sizesUrl]), or an artifact's pages while its PDF isn't there ([known] sizes).
 */
@Composable
private fun PageImages(sizesUrl: String?, pageUrl: (Int) -> String, onProgress: (Float?) -> Unit, known: List<Pair<Float, Float>>? = null) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var sizes by remember(sizesUrl) { mutableStateOf(known) }
    var error by remember(sizesUrl) { mutableStateOf<String?>(null) }
    LaunchedEffect(sizesUrl) {
        if (sizesUrl == null || sizes != null) return@LaunchedEffect
        onProgress(-1f) // making an office file's pages can take a while
        try {
            val a = JSONObject(Net.text(ctx, sizesUrl)).getJSONArray("pages")
            sizes = (0 until a.length()).map { val p = a.getJSONArray(it); p.getDouble(0).toFloat() to p.getDouble(1).toFloat() }
        } catch (e: Exception) {
            error = if (e is HttpError) "Couldn't make a preview (is the rendering service up?)" else unreachable("the devbox")
        }
        onProgress(null)
    }
    val s = sizes
    when {
        error != null -> Centered(error!!)
        s == null -> {}
        s.isEmpty() -> Centered("No pages.")
        else -> androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().background(cs.surfaceContainerHighest),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            items(s.size) { i ->
                val (w, h) = s[i]
                Box(Modifier.fillMaxWidth().aspectRatio(if (w > 0 && h > 0) w / h else 0.75f).background(androidx.compose.ui.graphics.Color.White)) {
                    coil.compose.AsyncImage(model = pageUrl(i + 1), contentDescription = "Page ${i + 1}", imageLoader = Net.images(ctx),
                        modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** The file, fetched into the cache, handed to another app on the phone (a chooser), the screen out of the way. */
fun openWith(ctx: Context, scope: kotlinx.coroutines.CoroutineScope, ops: ScreenOps, url: String, name: String) {
    scope.launch {
        val safe = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifEmpty { "file" }
        val dir = File(ctx.cacheDir, "open")
        val file = File(dir, safe)
        try {
            toast(ctx, "Opening $safe…")
            Net.download(ctx, url, file)
            Net.trim(dir, 200L shl 20)
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
            val ext = safe.substringAfterLast('.', "").lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(view, safe).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            ops.hide()
        } catch (e: Exception) {
            toast(ctx, "Couldn't open $safe")
        }
    }
}
