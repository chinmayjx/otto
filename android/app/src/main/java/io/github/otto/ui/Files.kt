package io.github.otto.ui

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * The Files section's state: the folder on screen (its listing as shown last, at once, then the hub's), the
 * folders walked through (Back walks back), hidden files, and the devbox being off.
 */
@Stable
class FilesModel(private val ctx: Context, private val bar: (up: Boolean, home: Boolean, hidden: Boolean) -> Unit) {
    var path by mutableStateOf("") // "" = home
        private set
    var listing by mutableStateOf<FsListing?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var pulled by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var down by mutableStateOf<BoxStatus?>(null) // the panel says the devbox isn't running
        private set
    var hidden by mutableStateOf(prefs().getBoolean("hidden", false))
        private set
    var filter by mutableStateOf("")
    private var home: String? = prefs().getString("home", null)
    private val trail = ArrayList<String>()
    val positions = HashMap<String, Pair<Int, Int>>() // where each folder was scrolled to
    private val scope = MainScope()
    private var job: Job? = null

    private fun prefs() = ctx.getSharedPreferences("files", Context.MODE_PRIVATE)

    init {
        // The home folder as shown last, and the hub's behind it (the bar hears of it once that answers).
        listing = Store.read(ctx, "fs:")?.let { runCatching { FsListing.parse(it) }.getOrNull() }
        refresh()
    }

    private fun show(p: String) {
        path = p
        filter = ""
        error = null
        listing = Store.read(ctx, "fs:$p")?.let { runCatching { FsListing.parse(it) }.getOrNull() }
        syncBar()
        refresh()
    }

    fun go(p: String) {
        val now = listing?.path ?: path
        if (p == now) return
        trail.add(now)
        show(p)
    }

    /** Back: the folder before, if any. */
    fun back(): Boolean {
        if (trail.isEmpty()) return false
        show(trail.removeAt(trail.size - 1))
        return true
    }

    fun up() = listing?.parent?.let { go(it) }
    fun home() = go(home ?: "")

    fun toggleHidden() {
        hidden = !hidden
        prefs().edit().putBoolean("hidden", hidden).apply()
        syncBar()
    }

    fun syncBar() {
        val l = listing
        bar(l?.parent != null, home != null && l != null && l.path != home, hidden)
    }

    fun refresh(pull: Boolean = false) {
        if (pull) pulled = true
        job?.cancel()
        val want = path
        job = scope.launch {
            busy = true
            try {
                while (true) {
                    // Tailscale gives no quick error while the devbox is off (requests hang); the control panel answers
                    // at once. So: the listing, raced against the panel's status.
                    val list = async { runCatching { FilesApi.list(ctx, want) } }
                    val status = async {
                        if (!PanelApi.configured(ctx)) null else {
                            delay(1200)
                            runCatching { PanelApi.status(ctx) }.getOrNull()?.takeIf { !it.running }
                        }
                    }
                    val first = select {
                        list.onAwait { it }
                        status.onAwait { s -> s?.let { Result.failure<String>(BoxDown(it)) } ?: list.await() }
                    }
                    val e = first.exceptionOrNull()
                    if (e is BoxDown) {
                        list.cancel()
                        down = e.status
                        // Wait for it to run again, then once more.
                        while (true) {
                            delay(if (down?.stopped == true) 10_000 else 3_000)
                            val s = runCatching { PanelApi.status(ctx) }.getOrNull() ?: continue
                            down = s.takeIf { !it.running }
                            if (s.running) break
                        }
                        continue
                    }
                    status.cancel()
                    down = null
                    val text = first.getOrThrow()
                    val l = FsListing.parse(text)
                    if (want != path) return@launch
                    listing = l
                    error = null
                    Store.write(ctx, "fs:$want", text)
                    Store.write(ctx, "fs:${l.path}", text)
                    if (l.home.isNotEmpty() && l.home != home) {
                        home = l.home
                        prefs().edit().putString("home", l.home).apply()
                    }
                    syncBar()
                    break
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = when {
                    e is HttpError && e.code == 403 -> "Outside the home folder"
                    e is HttpError && e.code == 409 -> "Not a folder"
                    e is HttpError -> "Not found"
                    else -> unreachable("the devbox")
                }
                syncBar()
            } finally {
                busy = false
                pulled = false
            }
        }
    }

    fun destroy() = scope.cancel()

    private class BoxDown(val status: BoxStatus) : Exception()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(model: FilesModel) {
    val ops = LocalOps.current
    val cs = MaterialTheme.colorScheme
    val sheets = remember { Sheets() }
    Surface(Modifier.fillMaxSize(), color = cs.surface) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Crumbs(model)
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    if (model.busy && !model.pulled) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                }
                PullToRefreshBox(isRefreshing = model.pulled, onRefresh = { model.refresh(pull = true) }, modifier = Modifier.weight(1f)) {
                    val l = model.listing
                    val down = model.down
                    // A list state per folder, starting where that folder was left.
                    val key = l?.path ?: model.path
                    val state = remember(key) {
                        val (i, o) = model.positions[key] ?: (0 to 0)
                        LazyListState(i, o)
                    }
                    LaunchedEffect(state, key) {
                        androidx.compose.runtime.snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
                            .collect { model.positions[key] = it }
                    }
                    LazyColumn(Modifier.fillMaxSize(), state = state) {
                        when {
                            down != null -> item { DownCard(down) }
                            l == null -> item { Empty(model.error ?: "Loading…") }
                            else -> {
                                if (model.error != null) item {
                                    Text(model.error!!, Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.bodySmall, color = cs.error)
                                }
                                val q = model.filter.trim().lowercase()
                                val entries = l.entries.filter { (model.hidden || !it.name.startsWith(".")) && (q.isEmpty() || it.name.lowercase().contains(q)) }
                                if (entries.isEmpty()) item {
                                    Empty(if (q.isNotEmpty()) "Nothing matches." else if (l.entries.isNotEmpty()) "Only hidden files here" else "Empty folder")
                                }
                                items(entries, key = { it.name }) { e ->
                                    val p = (if (l.path == "/") "" else l.path) + "/" + e.name
                                    FileRow(e, p,
                                        onClick = { if (e.dir) model.go(p) else ops.openViewer(Target.File(p)) },
                                        onLong = { if (!e.dir) sheets.show { FileSheet(p, e, sheets) } })
                                }
                            }
                        }
                    }
                }
            }
            SheetHost(sheets)
        }
    }
}

/** The path as buttons (~ for home), scrolled to its end; a filter for the folder at the right. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Crumbs(model: FilesModel) {
    val cs = MaterialTheme.colorScheme
    var filtering by remember { mutableStateOf(false) }
    val l = model.listing
    if (filtering) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SearchField(model.filter, "Filter this folder", { model.filter = it },
                Modifier.weight(1f).padding(start = 12.dp, top = 6.dp, bottom = 6.dp))
            IconButton(onClick = { filtering = false; model.filter = "" }) { Icon(Ic.close, "Close filter") }
        }
        return
    }
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        val scroll = rememberScrollState()
        LaunchedEffect(l?.path) { scroll.scrollTo(scroll.maxValue) }
        Row(Modifier.weight(1f).horizontalScroll(scroll).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (l != null) {
                val home = l.home
                val inHome = l.path == home || l.path.startsWith("$home/")
                val parts = (if (inHome) l.path.removePrefix(home) else l.path).split('/').filter { it.isNotEmpty() }
                var acc = if (inHome) home else ""
                val crumbs = ArrayList<Pair<String, String>>()
                if (inHome) crumbs.add("~" to home) else crumbs.add("/" to "/")
                for (p in parts) {
                    acc += "/$p"
                    crumbs.add(p to acc)
                }
                crumbs.forEachIndexed { i, (name, path) ->
                    if (i > 0 && !(i == 1 && !inHome)) Text("/", color = cs.outline, modifier = Modifier.padding(horizontal = 1.dp))
                    val last = i == crumbs.lastIndex
                    Text(name, Modifier.clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = { model.go(path) })
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (last) cs.onSurface else cs.onSurfaceVariant, maxLines = 1)
                }
            }
        }
        IconButton(onClick = { filtering = true }) { Icon(Ic.search, "Filter", Modifier.size(20.dp)) }
    }
}

private val KIND_COLORS = mapOf(
    "pdf" to Color(0xFFE5534B), "office" to Color(0xFF4A8FE7), "image" to Color(0xFF3FB27F), "video" to Color(0xFFB45AE0),
    "audio" to Color(0xFFE08A3C), "markdown" to Color(0xFF8C8C8C), "html" to Color(0xFFE0A33C),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(e: FsEntry, path: String, onClick: () -> Unit, onLong: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (e.dir) cs.secondaryContainer else cs.surfaceContainerHigh),
            contentAlignment = Alignment.Center) {
            if (e.dir) Icon(Ic.folder, null, Modifier.size(22.dp), tint = cs.onSecondaryContainer)
            else {
                val ext = e.name.substringAfterLast('.', "").take(4).lowercase()
                Text(ext.ifEmpty { "·" }, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp), fontWeight = FontWeight.Bold,
                    color = KIND_COLORS[e.kind] ?: cs.onSurfaceVariant)
                if (e.kind == "image" && (e.size ?: 0) < 8_000_000) {
                    AsyncImage(FilesApi.raw(ctx, path), null, Net.images(ctx), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (e.dir) whenShort(ctx, e.mtime) else "${size(e.size)} · ${whenShort(ctx, e.mtime)}",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun FileSheet(path: String, e: FsEntry, sheets: Sheets) {
    val ctx = LocalContext.current
    val ops = LocalOps.current
    val scope = rememberCoroutineScope()
    SheetTitle(path)
    SheetRow(Ic.open, "Open with…", "Another app on this phone") {
        sheets.dismiss()
        openWith(ctx, scope, ops, FilesApi.raw(ctx, path), e.name)
    }
    SheetRow(Ic.download, "Download", size(e.size)) {
        sheets.dismiss()
        ops.download(FilesApi.url(ctx, "raw", path, "&download=1"), e.name)
    }
    SheetRow(Ic.copy, "Copy path", "For a chat with your agent") {
        sheets.dismiss()
        copyText(ctx, path, "Path copied")
    }
}

@Composable
private fun DownCard(s: BoxStatus) {
    val ops = LocalOps.current
    val cs = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().padding(16.dp), shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHigh) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (s.stopped) "The devbox is off" else s.label, style = MaterialTheme.typography.titleMedium)
            Text(if (s.stopped) "Start it from the Devbox section; this folder opens once it's up." else "This takes a minute or two.",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            if (s.stopped) {
                Spacer(Modifier.height(6.dp))
                FilledTonalButton(onClick = { ops.showDevbox() }) { Text("Devbox") }
            }
        }
    }
}
