package io.github.otto

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File

/**
 * Which box this phone is paired with, and how to reach it: what `otto pair` on the box puts in the pairing link,
 * otto://pair?box=…&port=…&pipe_port=…&token=…&hub=…&artifacts=… (optionally also public_artifacts=… and panel=…).
 * Links from before the tunnel name the port browser_port and the token browser_token (and add a device link's
 * device_port and device_token, which are kept only for the old device link while the box may still have it).
 *
 * Kept in a small JSON file rather than SharedPreferences: the browser's process (":browser") reads it too, and
 * reads it afresh at every connection, so a new pairing reaches both processes without restarting them.
 */
data class Pairing(
    /** The box's tailnet name (or address): where the tunnel connects. */
    val box: String,
    /** The tunnel's control connection, and its pipes. */
    val port: Int,
    val pipePort: Int,
    /** What the app presents on the tunnel. */
    val token: String,
    /** The box's hub (files, desktop, terminal), e.g. https://<box>:8446. */
    val hubUrl: String,
    /** The artifact hub, over the tailnet. */
    val artifactsUrl: String,
    /** The artifact hub's public address, for links to share (optional). */
    val publicArtifactsUrl: String = "",
    /** The box's control panel: status, start, stop (optional). */
    val panelUrl: String = "",
    /** The old device link (a box from before the tunnel); 0 and "" when there's none. */
    val devicePort: Int = 0,
    val deviceToken: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("box", box).put("port", port).put("pipePort", pipePort).put("token", token)
        .put("hubUrl", hubUrl).put("artifactsUrl", artifactsUrl)
        .put("publicArtifactsUrl", publicArtifactsUrl).put("panelUrl", panelUrl)
        .put("devicePort", devicePort).put("deviceToken", deviceToken)

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

        // A pairing saved before the tunnel has browserPort and browserToken: the tunnel's, now.
        private fun fromJson(o: JSONObject) = Pairing(
            box = o.getString("box"),
            port = o.optInt("port", o.optInt("browserPort")),
            pipePort = o.getInt("pipePort"),
            token = o.optString("token").ifEmpty { o.getString("browserToken") },
            hubUrl = o.optString("hubUrl"),
            artifactsUrl = o.optString("artifactsUrl"),
            publicArtifactsUrl = o.optString("publicArtifactsUrl"),
            panelUrl = o.optString("panelUrl"),
            devicePort = o.optInt("devicePort"),
            deviceToken = o.optString("deviceToken"),
        )

        /** A pairing link, or why it isn't one. */
        fun parse(uri: Uri): Result<Pairing> = runCatching {
            require(uri.scheme == "otto" && uri.host == "pair") { "not a pairing link" }
            fun opt(k: String) = uri.getQueryParameter(k)?.trim()?.takeIf { it.isNotEmpty() }
            fun need(vararg k: String) = k.firstNotNullOfOrNull { opt(it) }
                ?: throw IllegalArgumentException("the link has no ${k[0]}")
            fun port(vararg k: String) = need(*k).toIntOrNull()?.takeIf { it in 1..65535 }
                ?: throw IllegalArgumentException("${k[0]} isn't a port")
            fun url(k: String, required: Boolean): String {
                val v = if (required) need(k) else uri.getQueryParameter(k)?.trim().orEmpty()
                if (v.isNotEmpty()) require(Uri.parse(v).let { it.scheme == "https" || it.scheme == "http" } ) { "$k isn't a URL" }
                return v.trimEnd('/')
            }
            Pairing(
                box = need("box"),
                port = port("port", "browser_port"),
                pipePort = port("pipe_port"),
                token = need("token", "browser_token"),
                hubUrl = url("hub", true),
                artifactsUrl = url("artifacts", true),
                publicArtifactsUrl = url("public_artifacts", false),
                panelUrl = url("panel", false),
                devicePort = opt("device_port")?.toIntOrNull() ?: 0,
                deviceToken = opt("device_token").orEmpty(),
            )
        }
    }
}

/**
 * Each link's state ("off", "connecting", "connected", or why not), for the setup screen: the tunnel's (DEVICE:
 * the box reaches the phone) and the browser's (BROWSER: the browser's process is up and on the tunnel). States go
 * through small files, which any of the app's processes can read.
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
