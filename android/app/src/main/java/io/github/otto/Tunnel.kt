package io.github.otto

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Log
import io.github.otto.device.DeviceApi
import org.json.JSONObject
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The phone's reverse tunnel to the box's relay (otto/box/relay/src/tunnel.ts): one control connection, dialled
 * from here (nothing listens on the phone), and a new outbound pipe for each stream the relay asks for:
 *   device    the device HTTP API (DeviceApi), served in this process straight from the pipe
 *   devtools  the browser's DevTools socket: the browser's process (":browser", BrowserLink) opens that pipe itself
 * and the other way, a pipe the app dials for itself (dial()): to the relay's box API (clips), HTTP with the app as
 * the client (Clips).
 *
 * Control lines (TCP over Tailscale, which encrypts; the first line carries the token from pairing):
 *   to the relay:   hello <package> <token> 2, info <json>, browser up|down, ping, dial <req> box, and the
 *                   browser's own lines
 *   from it:        open <pipe> <service>, dialed <req> <pipe>, ping, and lines for the browser (show, newtab, ...)
 * A pipe is a TCP connection to the pipe port that starts with "<pipe>\n".
 *
 * The tunnel lives in the main process, where the accessibility service and installer are. The browser's
 * process is bound with a Messenger: its lines go out through the tunnel, and the relay's browser lines come in to
 * it (BrowserService MSG_LINE). When it comes up it says "browser up" and lists its tabs; when it dies the tunnel
 * says "browser down".
 *
 * It runs while the handle is on (OverlayService), and also as soon as the accessibility service is bound if the
 * handle should be on: after an update or a restart, that keeps the box's reach to the phone even before (or
 * without) the foreground service. It reconnects with a growing pause (1 s up to 30 s), at once when the network
 * changes or the pairing does; the relay pings every 30 s, so 90 s of silence means the path is dead.
 */
object Tunnel {
    private const val TAG = "Tunnel"
    private const val MIN_PAUSE = 1_000L
    private const val MAX_PAUSE = 30_000L
    private const val PING_MS = 60_000L
    const val VERSION = 2

    private lateinit var app: Context
    @Volatile private var started = false
    @Volatile private var enabled = false
    @Volatile private var socket: Socket? = null
    private val lock = Any()
    @Volatile private var control: OutputStream? = null // written under lock
    private val wake = Object()
    private val writer = Executors.newSingleThreadExecutor()
    private val pipes = Executors.newCachedThreadPool()
    private var network: Network? = null
    private val dials = ConcurrentHashMap<String, CompletableFuture<String>>()
    private val nextDial = AtomicInteger(1)

    // The browser's process.
    private lateinit var handler: Handler
    @Volatile private var browser: Messenger? = null
    private var bound = false

    @Synchronized
    fun start(ctx: Context, why: String) {
        if (!started) {
            started = true
            app = ctx.applicationContext
            handler = Handler(HandlerThread("tunnel").apply { start() }.looper, ::fromBrowser)
            thread(name = "tunnel", isDaemon = true) { controlLoop() }
            thread(name = "tunnel-ping", isDaemon = true) { pingLoop() }
            app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) {
                    val changed = network != null && network != n
                    network = n
                    if (changed) relink() else poke()
                }
            })
        }
        if (!enabled) Events.add(app, "tunnel on ($why)")
        enabled = true
        bindBrowser()
        poke()
    }

    /** Disconnect, and stay so until started again (the handle was turned off). */
    @Synchronized
    fun stop() {
        if (!started || !enabled) return
        enabled = false
        Events.add(app, "tunnel off")
        unbindBrowser()
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

    val connected get() = socket?.isConnected == true && control != null

    private fun poke() = synchronized(wake) { wake.notifyAll() }

    private fun state(s: String) {
        LinkState.write(app, LinkState.DEVICE, s)
        LinkState.write(app, LinkState.BROWSER, when {
            s != "connected" -> s
            browser != null -> "connected"
            else -> "browser not running"
        })
    }

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
                    s.connect(InetSocketAddress(p.box, p.port), 10_000)
                    s.keepAlive = true
                    s.tcpNoDelay = true
                    val info = JSONObject()
                        .put("device", Build.MANUFACTURER + " " + Build.MODEL)
                        .put("android", Build.VERSION.RELEASE)
                        .put("app", BuildConfig.VERSION_NAME)
                        .put("versionCode", BuildConfig.VERSION_CODE)
                    synchronized(lock) {
                        control = s.getOutputStream().also {
                            it.write("hello ${app.packageName} ${p.token} $VERSION\ninfo $info\n".toByteArray())
                            it.flush()
                        }
                    }
                    pause = MIN_PAUSE
                    state("connected")
                    Events.add(app, "tunnel connected to ${p.box}")
                    toBrowser(MSG_SYNC) // its "browser up" and tabs, on this connection
                    s.getInputStream().bufferedReader().forEachLine {
                        // A relay that pings (every 30 s) is silent for 90 s only on a dead path.
                        if (it == "ping" && s.soTimeout == 0) s.soTimeout = 90_000
                        handle(it, p)
                    }
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
            if (connected) {
                send("ping")
                state("connected")
            }
        }
    }

    private fun handle(line: String, p: Pairing) {
        val w = line.split(" ", limit = 3)
        when (w[0]) {
            "ping" -> {}
            "dialed" -> if (w.size == 3) dials.remove(w[1])?.complete(w[2])
            "open" -> {
                if (w.size < 2) return
                when (w.getOrNull(2) ?: "devtools") {
                    "device" -> pipes.execute { devicePipe(w[1], p) }
                    else -> toBrowser(MSG_LINE, "open ${w[1]}")
                }
            }
            else -> toBrowser(MSG_LINE, line)
        }
    }

    /** A pipe for the device API: dial it, then serve HTTP on it until either end closes it. */
    private fun devicePipe(id: String, p: Pairing) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(p.box, p.pipePort), 10_000)
                s.tcpNoDelay = true
                s.keepAlive = true
                s.getOutputStream().write("$id\n".toByteArray())
                DeviceApi.serve(app, s)
            }
        } catch (e: Exception) {
            Log.d(TAG, "device pipe: $e")
        }
    }

    /**
     * A pipe of the app's own to one of the relay's services ("box": the box API), connected and past its first line:
     * from here on it's that service's stream. Blocks; throws when there's no tunnel, or the relay doesn't know it.
     */
    fun dial(service: String = "box", timeoutMs: Int = 10_000): Socket {
        val (at, pipe) = ask(service, timeoutMs)
        val s = Socket()
        try {
            s.connect(at, timeoutMs)
            s.tcpNoDelay = true
            s.getOutputStream().write("$pipe\n".toByteArray())
        } catch (e: Exception) {
            s.close()
            throw e
        }
        return s
    }

    /** Asks the relay for a pipe to its service: where to connect, and the line to start with. */
    fun ask(service: String, timeoutMs: Int = 10_000): Pair<InetSocketAddress, String> {
        if (!started) throw IOException("Otto's handle is off")
        val p = Pairing.load(app) ?: throw IOException("Otto isn't paired with a box")
        if (!connected) throw IOException("Not connected to the box")
        val req = "d" + nextDial.getAndIncrement()
        val f = CompletableFuture<String>()
        dials[req] = f
        send("dial $req $service")
        val pipe = try {
            f.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw IOException("The box's relay didn't answer (is it up to date?)")
        } finally {
            dials.remove(req)
        }
        if (pipe == "-") throw IOException("The box's relay has no $service service (update it)")
        return InetSocketAddress(p.box, p.pipePort) to pipe
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

    // --- The browser's process.

    const val MSG_TUNNEL = 100 // to the browser: replyTo is the tunnel
    const val MSG_LINE = 101 // either way: {line}
    const val MSG_SYNC = 102 // to the browser: say "browser up" and list the tabs

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            val m = Messenger(service)
            browser = m
            try {
                m.send(Message.obtain(null, MSG_TUNNEL).also { it.replyTo = Messenger(handler) })
            } catch (e: Exception) {
                Log.d(TAG, "browser: $e")
            }
            if (connected) toBrowser(MSG_SYNC)
            if (connected) state("connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            browser = null
            send("browser down")
            if (connected) state("connected")
        }
    }

    /** Bind to the browser's service, without starting it: it comes up with the handle (OverlayService). */
    private fun bindBrowser() {
        if (bound) return
        bound = try {
            app.bindService(Intent(app, BrowserService::class.java), connection, 0)
        } catch (e: Exception) {
            Log.w(TAG, "bind: $e")
            false
        }
    }

    private fun unbindBrowser() {
        if (!bound) return
        bound = false
        try {
            app.unbindService(connection)
        } catch (_: Exception) {
        }
        if (browser != null) send("browser down")
        browser = null
    }

    private fun toBrowser(what: Int, line: String? = null) {
        val m = browser ?: return
        try {
            m.send(Message.obtain(null, what).also { if (line != null) it.data.putString("line", line) })
        } catch (e: Exception) {
            Log.d(TAG, "to browser: $e")
        }
    }

    private fun fromBrowser(m: Message): Boolean {
        if (m.what == MSG_LINE) m.data.getString("line")?.let { send(it) }
        return true
    }
}
