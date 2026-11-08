package io.github.otto.device

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import java.io.File
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/**
 * Installs and uninstalls through PackageInstaller sessions. Updates of apps Otto installed go through without a
 * prompt (Android 12+); when Android still asks, Otto opens the prompt and taps its Install / Update / Uninstall
 * button itself.
 */
object Installer {
    private const val ACTION = "io.github.otto.INSTALL_STATUS"
    private val pending = ConcurrentHashMap<Int, CompletableFuture<String>>()

    /** The package name inside an APK file, or null if it isn't one. */
    fun packageOf(c: Context, apk: File): String? = c.packageManager.getPackageArchiveInfo(apk.path, 0)?.packageName

    /** Throws if the APK can't replace the installed app: an older version, or signed with another key. */
    fun checkUpdate(c: Context, apk: File, pkg: String) {
        val pm = c.packageManager
        val installed = try {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (e: PackageManager.NameNotFoundException) {
            return // a new install
        }
        val incoming = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return
        if (incoming.longVersionCode < installed.longVersionCode) {
            throw Exception("downgrade refused: the APK is versionCode ${incoming.longVersionCode}, the installed $pkg is ${installed.longVersionCode} (uninstall it first)")
        }
        val have = installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet() ?: return
        val theirs = incoming.signingInfo?.apkContentsSigners ?: return
        if (theirs.any { it.toCharsString() !in have }) {
            throw Exception("signed with a different key than the installed $pkg (debug vs release?); uninstall it first")
        }
    }

    /** Starts installing; the future finishes with "Success" or throws with Android's message. */
    fun install(c: Context, apk: File, pkg: String): CompletableFuture<String> {
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(pkg)
        params.setSize(apk.length())
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            apk.inputStream().use { input ->
                s.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out, 256 * 1024)
                    s.fsync(out)
                }
            }
            val f = CompletableFuture<String>()
            pending[id] = f
            s.commit(statusReceiver(c, id))
            return f
        }
    }

    fun uninstall(c: Context, pkg: String): CompletableFuture<String> {
        val id = (System.nanoTime() and 0x3fffffff).toInt() or 0x40000000 // apart from session ids
        val f = CompletableFuture<String>()
        pending[id] = f
        c.packageManager.packageInstaller.uninstall(pkg, statusReceiver(c, id))
        return f
    }

    fun await(f: CompletableFuture<String>): String = try {
        f.get(180, TimeUnit.SECONDS)
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    } catch (e: TimeoutException) {
        throw Exception("no answer from Android after 3 minutes; is a prompt waiting on the phone?")
    }

    private fun statusReceiver(c: Context, id: Int): IntentSender {
        val i = Intent(c, StatusReceiver::class.java).setAction(ACTION).putExtra("id", id)
        return PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT).intentSender
    }

    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val id = intent.getIntExtra("id", -1)
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val f = pending[id]
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    (AgentAccessibility.instance ?: c).startActivity(confirm)
                    thread(name = "install-confirm") { confirmPrompt(f) }
                }
                return
            }
            pending.remove(id)
            if (f == null) return
            if (status == PackageInstaller.STATUS_SUCCESS) f.complete("Success")
            else f.completeExceptionally(Exception(statusName(status) + ": " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
        }
    }

    /** Taps the package installer's confirm button (Install, Update, OK...) once it shows. */
    private fun confirmPrompt(f: CompletableFuture<String>?) {
        val labels = listOf("install", "update", "install anyway", "uninstall", "ok", "continue")
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline && (f == null || !f.isDone)) {
            val a = AgentAccessibility.instance ?: return // the user taps it themselves
            for (w in a.windowsTopFirst()) {
                val root = w.root ?: continue
                if (root.packageName?.toString()?.contains("packageinstaller") != true) continue
                for (label in labels) {
                    for (n in root.findAccessibilityNodeInfosByText(label)) {
                        val t = n.text
                        if (t != null && t.toString().trim().lowercase(Locale.ROOT) == label && n.isClickable && n.isEnabled) {
                            CommandLog.add("tapped \"$t\" in the installer prompt")
                            n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                            return
                        }
                    }
                }
            }
            try {
                Thread.sleep(300)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun statusName(s: Int) = when (s) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "aborted"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "blocked"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "conflict"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "incompatible"
        PackageInstaller.STATUS_FAILURE_INVALID -> "invalid"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "storage"
        else -> "failed"
    }
}
