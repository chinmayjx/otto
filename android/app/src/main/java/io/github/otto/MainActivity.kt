package io.github.otto

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.R as M
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.otto.device.AgentAccessibility

/**
 * Otto's own screen, and only for setup: the switch for the handle (which turns Otto, and its links to the box, on
 * and off), then each thing Otto needs, with its state and a button to grant it: pairing with a box, drawing over
 * other apps, the accessibility service, installing apps, notifications, log access, opening links.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var heroCard: MaterialCardView
    private lateinit var heroIcon: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroDetail: TextView
    private lateinit var handle: MaterialSwitch
    private lateinit var checklist: LinearLayout
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            ticker.postDelayed(this, 1000)
        }
    }

    override fun onCreate(saved: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(saved)
        enableEdgeToEdge()

        val scroll = ScrollView(this)
        scroll.clipToPadding = true // the page scrolls under nothing: not the status bar, not the gesture bar
        scroll.setBackgroundColor(color(M.attr.colorSurface))
        val col = column()
        col.setPadding(dp(24), dp(24), dp(24), dp(28))
        scroll.addView(col)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        col.addView(bold(label("Otto", 32, onSurface())))
        space(col, 8)
        col.addView(label("Your box on this phone: a second screen over every app, and the browser and phone your agents use.", 16, muted()))
        space(col, 28)

        // The main switch: the handle, and with it Otto's links to the box. The card takes the accent while on.
        val hero = column()
        heroIcon = ImageView(this)
        heroIcon.setImageResource(R.drawable.ic_stat_screen)
        heroIcon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        hero.addView(heroIcon, LinearLayout.LayoutParams(dp(36), dp(36)))
        space(hero, 16)
        handle = MaterialSwitch(this)
        handle.text = "The handle"
        handle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        handle.setTypeface(handle.typeface, Typeface.BOLD)
        handle.minHeight = dp(56)
        handle.setOnCheckedChangeListener { _, on ->
            if (on == OverlayService.running) return@setOnCheckedChangeListener
            if (on) {
                if (!Settings.canDrawOverlays(this)) {
                    handle.isChecked = false
                    openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true)
                    return@setOnCheckedChangeListener
                }
                OverlayService.send(this, OverlayService.ACTION_START)
            } else OverlayService.send(this, OverlayService.ACTION_STOP)
            handle.postDelayed({ render() }, 500)
        }
        hero.addView(handle, wide())
        heroTitle = label("", 15, onSurface())
        hero.addView(heroTitle, wide())
        space(hero, 4)
        heroDetail = label("", 14, onSurface())
        hero.addView(heroDetail, wide())
        heroCard = card(hero, 28)
        col.addView(heroCard, wide())
        space(col, 28)

        col.addView(bold(label("Setup", 20, onSurface())))
        space(col, 6)
        col.addView(label("What Otto needs, once.", 14, muted()))
        space(col, 12)
        checklist = column()
        col.addView(checklist, wide())
        space(col, 24)

        val footer = label("Nothing reaches this phone while the handle is off. Turn it off here or from the notification.", 13, muted())
        footer.gravity = Gravity.CENTER
        col.addView(footer, wide())

        setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)
        handleMicIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleMicIntent(intent)
    }

    /** A page in the second screen asked for the microphone; only an activity can ask Android for it. */
    private fun handleMicIntent(i: Intent?) {
        if (i?.getBooleanExtra("mic", false) == true) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        render()
        if (code == MIC && OverlayService.running) OverlayService.send(this, OverlayService.ACTION_SHOW) // back to the page that asked
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        ticker.post(tick)
    }

    override fun onPause() {
        resumed = false
        ticker.removeCallbacks(tick)
        super.onPause()
    }

    private fun render() {
        val on = OverlayService.running
        if (handle.isChecked != on) handle.isChecked = on
        val pairing = Pairing.load(this)
        val browser = LinkState.read(this, LinkState.BROWSER)
        val device = LinkState.read(this, LinkState.DEVICE)
        val connected = on && pairing != null && browser == "connected" && device == "connected"
        heroTitle.text = when {
            !on -> "Off"
            pairing == null -> "On, not paired with a box"
            connected -> "On, connected to ${pairing.box}"
            else -> "On, connecting to ${pairing.box}"
        }
        heroDetail.text = if (on) "Tap or swipe the handle on the right edge to bring in the second screen; drag it up or down to move it."
        else "Turn it on for the second screen, and for your agents to reach this phone's browser and screen."
        val fg = color(if (connected) M.attr.colorOnPrimaryContainer else M.attr.colorOnSurface)
        heroCard.setCardBackgroundColor(color(if (connected) M.attr.colorPrimaryContainer else M.attr.colorSurfaceContainerHigh))
        heroIcon.imageTintList = ColorStateList.valueOf(if (on) fg else muted())
        handle.setTextColor(fg)
        heroTitle.setTextColor(fg)
        heroDetail.setTextColor(fg)

        items.clear()
        // Pairing, and both links.
        if (pairing == null) {
            item("Pair with your box", "Not paired. On the box, run:  otto pair --send\n(or open the link otto pair prints on this phone)",
                false, "Copy the command") { copy("otto pair --send") }
        } else {
            val links = if (on) "Browser link: $browser\nDevice link: $device" else "Links: off while the handle is off"
            item("Paired with ${pairing.box}", "$links\nHub: ${pairing.hubUrl}\nArtifacts: ${pairing.artifactsUrl}", true, "Unpair") {
                Pairing.clear(this)
                if (OverlayService.running) OverlayService.send(this, OverlayService.ACTION_RELINK)
                render()
            }
        }
        val overlay = Settings.canDrawOverlays(this)
        item("Draw over other apps", if (overlay) "Allowed." else "Needed for the handle and the second screen.", overlay,
            if (overlay) null else "Allow") { openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true) }
        val accessibility = AgentAccessibility.instance != null
        item("Accessibility service", if (accessibility) "On: agents can see the screen, tap and type."
            else "For the screen, the UI tree, taps and typing: Settings › Accessibility › Otto. If Android greys it out, " +
                "first open App info › ⋮ › Allow restricted settings (needed once for an app not from the Play Store).",
            accessibility, if (accessibility) null else "Turn on") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        if (!accessibility) item("Restricted settings", "Android may need this first for a sideloaded app: App info › ⋮ › Allow restricted settings.",
            false, "App info") { openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }
        val install = packageManager.canRequestPackageInstalls()
        item("Install apps", if (install) "Allowed: agents can install and update APKs." else "Lets agents install and update APKs.",
            install, if (install) null else "Allow") { openSettings(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, true) }
        val notes = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        item("Notification", if (notes) "Shown while the handle is on: tap it to open the second screen, or turn Otto off."
            else "A quiet notification while the handle is on: open the second screen, or turn Otto off.",
            notes, if (notes) null else "Allow") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTES) }
        val logs = checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
        val grant = "adb shell pm grant $packageName android.permission.READ_LOGS"
        item("Log access", if (logs) "Granted: agents can read the phone's logs." else "Granted once over adb (USB or wireless debugging):\n$grant",
            logs, if (logs) null else "Copy the command") { copy(grant) }
        linksItem()
        val pm = getSystemService(PowerManager::class.java)
        val battery = pm.isIgnoringBatteryOptimizations(packageName)
        item("Background", if (battery) "Unrestricted: Otto stays connected with the screen off." else "Optional: keeps Otto connected with the screen off.",
            battery, if (battery) null else "Allow") { openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, true) }
        // Redrawn only when something changed (this runs every second), so a button isn't replaced under a finger.
        val key = items.joinToString("\u0000") { "${it.title}|${it.detail}|${it.done}|${it.action}" }
        if (key == shown) return
        shown = key
        checklist.removeAllViews()
        for (i in items) draw(i)
    }

    private class Item(val title: String, val detail: String, val done: Boolean, val action: String?, val onClick: () -> Unit)

    private val items = ArrayList<Item>()
    private var shown = ""

    private fun item(title: String, detail: String, done: Boolean, action: String?, onClick: () -> Unit) {
        items.add(Item(title, detail, done, action, onClick))
    }

    /** Links to the box and the artifact hub open here once Android is told so (Open by default › Add link). */
    private fun linksItem() {
        val hosts = StringBuilder()
        var all = true
        try {
            val st = getSystemService(DomainVerificationManager::class.java).getDomainVerificationUserState(packageName)!!
            for ((host, state) in st.hostToStateMap) {
                val on = state != DomainVerificationUserState.DOMAIN_STATE_NONE
                all = all && on
                hosts.append(if (on) "✓ " else "○ ").append(host).append('\n')
            }
        } catch (e: Exception) {
            all = false
        }
        item("Open links here", hosts.toString() + if (all) "Links to your box and artifacts open in the second screen; other links go to your browser."
            else "In Open by default, tap Add link and tick these.", all, if (all) null else "Open settings") {
            openSettings(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, true)
        }
    }

    private fun openSettings(action: String, forThisApp: Boolean) {
        val i = Intent(action)
        if (forThisApp) i.data = Uri.parse("package:$packageName")
        startActivity(i)
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Otto", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    /** One setup row: a card with what it is, its state, and (while it's not done) a button to do it. */
    private fun draw(item: Item) {
        val (title, detail, done) = Triple(item.title, item.detail, item.done)
        val action = item.action
        val onClick = item.onClick
        val body = column()
        val t = bold(label((if (done) "✓  " else "○  ") + title, 16, if (done) onSurface() else color(M.attr.colorPrimary)))
        body.addView(t, wide())
        space(body, 4)
        body.addView(label(detail.trim(), 13, muted()), wide())
        if (action != null) {
            space(body, 8)
            val b = MaterialButton(this, null, if (done) M.attr.materialButtonOutlinedStyle else M.attr.materialButtonStyle)
            b.text = action
            b.isAllCaps = false
            b.setOnClickListener { onClick() }
            body.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
        }
        val c = card(body, 20)
        body.setPadding(dp(20), dp(16), dp(20), dp(16))
        checklist.addView(c, wide().apply { bottomMargin = dp(10) })
    }

    private fun card(content: LinearLayout, radius: Int): MaterialCardView {
        val c = MaterialCardView(this)
        c.radius = dp(radius).toFloat()
        c.cardElevation = 0f
        c.strokeWidth = 0
        c.setCardBackgroundColor(color(M.attr.colorSurfaceContainer))
        content.setPadding(dp(24), dp(24), dp(24), dp(24))
        c.addView(content)
        return c
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun label(s: String, sp: Int, tint: Int) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
        setTextColor(tint)
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun bold(t: TextView) = t.apply { setTypeface(typeface, Typeface.BOLD) }

    private fun space(parent: LinearLayout, height: Int) = parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))

    private fun wide() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun color(attr: Int) = MaterialColors.getColor(this, attr, "Otto")
    private fun onSurface() = color(M.attr.colorOnSurface)
    private fun muted() = color(M.attr.colorOnSurfaceVariant)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val NOTES = 1
        private const val MIC = 2

        /** The screen is showing: Android asks for log consent only then (see device/LogReader). */
        @Volatile var resumed = false
            private set

        /** A page in the second screen asked for the microphone; only an activity can ask Android for it. */
        fun askForMicrophone(ctx: Context) {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra("mic", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
