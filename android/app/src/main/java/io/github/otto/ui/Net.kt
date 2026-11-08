package io.github.otto.ui

import android.content.Context
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** An HTTP answer that isn't 2xx. */
class HttpError(val code: Int) : IOException("HTTP $code")

/**
 * HTTP for the native sections: the box's hub, the artifact hub and the control panel, all on the tailnet. One
 * OkHttp client (shared with Coil's image loader), and small JSON files for what lists show first (Store).
 */
object Net {
    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var images: ImageLoader? = null

    fun client(ctx: Context): OkHttpClient = client ?: synchronized(this) {
        client ?: OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build().also { client = it }
    }

    /** Thumbnails, images and the box's pictures: memory and disk caches, SVG too. */
    fun images(ctx: Context): ImageLoader = images ?: synchronized(this) {
        val app = ctx.applicationContext
        images ?: ImageLoader.Builder(app)
            .okHttpClient { client(app) }
            .components { add(SvgDecoder.Factory()) }
            .memoryCache { MemoryCache.Builder(app).maxSizePercent(0.15).build() }
            .diskCache { DiskCache.Builder().directory(File(app.cacheDir, "images")).maxSizeBytes(150L shl 20).build() }
            .crossfade(120)
            .respectCacheHeaders(false) // thumbnails of a version never change; the hub says so anyway
            .build().also { images = it }
    }

    suspend fun text(ctx: Context, url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url)
        for ((k, v) in headers) b.header(k, v)
        client(ctx).newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw HttpError(r.code)
            r.body?.string() ?: ""
        }
    }

    /** A POST, PATCH or DELETE, with a JSON body or none. */
    suspend fun send(ctx: Context, method: String, url: String, json: String? = null, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val body = json?.toRequestBody("application/json".toMediaType())
                ?: if (method == "DELETE") null else ByteArray(0).toRequestBody(null)
            val b = Request.Builder().url(url).method(method, body)
            for ((k, v) in headers) b.header(k, v)
            client(ctx).newCall(b.build()).execute().use { r ->
                if (!r.isSuccessful) throw HttpError(r.code)
                r.body?.string() ?: ""
            }
        }

    /**
     * A file into dest (written beside it first, so a dest that exists is whole). progress(read, total) as it
     * comes; total is -1 when the server doesn't say.
     */
    suspend fun download(ctx: Context, url: String, dest: File, progress: (Long, Long) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        val call = client(ctx).newCall(Request.Builder().url(url).build())
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) throw HttpError(r.code)
                val body = r.body ?: throw IOException("empty")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var last = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (read - last > 32 * 1024) {
                                last = read
                                progress(read, total)
                            }
                        }
                        progress(read, total)
                    }
                }
            }
            if (!tmp.renameTo(dest)) throw IOException("can't keep $dest")
        } catch (e: Exception) {
            call.cancel()
            tmp.delete()
            throw e
        }
    }

    fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Keeps a cache folder under max bytes, dropping the files used longest ago. */
    fun trim(dir: File, max: Long) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= max) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= max) break
            total -= f.length()
            f.delete()
        }
    }
}

/** What the lists showed last, so they open with it at once and refresh behind it. */
object Store {
    private fun file(ctx: Context, key: String) = File(File(ctx.cacheDir, "lists"), Net.sha1(key) + ".json")

    fun read(ctx: Context, key: String): String? = try {
        file(ctx, key).takeIf { it.exists() }?.readText()
    } catch (_: Exception) {
        null
    }

    fun write(ctx: Context, key: String, text: String) {
        try {
            val f = file(ctx, key)
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(f)
        } catch (_: Exception) {
        }
    }
}
