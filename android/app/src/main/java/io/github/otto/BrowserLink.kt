package io.github.otto

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Message
import android.os.Messenger
import android.util.Log
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Lets agents on the box drive the second screen's browser with Playwright. Runs in the browser's process
 * (BrowserService starts it), so the DevTools socket it hands out is this process's own: the browser's tabs only.
 *
 * Its lines travel the app's tunnel to the box's relay (Tunnel, in the main process, which binds BrowserService:
 * MSG_LINE both ways). The relay's side is otto/box/relay/src/browser.ts. Lines from the relay:
 *   show <url>                     slide in the browser and load url in the current tab
 *   open <id>                      a debugger connected on the box: open a pipe tagged <id> to the box (the
 *                                  tunnel's pipe port) and join it to this process's DevTools socket
 *   newtab <req> <group> <title>   open a tab in that group (a new agent group titled <title> if there's no such
 *                                  group, or <group> is "-"); answered "made <req> <targetId> <group>"
 *   wake <req> <group>             bring the group's tabs to life and keep them so; answered "woke <req> <n>" once
 *                                  all n have reported their targets (0: no such group)
 *   idle <group>                   no one's working in the group any more
 *   close <targetId>               close that tab
 *   closegroup <group>             close the group's tabs, dormant ones too
 *   focus <targetId>               show that tab
 * and to it:
 *   browser up                     (when the tunnel asks: MSG_SYNC) then a "tab" line for each live tab
 *   active                         the user is looking at this app (the relay sends new tabs to it)
 *   tab <targetId> <tabId> <group> a live tab (group "-": none). A tab's id lasts; its target is new each time it
 *                                  comes back to life
 *   gone <targetId>                a tab was closed, or went dormant
 *
 * The browser knows nothing of agent sessions: which session works in which group is the relay's to know. It
 * does need each live tab's DevTools target id. A WebView can't say which target is its own, so each tab's is
 * found by elimination when it comes to life: the page target no other tab has.
 */
object BrowserLink {
    private const val TAG = "BrowserLink"

    private lateinit var app: Context
    private var started = false
    private val lock = Any()
    private val known = LinkedHashMap<String, String>() // targetId -> "<tabId> <group>"; guarded by lock
    @Volatile private var tunnel: Messenger? = null

    // Finding target ids runs one tab at a time, so "the target no tab has" is that tab's.
    private val finder = Executors.newSingleThreadExecutor()

    @Synchronized
    fun start(service: BrowserService) {
        if (started) return
        started = true
        app = service.applicationContext
    }

    /** The tunnel (main process) bound to the browser: lines go to it from now on. */
    fun attach(m: Messenger) {
        tunnel = m
    }

    /** The tunnel is connected to the box: say the browser is up, and list its live tabs (after any being found). */
    fun sync() {
        finder.execute {
            synchronized(lock) {
                send("browser up")
                for ((target, tab) in known) send("tab $target $tab")
            }
        }
    }

    fun stop() {
        tunnel = null
    }

    fun handle(line: String) {
        val w = line.split(" ", limit = 4)
        val svc = BrowserService.instance ?: return
        when (w[0]) {
            "show" -> svc.main.post {
                svc.go(if (w.size > 1) line.substring(5).trim() else null)
                svc.show()
            }
            "open" -> thread(name = "browser-link-pipe") { pipe(w[1]) }
            "newtab" -> {
                val req = w[1]
                val group = w[2]
                val title = w.getOrElse(3) { "Agent" }
                svc.main.post {
                    val tab = svc.agentTab(group, title)
                    // After its identification (the same single thread, queued before this).
                    finder.execute { send(if (tab.targetId == null) "fail $req" else "made $req ${tab.targetId} ${tab.group}") }
                }
            }
            "wake" -> {
                val req = w[1]
                svc.main.post {
                    val n = svc.wake(w[2]).size
                    finder.execute { send("woke $req $n") }
                }
            }
            "idle" -> svc.main.post { svc.idle(w[1]) }
            "closegroup" -> svc.main.post { svc.closeGroup(w[1]) }
            "close" -> svc.main.post { svc.byTarget(w[1])?.let { svc.close(it) } }
            "focus" -> svc.main.post {
                svc.byTarget(w[1])?.let {
                    svc.select(it)
                    svc.show()
                }
            }
        }
    }

    /** Send a line to the relay, through the tunnel (if it's bound; it asks for the state again when it connects). */
    fun send(line: String) {
        val m = tunnel ?: return
        try {
            m.send(Message.obtain(null, Tunnel.MSG_LINE).also { it.data.putString("line", line) })
        } catch (e: Exception) {
            Log.d(TAG, "send: $e")
            tunnel = null
        }
    }

    /** Find the DevTools target id of a tab just come to life (in the background) and tell the relay. Call on the main thread. */
    fun identify(tab: BrowserService.Tab) {
        if (!started) return
        finder.execute {
            try {
                val line = "${tab.id} ${tab.group ?: "-"}"
                var i = 0
                while (i < 40 && tab.targetId == null && tab.web != null) {
                    val ids = pageTargets()
                    synchronized(lock) {
                        ids.firstOrNull { it !in known }?.let { id ->
                            tab.targetId = id
                            known[id] = line
                            send("tab $id $line")
                        }
                    }
                    if (tab.targetId == null) Thread.sleep(100)
                    i++
                }
                if (tab.targetId == null && tab.web != null) Log.w(TAG, "no DevTools target for tab ${tab.id}")
            } catch (e: Exception) {
                Log.w(TAG, "identify: $e")
            }
        }
    }

    /** A tab was closed, or went dormant. */
    fun gone(targetId: String) {
        synchronized(lock) { if (known.remove(targetId) != null) send("gone $targetId") }
    }

    /** Ids of the page targets on this process's DevTools socket (its /json/list). */
    private fun pageTargets(): List<String> = devtools().use { s ->
        s.outputStream.write("GET /json/list HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        val buf = ByteArrayOutputStream()
        val input = s.inputStream
        val b = ByteArray(16 * 1024)
        var bodyAt = -1
        var length = -1
        while (true) {
            val n = input.read(b)
            if (n <= 0) break
            buf.write(b, 0, n)
            if (bodyAt < 0) {
                val head = buf.toString("ISO-8859-1")
                val end = head.indexOf("\r\n\r\n")
                if (end >= 0) {
                    bodyAt = end + 4
                    Regex("(?i)content-length:\\s*(\\d+)").find(head.substring(0, end))?.let { length = it.groupValues[1].toInt() }
                }
            }
            if (bodyAt >= 0 && length >= 0 && buf.size() >= bodyAt + length) break
        }
        val bytes = buf.toByteArray()
        val list = JSONArray(String(bytes, bodyAt, bytes.size - bodyAt, Charsets.UTF_8))
        (0 until list.length()).map { list.getJSONObject(it) }.filter { it.optString("type") == "page" }.map { it.getString("id") }
    }

    /** This process's DevTools socket (WebView opens it with the first WebView; it may still be starting). */
    private fun devtools(): LocalSocket {
        val name = "webview_devtools_remote_" + android.os.Process.myPid()
        repeat(40) {
            val s = LocalSocket()
            try {
                s.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
                return s
            } catch (e: Exception) {
                s.close()
            }
            Thread.sleep(250)
        }
        throw Exception("no DevTools socket")
    }

    private fun pipe(id: String) {
        val p = Pairing.load(app) ?: return
        try {
            devtools().use { devtools ->
                Socket().use { relay ->
                    relay.connect(InetSocketAddress(p.box, p.pipePort), 10_000)
                    relay.getOutputStream().write("$id\n".toByteArray())
                    val up = thread { copy(relay.getInputStream(), devtools.outputStream) { devtools.shutdownOutput() } }
                    copy(devtools.inputStream, relay.getOutputStream()) { relay.shutdownOutput() }
                    up.join()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "pipe $id: $e")
        }
    }

    private fun copy(from: InputStream, to: OutputStream, done: () -> Unit) {
        val buf = ByteArray(64 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n <= 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: Exception) {
        } finally {
            try {
                done()
            } catch (_: Exception) {
            }
        }
    }
}
