package io.github.otto.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Date
import kotlin.math.max

/**
 * Back for the native screens: the screen's Back (Screen.back) asks these first, the newest first. A sheet open in
 * the viewer, a folder to go back from.
 */
object NativeBack {
    private val handlers = ArrayList<() -> Boolean>()

    fun add(h: () -> Boolean) = handlers.add(h)
    fun remove(h: () -> Boolean) = handlers.remove(h)
    fun handle(): Boolean = handlers.asReversed().any { it() }
}

/** While it's in the composition and enabled, Back runs onBack. */
@Composable
fun OnBack(enabled: Boolean, onBack: () -> Unit) {
    val on by rememberUpdatedState(enabled)
    val run by rememberUpdatedState(onBack)
    DisposableEffect(Unit) {
        val h = { if (on) run().let { true } else false }
        NativeBack.add(h)
        onDispose { NativeBack.remove(h) }
    }
}

/** A bottom sheet drawn in the screen's own window (a popup window can't be had from the overlay's context). */
@Stable
class Sheets {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set

    fun show(c: @Composable () -> Unit) {
        content = c
    }

    fun dismiss() {
        content = null
    }
}

@Composable
fun BoxScope.SheetHost(sheets: Sheets) {
    val c = sheets.content
    OnBack(c != null) { sheets.dismiss() }
    AnimatedVisibility(c != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.matchParentSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))
            .clickable(MutableInteractionSource(), null) { sheets.dismiss() })
    }
    AnimatedVisibility(c != null, enter = slideInVertically { it }, exit = slideOutVertically { it },
        modifier = Modifier.align(Alignment.BottomCenter)) {
        var last by androidx.compose.runtime.remember { mutableStateOf(c) }
        if (c != null) last = c
        Surface(
            Modifier.fillMaxWidth().heightIn(max = 560.dp).clickable(MutableInteractionSource(), null) {},
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 1.dp,
        ) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
                }
                Column(Modifier.verticalScroll(rememberScrollState())) { last?.invoke() }
            }
        }
    }
}

@Composable
fun SheetTitle(text: String) {
    Text(text, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
fun SheetRow(icon: ImageVector?, title: String, sub: String? = null, on: Boolean = false, danger: Boolean = false,
             lead: String? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(14.dp))
            .background(if (on) cs.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (danger) cs.error else if (on) cs.onSecondaryContainer else cs.onSurfaceVariant
        Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
            if (icon != null) Icon(icon, null, Modifier.size(22.dp), tint = tint)
            else if (lead != null) Text(lead, style = MaterialTheme.typography.labelLarge, color = tint)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (danger) cs.error else cs.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (on) Icon(Ic.check, null, Modifier.size(20.dp), tint = cs.onSecondaryContainer)
    }
}

/** A question with Cancel and an action, in a sheet. */
fun Sheets.ask(title: String, sub: String, action: String, danger: Boolean = true, onYes: () -> Unit) = show {
    Column(Modifier.padding(horizontal = 24.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.size(8.dp))
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { dismiss() }) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { dismiss(); onYes() },
                colors = if (danger) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors(),
            ) { Text(action) }
        }
    }
}

/** A message in the middle of an empty area, with an optional button. */
@Composable
fun Empty(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.size(16.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun Bold(text: String) = Text(text, fontWeight = FontWeight.SemiBold)

fun toast(ctx: Context, text: String) = Toast.makeText(ctx.applicationContext, text, if (text.length > 40) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

fun copyText(ctx: Context, text: String, done: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", text))
    toast(ctx, done)
}

/** "5m ago", "3h ago", "2d ago", then a date. Times in seconds. */
fun ago(t: Double): String {
    val m = (System.currentTimeMillis() / 1000.0 - t) / 60
    return when {
        m < 60 -> "${max(1, m.toInt())}m ago"
        m < 1440 -> "${(m / 60).toInt()}h ago"
        m < 1440 * 30 -> "${(m / 1440).toInt()}d ago"
        else -> DateFormat.format("d MMM", Date((t * 1000).toLong())).toString()
    }
}

/** A file's time: the time today, else the day (and the year when it isn't this one). */
fun whenShort(ctx: Context, t: Long): String {
    val d = Date(t * 1000)
    val now = java.util.Calendar.getInstance()
    val c = java.util.Calendar.getInstance().apply { time = d }
    return when {
        c.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) && c.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR) ->
            DateFormat.getTimeFormat(ctx).format(d)
        c.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) -> DateFormat.format("d MMM", d).toString()
        else -> DateFormat.format("d MMM yyyy", d).toString()
    }
}

fun size(n: Long?): String = when {
    n == null -> ""
    n < 1024 -> "$n B"
    n < 1048576 -> if (n < 10240) "%.1f KB".format(n / 1024.0) else "${n / 1024} KB"
    n < 1073741824 -> "%.1f MB".format(n / 1048576.0)
    else -> "%.1f GB".format(n / 1073741824.0)
}

fun unreachable(what: String) = "Can't reach $what. Is Tailscale on?"
