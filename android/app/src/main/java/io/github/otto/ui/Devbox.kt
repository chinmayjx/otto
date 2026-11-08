package io.github.otto.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.min

/**
 * The Devbox section's state, from the box's control panel (config: pairing's panel): the box's state, polled
 * (every 3 s while it changes, 15 s otherwise), and its metrics every 5 s while it runs and the section is shown.
 */
@Stable
class DevboxModel(private val ctx: Context) {
    var status by mutableStateOf<BoxStatus?>(null)
        private set
    var metrics by mutableStateOf<JSONObject?>(null)
        private set
    var stale by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var acting by mutableStateOf(false)
        private set
    private val scope = MainScope()
    private var loop: Job? = null

    /** Polls while shown. */
    fun shown(on: Boolean) {
        loop?.cancel()
        loop = null
        if (!on) return
        if (!PanelApi.configured(ctx)) {
            error = "No control panel is set up for this box."
            return
        }
        loop = scope.launch {
            var lastMetrics = 0L
            while (true) {
                try {
                    val s = PanelApi.status(ctx)
                    status = s
                    error = null
                } catch (e: Exception) {
                    error = unreachable("the box's control panel")
                }
                val s = status
                if (s?.running == true) {
                    // Metrics every 5 s; the status with them, less often.
                    val until = System.currentTimeMillis() + 15_000
                    while (System.currentTimeMillis() < until) {
                        if (System.currentTimeMillis() - lastMetrics >= 4_500) {
                            lastMetrics = System.currentTimeMillis()
                            try {
                                metrics = PanelApi.metrics(ctx)
                                stale = false
                            } catch (_: Exception) {
                                stale = true
                            }
                        }
                        delay(5_000)
                    }
                } else {
                    metrics = null
                    delay(if (s == null || s.stopped) 10_000 else 3_000)
                }
            }
        }
    }

    fun act(what: String) {
        scope.launch {
            acting = true
            try {
                PanelApi.act(ctx, what)
            } catch (e: Exception) {
                toast(ctx, "Couldn't $what: ${e.message}")
            }
            acting = false
            shown(true)
        }
    }

    fun destroy() = scope.cancel()
}

private const val GB = 1073741824.0
private fun gb(b: Long) = "%.1f".format(b / GB)
private fun upFor(min: Int) = if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"

@Composable
fun DevboxScreen(model: DevboxModel) {
    val cs = MaterialTheme.colorScheme
    val sheets = remember { Sheets() }
    Surface(Modifier.fillMaxSize(), color = cs.surface) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val s = model.status
                when {
                    s == null && model.error != null -> Empty(model.error!!)
                    s == null -> Empty("Checking…")
                    else -> {
                        Head(s, model, sheets)
                        model.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error) }
                        model.metrics?.let { Metrics(it, Modifier.alpha(if (model.stale) 0.5f else 1f)) }
                    }
                }
            }
            SheetHost(sheets)
        }
    }
}

@Composable
private fun Head(s: BoxStatus, model: DevboxModel, sheets: Sheets) {
    val cs = MaterialTheme.colorScheme
    Section {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(when {
                s.running -> Color(0xFF3FB27F)
                s.stopped -> cs.outline
                else -> Color(0xFFE0A33C)
            }))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(s.label, style = MaterialTheme.typography.titleLarge)
                val sub = when {
                    s.running -> "up ${upFor(s.upMinutes)}" + if (s.upMinutes < 3) " · agents are ready in about a minute" else ""
                    s.stopped -> "not costing compute"
                    else -> "this takes a minute or two"
                }
                Text("${s.name} · ${s.type} · $sub", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (s.running) OutlinedButton(enabled = !model.acting, onClick = {
                sheets.ask("Stop the devbox?", "Running agents and terminals stop; they're back on the next start.", "Stop") { model.act("stop") }
            }) { Text("Stop") }
            else if (s.stopped) Button(enabled = !model.acting, onClick = { model.act("start") }) { Text("Start") }
        }
    }
}

@Composable
private fun Section(title: String? = null, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun Meter(pct: Double) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.surfaceContainerHighest)) {
        Box(Modifier.fillMaxWidth(min(1.0, pct / 100).toFloat()).height(6.dp).clip(RoundedCornerShape(3.dp))
            .background(if (pct >= 90) cs.error else if (pct >= 75) Color(0xFFE0A33C) else cs.primary))
    }
}

@Composable
private fun Kv(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(v, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Metrics(m: JSONObject, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val cpu = m.optJSONObject("cpu") ?: JSONObject()
    val mem = m.optJSONObject("mem") ?: JSONObject()
    val memPct = if (mem.optLong("total") > 0) mem.optLong("used") * 100.0 / mem.optLong("total") else 0.0
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat("${cpu.optDouble("pct").toInt()}%", "CPU · ${cpu.optInt("cores")} cores", cpu.optDouble("pct"), Modifier.weight(1f))
                Stat("${memPct.toInt()}%", "${gb(mem.optLong("used"))} of ${gb(mem.optLong("total"))} GB", memPct, Modifier.weight(1f))
                Stat(m.optInt("claude_chats").toString(), "Agent chats", null, Modifier.weight(1f))
            }
        }
        Section("Last 30 minutes") {
            Chart(m.optJSONArray("history"), cs.primary, cs.tertiary, cs.outlineVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Legend(cs.primary, "CPU")
                Legend(cs.tertiary, "Memory")
            }
        }
        m.optJSONObject("idle")?.let { idle ->
            Section("Auto-stop") {
                val busy = idle.optString("busy").takeIf { !idle.isNull("busy") && it.isNotEmpty() }
                Kv(if (busy != null) "Busy ($busy)" else "Idle ${idle.optInt("idle_minutes")} min",
                    if (busy != null || idle.optInt("shutdown_in") > 0) "stops ${idle.optInt("shutdown_in")} min after going idle" else "stopping soon")
            }
        }
        m.optJSONArray("disks")?.let { disks ->
            Section("Disks") {
                for (i in 0 until disks.length()) {
                    val d = disks.getJSONObject(i)
                    Kv(d.optString("mount"), "${gb(d.optLong("used"))} / ${gb(d.optLong("total"))} GB")
                    Meter(d.optLong("used") * 100.0 / maxOf(1L, d.optLong("total")))
                }
                cpu.optJSONArray("load")?.let { l ->
                    Kv("Load 1 / 5 / 15 min", (0 until l.length()).joinToString(" / ") { "%.2f".format(l.optDouble(it)) })
                }
            }
        }
        m.optJSONArray("top")?.let { top ->
            Section("Memory by program") {
                for (i in 0 until min(10, top.length())) {
                    val t = top.getJSONObject(i)
                    Kv(t.optString("name") + if (t.optInt("count") > 1) " ×${t.optInt("count")}" else "", "${gb(t.optLong("bytes"))} GB")
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, pct: Double?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
        if (pct != null) Meter(pct) else Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun Legend(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/** CPU and memory (percent) over the last 30 minutes: history [[time, cpu, mem], ...]. */
@Composable
private fun Chart(h: org.json.JSONArray?, cpuColor: Color, memColor: Color, grid: Color) {
    if (h == null || h.length() < 2) {
        Text("Collecting…", style = MaterialTheme.typography.bodySmall)
        return
    }
    val pts = (0 until h.length()).map { h.getJSONArray(it) }
    val t1 = pts.last().getDouble(0)
    val t0 = t1 - 1800
    Canvas(Modifier.fillMaxWidth().height(96.dp)) {
        fun x(t: Double) = ((t - t0) / 1800 * size.width).toFloat().coerceAtLeast(0f)
        fun y(v: Double) = (size.height - 2 - v / 100 * (size.height - 4)).toFloat()
        for (v in listOf(25.0, 50.0, 75.0)) drawLine(grid, Offset(0f, y(v)), Offset(size.width, y(v)), strokeWidth = 1f)
        for ((i, c) in listOf(2 to memColor, 1 to cpuColor)) {
            val p = Path()
            pts.forEachIndexed { k, a -> if (k == 0) p.moveTo(x(a.getDouble(0)), y(a.getDouble(i))) else p.lineTo(x(a.getDouble(0)), y(a.getDouble(i))) }
            drawPath(p, c, style = Stroke(width = 2.dp.toPx()))
        }
    }
}
