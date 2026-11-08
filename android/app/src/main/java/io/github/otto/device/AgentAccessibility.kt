package io.github.otto.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.otto.BootReceiver
import io.github.otto.Events
import io.github.otto.OverlayService
import io.github.otto.Tunnel
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The device agent's eyes and hands: the UI tree of every window, gestures, global actions (Back, Home, ...) and
 * screenshots. Android binds it while it's on in Settings > Accessibility; Commands reaches it through [instance].
 */
class AgentAccessibility : AccessibilityService() {
    /** When the last UI event came in (uptime ms); quiet means the screen has settled. */
    @Volatile private var lastEvent = 0L

    override fun onServiceConnected() {
        instance = this
        CommandLog.add("accessibility service on")
        // After an update (or a restart) Android binds this service again: the way back to the box, even if the
        // handle's foreground service can't be started from here.
        if (OverlayService.wanted(this)) {
            Events.add(this, "accessibility service on")
            Tunnel.start(this, "accessibility")
            BootReceiver.restore(this, "accessibility")
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        // the agent's own touches aren't the screen changing
        if (e.eventType and (AccessibilityEvent.TYPE_TOUCH_INTERACTION_START or AccessibilityEvent.TYPE_TOUCH_INTERACTION_END
                or AccessibilityEvent.TYPE_GESTURE_DETECTION_START or AccessibilityEvent.TYPE_GESTURE_DETECTION_END
                or AccessibilityEvent.TYPE_VIEW_HOVER_ENTER or AccessibilityEvent.TYPE_VIEW_HOVER_EXIT) == 0) {
            lastEvent = SystemClock.uptimeMillis()
        }
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.packageName != null && e.className != null) {
            val cls = e.className.toString()
            // Views and dialogs inside an app also fire this; keep the activity
            if (cls.startsWith("android.widget.") || cls.startsWith("android.view.") || cls.startsWith("android.app.Dialog")) return
            frontPackage = e.packageName.toString()
            frontClass = cls
        }
    }

    override fun onInterrupt() {}

    /** Watches for Android's log consent dialog for a few seconds and taps "Allow one-time access". */
    fun allowLogs() {
        val label = packageManager.getApplicationLabel(applicationInfo).toString()
        thread(name = "allow-logs") {
            var i = 0
            while (i++ < 40 && System.currentTimeMillis() < LogReader.expectDialogUntil) {
                for (w in windowsTopFirst()) {
                    val root = w.root ?: continue
                    var found = root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/log_access_dialog_allow_button")
                    if (found.isEmpty()) {
                        val title = root.findAccessibilityNodeInfosByText("access all device logs").lastOrNull()?.text
                        if (title == null || !title.toString().contains(label)) continue
                        found = root.findAccessibilityNodeInfosByText("Allow")
                    }
                    for (n in found) {
                        if (n.isClickable && n.text?.toString()?.lowercase(Locale.ROOT)?.startsWith("allow") == true) {
                            LogReader.expectDialogUntil = 0
                            n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            CommandLog.add("allowed log access")
                            return@thread
                        }
                    }
                }
                try {
                    Thread.sleep(250)
                } catch (e: InterruptedException) {
                    return@thread
                }
            }
        }
    }

    /** Windows on screen, top layer first. */
    fun windowsTopFirst(): List<AccessibilityWindowInfo> = windows.sortedByDescending { it.layer }

    /**
     * The screen, as a software bitmap. Blocks, so never on the main thread; the copy out of the hardware buffer runs
     * on [executor] (the main thread's by default; the lens gives its own, so the main thread doesn't stutter).
     */
    fun screenshot(executor: Executor = mainExecutor): Bitmap {
        var attempt = 0
        while (true) {
            val f = CompletableFuture<Any?>()
            takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                    val b = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    r.hardwareBuffer.close()
                    f.complete(b)
                }

                override fun onFailure(code: Int) {
                    f.complete(code)
                }
            })
            val r = f.get(5, TimeUnit.SECONDS)
            if (r is Bitmap) return r
            val code = r as? Int ?: -1
            // Android allows about three screenshots a second
            if (code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && attempt++ < 5) {
                Thread.sleep(350)
                continue
            }
            if (code == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) throw Exception("the screen shows a secure window (FLAG_SECURE); it can't be captured")
            throw Exception("screenshot failed ($code)")
        }
    }

    fun tap(x: Float, y: Float, ms: Long) = gesture(Path().apply { moveTo(x, y) }, ms)

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) = gesture(Path().apply {
        moveTo(x1, y1)
        lineTo(x2, y2)
    }, ms)

    private fun gesture(path: Path, ms: Long) {
        val f = CompletableFuture<Boolean>()
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, maxOf(1L, ms))).build()
        val sent = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription) {
                f.complete(true)
            }

            override fun onCancelled(d: GestureDescription) {
                f.complete(false)
            }
        }, null)
        if (!sent || !f.get(ms + 5000, TimeUnit.MILLISECONDS)) throw Exception("the gesture was cancelled")
    }

    /**
     * Returns once an action's effect is on screen: waits up to startMs for the screen to start changing, then until
     * it has been quiet for quietMs (a fling or a screen transition is over), at most maxMs in all.
     */
    fun settle(startMs: Long, quietMs: Long, maxMs: Long) {
        val t0 = SystemClock.uptimeMillis()
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (now - t0 >= maxMs) return
            if (lastEvent < t0) {
                if (now - t0 >= startMs) return // nothing changed
                Thread.sleep(50)
            } else {
                val quiet = now - lastEvent
                if (quiet >= quietMs) return
                Thread.sleep(quietMs - quiet)
            }
        }
    }

    /** Taps the middle of a node's on-screen bounds. */
    fun tapNode(n: AccessibilityNodeInfo, ms: Long) {
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.isEmpty) throw Exception("the element isn't on screen")
        tap(r.exactCenterX(), r.exactCenterY(), ms)
    }

    companion object {
        @Volatile var instance: AgentAccessibility? = null
            private set

        /** The last window that came to the front: package and class (an activity's class for app screens). */
        @Volatile var frontPackage = ""
            private set
        @Volatile var frontClass = ""
            private set

        fun require(): AgentAccessibility =
            instance ?: throw Exception("the accessibility service is off: turn on Otto in Settings > Accessibility")
    }
}
