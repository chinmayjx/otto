package io.github.otto.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** The Artifacts section's state: the list (what was shown last, at once, then the hub's), the search. */
@Stable
class ArtifactsModel(private val ctx: Context) {
    var items by mutableStateOf<List<ArtifactItem>>(emptyList())
        private set
    var fetched by mutableStateOf(false) // the hub answered at least once (until then, the list may be the cache's)
        private set
    var busy by mutableStateOf(false)
        private set
    var pulled by mutableStateOf(false) // a refresh the user pulled for: the spinner shows
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var query by mutableStateOf("")
    private val scope = MainScope()
    private var job: Job? = null

    init {
        Store.read(ctx, KEY)?.let { runCatching { items = ArtifactItem.parseList(it) } }
    }

    fun refresh(pull: Boolean = false) {
        if (pull) pulled = true
        if (job?.isActive == true) return
        job = scope.launch {
            busy = true
            try {
                val text = ArtifactsApi.list(ctx)
                items = ArtifactItem.parseList(text)
                Store.write(ctx, KEY, text)
                error = null
                fetched = true
            } catch (e: Exception) {
                error = if (e is HttpError) "The artifact hub said ${e.code}." else unreachable("the artifact hub")
            } finally {
                busy = false
                pulled = false
            }
        }
    }

    fun shown(): List<ArtifactItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return items
        return items.filter { it.title.lowercase().contains(q) || it.label.lowercase().contains(q) || it.kind.lowercase().contains(q) }
    }

    fun destroy() = scope.cancel()

    companion object {
        private const val KEY = "artifacts"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactsScreen(model: ArtifactsModel) {
    val ops = LocalOps.current
    val cs = MaterialTheme.colorScheme
    val grid = rememberLazyGridState()
    Surface(Modifier.fillMaxSize(), color = cs.surface) {
        Column(Modifier.fillMaxSize()) {
            SearchField(model.query, "Search artifacts", { model.query = it }, Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp))
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (model.busy && !model.pulled) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }
            PullToRefreshBox(isRefreshing = model.pulled, onRefresh = { model.refresh(pull = true) }, modifier = Modifier.weight(1f)) {
                val shown = model.shown()
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(156.dp),
                    state = grid,
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (shown.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Empty(when {
                                model.items.isNotEmpty() -> "Nothing matches."
                                model.error != null -> model.error!!
                                model.fetched -> "No artifacts yet."
                                else -> "Loading…"
                            })
                        }
                    }
                    items(shown, key = { it.id }) { a -> ArtifactCard(a) { ops.openViewer(Target.Artifact(a.id), a) } }
                }
            }
        }
    }
}

@Composable
private fun ArtifactCard(a: ArtifactItem, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerLow, tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                // The kind shows through where there's no thumbnail (or until it comes).
                Text(a.label, style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant.copy(alpha = 0.6f))
                AsyncImage(
                    model = ArtifactsApi.thumb(ctx, a.latestVersion),
                    contentDescription = null,
                    imageLoader = Net.images(ctx),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(a.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, minLines = 2)
                Spacer(Modifier.height(2.dp))
                Text("${a.label} · v${a.versions} · ${ago(a.updated)}", style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A rounded search field, as the setup screen's fields: search icon, the text, × to clear. */
@Composable
fun SearchField(value: String, hint: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, field: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerHigh, modifier = modifier.fillMaxWidth().height(48.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.search, null, Modifier.size(20.dp), tint = cs.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                BasicTextField(value, onChange, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = field.fillMaxWidth())
            }
            if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Ic.close, "Clear", Modifier.size(20.dp)) }
            else Spacer(Modifier.width(12.dp))
        }
    }
}
