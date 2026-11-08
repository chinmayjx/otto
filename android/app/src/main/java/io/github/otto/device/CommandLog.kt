package io.github.otto.device

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Every command the box sends, the last few hundred kept in memory (and in the log, under "Otto"). */
object CommandLog {
    private const val MAX = 500
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(line: String) {
        Log.i("Otto", line)
        lines.addFirst(time.format(Date()) + "  " + line)
        while (lines.size > MAX) lines.removeLast()
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")
}
