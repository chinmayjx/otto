package io.github.otto

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil.ImageLoader
import io.github.otto.ui.HttpError
import io.github.otto.ui.Net
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.math.max
import kotlin.math.roundToInt

/** A clip on the box, as the Clipboard section lists it. */
data class ClipItem(
    val id: String,
    val created: Long,
    val text: String,
    /** The images' file names (1.png, …). */
    val images: List<String>,
    val thumb: String?,
    /** "screen" or "artifact", or "" for none. */
    val kind: String,
    /** The app's package (screen), or the artifact's title. */
    val from: String,
) {
    companion object {
        fun parseList(text: String): List<ClipItem> {
            val a = JSONArray(text)
            return (0 until a.length()).map { parse(a.getJSONObject(it)) }
        }

        fun parse(o: JSONObject): ClipItem {
            val imgs = o.optJSONArray("images") ?: JSONArray()
            val src = o.optJSONObject("source")
            val kind = src?.optString("kind") ?: ""
            return ClipItem(
                id = o.getString("id"),
                created = runCatching { Instant.parse(o.optString("created")).toEpochMilli() }.getOrDefault(0L),
                text = o.optString("text"),
                images = (0 until imgs.length()).map { imgs.getJSONObject(it).optString("file") },
                thumb = o.optString("thumb").takeIf { it.isNotEmpty() },
                kind = kind,
                from = when (kind) {
                    "artifact" -> src?.optString("title")?.ifEmpty { src.optString("id") } ?: ""
                    "screen" -> src?.optString("label")?.ifEmpty { src.optString("app") } ?: ""
                    else -> ""
                },
            )
        }
    }
}

/**
 * Clips: what the collector (Lens, and the viewer's docked one) sends to the box for an agent to read with
 * `otto clip <id>`. They go to the relay's box API (otto/box/relay/src/clips.ts) over HTTP on pipes the app dials
 * through its tunnel (Tunnel.ask): no port and no token of their own, as the control connection already proved
 * who's asking. OkHttp gets there through a socket factory whose sockets are such pipes, so pooling, keep-alive and
 * Coil's image loading work as on any host; the host name is never looked up.
 */
object Clips {
    /** Every request goes to the relay that holds the tunnel, whatever the host says. */
    const val BASE = "http://otto-relay"

    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var images: ImageLoader? = null

    fun client(): OkHttpClient = client ?: synchronized(this) {
        client ?: OkHttpClient.Builder()
            .socketFactory(Pipes)
            .proxy(Proxy.NO_PROXY)
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String) = listOf(InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1)))
            })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS) // several MB of screenshots
            .build().also { client = it }
    }

    /** Coil, on these pipes (the app's caches; a clip's files never change). */
    fun images(ctx: Context): ImageLoader = images ?: synchronized(this) {
        images ?: Net.images(ctx).newBuilder().okHttpClient { client() }.build().also { images = it }
    }

    fun image(id: String, file: String) = "$BASE/clips/$id/$file"

    /** What goes on the phone's clipboard for a chat with an agent. */
    fun reference(id: String) = "Look at my Otto clip $id: run `otto clip $id`"

    fun copyReference(ctx: Context, id: String) {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Otto clip", reference(id)))
    }

    /** Sends a clip to the box (blocking: off the main thread); its id. */
    fun upload(text: String, pngs: List<Bitmap>, source: JSONObject?): String {
        val clip = JSONObject().put("text", text)
        if (source != null) clip.put("source", source)
        val b = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("clip", "clip.json", clip.toString().toRequestBody("application/json".toMediaType()))
        pngs.forEachIndexed { i, bm ->
            val out = ByteArrayOutputStream()
            bm.compress(Bitmap.CompressFormat.PNG, 100, out)
            b.addFormDataPart("image", "${i + 1}.png", out.toByteArray().toRequestBody("image/png".toMediaType()))
        }
        pngs.firstOrNull()?.let { b.addFormDataPart("thumb", "thumb.jpg", thumb(it).toRequestBody("image/jpeg".toMediaType())) }
        val req = Request.Builder().url("$BASE/clips").post(b.build()).build()
        client().newCall(req).execute().use { r ->
            val body = r.body?.string() ?: ""
            if (!r.isSuccessful) throw java.io.IOException(body.trim().ifEmpty { "HTTP ${r.code}" })
            return JSONObject(body).getString("id")
        }
    }

    /** The list's picture of the first image: at most 480 px on its long side. */
    private fun thumb(b: Bitmap): ByteArray {
        val k = 480f / max(b.width, b.height)
        val t = if (k < 1f) Bitmap.createScaledBitmap(b, max(1, (b.width * k).roundToInt()), max(1, (b.height * k).roundToInt()), true) else b
        val out = ByteArrayOutputStream()
        t.compress(Bitmap.CompressFormat.JPEG, 82, out)
        return out.toByteArray()
    }

    fun list(): String = get("$BASE/clips")

    fun delete(id: String) {
        client().newCall(Request.Builder().url("$BASE/clips/$id").delete().build()).execute().use { r ->
            if (!r.isSuccessful && r.code != 404) throw HttpError(r.code)
        }
    }

    private fun get(url: String): String = client().newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) throw HttpError(r.code)
        r.body?.string() ?: ""
    }

    /** Sockets that are tunnel pipes to the relay's box API: connect() dials one, wherever it's asked to go. */
    private object Pipes : SocketFactory() {
        override fun createSocket(): Socket = Pipe()
        override fun createSocket(host: String?, port: Int): Socket = unsupported()
        override fun createSocket(host: String?, port: Int, local: InetAddress?, localPort: Int): Socket = unsupported()
        override fun createSocket(host: InetAddress?, port: Int): Socket = unsupported()
        override fun createSocket(host: InetAddress?, port: Int, local: InetAddress?, localPort: Int): Socket = unsupported()
        private fun unsupported(): Nothing = throw UnsupportedOperationException("tunnel pipes connect through connect()")
    }

    private class Pipe : Socket() {
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            val (at, line) = Tunnel.ask("box", if (timeout > 0) timeout else 10_000)
            super.connect(at, timeout)
            tcpNoDelay = true
            getOutputStream().write("$line\n".toByteArray())
        }
    }
}

/**
 * What the collector holds until it's sent: its text and images, kept in files so that closing the lens (or the
 * viewer) and coming back finds them. One per key: "lens", or "artifact-<id>" for each artifact.
 */
object Drafts {
    class Draft(val text: String, val images: List<Bitmap>)

    private fun dir(ctx: Context, key: String) = File(File(ctx.filesDir, "drafts"), key.replace(Regex("[^\\w.-]"), "_"))

    fun load(ctx: Context, key: String): Draft? = try {
        val d = dir(ctx, key)
        val o = JSONObject(File(d, "draft.json").readText())
        val files = o.optJSONArray("images") ?: JSONArray()
        Draft(o.optString("text"), (0 until files.length()).mapNotNull { BitmapFactory.decodeFile(File(d, files.getString(it)).path) })
    } catch (_: Exception) {
        null
    }

    /** Keeps it (blocking: off the main thread); nothing in it clears it. */
    fun save(ctx: Context, key: String, text: String, images: List<Bitmap>) {
        val d = dir(ctx, key)
        if (text.isBlank() && images.isEmpty()) return clear(ctx, key)
        d.deleteRecursively()
        d.mkdirs()
        val names = images.mapIndexed { i, b ->
            "${i + 1}.png".also { n -> File(d, n).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        File(d, "draft.json").writeText(JSONObject().put("text", text).put("images", JSONArray(names)).toString())
    }

    fun clear(ctx: Context, key: String) {
        dir(ctx, key).deleteRecursively()
    }
}
