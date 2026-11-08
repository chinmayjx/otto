package io.github.otto

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short log of what brought Otto up and down (boot, updates, the services, the tunnel), kept in a file so it
 * outlives the process: after an update, the box can read why the links came back, or didn't (GET /events).
 */
object Events {
    private const val MAX = 300
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun file(ctx: Context) = File(ctx.filesDir, "events.log")

    @Synchronized
    fun add(ctx: Context, line: String) {
        val entry = "${time.format(Date())} [${android.os.Process.myPid()}] $line"
        Log.i("OttoEvents", entry)
        try {
            val f = file(ctx)
            val lines = if (f.exists()) f.readLines() else emptyList()
            f.writeText((lines.takeLast(MAX - 1) + entry).joinToString("\n", postfix = "\n"))
        } catch (_: Exception) {
        }
    }

    fun text(ctx: Context): String = try {
        file(ctx).readText()
    } catch (_: Exception) {
        ""
    }
}
