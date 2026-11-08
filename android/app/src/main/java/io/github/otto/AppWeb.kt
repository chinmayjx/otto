package io.github.otto

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * A WebView running the app's own web UI (otto/web, in the APK's assets at ide/: Artifacts, Files, Desktop,
 * Terminal, Devbox, the viewer). It is plain web code served from the APK at
 * https://appassets.androidplatform.net/assets/ide/ and calling the services' APIs itself.
 *
 * Where those services are comes from pairing: the page's config.js (see otto/web/config.js) is answered here
 * with the paired hub's and artifact hub's addresses, instead of the file in the assets.
 *
 * The page asks for what a page can't do through CCMNative, a message listener only that origin gets (not the
 * HTML artifacts and files it frames): {op: close | open | download | share | openInBrowser | terminalBar | filesBar}.
 */
object AppWeb {
    private const val TAG = "AppWeb"
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val URL = "$ORIGIN/assets/ide/index.html"
    private const val CONFIG = "/assets/ide/config.js"

    interface Ops {
        /** The viewer's back button. */
        fun close() {}

        /** The viewer at that route (the second screen shows it over everything). */
        fun open(hash: String): Boolean = Screen.current?.showViewer(hash) == true

        fun terminalBar(state: JSONObject?) {}

        /** Files: which top-bar buttons apply to the folder on screen. */
        fun filesBar(up: Boolean, home: Boolean, hidden: Boolean) {}
    }

    /** The web UI's config.js: where the services are, from pairing (empty until paired). */
    private fun config(ctx: Context): WebResourceResponse {
        val conf = Pairing.load(ctx)?.webConfig() ?: JSONObject()
        val js = "window.OTTO_CONFIG = Object.assign(window.OTTO_CONFIG || {}, $conf);\n"
        return WebResourceResponse("text/javascript", "utf-8", ByteArrayInputStream(js.toByteArray()))
            .apply { responseHeaders = mapOf("Cache-Control" to "no-store") }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun create(ctx: Context, ops: Ops): WebView {
        val web = WebView(ctx)
        web.setBackgroundColor(BrowserService.BG)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(false) // the viewer zooms its pages itself
        }
        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx)).build()
        // HTML artifacts' alert / confirm dialogs, and the devices input artifacts ask for: a MIDI keyboard and the
        // microphone. Everything this WebView shows is the owner's own (the app's UI, artifacts, the box's files),
        // so those are granted; the microphone needs the app's own Android permission first, asked for on first use.
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(req: PermissionRequest) {
                val grant = ArrayList<String>()
                for (r in req.resources) {
                    if (r == PermissionRequest.RESOURCE_MIDI_SYSEX) grant.add(r)
                    else if (r == PermissionRequest.RESOURCE_AUDIO_CAPTURE) {
                        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) grant.add(r)
                        else MainActivity.askForMicrophone(ctx) // a service can't ask: the app's screen does
                    }
                }
                if (grant.isEmpty()) req.deny() else req.grant(grant.toTypedArray())
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url
                if (u.host == "appassets.androidplatform.net" && u.path == CONFIG) return config(ctx)
                return assets.shouldInterceptRequest(u)
            }

            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                if (!req.isForMainFrame) return false // frames (HTML artifacts) navigate inside themselves
                val u = req.url
                if (u.host == "appassets.androidplatform.net") return false
                // A link out of the app's UI: into the second screen's own browser.
                Screen.current?.open(u.toString())
                return true
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "CCMNative", setOf(ORIGIN)) { _, message, _, isMainFrame, _ ->
                val data = message.data
                if (!isMainFrame || data == null) return@addWebMessageListener
                try {
                    handle(ctx, ops, JSONObject(data))
                } catch (e: Exception) {
                    Log.w(TAG, "bad message: $e")
                }
            }
        }
        return web
    }

    private fun handle(ctx: Context, ops: Ops, m: JSONObject) {
        when (m.optString("op")) {
            "close" -> ops.close()
            "open" -> ops.open(m.optString("hash")) // the viewer, at "artifact/<id>[/v<n>]", "view/<version id>" or "file?path=<file>"
            "download" -> download(ctx, m.optString("url"), m.optString("name"))
            "share" -> {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, m.optString("text")).putExtra(Intent.EXTRA_SUBJECT, m.optString("title"))
                ctx.startActivity(Intent.createChooser(send, m.optString("title")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Screen.current?.hide() // the share sheet opens under the second screen
            }
            "terminalBar" -> ops.terminalBar(m.optJSONObject("state"))
            "filesBar" -> ops.filesBar(m.optBoolean("up"), m.optBoolean("home"), m.optBoolean("hidden"))
            "openInBrowser" -> {
                ops.close()
                Screen.current?.open(m.optString("url"))
            }
        }
    }

    /**
     * A file from the hub or the artifact hub into Downloads. DownloadManager rejects the Downloads path under
     * scoped storage (and says nothing), so the app fetches the file and writes it through MediaStore, which needs
     * no permission and renames duplicates itself.
     */
    fun download(ctx: Context, url: String, name: String?) {
        val u = Uri.parse(url)
        if (!Links.isArtifactHost(ctx, u) && !Links.isHub(ctx, u)) return
        val file = name?.takeIf { it.isNotEmpty() } ?: u.lastPathSegment ?: "download"
        thread {
            val msg = try {
                saveToDownloads(ctx, url, file)
                "Saved to Downloads: $file"
            } catch (e: Exception) {
                Log.w(TAG, "download failed: $e")
                "Download failed: $file"
            }
            Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
        }
    }

    private fun saveToDownloads(ctx: Context, url: String, name: String) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
        val cr = ctx.contentResolver
        val v = ContentValues()
        v.put(MediaStore.Downloads.DISPLAY_NAME, name)
        c.contentType?.let { v.put(MediaStore.Downloads.MIME_TYPE, it.split(";")[0].trim()) }
        v.put(MediaStore.Downloads.IS_PENDING, 1)
        val item = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw IOException("MediaStore refused $name")
        try {
            c.inputStream.use { input -> cr.openOutputStream(item)!!.use { out -> input.copyTo(out, 64 * 1024) } }
        } catch (e: Exception) {
            cr.delete(item, null, null) // no half-written file left in Downloads
            throw e
        }
        v.clear()
        v.put(MediaStore.Downloads.IS_PENDING, 0)
        cr.update(item, v, null, null)
    }
}
