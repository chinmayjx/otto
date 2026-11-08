package io.github.otto.device

import android.os.Process
import android.util.Log
import java.util.ArrayDeque
import kotlin.concurrent.thread

/**
 * One long-running logcat, kept in memory for queries.
 *
 * Since Android 13, holding READ_LOGS isn't enough: each logcat reader needs the user's consent, which Android asks
 * for ("Allow one-time access") only while the app is in front, and silently limits to the app's own logs
 * otherwise. A reader that was allowed keeps full access for as long as it runs, so Otto starts one while its
 * screen is in front, taps Allow itself (see AgentAccessibility), and keeps that reader alive.
 */
object LogReader {
    private const val TAG = "Otto"
    private const val MAX_LINES = 40_000

    private val ring = ArrayDeque<String>()
    private var proc: java.lang.Process? = null

    /** The running reader sees other apps' logs (the user allowed it). */
    @Volatile var full = false
        private set

    /** Until when Otto watches for Android's log consent dialog to tap Allow. */
    @Volatile var expectDialogUntil = 0L

    @Synchronized
    fun running() = proc?.isAlive == true

    /** Starts a fresh reader; call it while Otto's screen is in front so Android asks for consent. */
    @Synchronized
    fun restart() {
        proc?.destroy()
        full = false
        expectDialogUntil = System.currentTimeMillis() + 15_000
        try {
            val p = ProcessBuilder("logcat", "-v", "threadtime", "-v", "year", "-v", "uid", "-b", "main,system,crash")
                .redirectErrorStream(true).start()
            proc = p
            synchronized(ring) { ring.clear() }
            thread(name = "logcat-reader", isDaemon = true) { read(p) }
            AgentAccessibility.instance?.allowLogs()
        } catch (e: Exception) {
            Log.w(TAG, "logcat: $e")
        }
    }

    @Synchronized
    fun stop() {
        proc?.destroy()
        proc = null
        full = false
    }

    private fun read(p: java.lang.Process) {
        val me = Process.myUid().toString()
        val meName = uidName(Process.myUid())
        try {
            p.inputStream.bufferedReader().forEachLine { line ->
                if (line.startsWith("--------- ")) return@forEachLine
                if (!full) {
                    val f = line.trim().split(Regex("\\s+"), 4)
                    if (f.size == 4 && f[2] != me && f[2] != meName) {
                        full = true
                        CommandLog.add("log access: all apps")
                    }
                }
                synchronized(ring) {
                    ring.addLast(line)
                    if (ring.size > MAX_LINES) ring.removeFirst()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "logcat reader: $e")
        }
        synchronized(this) {
            if (proc === p) {
                proc = null
                full = false
            }
        }
    }

    /** Query arguments, as the box sends them. */
    class Query {
        var since: String? = null // lines strictly after this "YYYY-MM-DD hh:mm:ss.mmm"
        var lines = 200 // keep the last N matching lines
        var level = 'V'
        var tags: Set<String>? = null // null = all
        var uids: Set<String>? = null // null = all
        var grep: Regex? = null
    }

    fun query(q: Query): String {
        var out = ArrayList<String>()
        synchronized(ring) {
            for (line in ring) {
                val since = q.since
                if (since != null && (line.length < 23 || line.substring(0, 23) <= since)) continue
                // date time uid pid tid level tag: message
                val f = line.trim().split(Regex("\\s+"), 7)
                if (f.size < 7) continue
                if (q.uids?.contains(f[2]) == false) continue
                if (levelRank(f[5].firstOrNull() ?: 'V') < levelRank(q.level)) continue
                val tags = q.tags
                if (tags != null) {
                    val colon = f[6].indexOf(':')
                    val tag = (if (colon < 0) f[6] else f[6].substring(0, colon)).trim()
                    if (tag !in tags) continue
                }
                if (q.grep?.containsMatchIn(line) == false) continue
                out.add(line)
            }
        }
        if (q.lines > 0 && out.size > q.lines) out = ArrayList(out.subList(out.size - q.lines, out.size))
        return out.joinToString("\n")
    }

    /** How logcat prints an app's uid: the number, or u0_aNN. */
    fun uidName(uid: Int): String {
        val app = uid % 100000
        return if (app >= 10000) "u${uid / 100000}_a${app - 10000}" else uid.toString()
    }

    private fun levelRank(c: Char) = maxOf(0, "VDIWEF".indexOf(c.uppercaseChar()))
}
