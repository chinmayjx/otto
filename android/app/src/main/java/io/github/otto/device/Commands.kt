package io.github.otto.device

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import io.github.otto.BuildConfig
import io.github.otto.Events
import io.github.otto.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Runs one command from the box (DeviceApi turns each route into one). The command names follow adb where they can.
 */
class Commands(private val ctx: Context) {
    /**
     * A command's result: JSON (a JSONObject or JSONArray), a string, or bytes of [type] with the result beside them.
     * [after] runs once the reply is sent (installing Otto itself, which ends this process).
     */
    class Reply(val result: Any?, val body: ByteArray? = null, val type: String = "application/octet-stream", val after: (() -> Unit)? = null)

    class Unknown(cmd: String) : Exception("unknown command $cmd")

    /** Runs a command; throws with the reason if it fails. */
    fun run(cmd: String, args: JSONObject, body: ByteArray?): Reply {
        val t0 = System.currentTimeMillis()
        val summary = cmd + if (args.length() > 0) " " + brief(args) else ""
        try {
            val r = dispatch(cmd, args, body)
            CommandLog.add("$summary  ✓ ${System.currentTimeMillis() - t0} ms")
            return r
        } catch (e: Throwable) {
            CommandLog.add("$summary  ✗ ${e.message ?: e}")
            throw e
        }
    }

    private fun dispatch(cmd: String, a: JSONObject, body: ByteArray?): Reply = when (cmd) {
        "info" -> Reply(info())
        "screenshot" -> screenshot(a)
        "ui" -> Reply(ui(a))
        "tap" -> Reply(settled(tap(a, false)))
        "longpress" -> Reply(settled(tap(a, true)))
        "swipe" -> Reply(settled(swipe(a)))
        "text" -> Reply(settled(text(a)))
        "key" -> Reply(settled(key(a.getString("key"))))
        "wait" -> Reply(waitFor(a))
        "current" -> Reply(current())
        "launch" -> Reply(settled(launch(a)))
        "forcestop" -> Reply(forceStop(a.getString("package")))
        "packages" -> Reply(packages(a))
        "appinfo" -> Reply(appInfo(a.getString("package")))
        "install" -> install(body)
        "uninstall" -> Reply(Installer.await(Installer.uninstall(ctx, a.getString("package"))))
        "logcat" -> logcat(a)
        "events" -> Reply(Events.text(ctx))
        else -> throw Unknown(cmd)
    }

    /** Actions return once the screen has settled, so the next screenshot or tree shows the result. */
    private fun settled(result: String): String {
        AgentAccessibility.instance?.settle(500, 300, 3000)
        return result
    }

    // ---- device ----

    private fun info(): JSONObject {
        val o = JSONObject()
        o.put("device", Build.MANUFACTURER + " " + Build.MODEL)
        o.put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
        o.put("agent", BuildConfig.VERSION_NAME)
        val r = ctx.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        o.put("screen", "${r.width()}x${r.height()}")
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        o.put("battery", (if (level in 0..100) "$level%" else "unknown") + if (bm.isCharging) " charging" else "")
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val nc = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = ArrayList<String>()
        if (nc != null) {
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) net.add("wifi")
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) net.add("mobile data")
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) net.add("vpn")
        }
        o.put("network", if (net.isEmpty()) "none" else net.joinToString(" + "))
        val pm = ctx.getSystemService(PowerManager::class.java)
        o.put("screenOn", pm.isInteractive)
        o.put("accessibility", AgentAccessibility.instance != null)
        o.put("readLogs", ctx.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED)
        o.put("canInstall", ctx.packageManager.canRequestPackageInstalls())
        o.put("batteryUnrestricted", pm.isIgnoringBatteryOptimizations(ctx.packageName))
        o.put("foreground", current())
        return o
    }

    private fun current(): JSONObject {
        val o = JSONObject()
        val a = AgentAccessibility.instance ?: return o.put("error", "accessibility service off")
        val root = a.rootInActiveWindow
        o.put("package", root?.packageName?.toString() ?: AgentAccessibility.frontPackage)
        if (o.optString("package") == AgentAccessibility.frontPackage) o.put("activity", AgentAccessibility.frontClass)
        return o
    }

    // ---- screen ----

    private fun screenshot(a: JSONObject): Reply {
        var b = AgentAccessibility.require().screenshot()
        val scale = a.optDouble("scale", 1.0)
        if (scale > 0 && scale < 1) {
            val s = Bitmap.createScaledBitmap(b, (b.width * scale).roundToInt(), (b.height * scale).roundToInt(), true)
            b.recycle()
            b = s
        }
        val format = a.optString("format", "png")
        val out = ByteArrayOutputStream()
        if (format == "jpg" || format == "jpeg") b.compress(Bitmap.CompressFormat.JPEG, a.optInt("quality", 80), out)
        else b.compress(Bitmap.CompressFormat.PNG, 100, out)
        val jpeg = format.startsWith("jp")
        val r = JSONObject().put("width", b.width).put("height", b.height).put("format", if (jpeg) "jpeg" else "png")
        b.recycle()
        return Reply(r, out.toByteArray(), if (jpeg) "image/jpeg" else "image/png")
    }

    private fun ui(a: JSONObject): String {
        val s = AgentAccessibility.require()
        val all = a.optBoolean("all")
        return if (a.optBoolean("xml")) UiTree.xml(s, all) else UiTree.text(s, all)
    }

    private fun selector(a: JSONObject) = UiTree.Selector(
        text = if (a.has("text")) a.optString("text") else null,
        id = if (a.has("id")) a.optString("id") else null,
        desc = if (a.has("desc")) a.optString("desc") else null,
        index = a.optInt("index", 0),
    )

    private fun findOne(s: AgentAccessibility, sel: UiTree.Selector): AccessibilityNodeInfo {
        val found = UiTree.find(s, sel)
        if (found.size <= sel.index) {
            throw Exception("no element with $sel" + if (found.isEmpty()) "" else " at index ${sel.index} (${found.size} found)")
        }
        return found[sel.index]
    }

    // ---- input ----

    private fun tap(a: JSONObject, longPress: Boolean): String {
        val s = AgentAccessibility.require()
        val ms = a.optLong("ms", if (longPress) 700 else 50)
        val sel = selector(a)
        val verb = if (longPress) "long-pressed " else "tapped "
        if (!sel.empty()) {
            val n = findOne(s, sel)
            s.tapNode(n, ms)
            return verb + UiTree.describe(n)
        }
        val x = a.getDouble("x").toFloat()
        val y = a.getDouble("y").toFloat()
        s.tap(x, y, ms)
        return verb + "${x.toInt()},${y.toInt()}"
    }

    private fun swipe(a: JSONObject): String {
        val s = AgentAccessibility.require()
        val ms = a.optLong("ms", 300)
        if (a.has("direction")) {
            // scroll the content: "down" moves the finger up, showing what's below
            val r = ctx.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            val cx = r.width() / 2f
            val cy = r.height() / 2f
            val dy = r.height() * 0.3f
            val dx = r.width() * 0.35f
            when (a.getString("direction")) {
                "down" -> s.swipe(cx, cy + dy, cx, cy - dy, ms)
                "up" -> s.swipe(cx, cy - dy, cx, cy + dy, ms)
                "right" -> s.swipe(cx + dx, cy, cx - dx, cy, ms)
                "left" -> s.swipe(cx - dx, cy, cx + dx, cy, ms)
                else -> throw Exception("direction is up, down, left or right")
            }
            return "scrolled " + a.getString("direction")
        }
        s.swipe(a.getDouble("x1").toFloat(), a.getDouble("y1").toFloat(), a.getDouble("x2").toFloat(), a.getDouble("y2").toFloat(), ms)
        return "swiped"
    }

    private fun text(a: JSONObject): String {
        val s = AgentAccessibility.require()
        val n = UiTree.focusedInput(s) ?: throw Exception("no text field has focus; tap one first")
        val add = a.getString("text")
        val cur = n.text
        val value = if (a.optBoolean("replace") || cur == null || n.isShowingHintText) add else "$cur$add"
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) throw Exception("the field refused the text")
        val sel = Bundle()
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, value.length)
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, value.length)
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        return "typed into " + UiTree.describe(n)
    }

    private fun key(key: String): String {
        val s = AgentAccessibility.require()
        if (key == "enter") {
            val n = UiTree.focusedInput(s) ?: throw Exception("no text field has focus")
            if (!n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) throw Exception("the field ignored Enter")
            return "pressed enter"
        }
        val action = when (key) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quicksettings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "power" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "lock" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "splitscreen" -> AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            "dismiss" -> AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
            else -> throw Exception("unknown key $key")
        }
        if (!s.performGlobalAction(action)) throw Exception("Android refused $key")
        return "pressed $key"
    }

    private fun waitFor(a: JSONObject): String {
        val s = AgentAccessibility.require()
        val sel = selector(a)
        if (sel.empty()) throw Exception("give text, id or desc")
        val gone = a.optBoolean("gone")
        val deadline = System.currentTimeMillis() + a.optLong("timeout", 10_000)
        while (true) {
            val found = UiTree.find(s, sel)
            if (gone && found.isEmpty()) return "gone: $sel"
            if (!gone && found.isNotEmpty()) return "found " + UiTree.describe(found[0])
            if (System.currentTimeMillis() > deadline) throw Exception("timed out waiting for $sel" + if (gone) " to go away" else "")
            Thread.sleep(250)
        }
    }

    // ---- apps ----

    /** Starting activities from the background is allowed for a bound accessibility service. */
    private fun launcher(): Context = AgentAccessibility.instance ?: ctx

    private fun launch(a: JSONObject): String {
        val pkg = if (a.has("package") && !a.isNull("package")) a.getString("package") else null
        val i: Intent = when {
            a.has("url") -> Intent(Intent.ACTION_VIEW, Uri.parse(a.getString("url"))).also { if (pkg != null) it.setPackage(pkg) }
            a.has("activity") -> {
                val act = a.getString("activity")
                Intent(Intent.ACTION_MAIN).setComponent(ComponentName(pkg!!, if (act.startsWith(".")) pkg + act else act))
            }
            else -> pkg?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
                ?: throw Exception("$pkg has no launcher activity (or isn't installed)")
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launcher().startActivity(i)
        return "started " + (i.component?.flattenToShortString() ?: i.dataString)
    }

    /** No API stops another app, so this taps Force stop in the app's settings page, then goes back. */
    private fun forceStop(pkg: String): String {
        val s = AgentAccessibility.require()
        ctx.packageManager.getApplicationInfo(pkg, 0) // throws if not installed
        launcher().startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY))
        try {
            var n: AccessibilityNodeInfo? = null
            var deadline = System.currentTimeMillis() + 8000
            while (n == null && System.currentTimeMillis() < deadline) {
                n = UiTree.find(s, UiTree.Selector(text = "force stop")).firstOrNull { it.text?.toString()?.trim()?.equals("force stop", ignoreCase = true) == true }
                if (n == null) Thread.sleep(250)
            }
            if (n == null) throw Exception("couldn't find Force stop in $pkg's settings page")
            val target = clickable(n)
            if (!target.isEnabled || !n.isEnabled) return "$pkg isn't running (Force stop is greyed out)"
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            // the confirmation dialog, in its own window: OK on Pixel, "Force stop" again on Samsung
            deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(250)
                for (label in listOf("ok", "force stop")) {
                    for (b in UiTree.find(s, UiTree.Selector(text = label))) {
                        if (b.windowId == n.windowId || b.text?.toString()?.trim()?.equals(label, ignoreCase = true) != true) continue
                        clickable(b).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return "force-stopped $pkg"
                    }
                }
            }
            throw Exception("tapped Force stop but no confirmation dialog showed")
        } finally {
            Thread.sleep(300)
            s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    private fun clickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var p: AccessibilityNodeInfo? = n
        while (p != null) {
            if (p.isClickable) return p
            p = p.parent
        }
        return n
    }

    private fun packages(a: JSONObject): JSONArray {
        val pm = ctx.packageManager
        val third = a.optBoolean("thirdParty")
        val withLabels = a.optBoolean("labels")
        val filter = (if (a.isNull("filter")) "" else a.optString("filter", "")).lowercase(Locale.ROOT)
        val out = JSONArray()
        for (p in pm.getInstalledPackages(0).sortedBy { it.packageName }) {
            val ai = p.applicationInfo ?: continue
            val system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
            if (third && system) continue
            val label = if (withLabels || filter.isNotEmpty()) label(pm, p, ai) else null
            if (filter.isNotEmpty() && !p.packageName.lowercase(Locale.ROOT).contains(filter) && label?.lowercase(Locale.ROOT)?.contains(filter) != true) continue
            val o = JSONObject().put("package", p.packageName).put("version", p.versionName).put("versionCode", p.longVersionCode)
            if (withLabels) o.put("label", label)
            if (system) o.put("system", true)
            out.put(o)
        }
        return out
    }

    private fun appInfo(pkg: String): JSONObject {
        val pm = ctx.packageManager
        val p = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        val ai = p.applicationInfo!!
        val o = JSONObject()
            .put("package", pkg)
            .put("label", pm.getApplicationLabel(ai).toString())
            .put("version", p.versionName).put("versionCode", p.longVersionCode)
            .put("uid", ai.uid)
            .put("enabled", ai.enabled)
            .put("system", ai.flags and ApplicationInfo.FLAG_SYSTEM != 0)
            .put("debuggable", ai.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            .put("stopped", ai.flags and ApplicationInfo.FLAG_STOPPED != 0)
            .put("minSdk", ai.minSdkVersion).put("targetSdk", ai.targetSdkVersion)
            .put("apk", ai.sourceDir)
            .put("firstInstall", Date(p.firstInstallTime).toString())
            .put("lastUpdate", Date(p.lastUpdateTime).toString())
        try {
            o.put("installer", pm.getInstallSourceInfo(pkg).installingPackageName)
        } catch (_: Exception) {
        }
        pm.getLaunchIntentForPackage(pkg)?.component?.let { o.put("launchActivity", it.className) }
        val perms = p.requestedPermissions
        val flags = p.requestedPermissionsFlags
        if (perms != null && flags != null) {
            val granted = JSONArray()
            val denied = JSONArray()
            for (i in perms.indices) (if (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0) granted else denied).put(perms[i])
            o.put("permissionsGranted", granted).put("permissionsDenied", denied)
        }
        return o
    }

    private fun install(apk: ByteArray?): Reply {
        if (apk == null || apk.isEmpty()) throw Exception("no APK sent")
        val f = File(ctx.cacheDir, "incoming.apk")
        f.writeBytes(apk)
        val pkg = Installer.packageOf(ctx, f) ?: throw Exception("that file isn't an APK Android can read")
        Installer.checkUpdate(ctx, f, pkg) // fail here rather than silently, above all when updating Otto itself
        if (pkg == ctx.packageName) {
            // Updating Otto itself ends this process: answer first, then install; it reconnects once the update is in
            return Reply("updating Otto itself; it reconnects in a few seconds", after = {
                Events.add(ctx, "updating Otto itself (from version ${BuildConfig.VERSION_CODE})")
                Installer.install(ctx, f, pkg)
            })
        }
        val r = Installer.await(Installer.install(ctx, f, pkg))
        f.delete()
        return Reply("$r: $pkg")
    }

    // ---- logs ----

    /**
     * Logcat lines from Otto's long-running reader (LogReader): threadtime with the year, plus the uid after the
     * time. If the reader can only see Otto's own logs, it first gets consent again (see ensureLogAccess).
     */
    private fun logcat(a: JSONObject): Reply {
        if (ctx.checkSelfPermission(Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            throw Exception("no log access; grant it once over adb: adb shell pm grant ${ctx.packageName} android.permission.READ_LOGS")
        }
        ensureLogAccess()
        val q = LogReader.Query()
        if (a.has("since") && !a.isNull("since")) {
            q.since = a.getString("since")
            q.lines = a.optInt("lines", 5000)
        } else q.lines = a.optInt("lines", 200)
        val level = (if (a.isNull("level")) "V" else a.optString("level", "V")).uppercase(Locale.ROOT)
        if (!level.matches(Regex("[VDIWEF]"))) throw Exception("level is one of V D I W E F")
        q.level = level[0]
        a.optJSONArray("tags")?.let { tags -> if (tags.length() > 0) q.tags = (0 until tags.length()).map { tags.getString(it) }.toSet() }
        if (a.has("package") && !a.isNull("package")) {
            val uid = ctx.packageManager.getApplicationInfo(a.getString("package"), 0).uid
            q.uids = setOf(uid.toString(), LogReader.uidName(uid))
        }
        if (a.has("grep") && !a.isNull("grep")) q.grep = Regex(a.getString("grep"))
        return Reply(JSONObject().put("fullAccess", LogReader.full), LogReader.query(q).toByteArray(), "text/plain; charset=utf-8")
    }

    /**
     * Android asks for log consent only while the app is in front. If the reader lost full access (it restarted in
     * the background, e.g. after an update), bring Otto's screen up for a moment, restart the reader so Android asks
     * (the accessibility service taps Allow), then go back to the app that was in front.
     */
    private fun ensureLogAccess() {
        if (LogReader.full && LogReader.running()) return
        val s = AgentAccessibility.require()
        val prev = AgentAccessibility.frontPackage
        s.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var deadline = System.currentTimeMillis() + 4000
        while (!MainActivity.resumed && System.currentTimeMillis() < deadline) Thread.sleep(100)
        if (!MainActivity.resumed) throw Exception("couldn't bring Otto's screen up to ask for log access")
        Thread.sleep(300)
        LogReader.restart()
        deadline = System.currentTimeMillis() + 10_000
        while (!LogReader.full && System.currentTimeMillis() < deadline) Thread.sleep(200)
        if (LogReader.full) Thread.sleep(500) // logcat replays its backlog first
        val back = if (prev.isEmpty() || prev == ctx.packageName) null else ctx.packageManager.getLaunchIntentForPackage(prev)
        if (back != null) s.startActivity(back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        else s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        if (!LogReader.full) throw Exception("Android didn't allow access to all logs. It remembers a declined or unanswered consent dialog for a minute; try again after that")
    }

    companion object {
        /** App labels take seconds to load for every app, so they're kept until the app updates. */
        private val labels = ConcurrentHashMap<String, String>()

        private fun label(pm: PackageManager, p: PackageInfo, ai: ApplicationInfo): String =
            labels.getOrPut(p.packageName + "@" + p.lastUpdateTime) { pm.getApplicationLabel(ai).toString() }

        private fun brief(a: JSONObject): String {
            val s = a.toString()
            return if (s.length > 160) s.substring(0, 160) + "…" else s
        }
    }
}
