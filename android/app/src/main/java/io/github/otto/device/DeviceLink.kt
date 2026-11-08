package io.github.otto.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.Log
import io.github.otto.BuildConfig
import io.github.otto.MainActivity
import io.github.otto.Pairing
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * TRANSITIONAL, to be removed with the next build: the old device link, for a relay from before the tunnel (it
 * connects only while the pairing still has a device port). The device link: dials the box's relay (otto/box/relay/src/device.ts) over Tailscale (plain TCP; Tailscale
 * encrypts), says hello with the device token from pairing, then runs the commands the box sends (Commands).
 * OverlayService holds it while the handle is on, so it lives in that foreground service. It reconnects after
 * 5 s, or at once when the network comes back or the pairing changes; the relay pings every 30 s, so 90 s of
 * silence means the path is dead.
 */
class DeviceLink(private val ctx: Context) {
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    private var loop: Thread? = null
    private val wake = Object()
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (running) return
        running = true
        // Started while Otto's screen is in front (the handle switch): the moment Android asks for log consent.
        // Never ask from the background: Android declines and remembers the decline for a minute.
        if (MainActivity.resumed && ctx.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED) LogReader.restart()
        loop = thread(name = "device-link") { connectLoop() }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) = poke()
        }
        netCallback = cb
        ctx.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb)
    }

    fun stop() {
        running = false
        netCallback?.let { ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        closeSocket()
        loop?.interrupt()
        pool.shutdownNow()
        LogReader.stop()
    }

    /** Reconnect now, with the pairing as it is now. */
    fun relink() {
        closeSocket()
        poke()
    }

    private fun poke() = synchronized(wake) { wake.notifyAll() }

    /** One command, answered in the link's frames (the same Commands as the device API's). */
    private fun run(commands: Commands, frames: Frames, req: Frames.Frame) {
        val id = req.head.optString("id")
        try {
            val r = commands.run(req.head.optString("cmd"), req.head.optJSONObject("args") ?: JSONObject(), req.body)
            frames.write(JSONObject().put("id", id).put("ok", true).put("result", r.result), r.body)
            r.after?.invoke()
        } catch (e: Throwable) {
            try {
                frames.write(JSONObject().put("id", id).put("ok", false).put("error", e.message ?: e.toString()))
            } catch (_: Exception) {
            }
        }
    }

    private fun connectLoop() {
        while (running) {
            val p = Pairing.load(ctx)
            if (p == null || p.devicePort == 0) {
                // The box has the tunnel only: this link has nothing to do (DEVICE's state is the tunnel's now).
            } else {
                try {
                    Socket().use { s ->
                        socket = s
                        s.connect(InetSocketAddress(p.box, p.devicePort), 10_000)
                        s.keepAlive = true
                        s.tcpNoDelay = true
                        s.soTimeout = 90_000 // the relay pings every 30 s; silence means the path is dead
                        val frames = Frames(s.getInputStream(), s.getOutputStream())
                        frames.write(JSONObject()
                            .put("hello", "device-agent")
                            .put("token", p.deviceToken)
                            .put("device", Build.MANUFACTURER + " " + Build.MODEL)
                            .put("android", Build.VERSION.RELEASE)
                            .put("version", BuildConfig.VERSION_NAME))
                        var f = frames.read()
                        if (f == null || !f.head.optBoolean("welcome")) {
                            throw Exception(if (f == null) "closed by the box" else f.head.optString("error", "refused"))
                        }
                        CommandLog.add("device link connected to ${p.box}")
                        val commands = Commands(ctx)
                        while (running) {
                            f = frames.read() ?: break
                            val h = f.head
                            if (h.optBoolean("ping")) {
                                frames.write(JSONObject().put("pong", true))
                            } else if (h.has("cmd")) {
                                val req = f
                                pool.execute { run(commands, frames, req) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "link: $e")
                } finally {
                    socket = null
                }
            }
            if (!running) break
            synchronized(wake) {
                try {
                    wake.wait(if (p == null || p.devicePort == 0) 30_000L else 5_000L)
                } catch (e: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "DeviceLink"
    }
}
