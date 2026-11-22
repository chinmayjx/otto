package io.github.otto.device

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs a command in Termux (`otto phone termux`), through Termux's own way in for other apps: its RUN_COMMAND
 * intent to RunCommandService. The command runs with bash in Termux's environment (its packages, its home, its
 * git and gh sign-ins), in the background with no terminal; Termux hands stdout, stderr and the exit code back
 * through a PendingIntent to a receiver here.
 *
 * Needs, once: Otto granted "Run commands in Termux environment" (com.termux.permission.RUN_COMMAND, in Otto's app
 * permissions), and `allow-external-apps=true` in Termux's ~/.termux/termux.properties.
 */
object Termux {
    private const val PACKAGE = "com.termux"
    private const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val HOME = "/data/data/com.termux/files/home"
    private val next = AtomicInteger(1)

    /** Runs `command` (bash -c) in `cwd` (Termux's home if null); waits up to timeoutMs for it to end. */
    fun run(ctx: Context, command: String, cwd: String?, timeoutMs: Long): JSONObject {
        try {
            ctx.packageManager.getPackageInfo(PACKAGE, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            throw Exception("Termux isn't installed")
        }
        if (ctx.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED)
            throw Exception("Otto may not run commands in Termux yet: on the phone, Settings > Apps > ${ctx.applicationInfo.loadLabel(ctx.packageManager)} > Permissions > Additional permissions > Run commands in Termux environment > Allow")

        val id = next.getAndIncrement()
        val action = "${ctx.packageName}.TERMUX_RESULT.$id"
        val done = CountDownLatch(1)
        var result: JSONObject? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val b = i.getBundleExtra("result")
                result = JSONObject()
                    .put("stdout", b?.getString("stdout") ?: "")
                    .put("stderr", b?.getString("stderr") ?: "")
                    .put("exitCode", if (b != null && b.containsKey("exitCode")) b.getInt("exitCode") else JSONObject.NULL)
                    .put("err", b?.getInt("err") ?: 0)
                    .put("errmsg", b?.getString("errmsg") ?: "")
                    .put("stdoutOriginalLength", b?.getString("stdout_original_length") ?: "")
                done.countDown()
            }
        }
        // Termux sends the PendingIntent as Otto (its creator), so the receiver needn't be open to other apps.
        ctx.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        try {
            val reply = PendingIntent.getBroadcast(ctx, id, Intent(action).setPackage(ctx.packageName),
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE) // mutable: Termux fills in the result
            val intent = Intent("com.termux.RUN_COMMAND")
                .setClassName(PACKAGE, "com.termux.app.RunCommandService")
                .putExtra("com.termux.RUN_COMMAND_PATH", BASH)
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
                .putExtra("com.termux.RUN_COMMAND_WORKDIR", cwd ?: HOME)
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Otto")
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", reply)
            try {
                ctx.startForegroundService(intent)
            } catch (e: SecurityException) {
                throw Exception("Termux refused: ${e.message}")
            }
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS))
                throw Exception("no answer from Termux in ${timeoutMs / 1000} s. If this is the first try: is allow-external-apps=true in ~/.termux/termux.properties (then `termux-reload-settings`)? Else the command may still be running")
            val r = result!!
            if (r.optInt("err") != 0 && r.optInt("err") != -1)
                throw Exception("Termux couldn't run it: ${r.optString("errmsg").ifEmpty { "error ${r.optInt("err")}" }}")
            return r
        } finally {
            try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }
}
