package io.github.otto.ui

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import java.io.File

/**
 * The viewer, native: an artifact (any version) or a file on the box, over the whole second screen. PDFs and
 * office files (their PDF) through PdfRenderer, images zoomable, audio and video through Media3, markdown through
 * Markwon, text and code. HTML, and what has no native view, goes to the web viewer (ScreenOps.webViewer).
 */
@Composable
fun Viewer(target: Target, hint: ArtifactItem?) {
    val sheets = remember { Sheets() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            when (target) {
                is Target.File -> FileViewer(target, sheets)
                else -> ArtifactViewer(target, hint, sheets)
            }
            SheetHost(sheets)
        }
    }
}

/** The viewer's bar: back, title (and a label button: the version), download, ⋮. */
@Composable
private fun ViewerBar(title: String, label: String?, progress: Float?, onLabel: () -> Unit, onDownload: (() -> Unit)?, onMore: (() -> Unit)?) {
    val ops = LocalOps.current
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceContainer) {
        Column {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { ops.closeViewer() }) { Icon(Ic.back, "Back") }
                Column(Modifier.weight(1f).padding(start = 2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (label != null) {
                        Text(label, Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onLabel).padding(vertical = 1.dp),
                            style = MaterialTheme.typography.labelMedium, color = cs.primary)
                    }
                }
                if (onDownload != null) IconButton(onClick = onDownload) { Icon(Ic.download, "Download") }
                if (onMore != null) IconButton(onClick = onMore) { Icon(Ic.more, "More") }
            }
            if (progress != null) {
                if (progress < 0f) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                else LinearProgressIndicator({ progress }, Modifier.fillMaxWidth().height(2.dp))
            } else Spacer(Modifier.height(2.dp))
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
    // HTML artifacts are web pages: the web viewer shows them.
    LaunchedEffect(cur?.kind) {
        if (cur != null && cur.kind == "html") ops.webViewer(target)
    }

    val title = cur?.title ?: hint?.title ?: ""
    val behind = cur != null && cur.pinned && cur.version < cur.latest
    val label = if (cur != null && cur.versions.size > 1) "v${cur.version}${if (behind) " (not latest)" else ""} ▾" else null
    Column(Modifier.fillMaxSize()) {
        ViewerBar(title, label, progress,
            onLabel = { if (cur != null) sheets.show { VersionsSheet(cur, sheets) { target = it } } },
            onDownload = cur?.let { { ops.download("${ArtifactsApi.files(ctx, it)}/download", it.filename) } },
            onMore = cur?.let { { sheets.show { ArtifactMoreSheet(it, sheets) } } },
        )
        Box(Modifier.weight(1f)) {
            when {
                error != null -> Centered(error!!)
                cur == null -> {}
                else -> androidx.compose.runtime.key(cur.versionId) {
                    ArtifactBody(cur, onProgress = { progress = it }, onNoPreview = { ops.webViewer(target) })
                }
            }
        }
    }
}

@Composable
private fun ArtifactBody(v: ArtifactVersion, onProgress: (Float?) -> Unit, onNoPreview: () -> Unit) {
    val ctx = LocalContext.current
    val ops = LocalOps.current
    val files = ArtifactsApi.files(ctx, v)
    when (v.kind) {
        "pdf", "office" -> {
            if (v.kind == "office" && v.preview == "failed") NoPreview(v.filename, "${files}/download", v.size, "The preview couldn't be made.")
            else PdfBody("$files/preview.pdf", if (v.kind == "pdf") "$files/file" else null, "a-" + v.versionId, v.pages, onProgress, onNoPreview)
        }
        "image" -> ZoomableImage("$files/file", v.title)
        "audio", "video" -> MediaPlayer("$files/file", v.kind == "audio")
        "markdown" -> TextBody("$files/file", "md-" + v.versionId, onProgress) { MarkdownText(it, ops::openInBrowser) }
        "text" -> TextBody("$files/file", "txt-" + v.versionId, onProgress) { CodeText(it, v.filename) }
        "html" -> {}
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
    LaunchedEffect(f?.kind) {
        // HTML pages, and office files (the box's hub previews those as page images), in the web viewer.
        if (f != null && (f.kind == "html" || f.kind == "office")) ops.webViewer(target)
    }
    val raw = FilesApi.raw(ctx, path)
    val download = FilesApi.url(ctx, "raw", path, "&download=1")
    Column(Modifier.fillMaxSize()) {
        ViewerBar(name, null, progress, onLabel = {},
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
        Box(Modifier.weight(1f)) {
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
                    "html", "office" -> {}
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
private fun ZoomableImage(url: String, description: String) {
    val ctx = LocalContext.current
    ZoomableAsyncImage(
        model = ImageRequest.Builder(ctx).data(url).build(),
        contentDescription = description,
        imageLoader = Net.images(ctx),
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
