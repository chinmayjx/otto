package io.github.otto.ui

import android.graphics.Typeface
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableTheme
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.network.OkHttpNetworkSchemeHandler
import io.noties.markwon.linkify.LinkifyPlugin

// --- Audio and video: Media3 ------------------------------------------------------------------------------------

/** A player for the file at url, with the platform's controls. Audio keeps its controls shown. */
@OptIn(UnstableApi::class)
@Composable
fun MediaPlayer(url: String, audio: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { c ->
            PlayerView(c).apply {
                this.player = player
                setShowNextButton(false)
                setShowPreviousButton(false)
                if (audio) {
                    controllerShowTimeoutMs = 0
                    controllerHideOnTouch = false
                    showController()
                }
            }
        },
        modifier = modifier.fillMaxSize().background(Color.Black),
    )
}

// --- Markdown: Markwon ------------------------------------------------------------------------------------------

/** Markdown, rendered with Markwon in the theme's colors. Links open in the second screen's browser. */
@Composable
fun MarkdownText(text: String, onLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val markwon = remember(cs) {
        Markwon.builder(ctx)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create { b: TableTheme.Builder ->
                b.tableBorderColor(cs.outlineVariant.toArgb()).tableBorderWidth(with(density) { 1.dp.roundToPx() })
                    .tableCellPadding(with(density) { 6.dp.roundToPx() })
                    .tableHeaderRowBackgroundColor(cs.surfaceContainerHigh.toArgb())
                    .tableEvenRowBackgroundColor(cs.surfaceContainerLow.toArgb())
                    .tableOddRowBackgroundColor(cs.surface.toArgb())
            })
            .usePlugin(TaskListPlugin.create(cs.primary.toArgb(), cs.primary.toArgb(), cs.surface.toArgb()))
            .usePlugin(HtmlPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(ImagesPlugin.create { it.addSchemeHandler(OkHttpNetworkSchemeHandler.create(Net.client(ctx))) })
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(b: MarkwonTheme.Builder) {
                    b.linkColor(cs.primary.toArgb())
                        .codeTextColor(cs.onSurface.toArgb())
                        .codeBackgroundColor(cs.surfaceContainerHigh.toArgb())
                        .codeBlockBackgroundColor(cs.surfaceContainerHigh.toArgb())
                        .codeTypeface(Typeface.MONOSPACE)
                        .codeTextSize(with(density) { 13.sp.roundToPx() })
                        .blockQuoteColor(cs.outlineVariant.toArgb())
                        .thematicBreakColor(cs.outlineVariant.toArgb())
                        .headingBreakColor(cs.outlineVariant.toArgb())
                        .bulletWidth(with(density) { 5.dp.roundToPx() })
                }

                override fun configureConfiguration(b: MarkwonConfiguration.Builder) {
                    b.linkResolver { _, link -> onLink(link) }
                }
            })
            .build()
    }
    val fg = cs.onSurface.toArgb()
    val place = LocalPlace.current
    val slack = with(density) { 24.dp.roundToPx() }
    AndroidView(
        factory = { c ->
            ScrollView(c).apply {
                isFillViewport = true
                // The heading above the top of what's in view, for the collector's notes.
                if (place != null) setOnScrollChangeListener { v, _, y, _, _ -> place.label = heading((v as ScrollView).getChildAt(0) as TextView, y + slack) }
                val pad = with(density) { 18.dp.roundToPx() }
                addView(TextView(c).apply {
                    setPadding(pad, pad / 2, pad, pad * 3)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                    setLineSpacing(0f, 1.25f)
                    setTextIsSelectable(true)
                    movementMethod = LinkMovementMethod.getInstance()
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        },
        update = { v ->
            val tv = v.getChildAt(0) as TextView
            tv.setTextColor(fg)
            if (tv.tag != text) {
                tv.tag = text
                markwon.setMarkdown(tv, text)
                if (place != null) tv.post { place.label = heading(tv, v.scrollY + slack) }
            }
        },
        modifier = modifier.fillMaxSize(),
    )
}

/** The last heading that starts above y (the text view's pixels), as a place: "§ Heading". */
private fun heading(tv: TextView, y: Int): String? {
    val sp = tv.text as? android.text.Spanned ?: return null
    val layout = tv.layout ?: return null
    var best: String? = null
    var bestAt = -1
    for (h in sp.getSpans(0, sp.length, io.noties.markwon.core.spans.HeadingSpan::class.java)) {
        val start = sp.getSpanStart(h)
        val at = layout.getLineTop(layout.getLineForOffset(start)) + tv.paddingTop
        if (at <= y && at > bestAt) {
            bestAt = at
            best = sp.subSequence(start, sp.getSpanEnd(h)).toString().trim()
        }
    }
    return best?.takeIf { it.isNotEmpty() }?.let { "§ " + if (it.length > 60) it.take(59) + "…" else it }
}

// --- Text and code ----------------------------------------------------------------------------------------------

/**
 * Plain text or code: numbered lines, wrapped, lazily laid out (a big log opens at once), lightly highlighted by
 * the file's extension.
 */
@Composable
fun CodeText(text: String, name: String, note: String? = null, wrap: Boolean = true, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val lines = remember(text) { text.removeSuffix("\n").split('\n') }
    val lang = remember(name) { Lang.of(name) }
    val states = remember(text, lang) { if (lang == null || text.length > 3_000_000) null else lang.blockStates(lines) }
    val numW = with(LocalDensity.current) { (lines.size.toString().length * 8.5f + 14).dp }
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp, color = cs.onSurface)
    val hs = rememberScrollState()
    SelectionContainer(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().then(if (wrap) Modifier else Modifier.horizontalScroll(hs)).padding(vertical = 6.dp)) {
            if (note != null) item {
                Text(note, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            itemsIndexed(lines) { i, line ->
                Row(Modifier.then(if (wrap) Modifier.fillMaxWidth() else Modifier)) {
                    Text((i + 1).toString(), Modifier.width(numW).padding(end = 10.dp), style = style.copy(color = cs.outline,
                        textAlign = TextAlign.End))
                    val shown = remember(line, lang, states) {
                        if (lang == null || states == null) AnnotatedString(line) else lang.highlight(line, states[i], cs)
                    }
                    Text(shown, Modifier.padding(end = 12.dp), style = style, softWrap = wrap)
                }
            }
        }
    }
}

/** A small highlighter: comments, strings, numbers and keywords, by language family. Not a parser; a reading aid. */
class Lang(
    private val keywords: Set<String>,
    private val line: List<String>, // line comment starts
    private val block: Pair<String, String>?,
    private val quotes: String,
) {
    /** For each line: whether it starts inside a block comment. */
    fun blockStates(lines: List<String>): BooleanArray {
        val out = BooleanArray(lines.size)
        if (block == null) return out
        var inside = false
        for ((i, l) in lines.withIndex()) {
            out[i] = inside
            var j = 0
            var q: Char? = null
            while (j < l.length) {
                if (inside) {
                    val e = l.indexOf(block.second, j)
                    if (e < 0) break
                    inside = false
                    j = e + block.second.length
                    continue
                }
                val c = l[j]
                if (q != null) {
                    if (c == '\\') j++ else if (c == q) q = null
                } else if (quotes.indexOf(c) >= 0) q = c
                else if (line.any { l.startsWith(it, j) }) break
                else if (l.startsWith(block.first, j)) {
                    inside = true
                    j += block.first.length
                    continue
                }
                j++
            }
        }
        return out
    }

    fun highlight(l: String, startsInside: Boolean, cs: ColorScheme): AnnotatedString = buildAnnotatedString {
        val comment = SpanStyle(color = cs.outline, fontStyle = FontStyle.Italic)
        val string = SpanStyle(color = cs.tertiary)
        val number = SpanStyle(color = cs.secondary)
        val keyword = SpanStyle(color = cs.primary)
        var j = 0
        if (startsInside && block != null) {
            val e = l.indexOf(block.second)
            val end = if (e < 0) l.length else e + block.second.length
            pushStyle(comment); append(l.substring(0, end)); pop()
            j = end
        }
        while (j < l.length) {
            val c = l[j]
            if (line.any { l.startsWith(it, j) }) {
                pushStyle(comment); append(l.substring(j)); pop()
                return@buildAnnotatedString
            }
            if (block != null && l.startsWith(block.first, j)) {
                val e = l.indexOf(block.second, j + block.first.length)
                val end = if (e < 0) l.length else e + block.second.length
                pushStyle(comment); append(l.substring(j, end)); pop()
                j = end
                continue
            }
            if (quotes.indexOf(c) >= 0) {
                var k = j + 1
                while (k < l.length && l[k] != c) k += if (l[k] == '\\') 2 else 1
                val end = min(l.length, k + 1)
                pushStyle(string); append(l.substring(j, end)); pop()
                j = end
                continue
            }
            if (c.isLetter() || c == '_' || c == '$' || c == '@') {
                var k = j + 1
                while (k < l.length && (l[k].isLetterOrDigit() || l[k] == '_' || l[k] == '$')) k++
                val w = l.substring(j, k)
                if (w in keywords) { pushStyle(keyword); append(w); pop() } else append(w)
                j = k
                continue
            }
            if (c.isDigit()) {
                var k = j + 1
                while (k < l.length && (l[k].isLetterOrDigit() || l[k] == '.' || l[k] == '_')) k++
                pushStyle(number); append(l.substring(j, k)); pop()
                j = k
                continue
            }
            append(c)
            j++
        }
    }

    companion object {
        private val C_LIKE = setOf("if", "else", "for", "while", "do", "return", "break", "continue", "switch", "case", "default",
            "class", "interface", "enum", "struct", "new", "this", "super", "null", "true", "false", "try", "catch", "finally",
            "throw", "throws", "import", "package", "public", "private", "protected", "static", "final", "const", "let", "var",
            "val", "fun", "function", "async", "await", "yield", "export", "from", "extends", "implements", "typeof", "instanceof",
            "void", "int", "long", "float", "double", "boolean", "bool", "char", "string", "String", "object", "override", "data",
            "sealed", "when", "is", "in", "as", "fn", "impl", "pub", "mut", "use", "mod", "match", "type", "go", "func", "defer",
            "chan", "map", "range", "undefined", "nil", "self", "Self", "static", "using", "namespace", "template", "auto", "lateinit",
            "suspend", "inline", "companion", "abstract", "open", "internal", "operator", "where", "readonly", "declare", "of")
        private val HASH = setOf("if", "elif", "else", "for", "while", "in", "def", "class", "return", "import", "from", "as",
            "with", "try", "except", "finally", "raise", "pass", "break", "continue", "lambda", "yield", "True", "False", "None",
            "and", "or", "not", "is", "async", "await", "global", "nonlocal", "then", "fi", "do", "done", "case", "esac", "function",
            "local", "export", "echo", "true", "false", "null", "end", "module", "require", "unless", "begin", "yes", "no", "on", "off")
        private val SQL = setOf("select", "from", "where", "and", "or", "not", "insert", "into", "values", "update", "set", "delete",
            "create", "table", "index", "drop", "alter", "join", "left", "right", "inner", "outer", "on", "group", "by", "order",
            "limit", "as", "null", "is", "in", "primary", "key", "distinct", "having", "union", "case", "when", "then", "else", "end",
            "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE",
            "TABLE", "JOIN", "LEFT", "ON", "GROUP", "BY", "ORDER", "LIMIT", "AS", "NULL", "IS", "IN", "DISTINCT", "WITH")

        fun of(name: String): Lang? {
            val n = name.lowercase()
            val e = n.substringAfterLast('.', "")
            return when {
                e in setOf("js", "mjs", "cjs", "ts", "tsx", "jsx", "java", "kt", "kts", "c", "h", "cpp", "cc", "hpp", "cs", "go",
                    "rs", "swift", "scala", "dart", "php", "gradle", "groovy", "proto") -> Lang(C_LIKE, listOf("//"), "/*" to "*/", "\"'`")
                e in setOf("css", "scss", "less") -> Lang(emptySet(), emptyList(), "/*" to "*/", "\"'")
                e in setOf("json", "jsonl") -> Lang(setOf("true", "false", "null"), emptyList(), null, "\"")
                e in setOf("py", "sh", "bash", "zsh", "yml", "yaml", "toml", "rb", "conf", "ini", "cfg", "r", "pl", "env", "service",
                    "properties") || n == "dockerfile" || n == "makefile" || n.startsWith(".") && e == n.drop(1) ->
                    Lang(HASH, listOf("#"), null, "\"'")
                e == "sql" -> Lang(SQL, listOf("--"), "/*" to "*/", "'\"")
                e in setOf("lua") -> Lang(HASH, listOf("--"), null, "\"'")
                else -> null
            }
        }
    }
}

private fun min(a: Int, b: Int) = if (a < b) a else b

/** A loading or failure message filling the viewer. */
@Composable
fun Centered(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { Empty(text) }
}
