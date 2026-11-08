package io.github.otto.ui

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.otto.ClipItem
import io.github.otto.Clips
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Clipboard section's state: the clips on the box (what was shown last, at once, then the relay's). */
@Stable
class ClipboardModel(private val ctx: Context) {
    var items by mutableStateOf<List<ClipItem>>(emptyList())
        private set
    var fetched by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var pulled by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private val scope = MainScope()
    private var job: Job? = null

    init {
        Store.read(ctx, KEY)?.let { runCatching { items = ClipItem.parseList(it) } }
    }

    fun refresh(pull: Boolean = false) {
        if (pull) pulled = true
        if (job?.isActive == true) return
        job = scope.launch {
            busy = true
            try {
                val text = withContext(Dispatchers.IO) { Clips.list() }
                items = ClipItem.parseList(text)
                Store.write(ctx, KEY, text)
                error = null
                fetched = true
            } catch (e: Exception) {
                error = if (e is HttpError) "The relay said ${e.code}." else "Can't reach the box: ${e.message ?: e}"
            } finally {
                busy = false
                pulled = false
            }
        }
    }

    fun delete(id: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { Clips.delete(id) }
                items = items.filter { it.id != id }
                toast(ctx, "Deleted")
            } catch (e: Exception) {
                toast(ctx, "Couldn't delete: ${e.message ?: e}")
            }
        }
    }

    fun destroy() = scope.cancel()

    companion object {
        private const val KEY = "clips"
    }
}

/**
 * The clips sent from this phone (the lens, the viewer's collector), newest first. A tap copies a clip's reference
 * again; its picture opens full screen; × or a long press deletes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardScreen(model: ClipboardModel) {
    val cs = MaterialTheme.colorScheme
    val sheets = remember { Sheets() }
    Surface(Modifier.fillMaxSize(), color = cs.surface) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    if (model.busy && !model.pulled) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                }
                PullToRefreshBox(isRefreshing = model.pulled, onRefresh = { model.refresh(pull = true) }, modifier = Modifier.weight(1f)) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                        if (model.items.isEmpty()) {
                            item {
                                Empty(when {
                                    model.error != null -> model.error!!
                                    model.fetched -> "No clips yet. Long-press Otto's handle to take one from the screen, " +
                                        "or open an artifact and collect notes on it."
                                    else -> "Loading…"
                                })
                            }
                        }
                        items(model.items, key = { it.id }) { c ->
                            ClipRow(c, onDelete = {
                                sheets.ask("Delete clip ${c.id}?", "It goes from the box, for good.", "Delete") { model.delete(c.id) }
                            })
                            HorizontalDivider(color = cs.outlineVariant)
                        }
                    }
                }
            }
            SheetHost(sheets)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipRow(c: ClipItem, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val ops = LocalOps.current
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onLongClick = onDelete) {
                Clips.copyReference(ctx, c.id)
                toast(ctx, "Copied")
            }
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val first = c.images.firstOrNull()
        Box(Modifier.size(64.dp).clip(MaterialTheme.shapes.small).background(cs.surfaceContainerHighest)
            .clickable(enabled = first != null) { if (first != null) ops.openViewer(Target.Clip(c.id, first)) },
            contentAlignment = Alignment.Center) {
            if (first != null) {
                AsyncImage(
                    model = Clips.image(c.id, c.thumb ?: first),
                    contentDescription = "Open the image",
                    imageLoader = Clips.images(ctx),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            } else Icon(Ic.clipboard, null, tint = cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (c.text.isNotBlank()) {
                Text(c.text.trim(), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                Text("No text", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = cs.onSurfaceVariant)
            }
            val n = c.images.size
            val parts = listOfNotNull(c.from.ifEmpty { null }, if (n > 1) "$n images" else null, ago(c.created / 1000.0), c.id)
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onDelete) { Icon(Ic.close, "Delete", Modifier.size(20.dp), tint = cs.onSurfaceVariant) }
    }
}
