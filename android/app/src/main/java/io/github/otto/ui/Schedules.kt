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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.otto.Clips
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ScheduleItem(
    val name: String, val description: String, val prompt: String, val agent: String,
    val paused: Boolean, val running: Boolean, val next: String?, val last: RunItem?,
)

data class RunItem(val id: String, val schedule: String, val started: String, val status: String, val trigger: String, val result: String) {
    companion object {
        fun parse(o: JSONObject) = RunItem(o.getString("id"), o.getString("schedule"), o.getString("started"),
            o.getString("status"), o.optString("trigger"), o.optString("result"))
    }
}

/** The box's schedules (otto-bots, through the relay's box API: /schedules…). */
object SchedulesApi {
    private val JSON = "application/json".toMediaType()

    private fun call(method: String, path: String, body: JSONObject? = null): String {
        val b = Request.Builder().url("${Clips.BASE}$path")
        if (body != null) b.method(method, body.toString().toRequestBody(JSON)) else if (method != "GET") b.method(method, ByteArray(0).toRequestBody(JSON))
        return Clips.client().newCall(b.build()).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) throw java.io.IOException(text.trim().ifEmpty { "the box said ${r.code}" })
            text
        }
    }

    fun list(): Pair<String, List<ScheduleItem>> {
        val o = JSONObject(call("GET", "/schedules"))
        val a = o.getJSONArray("schedules")
        return o.optString("timezone", "Asia/Kolkata") to (0 until a.length()).map { i ->
            val s = a.getJSONObject(i)
            ScheduleItem(s.getString("name"), s.optString("description"), s.optString("prompt"), s.optString("agent", "claude"),
                s.optBoolean("paused"), s.optBoolean("running"), if (s.isNull("next")) null else s.optString("next"),
                s.optJSONObject("last")?.let { RunItem.parse(it) })
        }
    }

    fun runs(name: String): List<RunItem> {
        val a = JSONObject(call("GET", "/schedules/$name/runs?limit=15")).getJSONArray("runs")
        return (0 until a.length()).map { RunItem.parse(a.getJSONObject(it)) }
    }

    /** A run and its steps: (kind, text), kind prompt|text|tool|result. */
    fun run(name: String, id: String): Pair<RunItem, List<Pair<String, String>>> {
        val o = JSONObject(call("GET", "/schedules/$name/runs/$id"))
        val s: JSONArray = o.getJSONArray("steps")
        return RunItem.parse(o.getJSONObject("run")) to (0 until s.length()).map { s.getJSONObject(it).let { x -> x.getString("kind") to x.getString("text") } }
    }

    fun pause(name: String, on: Boolean) { call("PATCH", "/schedules/$name", JSONObject().put("paused", on)) }
    fun runNow(name: String) { call("POST", "/schedules/$name/run") }
}

/** The Schedules section's state: the box's schedules (what was shown last, at once, then the box's). */
@Stable
class SchedulesModel(private val ctx: Context) {
    var items by mutableStateOf<List<ScheduleItem>>(emptyList())
        private set
    var zone by mutableStateOf("Asia/Kolkata")
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

    fun refresh(pull: Boolean = false) {
        if (pull) pulled = true
        if (job?.isActive == true) return
        job = scope.launch {
            busy = true
            try {
                val (tz, list) = withContext(Dispatchers.IO) { SchedulesApi.list() }
                zone = tz
                items = list
                error = null
                fetched = true
            } catch (e: Exception) {
                error = "Can't get the schedules: ${e.message ?: e}"
            } finally {
                busy = false
                pulled = false
            }
        }
    }

    /** Something done to a schedule, then the list again. */
    fun act(done: String, f: () -> Unit) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { f() }
                toast(ctx, done)
            } catch (e: Exception) {
                toast(ctx, e.message ?: e.toString())
            }
            refresh()
        }
    }

    fun <T> load(f: () -> T, then: (T) -> Unit) {
        scope.launch {
            try { then(withContext(Dispatchers.IO) { f() }) } catch (e: Exception) { toast(ctx, e.message ?: e.toString()) }
        }
    }

    fun time(iso: String?): String {
        if (iso.isNullOrEmpty()) return "-"
        return try {
            DateTimeFormatter.ofPattern("EEE d MMM, HH:mm").withZone(ZoneId.of(zone)).format(Instant.parse(iso))
        } catch (e: Exception) { iso }
    }

    fun destroy() = scope.cancel()
}

/**
 * The schedules on the box: each one's timing, when it runs next, and how its last run went. A tap opens it: its
 * prompt, Run now and Pause, and its recent runs; a run opens what it did. Making and changing schedules is the
 * assistant's job (it runs `otto schedule`): just ask it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulesScreen(model: SchedulesModel) {
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
                                    model.fetched -> "No schedules yet. Ask your assistant for one, e.g. \"every weekday at 8, " +
                                        "tell me what's on my calendar\"."
                                    else -> "Loading…"
                                })
                            }
                        }
                        items(model.items, key = { it.name }) { s ->
                            ScheduleRow(model, s) { sheets.show { ScheduleSheet(model, sheets, s) } }
                            HorizontalDivider(color = cs.outlineVariant)
                        }
                    }
                }
            }
            SheetHost(sheets)
        }
    }
}

@Composable
private fun ScheduleRow(model: SchedulesModel, s: ScheduleItem, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.description, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
            Text(when {
                s.running -> "Running now"
                s.paused -> "Paused"
                s.next == null -> "Done"
                else -> "Next ${model.time(s.next)}"
            }, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        s.last?.let { StatusChip(it.status) }
    }
}

@Composable
private fun StatusChip(status: String) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (status) {
        "ok" -> cs.secondaryContainer to cs.onSecondaryContainer
        "failed", "timeout" -> cs.errorContainer to cs.onErrorContainer
        else -> cs.surfaceContainerHighest to cs.onSurfaceVariant
    }
    Text(status, Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall, color = fg)
}

/** One schedule: its prompt, Run now, Pause or Resume, and its recent runs. */
@Composable
private fun ScheduleSheet(model: SchedulesModel, sheets: Sheets, s: ScheduleItem) {
    val cs = MaterialTheme.colorScheme
    var runs by remember(s.name) { mutableStateOf<List<RunItem>?>(null) }
    androidx.compose.runtime.LaunchedEffect(s.name) { model.load({ SchedulesApi.runs(s.name) }) { runs = it } }
    SheetTitle("${s.name} · ${s.description}")
    Text(s.prompt.trim(), Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium,
        maxLines = 8, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.size(8.dp))
    SheetRow(Ic.latest, "Run now", if (s.running) "A run is going" else null) {
        sheets.dismiss()
        model.act("Started") { SchedulesApi.runNow(s.name) }
    }
    SheetRow(Ic.power, if (s.paused) "Resume" else "Pause", if (s.paused) "Paused" else s.next?.let { "Next ${model.time(it)}" }) {
        sheets.dismiss()
        model.act(if (s.paused) "Resumed" else "Paused") { SchedulesApi.pause(s.name, !s.paused) }
    }
    SheetTitle("Recent runs")
    val list = runs
    when {
        list == null -> Text("Loading…", Modifier.padding(horizontal = 24.dp), color = cs.onSurfaceVariant)
        list.isEmpty() -> Text("None yet", Modifier.padding(horizontal = 24.dp), color = cs.onSurfaceVariant)
        else -> list.forEach { r ->
            SheetRow(null, "${model.time(r.started)} · ${r.status}${if (r.trigger == "now") " (run now)" else ""}",
                r.result.lineSequence().firstOrNull()?.take(120)) {
                model.load({ SchedulesApi.run(s.name, r.id) }) { (run, steps) -> sheets.show { RunSheet(model, run, steps) } }
            }
        }
    }
}

/** What a run did: the agent's words, the commands it ran, and its last words. */
@Composable
private fun RunSheet(model: SchedulesModel, r: RunItem, steps: List<Pair<String, String>>) {
    val cs = MaterialTheme.colorScheme
    SheetTitle("${r.schedule} · ${model.time(r.started)} · ${r.status}")
    if (steps.isEmpty()) Text(r.result.ifEmpty { "Nothing recorded." }, Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
    steps.forEach { (kind, text) ->
        if (kind == "tool") {
            Text(text, Modifier.padding(horizontal = 24.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
        } else {
            Text(text, Modifier.padding(horizontal = 24.dp, vertical = 6.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
