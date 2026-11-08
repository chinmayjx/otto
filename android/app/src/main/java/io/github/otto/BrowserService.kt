package io.github.otto

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Parcel
import android.util.Base64
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.KeyEvent
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The second screen's browser, in its own process (":browser", see App). Its views are drawn into the main
 * screen through SurfaceControlViewHost (Screen embeds the surface), so the two screens move as one, while this
 * process's DevTools socket, the one BrowserLink hands to the box, holds only the browser.
 *
 * While the handle is on it is a foreground service of its own (OverlayService starts it; the two share one
 * notification), so the browser and its link to the box stay up with the screen closed: Android neither kills nor
 * freezes a foreground service's process. Screen binds to it besides, to embed it and talk to it.
 *
 * A browser like any other: tabs, all sharing one set of cookies, and tab groups. A tab has an id of its own that
 * lasts (its WebView, and so its DevTools target, does not). Tabs are kept (page, title, back history) and outlive
 * this process; only a few have a live WebView at a time: the one in view, the most recently used, and those of
 * groups someone has woken (BrowserLink: an agent session working in them). The rest are dormant and come back to
 * life when selected or woken. Tabs unused for 30 days are dropped.
 *
 * A group has a title and an owner ("agent" for an agent session's, which shows as ✳). What a group means to
 * whoever woke it is the box relay's business, not this browser's.
 *
 * The tab block and its menu (address bar, tab list) are Screen's, in the top bar; this process only has the
 * pages, and reports the tabs to it (TABS) whenever they change.
 *
 * Messages from Screen (a Messenger):
 *   ATTACH {hostToken, displayId, width, height} → replies ATTACH {surface}: the SurfacePackage to embed
 *   RESIZE {width, height}
 *   LOAD {url}       in the current tab (a URL, a bare host, or a search)
 *   SELECT {id}, CLOSE {id}, NEW, RELOAD, BACK
 *   DESKTOP {on}     the desktop site for the current tab's site, remembered per site (and reload)
 *   SUGGEST {query, seq} → replies SUGGESTIONS {json, seq}: open tabs, then history, matching what's typed
 *   ACTIVE           the user opened the second screen: this app is the one in use (the relay sends new tabs here)
 *   HIDE_KEYBOARD    Back with the keyboard up over a page: the page lets go of focus, and so of the keyboard
 *   CLIENT           Screen is bound (replyTo: where to send what follows), before it has a surface to embed in
 * and from the tunnel (Tunnel, main process): TUNNEL (replyTo: the tunnel), SYNC, LINE {line} (see BrowserLink).
 * and to it: SHOW (slide in the second screen on the Browser tab), TABS {json}, BACK_KEY (Back was pressed while a
 * page had the keyboard focus: the key comes here, not to the screen's window).
 */
class BrowserService : Service() {
    /** A tab. Its WebView (web) is null while it's dormant. */
    class Tab(val id: String, val group: String?) {
        var web: WebView? = null
        @Volatile var targetId: String? = null // its DevTools target while live, once BrowserLink has found it
        var url = ""
        var title = ""
        var state: Bundle? = null // while dormant: its back history (WebView.saveState)
        var used = System.currentTimeMillis()
        var progress = 100
        var desktop = false // showing the desktop site
        var wide: ScriptHandler? = null // while desktop: the script that widens the page's viewport
    }

    class Group(val id: String, var title: String, val owner: String?) // owner: "agent", or null

    private val messenger = Messenger(Handler(Looper.getMainLooper(), ::handle))
    val main = Handler(Looper.getMainLooper())
    private var client: Messenger? = null // Screen
    private var host: SurfaceControlViewHost? = null
    private lateinit var ui: Context
    private lateinit var pages: FrameLayout
    private lateinit var progress: View // the current tab's loading progress: a thin line along the top
    private lateinit var history: BrowserHistory
    val tabs = ArrayList<Tab>()
    val groups = ArrayList<Group>()
    private val woken = HashSet<String>() // groups kept live (someone's working in them)
    private var nextId = 1
    private var current: Tab? = null
    private lateinit var desktopSites: SharedPreferences // sites shown as on a computer: host -> true
    private var mobileAgent: String? = null
    private var desktopAgent: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        history = BrowserHistory(this)
        desktopSites = getSharedPreferences("desktop_sites", MODE_PRIVATE)
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        BrowserLink.start(this)
        build(ContextThemeWrapper(createDisplayContext(display), android.R.style.Theme_DeviceDefault))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started by OverlayService with startForegroundService, which wants startForeground every time.
        Notifications.startForeground(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun handle(m: Message): Boolean {
        val b = m.data
        try {
            when (m.what) {
                MSG_ATTACH -> {
                    val display = getSystemService(DisplayManager::class.java).getDisplay(b.getInt("displayId"))
                    host?.release()
                    (pages.parent as? ViewGroup)?.removeView(pages)
                    val h = SurfaceControlViewHost(this, display, b.getBinder("hostToken"))
                    host = h
                    h.setView(pages, b.getInt("width"), b.getInt("height"))
                    client = m.replyTo
                    val reply = Message.obtain(null, MSG_ATTACH)
                    reply.data.putParcelable("surface", h.surfacePackage)
                    m.replyTo.send(reply)
                    BrowserLink.send("active") // this app is the one in use: sessions' tabs open here
                    publish()
                }
                MSG_CLIENT -> {
                    client = m.replyTo
                    publish()
                }
                Tunnel.MSG_TUNNEL -> BrowserLink.attach(m.replyTo)
                Tunnel.MSG_SYNC -> BrowserLink.sync()
                Tunnel.MSG_LINE -> b.getString("line")?.let { BrowserLink.handle(it) }
                MSG_RESIZE -> host?.relayout(b.getInt("width"), b.getInt("height"))
                MSG_LOAD -> go(b.getString("url"))
                MSG_SELECT -> byId(b.getString("id"))?.let { select(it) }
                MSG_CLOSE -> byId(b.getString("id"))?.let { close(it) }
                MSG_NEW -> select(newTab(null, HOME))
                MSG_DESKTOP -> current?.let { if (it.web != null) setDesktop(it, b.getString("on") != null) }
                MSG_RELOAD -> current?.web?.reload()
                MSG_BACK -> current?.web?.let { if (it.canGoBack()) it.goBack() }
                MSG_ACTIVE -> BrowserLink.send("active")
                MSG_HIDE_KEYBOARD -> hideKeyboard()
                MSG_SUGGEST -> {
                    val reply = Message.obtain(null, MSG_SUGGESTIONS)
                    reply.data.putString("json", suggest(b.getString("query", "")).toString())
                    reply.data.putInt("seq", b.getInt("seq"))
                    m.replyTo.send(reply)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "message ${m.what}", e)
        }
        return true
    }

    override fun onDestroy() {
        instance = null
        BrowserLink.stop()
        main.removeCallbacks(save)
        save()
        host?.release()
        for (t in tabs) t.web?.destroy()
        super.onDestroy()
    }

    /**
     * The keyboard a page brought up, away. A page shows it through this process's embedded window's insets
     * controller, which only that controller hides again (the screen's window, which gets the Back key, can't).
     */
    private fun hideKeyboard() {
        pages.windowInsetsController?.hide(WindowInsets.Type.ime())
    }

    private fun tell(what: Int) {
        try {
            client?.send(Message.obtain(null, what))
        } catch (e: Exception) {
            Log.d(TAG, "tell $what: $e")
        }
    }

    /** Slide in the second screen at the browser (an agent asked to show the user something). */
    fun show() = tell(MSG_SHOW)

    private fun build(ctx: Context) {
        ui = ctx
        WebView.setWebContentsDebuggingEnabled(true) // the DevTools socket BrowserLink relays to the box
        // Back, while a page has focus, is the screen's to handle (a page's own history first, then closing);
        // with the keyboard up it only puts the keyboard away.
        pages = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e)
                if (e.action != KeyEvent.ACTION_UP || e.isCanceled) return true
                if (rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) hideKeyboard()
                else tell(MSG_BACK_KEY)
                return true
            }
        }
        pages.setBackgroundColor(BG)
        progress = View(ctx).apply {
            setBackgroundColor(ACCENT)
            pivotX = 0f
            visibility = View.GONE
        }
        pages.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (2.5f * resources.displayMetrics.density).roundToInt()))
        if (!restore()) select(newTab(null, HOME))
        headless()
    }

    /**
     * Until the screen first embeds the pages (it may not be opened for a long time: after a restart the browser
     * comes up with the handle), they're laid out in a window of their own that isn't shown, at the display's size,
     * so agents can drive them: a page that was never laid out has no size, and so no screenshots.
     */
    private fun headless() {
        if (host != null) return
        try {
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            val bounds = getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
            val h = SurfaceControlViewHost(this, display, null as IBinder?)
            h.setView(pages, bounds.width(), bounds.height())
            host = h
        } catch (e: Exception) {
            Log.w(TAG, "headless: $e")
        }
    }

    // Tabs and groups.

    /** A new live tab in group (null: none), loading url (null: a popup, whose page its opener supplies). */
    fun newTab(group: String?, url: String?): Tab {
        val tab = Tab("t${nextId++}", group)
        tabs.add(tab)
        live(tab)
        if (url != null) load(tab, url)
        trim()
        publish()
        return tab
    }

    /** A new tab for an agent session in group groupId; a new group (titled title) if that group isn't here. */
    fun agentTab(groupId: String, title: String): Tab {
        var g = group(groupId)
        if (g == null) {
            // "o": Otto's own groups, apart from those the relay may still remember from older apps ("g", "sg").
            g = Group("o${nextId++}", title, AGENT)
            groups.add(g)
        }
        woken.add(g.id) // asked for by someone working in it
        return newTab(g.id, "about:blank")
    }

    fun group(id: String?): Group? = id?.let { i -> groups.firstOrNull { it.id == i } }

    /** Bring a group's tabs to life and keep them so until idle(); the tabs that are in it. */
    fun wake(groupId: String): List<Tab> {
        if (group(groupId) == null) return emptyList()
        woken.add(groupId)
        val now = System.currentTimeMillis()
        val inGroup = tabs.filter { it.group == groupId }
        for (t in inGroup) {
            t.used = now
            if (t.web == null) live(t)
        }
        publish()
        return inGroup
    }

    /** No one is working in the group any more: its tabs may go dormant like any others. */
    fun idle(groupId: String) {
        woken.remove(groupId)
        trim()
    }

    /** Close every tab of a group, and the group. */
    fun closeGroup(groupId: String) {
        for (t in tabs.filter { it.group == groupId }) close(t)
        groups.removeAll { it.id == groupId }
        woken.remove(groupId)
        publish()
    }

    /** Give a tab a WebView: a new one, at its saved history or page. Under the others. */
    private fun live(tab: Tab) {
        val web = WebView(ui)
        tab.web = web
        tab.desktop = false
        tab.wide = null
        tab.progress = 100
        setUp(tab, web)
        // Under the others: tabs are stacked, all drawn, as a page that isn't drawn stalls whoever drives it
        // (agents' screenshots and clicks wait for frames).
        pages.addView(web, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        applyDesktop(tab, wantsDesktop(tab.url))
        val back = tab.state?.let { web.restoreState(it) } != null
        tab.state = null
        if (!back && tab.url.isNotEmpty()) web.loadUrl(tab.url)
        BrowserLink.identify(tab)
    }

    /** Put a tab to sleep: keep its history, let its WebView go. */
    private fun discard(tab: Tab) {
        val web = tab.web ?: return
        val state = Bundle()
        if (web.saveState(state) != null) tab.state = state
        drop(tab)
    }

    /** Let a tab's WebView go (its DevTools target goes with it). */
    private fun drop(tab: Tab) {
        val web = tab.web ?: return
        pages.removeView(web)
        web.destroy()
        tab.web = null
        tab.wide = null
        tab.targetId?.let { BrowserLink.gone(it) }
        tab.targetId = null
    }

    /** At most MAX_LIVE live tabs, besides the current one and woken groups': the least recently used sleep. */
    private fun trim() {
        val spare = tabs.filter { it.web != null && it !== current && (it.group == null || it.group !in woken) }.sortedBy { it.used }
        for (i in 0 until spare.size - (MAX_LIVE - 1)) discard(spare[i])
    }

    // Saving and reopening the tabs.

    private fun saved() = File(filesDir, "browser-tabs.json")

    private val save = Runnable { save() }

    /** Save the tabs soon (they change in bursts while pages load). */
    private fun saveSoon() {
        main.removeCallbacks(save)
        main.postDelayed(save, 1000)
    }

    private fun save() {
        try {
            val list = JSONArray()
            for (t in tabs) {
                val o = JSONObject().put("id", t.id).put("group", t.group ?: JSONObject.NULL)
                    .put("url", t.url).put("title", t.title).put("used", t.used)
                var state = t.state
                t.web?.let { web -> state = Bundle().takeIf { web.saveState(it) != null } }
                state?.let { o.put("history", encode(it)) }
                list.put(o)
            }
            val gs = JSONArray()
            for (g in groups) gs.put(JSONObject().put("id", g.id).put("title", g.title).put("owner", g.owner ?: JSONObject.NULL))
            val all = JSONObject().put("tabs", list).put("groups", gs).put("next", nextId)
                .put("current", current?.id ?: JSONObject.NULL)
            val f = saved()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(all.toString())
            if (!tmp.renameTo(f)) Log.w(TAG, "save: rename failed")
        } catch (e: Exception) {
            Log.w(TAG, "save", e)
        }
    }

    /** Reopen the saved tabs, dormant (the current one live); false if there were none. */
    private fun restore(): Boolean {
        try {
            val f = saved()
            if (!f.exists()) return false
            val all = JSONObject(f.readText())
            nextId = max(nextId, all.optInt("next", 1))
            val gs = all.optJSONArray("groups")
            for (i in 0 until (gs?.length() ?: 0)) {
                val o = gs!!.getJSONObject(i)
                groups.add(Group(o.getString("id"), o.optString("title"), if (o.isNull("owner")) null else o.optString("owner")))
            }
            val old = System.currentTimeMillis() - KEEP_MS
            val cur = if (all.isNull("current")) null else all.optString("current")
            val list = all.getJSONArray("tabs")
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                val id = o.optString("id")
                if (id.isEmpty() || o.optLong("used") < old && id != cur) continue // unused for a month
                val g = if (o.isNull("group")) null else o.optString("group").ifEmpty { null }
                val t = Tab(id, if (group(g) == null) null else g)
                t.url = o.optString("url")
                t.title = o.optString("title")
                t.used = o.optLong("used", t.used)
                if (o.has("history")) {
                    try {
                        t.state = decode(o.getString("history"))
                    } catch (e: Exception) {
                        Log.w(TAG, "restore history", e)
                    }
                }
                tabs.add(t)
            }
            groups.removeAll { g -> tabs.none { it.group == g.id } } // empty groups go
            if (tabs.isEmpty()) return false
            select(byId(cur) ?: tabs.last())
            return true
        } catch (e: Exception) {
            Log.w(TAG, "restore", e)
            tabs.clear()
            groups.clear()
            return false
        }
    }

    /** A tab's page process died: give it a new WebView at its page (the old one can't be used again). */
    private fun reopen(tab: Tab) {
        if (tab !in tabs || tab.web == null) return
        drop(tab)
        tab.state = null
        if (tab === current || tab.group != null && tab.group in woken) {
            live(tab)
            if (tab === current) select(tab)
        }
        publish()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUp(tab: Tab, web: WebView) {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        // Pass as Chrome (sites, Google sign-in in particular, turn WebViews away).
        if (mobileAgent == null) {
            val mobile = s.userAgentString.replace("; wv)", ")").replaceFirst(Regex(" Version/[\\d.]+"), "")
            mobileAgent = mobile
            // What Chrome sends for "Desktop site": Linux on a computer, no "Mobile".
            desktopAgent = mobile.replaceFirst(Regex("\\([^)]*\\)"), "(X11; Linux x86_64)").replace(" Mobile ", " ")
        }
        s.userAgentString = mobileAgent
        s.builtInZoomControls = true // pinch to zoom (desktop sites need it)
        s.displayZoomControls = false
        // Popups ("Sign in with Google" and the like) open as tabs and can report back to their opener.
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            // Entering a site shown the other way (mobile or desktop): switch, then load it afresh.
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                if (!r.isForMainFrame || r.method != "GET") return false
                val url = r.url.toString()
                if (wantsDesktop(url) == tab.desktop) return false
                applyDesktop(tab, !tab.desktop)
                v.loadUrl(url)
                return true
            }

            override fun onPageStarted(v: WebView, url: String?, icon: Bitmap?) {
                if (url != null && !url.startsWith("about:")) tab.url = url
                publish()
            }

            override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "page process gone (${if (detail.didCrash()) "crashed" else "ended by Android"}): reopening tab ${tab.id}")
                main.post { reopen(tab) }
                return true // handled: the browser stays up
            }

            override fun doUpdateVisitedHistory(v: WebView, url: String?, reload: Boolean) {
                if (url != null && !url.startsWith("about:")) tab.url = url
                tab.used = System.currentTimeMillis()
                if (!isAgents(tab)) history.visit(url, reload)
                publish()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, percent: Int) {
                tab.progress = percent
                if (tab === current) showProgress()
            }

            override fun onReceivedTitle(v: WebView, title: String?) {
                tab.title = title ?: ""
                if (!isAgents(tab)) history.title(v.url, tab.title)
                publish()
            }

            override fun onCreateWindow(v: WebView, dialog: Boolean, gesture: Boolean, result: Message): Boolean {
                val popup = newTab(tab.group, null)
                if (tab === current) select(popup)
                (result.obj as WebView.WebViewTransport).webView = popup.web
                result.sendToTarget()
                return true
            }

            override fun onCloseWindow(w: WebView) {
                close(tab)
            }
        }
    }

    /** A tab in an agent session's group (its pages stay out of the user's history). */
    private fun isAgents(tab: Tab) = group(tab.group)?.owner == AGENT

    fun select(tab: Tab) {
        current = tab
        tab.used = System.currentTimeMillis()
        if (tab.web == null) live(tab)
        tab.web?.bringToFront()
        progress.bringToFront()
        showProgress()
        trim()
        publish()
    }

    fun close(tab: Tab) {
        val i = tabs.indexOf(tab)
        if (i < 0) return
        tabs.removeAt(i)
        if (tab.web != null) drop(tab)
        val g = tab.group
        if (g != null && tabs.none { it.group == g }) {
            groups.removeAll { it.id == g } // its last tab
            woken.remove(g)
        }
        if (tab === current) {
            current = null
            if (tabs.isEmpty()) select(newTab(null, HOME)) else select(tabs[min(i, tabs.size - 1)])
        } else publish()
    }

    private fun byId(id: String?): Tab? = id?.let { i -> tabs.firstOrNull { it.id == i } }

    fun byTarget(targetId: String): Tab? = tabs.firstOrNull { it.targetId == targetId }

    private fun showProgress() {
        val p = current?.progress ?: 100
        if (p >= 100) {
            if (progress.visibility == View.VISIBLE) {
                progress.animate().scaleX(1f).setDuration(120).withEndAction { progress.visibility = View.GONE }.start()
            }
            return
        }
        if (progress.visibility != View.VISIBLE) {
            progress.scaleX = 0.05f
            progress.visibility = View.VISIBLE
        }
        progress.animate().cancel()
        progress.animate().scaleX(max(0.05f, p / 100f)).setDuration(150).start()
    }

    /** Tell Screen what the tabs are (it draws the tab block and the tab menu), and save them soon. */
    private fun publish() {
        saveSoon()
        val client = client ?: return
        try {
            val list = JSONArray()
            for (t in tabs) list.put(JSONObject().put("id", t.id).put("group", t.group ?: "").put("title", t.title).put("url", t.url))
            val gs = JSONArray()
            for (g in groups) gs.put(JSONObject().put("id", g.id).put("title", g.title).put("owner", g.owner ?: ""))
            val cur = current
            val state = JSONObject().put("tabs", list).put("groups", gs)
                .put("current", cur?.id ?: "")
                .put("canGoBack", cur?.web?.canGoBack() == true)
                .put("desktop", cur?.desktop == true)
            val m = Message.obtain(null, MSG_TABS)
            m.data.putString("json", state.toString())
            client.send(m)
        } catch (e: Exception) {
            Log.d(TAG, "publish: $e")
        }
    }

    /** What the address bar offers for a query: open tabs (to switch to), then pages from history. */
    private fun suggest(query: String): JSONArray {
        val out = JSONArray()
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return out
        val words = q.split(Regex("\\s+"))
        val open = HashSet<String>()
        for (t in tabs) {
            open.add(t.url)
            val hay = "${t.url} ${t.title}".lowercase(Locale.ROOT)
            val all = t.url.isNotEmpty() && t !== current && words.all { hay.contains(it) }
            if (all && out.length() < 3) out.put(JSONObject().put("tab", t.id).put("title", t.title).put("url", t.url))
        }
        for ((url, title) in history.search(q, 12)) {
            if (out.length() >= 8) break
            if (url !in open) out.put(JSONObject().put("title", title).put("url", url))
        }
        return out
    }

    /** Load what was typed (a URL, a bare host, or a search) in the current tab. */
    fun go(input: String?) {
        val q = input?.trim().orEmpty()
        if (q.isEmpty()) return
        val url = when {
            q.matches(Regex("(?i)^[a-z][a-z0-9+.-]*://.*")) || q.startsWith("about:") -> q
            !q.contains(" ") && q.matches(Regex("^[^/]+\\.[a-zA-Z]{2,}(/.*)?$|^localhost(:\\d+)?(/.*)?$")) -> "https://$q"
            else -> "https://www.google.com/search?q=" + Uri.encode(q)
        }
        val cur = current
        if (cur == null) select(newTab(null, url))
        else {
            if (cur.web == null) live(cur)
            load(cur, url)
        }
    }

    /** Load url in tab, as that site is shown (mobile or desktop). */
    private fun load(tab: Tab, url: String) {
        applyDesktop(tab, wantsDesktop(url))
        tab.web?.loadUrl(url)
    }

    private fun wantsDesktop(url: String?): Boolean {
        val site = site(url)
        return site.isNotEmpty() && desktopSites.getBoolean(site, false)
    }

    /** The user's choice for the tab's site: remember it, and reload the page that way. */
    private fun setDesktop(tab: Tab, on: Boolean) {
        val web = tab.web ?: return
        val site = site(web.url)
        if (site.isEmpty()) return
        if (on) desktopSites.edit().putBoolean(site, true).apply() else desktopSites.edit().remove(site).apply()
        applyDesktop(tab, on)
        web.reload()
        publish()
    }

    /** Show tab's pages as on a computer (or not): the user agent, and a wide viewport sites can't narrow. */
    private fun applyDesktop(tab: Tab, on: Boolean) {
        if (tab.desktop == on) return
        val web = tab.web ?: return
        tab.desktop = on
        val s = web.settings
        s.userAgentString = if (on) desktopAgent else mobileAgent
        s.useWideViewPort = on
        s.loadWithOverviewMode = on
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            val ua = WebSettingsCompat.getUserAgentMetadata(s)
            WebSettingsCompat.setUserAgentMetadata(s, UserAgentMetadata.Builder(ua)
                .setMobile(!on).setPlatform(if (on) "Linux" else "Android").setPlatformVersion(if (on) "" else ua.platformVersion)
                .setModel(if (on) "" else ua.model).build())
        }
        if (on && tab.wide == null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // Pages ask for width=device-width; lay them out at a laptop's width instead. Chrome's desktop mode uses
            // 980px, which still squeezes dashboards built for 1280px and up. Pages with no viewport tag get one,
            // or WebView's own 980px default would apply.
            tab.wide = WebViewCompat.addDocumentStartJavaScript(web,
                "(() => { const W = 'width=$DESKTOP_WIDTH';" +
                    " const fix = m => m.name === 'viewport' && m.setAttribute('content', W);" +
                    " new MutationObserver(rs => { for (const r of rs) for (const n of r.addedNodes) if (n.tagName === 'META') fix(n); })" +
                    ".observe(document, { childList: true, subtree: true });" +
                    " document.querySelectorAll('meta').forEach(fix);" +
                    " document.addEventListener('DOMContentLoaded', () => { if (!document.querySelector('meta[name=viewport]')) {" +
                    " const m = document.createElement('meta'); m.name = 'viewport'; m.content = W; document.head.appendChild(m); } }); })();",
                setOf("*"))
        } else if (!on) {
            tab.wide?.remove()
            tab.wide = null
        }
    }

    companion object {
        const val MSG_ATTACH = 1
        const val MSG_RESIZE = 2
        const val MSG_LOAD = 3
        const val MSG_SHOW = 4
        const val MSG_TABS = 5
        const val MSG_SELECT = 6
        const val MSG_CLOSE = 7
        const val MSG_NEW = 8
        const val MSG_RELOAD = 9
        const val MSG_BACK = 10
        const val MSG_SUGGEST = 11
        const val MSG_SUGGESTIONS = 12
        const val MSG_DESKTOP = 13
        const val MSG_ACTIVE = 14
        const val MSG_BACK_KEY = 15
        const val MSG_HIDE_KEYBOARD = 16
        const val MSG_CLIENT = 17

        /** Start command: keep it in the foreground (OverlayService, while the handle is on). */
        const val ACTION_KEEP = "keep"

        const val AGENT = "agent"
        private const val TAG = "BrowserService"
        // The second screen's colours (always dark): AMOLED black and steel. The icon's red stays on the icon.
        val BG = Color.parseColor("#000000")
        val FIELD = Color.parseColor("#17171a")
        val LINE = Color.parseColor("#2e2e33")
        val TEXT = Color.parseColor("#edeef0")
        val MUTED = Color.parseColor("#8e949c")
        val ACCENT = Color.parseColor("#aeb4bc") // the one primary button's fill, and marks
        val ON_ACCENT = Color.parseColor("#0b0c0e") // text and icons on ACCENT
        private const val HOME = "https://www.google.com"
        private const val MAX_LIVE = 5 // live WebViews, besides those of woken groups
        private const val KEEP_MS = 30L * 24 * 3600 * 1000 // a tab unused this long is dropped
        /** Width desktop-mode pages are laid out at, in CSS px. */
        private const val DESKTOP_WIDTH = 1280

        var instance: BrowserService? = null // for BrowserLink, in this process
            private set

        private fun site(url: String?): String {
            val h = url?.let { Uri.parse(it).host } ?: return ""
            return h.lowercase(Locale.ROOT).replaceFirst(Regex("^(www|m|mobile)\\."), "")
        }

        private fun encode(state: Bundle): String {
            val parcel = Parcel.obtain()
            state.writeToParcel(parcel, 0)
            val s = Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)
            parcel.recycle()
            return s
        }

        private fun decode(s: String): Bundle {
            val bytes = Base64.decode(s, Base64.NO_WRAP)
            val parcel = Parcel.obtain()
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            val b = Bundle.CREATOR.createFromParcel(parcel)
            parcel.recycle()
            return b
        }
    }
}
