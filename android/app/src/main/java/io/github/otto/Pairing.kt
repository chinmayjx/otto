package io.github.otto

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File

/**
 * Which box this phone is paired with, and how to reach it: what `otto pair` on the box puts in the pairing link,
 * otto://pair?box=…&browser_port=…&pipe_port=…&device_port=…&browser_token=…&device_token=…&hub=…&artifacts=…
 * (optionally also public_artifacts=… and panel=…).
 *
 * Kept in a small JSON file rather than SharedPreferences: the browser's process (":browser") reads it too, and
 * reads it afresh at every connection, so a new pairing reaches both processes without restarting them.
 */
data class Pairing(
    /** The box's tailnet name (or address): where the links connect. */
    val box: String,
    val browserPort: Int,
    val pipePort: Int,
    val devicePort: Int,
    val browserToken: String,
    val deviceToken: String,
    /** The box's hub (files, desktop, terminal), e.g. https://<box>:8446. */
    val hubUrl: String,
    /** The artifact hub, over the tailnet. */
    val artifactsUrl: String,
    /** The artifact hub's public address, for links to share (optional). */
    val publicArtifactsUrl: String = "",
    /** The box's control panel: status, start, stop (optional). */
    val panelUrl: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("box", box).put("browserPort", browserPort).put("pipePort", pipePort).put("devicePort", devicePort)
        .put("browserToken", browserToken).put("deviceToken", deviceToken)
        .put("hubUrl", hubUrl).put("artifactsUrl", artifactsUrl)
        .put("publicArtifactsUrl", publicArtifactsUrl).put("panelUrl", panelUrl)

    /** What the web UI is told (no tokens): see otto/web/config.js. */
    fun webConfig(): JSONObject = JSONObject()
        .put("hub", hubUrl).put("artifacts", artifactsUrl)
        .put("publicArtifacts", publicArtifactsUrl.ifEmpty { artifactsUrl })
        .put("panel", panelUrl)

    companion object {
        private fun file(ctx: Context) = File(ctx.filesDir, "pairing.json")

        fun load(ctx: Context): Pairing? = try {
            val f = file(ctx)
            if (!f.exists()) null else fromJson(JSONObject(f.readText()))
        } catch (e: Exception) {
            null
        }

        fun save(ctx: Context, p: Pairing) {
            val f = file(ctx)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(p.toJson().toString())
            tmp.renameTo(f)
        }

        fun clear(ctx: Context) {
            file(ctx).delete()
        }

        private fun fromJson(o: JSONObject) = Pairing(
            box = o.getString("box"),
            browserPort = o.getInt("browserPort"),
            pipePort = o.getInt("pipePort"),
            devicePort = o.getInt("devicePort"),
            browserToken = o.getString("browserToken"),
            deviceToken = o.getString("deviceToken"),
            hubUrl = o.optString("hubUrl"),
            artifactsUrl = o.optString("artifactsUrl"),
            publicArtifactsUrl = o.optString("publicArtifactsUrl"),
            panelUrl = o.optString("panelUrl"),
        )

        /** A pairing link, or why it isn't one. */
        fun parse(uri: Uri): Result<Pairing> = runCatching {
            require(uri.scheme == "otto" && uri.host == "pair") { "not a pairing link" }
            fun need(k: String) = uri.getQueryParameter(k)?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("the link has no $k")
            fun port(k: String) = need(k).toIntOrNull()?.takeIf { it in 1..65535 }
                ?: throw IllegalArgumentException("$k isn't a port")
            fun url(k: String, required: Boolean): String {
                val v = if (required) need(k) else uri.getQueryParameter(k)?.trim().orEmpty()
                if (v.isNotEmpty()) require(Uri.parse(v).let { it.scheme == "https" || it.scheme == "http" } ) { "$k isn't a URL" }
                return v.trimEnd('/')
            }
            Pairing(
                box = need("box"),
                browserPort = port("browser_port"),
                pipePort = port("pipe_port"),
                devicePort = port("device_port"),
                browserToken = need("browser_token"),
                deviceToken = need("device_token"),
                hubUrl = url("hub", true),
                artifactsUrl = url("artifacts", true),
                publicArtifactsUrl = url("public_artifacts", false),
                panelUrl = url("panel", false),
            )
        }
    }
}

/**
 * Each link's state ("off", "connecting", "connected", or why not), for the setup screen. The browser link
 * lives in another process, so states go through small files.
 */
object LinkState {
    const val BROWSER = "browser"
    const val DEVICE = "device"

    private fun file(ctx: Context, link: String) = File(ctx.filesDir, "link-$link.state")

    /** A live link rewrites its state at least this often; an older one belongs to a process that's gone. */
    const val FRESH_MS = 150_000L

    fun write(ctx: Context, link: String, state: String) {
        try {
            val f = file(ctx, link)
            val tmp = File(f.path + ".tmp")
            tmp.writeText("$state\n${System.currentTimeMillis()}")
            tmp.renameTo(f)
        } catch (_: Exception) {
        }
    }

    fun read(ctx: Context, link: String): String = try {
        val f = file(ctx, link)
        if (!f.exists()) "off" else {
            val (state, at) = f.readText().split("\n", limit = 2).let { it[0] to (it.getOrNull(1)?.toLongOrNull() ?: 0L) }
            if (state != "off" && System.currentTimeMillis() - at > FRESH_MS) "not running" else state
        }
    } catch (_: Exception) {
        "off"
    }
}
