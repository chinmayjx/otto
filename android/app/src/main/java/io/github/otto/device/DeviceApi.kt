package io.github.otto.device

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * The device API: plain HTTP/1.1, served from the tunnel's "device" pipes (Tunnel), never on a port of the phone
 * (any app on the phone could reach a localhost port). One route per command (Commands), e.g.
 *   GET  /info  /current  /screenshot?scale=0.5&format=jpg&quality=80  /ui?all=true&xml=true  /packages?filter=…
 *        /appinfo?package=…  /logcat?package=…&level=W&grep=…&lines=…&since=…&tags=a,b  /wait?text=…  /events
 *   POST /tap /longpress /swipe /text /key /launch /forcestop /uninstall   (JSON args)
 *   POST /install?name=…   (the APK as the body)
 * Args come from the query, and for POST also from a JSON body. Replies: 200 with application/json (the result),
 * text/plain (a string result), or bytes of their own type (a screenshot, logs) with the result as JSON in
 * X-Otto-Result; 404 for an unknown command, 500 with the reason as text when a command fails.
 *
 * Connections are kept alive (the relay reuses pipes), one request at a time; an idle one is closed after 10
 * minutes. Bodies come with Content-Length (no chunked requests).
 */
object DeviceApi {
    private const val TAG = "DeviceApi"
    private const val IDLE_MS = 10 * 60_000
    private const val MAX_HEAD = 64 * 1024
    private const val MAX_BODY = 512L * 1024 * 1024

    private class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: ByteArray)

    @Volatile private var commands: Commands? = null

    private fun commands(ctx: Context) = commands ?: synchronized(this) { commands ?: Commands(ctx.applicationContext).also { commands = it } }

    /** Serves requests on a connection until it closes, goes idle, or a reply asks to close it. */
    fun serve(ctx: Context, s: Socket) {
        s.soTimeout = IDLE_MS
        val input = BufferedInputStream(s.getInputStream(), 64 * 1024)
        val out = s.getOutputStream()
        while (true) {
            val req = try {
                read(input) ?: return
            } catch (e: SocketTimeoutException) {
                return
            } catch (e: BadRequest) {
                write(out, 400, "text/plain; charset=utf-8", (e.message ?: "bad request").toByteArray(), close = true)
                return
            }
            val close = req.headers["connection"]?.equals("close", ignoreCase = true) == true
            val after = respond(ctx, req, out, close)
            if (after != null) {
                // The reply is out (and the connection closed): now what ends this process may begin.
                try {
                    s.close()
                } catch (_: Exception) {
                }
                after()
                return
            }
            if (close) return
        }
    }

    private fun respond(ctx: Context, req: Request, out: OutputStream, close: Boolean): (() -> Unit)? {
        val cmd = req.path.trimStart('/')
        val reply = try {
            commands(ctx).run(cmd, args(req), if (req.method == "POST" && cmd == "install") req.body else null)
        } catch (e: Commands.Unknown) {
            write(out, 404, "text/plain; charset=utf-8", e.message!!.toByteArray(), close)
            return null
        } catch (e: Throwable) {
            write(out, 500, "text/plain; charset=utf-8", (e.message ?: e.toString()).toByteArray(), close)
            return null
        }
        val r = reply.result
        when {
            reply.body != null -> write(out, 200, reply.type, reply.body, close || reply.after != null,
                "X-Otto-Result" to ascii(JSONObject.wrap(r)?.toString() ?: "null"))
            r is String -> write(out, 200, "text/plain; charset=utf-8", r.toByteArray(), close || reply.after != null)
            else -> write(out, 200, "application/json", (JSONObject.wrap(r)?.toString() ?: "null").toByteArray(), close || reply.after != null)
        }
        return reply.after
    }

    /** A command's args: the query, then a JSON body's fields. "tags" is a comma-separated list. */
    private fun args(req: Request): JSONObject {
        val a = JSONObject()
        for ((k, v) in req.query) {
            if (k == "tags") a.put(k, JSONArray(v.split(',').filter { it.isNotEmpty() })) else a.put(k, v)
        }
        val type = req.headers["content-type"].orEmpty()
        if (req.body.isNotEmpty() && type.startsWith("application/json")) {
            val o = try {
                JSONObject(String(req.body, Charsets.UTF_8))
            } catch (e: JSONException) {
                throw Exception("the body isn't a JSON object: ${e.message}")
            }
            for (k in o.keys()) a.put(k, o.get(k))
        }
        return a
    }

    private class BadRequest(m: String) : IOException(m)

    /** The next request, or null at the end of the stream. */
    private fun read(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var last = 0
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (head.size() == 0) return null
                throw BadRequest("the request ended early")
            }
            head.write(b)
            last = (last shl 8) or b
            if (last == 0x0d0a0d0a) break
            if (head.size() == 2 && last == 0x0d0a) {
                head.reset() // a stray CRLF between requests
                last = 0
            }
            if (head.size() > MAX_HEAD) throw BadRequest("the request's head is too long")
        }
        val lines = head.toString("ISO-8859-1").split("\r\n")
        val first = lines[0].split(" ")
        if (first.size < 3) throw BadRequest("not an HTTP request")
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val c = l.indexOf(':')
            if (c > 0) headers[l.substring(0, c).trim().lowercase()] = l.substring(c + 1).trim()
        }
        if (headers["transfer-encoding"] != null) throw BadRequest("send a Content-Length, not a chunked body")
        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        if (length < 0 || length > MAX_BODY) throw BadRequest("body too large")
        val body = ByteArray(length.toInt())
        var n = 0
        while (n < body.size) {
            val r = input.read(body, n, body.size - n)
            if (r < 0) throw BadRequest("the body ended early")
            n += r
        }
        val target = first[1]
        val q = target.indexOf('?')
        val path = URLDecoder.decode(if (q < 0) target else target.substring(0, q), "UTF-8")
        val query = LinkedHashMap<String, String>()
        if (q >= 0) {
            for (pair in target.substring(q + 1).split('&')) {
                if (pair.isEmpty()) continue
                val e = pair.indexOf('=')
                val k = URLDecoder.decode(if (e < 0) pair else pair.substring(0, e), "UTF-8")
                query[k] = if (e < 0) "" else URLDecoder.decode(pair.substring(e + 1), "UTF-8")
            }
        }
        return Request(first[0].uppercase(), path, query, headers, body)
    }

    private fun write(out: OutputStream, status: Int, type: String, body: ByteArray, close: Boolean, vararg extra: Pair<String, String>) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Internal Server Error"
        }
        val h = StringBuilder("HTTP/1.1 $status $reason\r\n")
            .append("Content-Type: ").append(type).append("\r\n")
            .append("Content-Length: ").append(body.size).append("\r\n")
            .append("Connection: ").append(if (close) "close" else "keep-alive").append("\r\n")
        for ((k, v) in extra) h.append(k).append(": ").append(v).append("\r\n")
        h.append("\r\n")
        try {
            out.write(h.toString().toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.flush()
        } catch (e: IOException) {
            Log.d(TAG, "reply: $e")
            throw e
        }
    }

    /** JSON with everything outside ASCII escaped, to travel in a header. */
    private fun ascii(json: String): String {
        val b = StringBuilder(json.length)
        for (c in json) {
            if (c.code in 0x20..0x7e) b.append(c) else b.append(String.format("\\u%04x", c.code))
        }
        return b.toString()
    }
}
