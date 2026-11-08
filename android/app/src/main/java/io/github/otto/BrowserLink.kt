package io.github.otto

import android.content.Context
import android.net.ConnectivityManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Network
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
 * (BrowserService starts it), so the DevTools socket it relays is this process's own: the browser's tabs only.
 *
 * The app keeps a control connection to the box's relay (otto/box/relay/src/browser.ts; plain TCP over
 * Tailscale, which already encrypts; the first line carries the browser token from pairing). Lines from the relay:
 *   show <url>                     slide in the browser and load url in the current tab
 *   open <id>                      a debugger connected on the box: open a pipe connection tagged <id> and join it
 *                                  to this process's DevTools socket
 *   newtab <req> <group> <title>   open a tab in that group (a new agent group titled <title> if there's no such
 *                                  group, or <group> is "-"); answered "made <req> <targetId> <group>"
 *   wake <req> <group>             bring the group's tabs to life and keep them so; answered "woke <req> <n>" once
 *                                  all n have reported their targets (0: no such group)
 *   idle <group>                   no one's working in the group any more
 *   close <targetId>               close that tab
 *   closegroup <group>             close the group's tabs, dormant ones too
 *   focus <targetId>               show that tab
 * and to it:
 *   hello <package> <token>, then a "tab" line for each live tab
 *   active                         the user is looking at this app (the relay sends new tabs to it)
 *   tab <targetId> <tabId> <group> a live tab (group "-": none). A tab's id lasts; its target is new each time it
 *                                  comes back to life
 *   gone <targetId>                a tab was closed, or went dormant
 *   ping                           now and then, to notice a dead connection (the relay ignores it)
 *
 * The connection is kept up for as long as this process runs (BrowserService is a foreground service while the
 * handle is on): it reconnects with a growing pause (1 s up to 30 s) and at once when the network comes back or
 * the pairing changes.
 *
 * The browser knows nothing of agent sessions: which session works in which group is the relay's to know. It
 * does need each live tab's DevTools target id. A WebView can't say which target is its own, so each tab's is
 * found by elimination when it comes to life: the page target no other tab has.
 */
object BrowserLink {
    private const val TAG = "BrowserLink"
    private const val MIN_PAUSE = 1_000L
    private const val MAX_PAUSE = 30_000L
    private const val PING_MS = 60_000L

    private lateinit var app: Context
    private var started = false
    private val lock = Any()
    private var control: OutputStream? = null // guarded by lock
    private val known = LinkedHashMap<String, String>() // targetId -> "<tabId> <group>"; guarded by lock
    @Volatile private var socket: Socket? = null
    private val wake = Object()
    private var network: Network? = null

    // Finding target ids runs one tab at a time, so "the target no tab has" is that tab's.
    private val finder = Executors.newSingleThreadExecutor()
    private val writer = Executors.newSingleThreadExecutor()

    @Volatile private var enabled = false

    @Synchronized
    fun start(service: BrowserService) {
        enabled = true
        if (started) return poke()
        started = true
        app = service.applicationContext
        thread(name = "browser-link", isDaemon = true) { controlLoop() }
        thread(name = "browser-link-ping", isDaemon = true) { pingLoop() }
        app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                val changed = network != null && network != n
                network = n
                if (changed) relink() else poke()
            }
        })
    }

    /** Disconnect, and stay so until started again (the browser's service is gone). */
    fun stop() {
        enabled = false
        relink()
    }

    /** Reconnect now (a new pairing, or a new network). */
    fun relink() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        poke()
    }

    private fun poke() = synchronized(wake) { wake.notifyAll() }

    private fun state(s: String) = LinkState.write(app, LinkState.BROWSER, s)

    private fun controlLoop() {
        var pause = MIN_PAUSE
        while (true) {
            if (!enabled) {
                state("off")
                synchronized(wake) { wake.wait(MAX_PAUSE) }
                continue
            }
            val p = Pairing.load(app)
            if (p == null) {
                state("not paired")
                synchronized(wake) { wake.wait(MAX_PAUSE) }
                continue
            }
            state("connecting")
            try {
                Socket().use { s ->
                    socket = s
                    s.connect(InetSocketAddress(p.box, p.browserPort), 10_000)
                    s.keepAlive = true
                    synchronized(lock) {
                        val hello = StringBuilder("hello ${app.packageName} ${p.browserToken}\n")
                        for ((target, tab) in known) hello.append("tab ").append(target).append(' ').append(tab).append('\n')
                        control = s.getOutputStream().also { it.write(hello.toString().toByteArray()) }
                    }
                    pause = MIN_PAUSE
                    state("connected")
                    s.getInputStream().bufferedReader().forEachLine { handle(it) }
                }
            } catch (e: Exception) {
                Log.d(TAG, "control: $e")
            } finally {
                socket = null
                synchronized(lock) { control = null }
            }
            if (!enabled) continue
            state("can't reach the box; retrying")
            synchronized(wake) { wake.wait(pause) }
            pause = minOf(pause * 2, MAX_PAUSE)
        }
    }

    private fun pingLoop() {
        while (true) {
            Thread.sleep(PING_MS)
            if (socket != null) {
                send("ping")
                state("connected")
            }
        }
    }

    private fun handle(line: String) {
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

    /** Send a line to the relay, if connected (from any thread: the writing happens on its own). */
    fun send(line: String) {
        writer.execute {
            synchronized(lock) {
                val out = control ?: return@execute
                try {
                    out.write("$line\n".toByteArray())
                    out.flush()
                } catch (e: Exception) {
                    Log.d(TAG, "send: $e")
                }
            }
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
                    relay.connect(InetSocketAddress(p.box, p.pipePort), 5000)
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
