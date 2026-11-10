package io.github.otto

import android.widget.Toast
import android.content.ClipData
import android.content.ClipboardManager
import android.text.style.ForegroundColorSpan
import android.text.Spanned
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import android.text.SpannableStringBuilder
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceControlViewHost
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import io.github.otto.ui.ArtifactItem
import io.github.otto.ui.ArtifactsModel
import io.github.otto.ui.ArtifactsScreen
import io.github.otto.ui.ClipboardModel
import io.github.otto.ui.ClipboardScreen
import io.github.otto.ui.SchedulesModel
import io.github.otto.ui.SchedulesScreen
import io.github.otto.ui.ComposeHost
import io.github.otto.ui.DevboxModel
import io.github.otto.ui.DevboxScreen
import io.github.otto.ui.FilesModel
import io.github.otto.ui.FilesScreen
import io.github.otto.ui.NativeBack
import io.github.otto.ui.ScreenOps
import io.github.otto.ui.Target
import io.github.otto.ui.Viewer
import io.github.otto.ui.ViewerBarState
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The second screen, over every app: a full screen that fades in and out at a tap on the edge handle
 * (OverlayService). Files is a file manager, the browser a browser.
 *
 * The top bar: the section's name as a title (tap it to switch sections), then, for the browser, the tab block,
 * whose menu holds the address bar and the tabs, as BrowserService reports them. A pull down on the bar reloads;
 * Back walks back (the viewer, the section's history) and then closes. Nothing on the screen itself swipes it away:
 * the handle, Back and Home put it away.
 *
 * Sections: Files, Artifacts, Clipboard, Schedules and Devbox, native (Compose, io.github.otto.ui); Desktop and Terminal, the app's own
 * web UI (AppWeb, a WebView each, under the bar); and Browser, BrowserService's views drawn here from the
 * ":browser" process through a SurfaceView (SurfaceControlViewHost), the browser agents on the box drive through
 * BrowserLink. An artifact or a file opens in the viewer, native too (ui/Viewer), over the whole screen; HTML in
 * the web viewer (AppWeb).
 */
class Screen(private val ctx: Context, private val host: Host) : ServiceConnection {
    interface Host {
        /** Where the screen is now, 0 (out) to 1 (covering the display): on every frame it moves. */
        fun moved(position: Float)

        /** It came to rest open, or closed. */
        fun settled(open: Boolean)
    }

    private val root: Root = Root(ctx) // the overlay window's view
    private val scrim = View(ctx) // dims the app beneath as the screen comes in
    private val shutter = FrameLayout(ctx) // what moves
    private val panel = LinearLayout(ctx)
    private val bar = LinearLayout(ctx)
    private val tabBlock = LinearLayout(ctx)
    private val more: ImageView // the browser's options (⋮)
    private val home: ImageView // an open viewer's section, at home (the Artifacts list, the file's folder)
    private val filesButtons = LinearLayout(ctx) // Files: up a folder, home, hidden files
    private val filesUp: ImageView
    private val filesHome: ImageView
    private val filesHidden: ImageView
    private val titleText: TextView
    private val titleIcon: ImageView // the section, as its icon: what the bar shows (the name is in the menu)
    private var viewerBar: ViewerBarState? = null // the open viewer's title and actions, in the bar
    private lateinit var tabChevron: ImageView
    private val tabTitle: TextView
    private val tabHost: TextView
    private val tabMark: TextView
    private val tabCount: TextView
    private val tabsButton = FrameLayout(ctx) // Browser: the tabs, as their count in a box; a tap lists them
    private val tabsCount: TextView
    private val findRow = LinearLayout(ctx) // Browser: find in page, in place of the address while it's on
    private val findField = EditText(ctx)
    private val findCount: TextView
    private var finding = false
    private val surface = SurfaceView(ctx)
    private val compose = ComposeHost() // the native sections' lifecycle: the screen's
    private val ops = Ops()
    private val filesModel = FilesModel(ctx) { up, home, hidden -> filesBar(up, home, hidden) }
    private val artifactsModel = ArtifactsModel(ctx)
    private val devboxModel = DevboxModel(ctx)
    private val clipboardModel = ClipboardModel(ctx)
    private val schedulesModel = SchedulesModel(ctx)
    private val files: ComposeView
    private val artifacts: ComposeView
    private val desktop: WebView
    private val terminal: WebView
    private val devbox: ComposeView
    private val clipboard: ComposeView
    private val schedules: ComposeView
    private val body = FrameLayout(ctx)
    private var viewer: View? = null // an artifact or file opened from Files or Artifacts, over the whole second screen
    private var viewerTarget: Target? = null
    private var viewerHint: ArtifactItem? = null
    // Back follows how things were reached; ← in the viewer's bar is Up, to its section's home.
    private var shown: Entry? = null // the viewer on screen
    private val under = ArrayDeque<Entry>() // the viewers it was opened from, inside Otto (the last on top)
    private var rootViewer = false // the bottom viewer came from outside Otto (a link): Back there hides the screen
    private var browserOver: String? = null // the browser was opened from a viewer (still in under), at this page
    /** A viewer left for another section, kept as it was: picking its section again brings it back. */
    private class Parked(val view: View, val section: Int, val target: Target?, val hint: ArtifactItem?, val bar: ViewerBarState?, val shown: Entry?, val under: List<Entry>, val rootViewer: Boolean)
    private var parked: Parked? = null
    private var tabState = JSONObject() // BrowserService's TABS
    private var menu: Dialog? = null // a dialog, not a PopupWindow: a popup can't show text selection's toolbar
    private var tabList: LinearLayout? = null // the open tab menu's list, refreshed as the tabs change
    private var editing = false // the address is being edited: its field in the bar, suggestions over the page
    private val addressRow = LinearLayout(ctx) // Browser: the address field, in the bar while editing
    private val addressField = EditText(ctx)
    private val editPanel = ScrollView(ctx) // Browser, while editing: this page, then suggestions, over the page
    private val editCurrent = LinearLayout(ctx) // the page open now (copy, share), while nothing is typed
    private val editList = LinearLayout(ctx)
    private var typed: String? = null // what's typed in the tab menu's address bar, while it's a query (then the list suggests)
    private var suggestSeq = 0
    private var tab = ARTIFACTS
    private val replies = Messenger(Handler(Looper.getMainLooper(), ::onReply))
    private var service: Messenger? = null
    private var attached = false
    private var bound = false

    private var position = 0f // 0 = out, 1 = covering the display
    private var wasOpen = false
    private var settle: ValueAnimator? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var tracking = false
    private var pulling = false
    private val pull: ImageView // the reload mark a pull down on the top bar brings down
    private var downX = 0f
    private var downY = 0f
    private var topInset = 0
    private var refocus = false // the window's next focus goes on to the section in view (see Root.onWindowFocusChanged)
    private var terminalState: JSONObject? = null
    private var terminalList: LinearLayout? = null

    init {
        scrim.setBackgroundColor(0x80000000.toInt())
        scrim.alpha = 0f
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        shutter.elevation = dp(24).toFloat()
        root.addView(shutter, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(BrowserService.BG)
        panel.isClickable = true // nothing falls through to the scrim
        shutter.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Section contents, one shown at a time; the web sections run under the top bar.
        panel.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        compose.install(root)
        // Files and Artifacts: native, below the bar; a file or an artifact opens in the viewer.
        fun underBar() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { topMargin = dp(BAR_DP) }
        files = compose.view(ctx, ops) { FilesScreen(filesModel) }
        body.addView(files, underBar())
        artifacts = compose.view(ctx, ops) { ArtifactsScreen(artifactsModel) }
        body.addView(artifacts, underBar())
        // Desktop: the app's own VNC view (web/desktop.js); connected when first opened.
        desktop = AppWeb.create(ctx, object : AppWeb.Ops {})
        body.addView(desktop, underBar())
        // Terminal: the box's tmux windows (web/terminal.js); connected when first opened.
        terminal = AppWeb.create(ctx, object : AppWeb.Ops {
            override fun terminalBar(state: JSONObject?) {
                terminalState = state
                if (tab == TERMINAL && viewer == null) showTerminals()
            }
        })
        body.addView(terminal, underBar())
        // Devbox: the box's status, metrics, start and stop (native, from the box's control panel).
        devbox = compose.view(ctx, ops) { DevboxScreen(devboxModel) }
        body.addView(devbox, underBar())
        // Clipboard: the clips sent to the box from the lens and the viewer (native, from the relay's box API).
        clipboard = compose.view(ctx, ops) { ClipboardScreen(clipboardModel) }
        body.addView(clipboard, underBar())
        // Schedules: the box's schedules and their runs (native, from otto-bots through the relay's box API).
        schedules = compose.view(ctx, ops) { SchedulesScreen(schedulesModel) }
        body.addView(schedules, underBar())

        // Browser: BrowserService's views from the ":browser" process.
        // Typing in the browser needs window focus, which only the host can grant the embedded views: a focused
        // SurfaceView passes it on (see focusTab()).
        // Above the window, not punched through below it: input goes to the topmost window under the finger.
        surface.setZOrderOnTop(true)
        surface.isFocusable = true
        surface.isFocusableInTouchMode = true
        body.addView(surface, underBar())
        surface.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) attachOrResize()
        }
        // Editing the address: the page open now, then what was visited last (or what the query suggests), over the
        // page (the surface steps aside meanwhile: it's drawn on top of the window).
        val editBox = LinearLayout(ctx)
        editBox.orientation = LinearLayout.VERTICAL
        editBox.setPadding(dp(8), dp(6), dp(8), dp(8))
        editCurrent.orientation = LinearLayout.VERTICAL
        editBox.addView(editCurrent)
        editList.orientation = LinearLayout.VERTICAL
        editBox.addView(editList)
        editPanel.addView(editBox)
        editPanel.setBackgroundColor(BrowserService.BG)
        editPanel.visibility = View.GONE
        body.addView(editPanel, underBar())
        // The top bar, over the sections: the title, then (Browser) the tab block.
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(BAR_START), 0, dp(8), 0)
        bar.setBackgroundColor(BrowserService.BG)
        val title = LinearLayout(ctx)
        title.gravity = Gravity.CENTER_VERTICAL
        title.setPadding(dp(TITLE_PAD), 0, dp(TITLE_PAD), 0)
        title.background = pressable(dp(8))
        titleText = titleLabel()
        titleText.visibility = View.GONE
        title.addView(titleText)
        titleIcon = icon(ICONS[ARTIFACTS], BrowserService.TEXT)
        title.addView(titleIcon, LinearLayout.LayoutParams(dp(22), dp(22)))
        title.addView(icon(R.drawable.ccm_chevron_down, BrowserService.MUTED), LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginStart = dp(5) })
        title.contentDescription = "Switch section"
        onPress(title) { sectionMenu() }
        bar.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))

        tabBlock.gravity = Gravity.CENTER_VERTICAL
        tabBlock.setPadding(dp(11), 0, dp(9), 0)
        tabBlock.background = rounded(BrowserService.FIELD, dp(9))
        tabBlock.contentDescription = "Tabs"
        tabMark = label("✳", 13)
        tabMark.setTextColor(BrowserService.ACCENT)
        tabMark.setPadding(0, 0, dp(7), 0)
        tabBlock.addView(tabMark)
        val lines = LinearLayout(ctx)
        lines.orientation = LinearLayout.VERTICAL
        tabTitle = line(13f, BrowserService.TEXT)
        tabHost = line(10.5f, BrowserService.MUTED)
        lines.addView(tabTitle)
        lines.addView(tabHost)
        tabBlock.addView(lines, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabCount = label("1", 11)
        tabCount.setTextColor(BrowserService.MUTED)
        tabCount.background = GradientDrawable().apply {
            cornerRadius = dp(5).toFloat()
            setStroke(max(1, dp(1)), BrowserService.MUTED)
        }
        tabCount.setPadding(dp(4), 0, dp(4), 0)
        tabCount.minWidth = dp(18)
        tabBlock.addView(tabCount, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(17)).apply { marginStart = dp(8) })
        tabChevron = icon(R.drawable.ccm_chevron_down, BrowserService.MUTED)
        tabBlock.addView(tabChevron, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginStart = dp(6) })
        // The browser's address and a viewer's tab open on a tap (as the finger lifts), so a pull down on them reloads.
        onPress(tabBlock, tapOnly = { viewer != null || tab == BROWSER }) {
            when {
                viewer != null -> viewerBar?.onLabel?.invoke()
                tab == TERMINAL -> terminalMenu()
                else -> startEdit()
            }
        }
        bar.addView(tabBlock, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(8) })
        // Browser: the tabs as their count in a box (a tap lists them), after the address.
        tabsCount = label("1", 12)
        tabsCount.setTextColor(BrowserService.TEXT)
        tabsCount.gravity = Gravity.CENTER
        tabsCount.typeface = Typeface.DEFAULT_BOLD
        tabsCount.background = GradientDrawable().apply {
            cornerRadius = dp(5).toFloat()
            setStroke(max(1, dp(3) / 2), BrowserService.TEXT)
        }
        tabsButton.addView(tabsCount, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        tabsButton.background = pressable(dp(20))
        tabsButton.contentDescription = "Tabs"
        onPress(tabsButton) { tabsMenu() }
        bar.addView(tabsButton, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(2) })
        // Browser: find in page: the query, "2 of 5", previous, next, done.
        findRow.gravity = Gravity.CENTER_VERTICAL
        findRow.visibility = View.GONE
        findField.isSingleLine = true
        findField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        findField.setTextColor(BrowserService.TEXT)
        findField.setHintTextColor(BrowserService.MUTED)
        findField.hint = "Find in page"
        findField.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_FULLSCREEN
        findField.inputType = EditorInfo.TYPE_CLASS_TEXT
        findField.setPadding(dp(11), 0, dp(6), 0)
        findField.background = null
        findField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun afterTextChanged(e: Editable) {
                if (!finding) return
                findCount.text = ""
                send2(BrowserService.MSG_FIND, "q" to e.toString(), "dir" to "next")
            }
        })
        findField.setOnEditorActionListener { _, _, _ ->
            send2(BrowserService.MSG_FIND, "q" to findField.text.toString(), "dir" to "next")
            true
        }
        val findBox = LinearLayout(ctx)
        findBox.gravity = Gravity.CENTER_VERTICAL
        findBox.background = rounded(BrowserService.FIELD, dp(9))
        findBox.addView(findField, LinearLayout.LayoutParams(0, dp(40), 1f))
        findCount = line(12f, BrowserService.MUTED)
        findCount.setPadding(0, 0, dp(10), 0)
        findBox.addView(findCount)
        findRow.addView(findBox, LinearLayout.LayoutParams(0, dp(40), 1f))
        fun findButton(res: Int, label: String, act: () -> Unit) = icon(res, BrowserService.TEXT).also {
            it.scaleType = ImageView.ScaleType.CENTER
            it.background = pressable(dp(20))
            it.contentDescription = label
            it.setOnClickListener { act() }
            findRow.addView(it, LinearLayout.LayoutParams(dp(36), dp(40)).apply { marginStart = dp(2) })
        }
        findButton(R.drawable.ccm_chevron_up, "Previous match") { send2(BrowserService.MSG_FIND, "q" to findField.text.toString(), "dir" to "prev") }
        findButton(R.drawable.ccm_chevron_next, "Next match") { send2(BrowserService.MSG_FIND, "q" to findField.text.toString(), "dir" to "next") }
        findButton(R.drawable.ccm_close, "Done") { endFind() }
        bar.addView(findRow, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(8) })
        // Browser: the address, edited in place: the field where the address was, × to empty it.
        addressRow.gravity = Gravity.CENTER_VERTICAL
        addressRow.visibility = View.GONE
        addressRow.background = rounded(BrowserService.FIELD, dp(9))
        addressField.isSingleLine = true
        addressField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        addressField.setTextColor(BrowserService.TEXT)
        addressField.setHintTextColor(BrowserService.MUTED)
        addressField.hint = "Search or type a URL"
        addressField.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_FULLSCREEN
        addressField.inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_URI
        addressField.setPadding(dp(11), 0, dp(4), 0)
        addressField.background = null
        addressField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun afterTextChanged(e: Editable) {
                if (!editing) return
                val q = e.toString()
                typed = if (q.isBlank() || q == currentTab()?.optString("url")) null else q
                editCurrent.visibility = if (typed == null) View.VISIBLE else View.GONE
                suggest(typed ?: "")
            }
        })
        addressField.setOnEditorActionListener { _, _, _ ->
            val q = addressField.text.toString()
            endEdit()
            send(BrowserService.MSG_LOAD, "url", q)
            true
        }
        addressRow.addView(addressField, LinearLayout.LayoutParams(0, dp(40), 1f))
        val clear = icon(R.drawable.ccm_close, BrowserService.MUTED)
        clear.scaleType = ImageView.ScaleType.CENTER
        clear.background = pressable(dp(18))
        clear.contentDescription = "Clear"
        clear.setOnClickListener { addressField.setText("") }
        addressRow.addView(clear, LinearLayout.LayoutParams(dp(36), dp(40)))
        bar.addView(addressRow, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(8) })
        home = icon(R.drawable.ccm_home, BrowserService.TEXT)
        home.scaleType = ImageView.ScaleType.CENTER
        home.background = pressable(dp(20))
        home.contentDescription = "Home"
        home.setOnClickListener { up() }
        bar.addView(home, LinearLayout.LayoutParams(dp(36), dp(40)).apply { marginStart = dp(2) })
        more = icon(R.drawable.ccm_more, BrowserService.TEXT)
        more.scaleType = ImageView.ScaleType.CENTER
        more.background = pressable(dp(20))
        more.contentDescription = "Options"
        onPress(more) { if (viewer != null) viewerMenu() else optionsMenu() }
        bar.addView(more, LinearLayout.LayoutParams(dp(36), dp(40)).apply { marginStart = dp(2) })
        // Files' buttons, on the right: each runs the page's ccmFiles(action). They keep their places (Up and Home dim
        // when they don't apply) so nothing moves under a finger; the page says what applies (AppWeb op "filesBar").
        filesButtons.gravity = Gravity.CENTER_VERTICAL or Gravity.END
        filesUp = filesButton(R.drawable.ccm_up, "Up one folder", "up")
        filesHome = filesButton(R.drawable.ccm_home, "Home folder", "home")
        filesHidden = filesButton(R.drawable.ccm_eye, "Show hidden files", "hidden")
        filesBar(false, false, false)
        bar.addView(filesButtons, LinearLayout.LayoutParams(0, dp(40), 1f))
        body.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(BAR_DP)))
        showTabs()
        select(ARTIFACTS)

        // The window lies under the system bars and the keyboard; the screen keeps its content clear of them.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            panel.setPadding(b.left, b.top, b.right, b.bottom)
            topInset = b.top
            WindowInsets.CONSUMED
        }

        pull = icon(R.drawable.ccm_reload, BrowserService.TEXT)
        pull.scaleType = ImageView.ScaleType.CENTER
        pull.background = rounded(BrowserService.LINE, dp(20)) // lighter than the bar's blocks
        pull.elevation = dp(30).toFloat()
        pull.alpha = 0f
        root.addView(pull, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        root.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) apply() }
        // Each time it lands in a window (ScreenActivity's, anew each time it comes in), the browser's pages are
        // embedded in it again: the surface, and the window it belongs to, are new.
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                attached = false
                root.post { attachOrResize() }
            }

            override fun onViewDetachedFromWindow(v: View) {
                attached = false
            }
        })
        apply()

        bind()
        current = this
    }

    fun view(): View = root

    /** Into ScreenActivity's window, as it comes in. */
    fun attach(parent: ViewGroup) {
        if (root.parent === parent) return
        (root.parent as? ViewGroup)?.removeView(root)
        parent.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Out of it, as it goes. The browser's pages stay alive in BrowserService meanwhile. */
    fun detach(parent: ViewGroup) {
        if (root.parent === parent) parent.removeView(root)
    }

    /** Out (or on its way out) and not moving: nothing to show. */
    val isClosed: Boolean get() = position == 0f && settle?.isRunning != true

    /** Out at once, no fade (something else came to the front). */
    fun closeNow() {
        settle?.cancel()
        position = 0f
        apply()
        host.settled(false)
    }

    fun destroy() {
        if (current === this) current = null
        menu?.dismiss()
        if (bound) {
            bound = false
            ctx.unbindService(this)
        }
        (viewer as? WebView)?.destroy()
        for (w in listOf(desktop, terminal)) w.destroy()
        filesModel.destroy()
        artifactsModel.destroy()
        devboxModel.destroy()
        clipboardModel.destroy()
        schedulesModel.destroy()
        compose.destroy()
    }

    val isOpen: Boolean get() = position > 0.5f

    /** In, where it was left. */
    fun open() {
        if (tab == ARTIFACTS) refreshArtifacts()
        if (tab == CLIPBOARD) clipboardModel.refresh()
        if (tab == SCHEDULES) schedulesModel.refresh()
        animateTo(1f)
    }

    /** In on the Browser tab at that page (a link from the app's UI, or the relay's "show"). */
    fun open(url: String?) {
        if (url != null) {
            send(BrowserService.MSG_LOAD, "url", url)
            closeViewer()
            select(BROWSER)
        }
        animateTo(1f)
    }

    /** A page in the browser, from a link in the viewer: Back comes back to the viewer. */
    fun openFromViewer(url: String) {
        val cur = shown
        if (viewer == null || cur == null) return open(url)
        send(BrowserService.MSG_LOAD, "url", url)
        under.addLast(cur)
        removeViewer()
        browserOver = url
        select(BROWSER)
        animateTo(1f)
    }

    /** A link tapped in another app (LinkActivity): an artifact opens in the viewer, the hub's pages in their sections, the rest in the browser. */
    fun openLink(url: String) {
        val u = Uri.parse(url)
        val path = u.path ?: ""
        when {
            Links.isArtifactHost(ctx, u) -> {
                val hash = Links.viewerHash(u)
                if (hash != null) showViewer(hash, root = true)
                else {
                    closeViewer()
                    select(ARTIFACTS)
                }
                open()
            }
            Links.isHub(ctx, u) && (path.startsWith("/files") || path.startsWith("/api/fs/")) -> {
                // A folder in Files, or a file in the viewer (/files?path=…, /api/fs/raw?path=…)
                val p = u.getQueryParameter("path")
                if (path.startsWith("/api/fs/") && p != null) showRoot(Target.File(p))
                else {
                    closeViewer()
                    if (p != null) filesModel.go(p)
                    select(FILES)
                }
                open()
            }
            Links.isHub(ctx, u) && path.startsWith("/desktop") -> {
                closeViewer()
                select(DESKTOP)
                open()
            }
            Links.isHub(ctx, u) && path.startsWith("/terminal") -> {
                closeViewer()
                select(TERMINAL)
                open()
            }
            else -> open(url)
        }
    }

    /** Out of the way at once, for something opening under it (a share sheet, an app). */
    fun hide() = animateTo(0f)

    /** Back: the viewer, then the section's own history, then the browser's; then the screen goes out. */
    fun back() {
        menu?.let {
            if (it.isShowing) {
                it.dismiss()
                return
            }
        }
        when {
            finding -> endFind()
            editing -> endEdit()
            NativeBack.handle() -> {} // a sheet in a native screen
            viewer != null -> when {
                under.isNotEmpty() -> display(under.removeLast()) // the viewer it was opened from
                rootViewer -> animateTo(0f) // reached from another app: back to it (the viewer stays, for the next time)
                else -> closeViewer() // the list it was opened from
            }
            tab == BROWSER && browserOver != null && under.isNotEmpty() && sameUrl(currentTab()?.optString("url"), browserOver) -> {
                browserOver = null
                display(under.removeLast()) // the viewer the page was opened from
            }
            tab == FILES && filesModel.back() -> {} // Desktop and Terminal: no history to walk
            tab == BROWSER && tabState.optBoolean("canGoBack") -> send(BrowserService.MSG_BACK)
            else -> animateTo(0f)
        }
    }

    /**
     * A top-bar button's tap, on the finger lifting inside it. Not a click: Android drops a click whose window lost
     * focus while the finger was down, and the focus does move between this window and the browser's pages as they
     * get the keyboard. (The click stays, for accessibility.)
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun onTap(v: View, run: () -> Unit) {
        v.setOnClickListener { run() }
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.drawableHotspotChanged(e.x, e.y)
                    view.isPressed = true
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    if (e.x >= 0 && e.y >= 0 && e.x < view.width && e.y < view.height) run()
                }
                MotionEvent.ACTION_CANCEL -> view.isPressed = false
            }
            true
        }
    }

    /**
     * A top-bar button that opens a menu, the moment the finger comes down, so it can be dragged onto an item and let
     * go there to pick it (the item under the finger lights up, with a tick as it changes). Let go over nothing it
     * closes; a plain tap leaves it open, to tap an item as usual. The menu opens on the touch, not on a click, so
     * the focus moving away from this window while the finger is down (see onTap) doesn't matter; the gesture's
     * moves still come here, as the menu's window appeared after it began.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun onPress(v: View, tapOnly: () -> Boolean = { false }, open: () -> Unit) {
        v.setOnClickListener { open() } // for accessibility
        v.setOnTouchListener(object : View.OnTouchListener {
            var hot: View? = null // the item under the finger
            var entered = false // the finger has been over an item
            var downX = 0f
            var downY = 0f

            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.drawableHotspotChanged(e.x, e.y)
                        view.isPressed = true
                        hot = null
                        entered = false
                        downX = e.rawX
                        downY = e.rawY
                        if (!tapOnly()) open()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val item = menuItemAt(e.rawX, e.rawY)
                        if (item !== hot) {
                            hot?.isPressed = false
                            hot = item
                            if (item != null) {
                                entered = true
                                item.isPressed = true
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }
                        item?.let { at(it, e.rawX, e.rawY) }
                    }
                    MotionEvent.ACTION_UP -> {
                        view.isPressed = false
                        hot?.isPressed = false
                        hot = null
                        val item = menuItemAt(e.rawX, e.rawY)
                        val moved = max(abs(e.rawX - downX), abs(e.rawY - downY)) > slop
                        when {
                            tapOnly() -> if (!moved) open() // opens as the finger lifts: a pull down on it reloads
                            item != null -> item.performClick()
                            entered || moved -> menu?.dismiss() // dragged off, onto nothing
                            else -> {} // a tap: the menu stays, to tap an item
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        view.isPressed = false
                        hot?.isPressed = false
                        hot = null
                    }
                }
                return true
            }

            private fun at(item: View, x: Float, y: Float) {
                val loc = IntArray(2)
                item.getLocationOnScreen(loc)
                item.drawableHotspotChanged(x - loc[0], y - loc[1])
            }
        })
    }

    /** The open menu's item (a row, not a field) under a point on the screen, if any. */
    private fun menuItemAt(x: Float, y: Float): View? {
        val d = menu ?: return null
        if (!d.isShowing) return null
        val root = d.window?.decorView ?: return null
        val loc = IntArray(2)
        fun find(v: View): View? {
            if (v.visibility != View.VISIBLE) return null
            v.getLocationOnScreen(loc)
            if (x < loc[0] || y < loc[1] || x >= loc[0] + v.width || y >= loc[1] + v.height) return null
            if (v.isClickable && v !is EditText && v !== root) return v // the outermost: the row, not its ×
            if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) find(v.getChildAt(i))?.let { return it }
            return null
        }
        return find(root)
    }

    private fun filesButton(res: Int, label: String, action: String): ImageView {
        val b = icon(res, BrowserService.TEXT)
        b.scaleType = ImageView.ScaleType.CENTER
        b.background = pressable(dp(20))
        b.contentDescription = label
        onTap(b) {
            if (b.isEnabled) when (action) {
                "up" -> filesModel.up()
                "home" -> filesModel.home()
                else -> filesModel.toggleHidden()
            }
        }
        filesButtons.addView(b, LinearLayout.LayoutParams(dp(40), dp(40)))
        return b
    }

    /** Files says what applies to the folder on screen: Up (not at the top), Home (not at home already), hidden files shown. */
    fun filesBar(up: Boolean, home: Boolean, hidden: Boolean) {
        filesUp.isEnabled = up
        filesUp.alpha = if (up) 1f else 0.3f
        filesHome.isEnabled = home
        filesHome.alpha = if (home) 1f else 0.3f
        filesHidden.setColorFilter(if (hidden) BrowserService.TEXT else BrowserService.MUTED)
    }

    /**
     * The viewer over the second screen, bar included, at the web viewer's route (#artifact/<id>[/v<n>], #view/<id>
     * or #file?path=): native (ui/Viewer), which hands HTML on to the web viewer.
     */
    /**
     * A viewer, and where Back goes from it. [root]: reached from outside Otto (a link in another app), so Back hides
     * the screen; otherwise from inside, so Back comes back: to the viewer this one was opened from, or to the list.
     */
    private class Entry(val target: Target, val hint: ArtifactItem?)

    /** A viewer at a route (#artifact/<id>[/v<n>], #view/<id>, #file?path=), always the native one. */
    fun showViewer(hash: String, root: Boolean = false): Boolean {
        val t = Target.parse(hash) ?: return false
        navigate(Entry(t, null), root)
        return true
    }

    private fun showRoot(t: Target) = navigate(Entry(t, null), root = true)

    /** From a list (Artifacts, Files, Clipboard) or a viewer, inside Otto. */
    private fun showViewer(t: Target, hint: ArtifactItem?) = navigate(Entry(t, hint), root = false)

    private fun navigate(e: Entry, root: Boolean) {
        val cur = shown
        when {
            root -> { // a fresh start, from outside
                under.clear()
                rootViewer = true
            }
            viewer != null && cur != null -> under.addLast(cur) // from the viewer on screen
            else -> { // from a list
                under.clear()
                rootViewer = false
            }
        }
        browserOver = null
        display(e)
    }

    /** Shows a viewer (the native one, whatever it shows: the web UI's viewer isn't the app's), leaving the stack as it is. */
    private fun display(e: Entry) {
        removeViewer()
        dropParked()
        shown = e
        viewerTarget = e.target
        viewerHint = e.hint
        putViewer(compose.view(ctx, ops) { Viewer(e.target, e.hint) })
    }

    /** Up (← in the viewer's bar): the viewer's section, at home: the Artifacts list, the file's folder, the clips. */
    private fun up() {
        val t = viewerTarget
        closeViewer(refresh = false)
        when (t) {
            is Target.File -> {
                filesModel.go(t.path.substringBeforeLast('/', "/").ifEmpty { "/" })
                select(FILES)
            }
            is Target.Clip -> select(CLIPBOARD)
            else -> select(ARTIFACTS)
        }
    }

    private fun sameUrl(a: String?, b: String?) = a != null && b != null && a.trimEnd('/') == b.trimEnd('/')

    private fun putViewer(v: View) {
        viewer = v
        viewerBar = null
        // Under the top bar, which stays: the viewer puts its title and actions in it (ScreenOps.viewerBar).
        body.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { topMargin = dp(BAR_DP) })
        bar.bringToFront()
        syncBar()
        placeSurface()
        if (isOpen) focusTab()
    }

    private fun viewerSection() = when (viewerTarget) {
        is Target.File -> FILES
        is Target.Clip -> CLIPBOARD
        else -> ARTIFACTS
    }

    /** The viewer on screen, put aside (hidden, as it is) while another section is shown. */
    private fun park() {
        val v = viewer ?: return
        dropParked()
        parked = Parked(v, viewerSection(), viewerTarget, viewerHint, viewerBar, shown, under.toList(), rootViewer)
        v.visibility = View.GONE
        viewer = null
        viewerTarget = null
        viewerBar = null
        shown = null
        under.clear()
        rootViewer = false
        browserOver = null
    }

    /** The parked viewer back on screen, in its section. */
    private fun unpark() {
        val p = parked ?: return
        parked = null
        removeViewer()
        viewer = p.view
        viewerTarget = p.target
        viewerHint = p.hint
        viewerBar = p.bar
        shown = p.shown
        under.clear()
        under.addAll(p.under)
        rootViewer = p.rootViewer
        p.view.visibility = View.VISIBLE
        bar.bringToFront()
        select(p.section)
    }

    private fun dropParked() {
        val p = parked ?: return
        parked = null
        body.removeView(p.view)
        if (p.view is WebView) p.view.destroy() else (p.view as? ComposeView)?.disposeComposition()
    }

    /** The viewer out, and what it was opened from forgotten: Otto is back at a section. */
    private fun closeViewer(refresh: Boolean = true) {
        under.clear()
        rootViewer = false
        browserOver = null
        if (viewer == null) return
        removeViewer()
        if (refresh) refreshArtifacts() // e.g. after a delete
    }

    private fun removeViewer() {
        val v = viewer ?: return
        body.removeView(v)
        if (v is WebView) v.destroy() else (v as? ComposeView)?.disposeComposition()
        viewer = null
        viewerTarget = null
        viewerBar = null
        shown = null
        syncBar()
        placeSurface()
        if (isOpen) focusTab()
    }

    /** The Artifacts list fetches the list again: what was published (or deleted) since. */
    fun refreshArtifacts() = artifactsModel.refresh()

    /** What the native screens ask of the second screen. */
    private inner class Ops : ScreenOps {
        override fun openViewer(target: Target, hint: ArtifactItem?) = showViewer(target, hint)
        override fun closeViewer() = this@Screen.closeViewer()
        override fun up() = this@Screen.up()
        override fun openInBrowser(url: String) = openFromViewer(url)
        override fun download(url: String, name: String) = AppWeb.download(ctx, url, name)
        override fun share(text: String, title: String) {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, title)
            ctx.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            hide() // the share sheet opens under the second screen
        }
        override fun hide() = this@Screen.hide()
        override fun artifactsChanged() = refreshArtifacts()
        override fun showDevbox() {
            closeViewer(refresh = false)
            select(DEVBOX)
        }
        override fun viewerBar(bar: ViewerBarState) {
            viewerBar = bar
            if (viewer != null) syncBar()
        }
    }

    private fun select(which: Int) {
        if (which == ARTIFACTS && tab != ARTIFACTS) refreshArtifacts()
        if (which == FILES && tab != FILES) filesModel.refresh()
        if (which == CLIPBOARD) clipboardModel.refresh()
        if (which == SCHEDULES) schedulesModel.refresh()
        tab = which
        devboxModel.shown(which == DEVBOX && position > 0f)
        syncBar()
        files.visibility = if (which == FILES) View.VISIBLE else View.GONE
        artifacts.visibility = if (which == ARTIFACTS) View.VISIBLE else View.GONE
        desktop.visibility = if (which == DESKTOP) View.VISIBLE else View.GONE
        if (which == DESKTOP && desktop.url == null) desktop.loadUrl(AppWeb.URL + "#desktop")
        terminal.visibility = if (which == TERMINAL) View.VISIBLE else View.GONE
        if (which == TERMINAL && terminal.url == null) terminal.loadUrl(AppWeb.URL + "#terminal")
        devbox.visibility = if (which == DEVBOX) View.VISIBLE else View.GONE
        clipboard.visibility = if (which == CLIPBOARD) View.VISIBLE else View.GONE
        schedules.visibility = if (which == SCHEDULES) View.VISIBLE else View.GONE
        placeSurface()
        if (isOpen) focusTab()
    }

    /**
     * The top bar for what's in view: the section's icon (the viewer's section while it's open), then the section's
     * own block (Browser's tabs, Terminal's windows, Files' buttons), or the open viewer's tab: its title and version,
     * with its actions under ⋮.
     */
    private fun syncBar() {
        val v = viewer != null
        val section = if (v) viewerSection() else tab
        titleText.text = NAMES[section]
        titleIcon.setImageResource(ICONS[section])
        titleIcon.contentDescription = NAMES[section]
        val browser = !v && tab == BROWSER
        if (!browser && finding) endFind()
        if (!browser && editing) endEdit()
        val plain = browser && !finding && !editing // the browser's usual bar: address, tabs, ⋮
        tabBlock.visibility = if (v || plain || tab == TERMINAL) View.VISIBLE else View.GONE
        // A viewer's tab is just its title and version, on the bar itself; Browser's address and Terminal's
        // windows are blocks.
        tabBlock.background = if (v) pressable(dp(9)) else rounded(BrowserService.FIELD, dp(9))
        tabChevron.visibility = if (!v && tab == TERMINAL) View.VISIBLE else View.GONE
        tabsButton.visibility = if (plain) View.VISIBLE else View.GONE
        findRow.visibility = if (browser && finding) View.VISIBLE else View.GONE
        addressRow.visibility = if (browser && editing) View.VISIBLE else View.GONE
        editPanel.visibility = addressRow.visibility
        filesButtons.visibility = if (!v && tab == FILES) View.VISIBLE else View.GONE
        home.visibility = if (v) View.VISIBLE else View.GONE
        more.visibility = if ((v && viewerBar != null) || plain) View.VISIBLE else View.GONE
        if (v) showViewerTab() else if (tab == TERMINAL) showTerminals() else if (tab == BROWSER) showTabs()
    }

    /** The tab block as the open viewer's tab: its title, and its version under it. */
    private fun showViewerTab() {
        val b = viewerBar
        tabMark.visibility = View.GONE
        tabCount.visibility = View.GONE
        tabTitle.hint = null
        tabTitle.text = b?.title ?: ""
        // The second line: Public (a globe and the word, in the accent) when it's shared, then the version.
        val line = SpannableStringBuilder()
        if (b?.public == true) {
            val globe = ctx.getDrawable(R.drawable.ccm_tab_browser)!!.mutate()
            val size = (tabHost.textSize * 0.95f).toInt()
            globe.setBounds(0, 0, size, size)
            globe.setTint(BrowserService.ACCENT)
            line.append("\u00a0 Public") // the globe takes the no-break space
            line.setSpan(ImageSpan(globe, DynamicDrawableSpan.ALIGN_CENTER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            line.setSpan(ForegroundColorSpan(BrowserService.ACCENT), 0, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (b.label != null) line.append("  ·  ")
        }
        b?.label?.let { line.append("$it ▾") }
        tabHost.text = line
        tabHost.visibility = if (line.isEmpty()) View.GONE else View.VISIBLE
        tabBlock.contentDescription = listOfNotNull(b?.title, b?.label?.let { "$it: other versions" }, if (b?.public == true) "public" else null).joinToString(", ")
    }

    /** The open viewer's actions, under ⋮. */
    private fun viewerMenu() {
        val b = viewerBar ?: return
        val list = menuBox()
        fun item(icon: Int, name: String, act: () -> Unit) = list.addView(menuItem(icon, name, null, false, {
            menu?.dismiss()
            act()
        }, null))
        if (b.clipping != null) item(R.drawable.ccm_tab_clipboard, if (b.clipping) "Close the clip" else "Clip", b.onClip)
        b.onDownload?.let { item(R.drawable.ccm_download, "Download", it) }
        if (b.label != null) item(R.drawable.ccm_history, "Versions", b.onLabel)
        b.onMore?.let { item(R.drawable.ccm_more, "More…", it) }
        for (a in b.actions) item(a.icon, a.name, a.run)
        showMenu(list, dp(220), false)
        menu?.window?.let { w ->
            val lp = w.attributes
            lp.x = max(dp(8), lp.x + bar.width - dp(16) - dp(220)) // under ⋮, at the bar's right end
            w.attributes = lp
        }
    }

    /**
     * The browser stays laid out when another section (or the viewer) is in front, only moved out of sight: agents
     * may be working in its tabs, and a page that was never shown has no size (no screenshots, no layout). Its
     * surface is drawn above the window, so out of sight has to mean off the screen.
     */
    private fun placeSurface() {
        surface.translationX = if (tab == BROWSER && viewer == null && !editing) 0f else 4f * max(surface.width, dp(400))
    }

    private fun sectionView(): View = when (tab) {
        FILES -> files
        ARTIFACTS -> artifacts
        DEVBOX -> devbox
        CLIPBOARD -> clipboard
        SCHEDULES -> schedules
        TERMINAL -> terminal
        else -> desktop
    }

    /** Focus (and so the keyboard) goes to the section in view. */
    private fun focusTab() {
        val v = viewer
        when {
            v != null -> {
                surface.clearFocus()
                if (v is WebView) v.requestFocus() // a native viewer: no focus asked (it would go to a field, and the keyboard with it)
            }
            tab == BROWSER -> surface.requestFocus()
            else -> {
                surface.clearFocus()
                (sectionView() as? WebView)?.requestFocus()
            }
        }
    }

    // Moving. The handle (OverlayService) brings the screen in and out through animateTo, and Back and Home put it
    // away; no swipe on the screen does. A pull down on the top bar (or the viewer's header) reloads: the views under
    // the finger get a cancel instead.

    /** The window's view: asks onTouch first, and takes Back. */
    private inner class Root(c: Context) : FrameLayout(c) {
        override fun dispatchTouchEvent(e: MotionEvent): Boolean = onTouch(e) || super.dispatchTouchEvent(e)

        /**
         * The window can take focus only once the screen is in (OverlayService.settled), after focusTab() already
         * ran, and it loses it to a menu: when it gets it then, the section in view asks again. The browser's
         * surface has to lose and regain focus for that, as only then does it hand the focus on to its pages. Not
         * otherwise: a tap on the top bar while a page has the focus brings it here too, and handing it straight
         * back would lose the tap.
         */
        override fun onWindowFocusChanged(has: Boolean) {
            super.onWindowFocusChanged(has)
            if (!has || !refocus || !isOpen) return
            refocus = false
            if (surface.hasFocus()) surface.clearFocus()
            focusTab()
        }

        fun cancelChildren(e: MotionEvent) {
            val cancel = MotionEvent.obtain(e)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
        }

        override fun dispatchKeyEvent(e: KeyEvent): Boolean {
            if (e.keyCode == KeyEvent.KEYCODE_BACK) {
                if (e.action != KeyEvent.ACTION_UP || e.isCanceled) return true
                if (rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) { // the keyboard goes first
                    // Over the browser it's the browser process's to put away (BrowserService); else this window's.
                    if (tab == BROWSER && viewer == null) send(BrowserService.MSG_HIDE_KEYBOARD)
                    else ctx.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
                } else {
                    back()
                }
                return true
            }
            return super.dispatchKeyEvent(e)
        }
    }

    private fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = startsPull(e)
                pulling = false
                downX = e.x
                downY = e.y
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                // A press that opened a menu (onPress) is dragging onto its items, not pulling to reload.
                if (!pulling && menu?.isShowing == true) {
                    tracking = false
                    return false
                }
                val dx = e.x - downX
                val dy = e.y - downY
                if (pulling) {
                    showPull(dy)
                    return true
                }
                if (dy > slop && dy > 2 * abs(dx)) { // down on the top bar: reload
                    pulling = true
                    root.cancelChildren(e)
                    showPull(dy)
                    return true
                }
                if (max(abs(dx), abs(dy)) > slop) tracking = false // something else: the views under it have it
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return false
                tracking = false
                if (!pulling) return false
                pulling = false
                val go = e.actionMasked == MotionEvent.ACTION_UP && e.y - downY >= dp(PULL_DP)
                if (go) reload()
                hidePull(go)
                return true
            }
        }
        return tracking && pulling
    }

    // Pulling down on the top bar (or the viewer's header) reloads what's on screen: the viewer, the browser tab or the section.

    private fun showPull(dy: Float) {
        val d = max(0f, dy) * 0.6f
        val most = dp(PULL_DP) * 0.6f
        pull.animate().cancel()
        var y = topInset + min(d, most * 1.4f) - dp(20)
        // The browser's pages are drawn above this window (see surface): there it stays in the bar.
        if (tab == BROWSER && viewer == null) y = min(y, (topInset + dp((BAR_DP - 40) / 2)).toFloat())
        pull.translationY = y
        pull.rotation = d / most * 270
        val ready = min(1f, d / most)
        pull.alpha = ready
        pull.setColorFilter(if (ready >= 1) BrowserService.TEXT else BrowserService.MUTED)
    }

    private fun hidePull(reloading: Boolean) {
        if (reloading) pull.animate().rotationBy(360f).setDuration(400).withEndAction { pull.animate().alpha(0f).setDuration(150) }
        else pull.animate().alpha(0f).setDuration(150)
    }

    private fun reload() {
        val v = viewer
        when {
            v is WebView -> v.reload()
            v != null -> shown?.let { display(it) }
            tab == BROWSER -> send(BrowserService.MSG_RELOAD)
            tab == FILES -> filesModel.refresh(pull = true)
            tab == ARTIFACTS -> artifactsModel.refresh(pull = true)
            tab == CLIPBOARD -> clipboardModel.refresh(pull = true)
            tab == SCHEDULES -> schedulesModel.refresh(pull = true)
            tab == DEVBOX -> devboxModel.shown(true)
            else -> (sectionView() as? WebView)?.reload()
        }
    }

    /** A pull can start on the top bar (a viewer's too: it's in the bar). */
    private fun startsPull(e: MotionEvent): Boolean {
        if (position < 0.5f) return false
        val r = Rect()
        bar.getGlobalVisibleRect(r)
        return r.contains(e.x.toInt(), e.y.toInt())
    }

    fun animateTo(target: Float) {
        settle?.cancel()
        val a = ValueAnimator.ofFloat(position, target)
        settle = a
        a.duration = (60 + 160 * abs(target - position)).toLong()
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener {
            position = it.animatedValue as Float
            apply()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (cancelled) return
                if (target >= 1) {
                    send(BrowserService.MSG_ACTIVE) // agents' new tabs open here now
                    refocus = true
                }
                host.settled(target >= 1)
            }
        })
        a.start()
    }

    private fun apply() {
        // A fade: the screen's views, and the browser's surface, which is drawn apart and takes no parent's opacity.
        root.alpha = position
        surface.alpha = position
        host.moved(position)
        val open = position > 0.5f
        if (open != wasOpen) {
            wasOpen = open
            // Focus (and so the keyboard) goes with the screen in view.
            ctx.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
            if (open) focusTab()
            else {
                surface.clearFocus()
                menu?.dismiss()
            }
            devboxModel.shown(open && tab == DEVBOX) // its metrics poll only while it's in view
        }
    }

    // The top bar.

    private fun titleLabel() = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(BrowserService.TEXT)
        isSingleLine = true
    }

    /** The tab block: the current tab's title and site, and how many tabs there are. */
    private fun showTabs() {
        if (tab == TERMINAL || viewer != null) return // an open viewer has the bar
        tabBlock.contentDescription = "Tabs"
        val t = currentTab()
        val g = t?.let { groupOf(it.optString("group")) }
        tabMark.visibility = if (g != null && g.optString("owner") == BrowserService.AGENT) View.VISIBLE else View.GONE
        // The address, on one line: the site and the path, without the scheme (a tap edits it).
        tabTitle.hint = "Search or type a URL"
        tabTitle.setHintTextColor(BrowserService.MUTED)
        tabTitle.text = shownUrl(t?.optString("url") ?: "")
        tabHost.visibility = View.GONE
        tabCount.visibility = View.GONE
        tabsCount.text = (tabState.optJSONArray("tabs")?.length() ?: 1).toString()
        if (tabList != null && typed == null) fillTabList()
    }

    private fun showTerminals() {
        val state = terminalState
        val windows = state?.optJSONArray("windows")
        val current = state?.optString("current") ?: ""
        tabMark.visibility = View.GONE
        tabTitle.hint = null
        tabTitle.text = "Terminal"
        tabHost.text = if (state != null && state.has("error")) state.optString("error") else "Connecting…"
        tabHost.visibility = View.VISIBLE
        tabBlock.contentDescription = "Terminal windows"
        tabCount.text = (windows?.length() ?: 0).toString()
        tabCount.visibility = View.VISIBLE
        for (i in 0 until (windows?.length() ?: 0)) {
            val w = windows!!.optJSONObject(i)
            if (current != w.optString("target")) continue
            tabTitle.text = w.optString("name")
            tabHost.text = w.optString("command")
        }
        if (terminalList != null) fillTerminalList()
    }

    private fun terminalMenu() {
        terminal.evaluateJavascript("window.ccmTerminal?.()", null)
        val box = menuBox()
        val scroll = maxHeightScroll((panel.height * 0.6f).toInt())
        val list = LinearLayout(ctx)
        list.orientation = LinearLayout.VERTICAL
        terminalList = list
        scroll.addView(list)
        box.addView(scroll)
        fillTerminalList()
        showMenu(box, bar.width - dp(16), true)
        menu?.setOnDismissListener { terminalList = null }
    }

    private fun fillTerminalList() {
        val list = terminalList ?: return
        list.removeAllViews()
        val state = terminalState
        val windows = state?.optJSONArray("windows")
        if (windows == null || windows.length() == 0) {
            val empty = line(14f, BrowserService.MUTED)
            empty.text = if (state != null && state.has("error")) state.optString("error") else "Waiting for terminals…"
            empty.setPadding(dp(10), dp(10), dp(10), dp(10))
            list.addView(empty)
            return
        }
        val all = (0 until windows.length()).map { windows.optJSONObject(it) }
        for (group in all.map { it.optString("group") }.distinct()) {
            var heading = false
            for (w in all) {
                if (group != w.optString("group")) continue
                if (!heading) {
                    val head = line(11f, BrowserService.MUTED)
                    head.text = (if (group.startsWith("claude:")) "✳  " else "") + w.optString("title").uppercase(Locale.ROOT)
                    head.letterSpacing = 0.04f
                    head.setPadding(dp(10), dp(8), dp(10), dp(3))
                    list.addView(head)
                    heading = true
                }
                val target = w.optString("target")
                val sub = w.optString("command") + " · " + w.optString("session")
                list.addView(menuItem(R.drawable.ccm_tab_terminal, w.optString("name"), sub, target == state.optString("current"), {
                    terminal.evaluateJavascript("window.ccmTerminal?.(" + JSONObject.quote(target) + ")", null)
                    menu?.dismiss()
                }, null))
            }
        }
    }

    private fun suggest(query: String) {
        val svc = service ?: return
        val m = Message.obtain(null, BrowserService.MSG_SUGGEST)
        m.data.putString("query", query)
        m.data.putInt("seq", ++suggestSeq)
        m.replyTo = replies
        try {
            svc.send(m)
        } catch (e: Exception) {
            Log.e(TAG, "suggest", e)
        }
    }

    /** In place of the tab list while a query is typed: matching open tabs, then pages from history. */
    private fun showSuggestions(list: JSONArray) {
        val tabList = tabList ?: return
        tabList.removeAllViews()
        val q = typed
        if (editing && q != null) tabList.addView(menuItem(R.drawable.ccm_find, q, null, false, {
            endEdit()
            send(BrowserService.MSG_LOAD, "url", q)
        }, null))
        else if (editing && list.length() > 0) tabList.addView(line(11f, BrowserService.MUTED).apply {
            text = "RECENT"
            letterSpacing = 0.04f
            setPadding(dp(10), dp(4), dp(10), dp(4))
        })
        for (i in 0 until list.length()) {
            val s = list.optJSONObject(i)
            val url = s.optString("url")
            val host = host(url)
            val name = tabName(s)
            val shown = url.replaceFirst(Regex("^https?://(www\\.)?"), "").replaceFirst(Regex("/$"), "")
            if (s.has("tab")) {
                val id = s.optString("tab")
                tabList.addView(menuItem(R.drawable.ccm_tab_browser, name, "Switch to tab · $host", false, {
                    send(BrowserService.MSG_SELECT, "id", id)
                    menu?.dismiss()
                    endEdit()
                }, null))
            } else {
                tabList.addView(menuItem(R.drawable.ccm_history, name, shown, false, {
                    send(BrowserService.MSG_LOAD, "url", url)
                    menu?.dismiss()
                    endEdit()
                }, null))
            }
        }
    }

    private fun currentTab(): JSONObject? {
        val all = tabState.optJSONArray("tabs") ?: return null
        val cur = tabState.optString("current")
        return (0 until all.length()).map { all.optJSONObject(it) }.firstOrNull { it.optString("id") == cur }
    }

    /** A tab group, by id ("" or unknown: null). */
    private fun groupOf(id: String): JSONObject? {
        if (id.isEmpty()) return null
        val gs = tabState.optJSONArray("groups") ?: return null
        return (0 until gs.length()).map { gs.optJSONObject(it) }.firstOrNull { it.optString("id") == id }
    }

    /** The section menu, under the title. */
    private fun sectionMenu() {
        val list = menuBox()
        val all = tabState.optJSONArray("tabs")
        for (i in intArrayOf(ARTIFACTS, BROWSER, FILES, CLIPBOARD, SCHEDULES, DESKTOP, TERMINAL, DEVBOX)) {
            val sub = if (i == BROWSER && all != null) "${all.length()} " + (if (all.length() == 1) "tab" else "tabs") else null
            list.addView(menuItem(ICONS[i], NAMES[i], sub, i == tab && viewer == null, {
                menu?.dismiss()
                val p = parked
                when {
                    viewer == null && p != null && p.section == i -> unpark() // the viewer left there, as it was
                    viewer != null && viewerSection() != i -> { // the viewer stays in its section, for coming back
                        park()
                        select(i)
                    }
                    else -> {
                        closeViewer(refresh = false) // its own section, at home
                        select(i)
                    }
                }
            }, null))
        }
        showMenu(list, dp(220), false)
    }

    /** The browser's options, under ⋮ (at the right end): settings for the current tab's site, and Chrome. */
    private fun optionsMenu() {
        val list = menuBox()
        val url = currentTab()?.optString("url") ?: ""
        val host = host(url)
        val page = host.isNotEmpty()
        // The page's own buttons, in a row: back, forward, reload, copy the link.
        val row = LinearLayout(ctx)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(4), dp(2), dp(4), dp(6))
        fun button(res: Int, label: String, on: Boolean, act: () -> Unit) {
            val b = icon(res, BrowserService.TEXT)
            b.scaleType = ImageView.ScaleType.CENTER
            b.background = rounded(BrowserService.BG, dp(9))
            b.foreground = pressable(dp(9))
            b.contentDescription = label
            b.isEnabled = on
            b.alpha = if (on) 1f else 0.35f
            b.setOnClickListener {
                menu?.dismiss()
                act()
            }
            row.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
        button(R.drawable.ccm_back, "Back", tabState.optBoolean("canGoBack")) { send(BrowserService.MSG_BACK) }
        button(R.drawable.ccm_forward, "Forward", tabState.optBoolean("canGoForward")) { send(BrowserService.MSG_FORWARD) }
        button(R.drawable.ccm_reload, "Reload", page) { send(BrowserService.MSG_RELOAD) }
        button(R.drawable.ccm_link, "Copy link", page) {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Link", url))
            Toast.makeText(ctx, "Link copied", Toast.LENGTH_SHORT).show()
        }
        list.addView(row)
        fun item(res: Int, name: String, sub: String?, on: Boolean, act: () -> Unit): LinearLayout {
            val r = menuItem(res, name, sub, false, {
                menu?.dismiss()
                act()
            }, null) as LinearLayout
            r.isEnabled = on
            r.alpha = if (on) 1f else 0.4f
            list.addView(r)
            return r
        }
        item(R.drawable.ccm_plus, "New tab", null, true) { newTab() }
        item(R.drawable.ccm_find, "Find in page", null, page) { startFind() }
        item(R.drawable.ccm_share, "Share…", null, page) {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
            ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            hide() // the share sheet opens under the second screen
        }
        val on = tabState.optBoolean("desktop")
        val desktopRow = item(R.drawable.ccm_tab_desktop, "Desktop site",
            if (!page) null else (if (on) "On for " else "For ") + host.replaceFirst(Regex("^www\\."), ""), page) {
            send(BrowserService.MSG_DESKTOP, "on", if (on) null else "1")
        }
        val check = icon(R.drawable.ccm_check, BrowserService.ACCENT)
        check.visibility = if (on) View.VISIBLE else View.INVISIBLE
        desktopRow.addView(check, LinearLayout.LayoutParams(dp(20), dp(20)))
        item(R.drawable.ccm_open, "Open in Chrome", null, page) { openOutside(url) }
        showMenu(list, dp(264), false)
        menu?.window?.let { w ->
            val lp = w.attributes
            lp.x = max(dp(8), lp.x + bar.width - dp(16) - dp(264)) // under ⋮, at the bar's right end
            w.attributes = lp
        }
    }

    /** The page in Chrome (or, without it, the default browser; never this app), with the screen out of the way. */
    private fun openOutside(url: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(Intent(i).setPackage("com.android.chrome"))
        } catch (e: Exception) {
            Links.openElsewhere(ctx, url)
        }
        animateTo(0f)
    }

    /**
     * The address, edited in place, as Chrome does: the field in the bar (the address selected, the keyboard up), and
     * over the page the page open now (copy its link, share it), then the pages visited last, or what the query
     * suggests (open tabs, then pages from history).
     */
    private fun startEdit() {
        if (editing) return
        menu?.dismiss()
        editing = true
        typed = null
        tabList = editList
        editList.removeAllViews()
        fillEditCurrent()
        val url = currentTab()?.optString("url") ?: ""
        addressField.setText(url)
        syncBar()
        placeSurface()
        surface.clearFocus()
        addressField.requestFocus()
        addressField.selectAll()
        addressField.post { ctx.getSystemService(InputMethodManager::class.java).showSoftInput(addressField, 0) }
        suggest("") // the pages visited last
    }

    private fun endEdit() {
        if (!editing) return
        editing = false
        typed = null
        tabList = null
        ctx.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(addressField.windowToken, 0)
        addressField.clearFocus()
        syncBar()
        placeSurface()
        if (isOpen) focusTab()
    }

    /** The page open now, at the top while nothing is typed: its title and address, copy the link, share it. */
    private fun fillEditCurrent() {
        editCurrent.removeAllViews()
        val t = currentTab() ?: return
        val url = t.optString("url")
        if (host(url).isEmpty()) return
        val row = menuItem(R.drawable.ccm_tab_browser, tabName(t), shownUrl(url), false, { endEdit() }, null) as LinearLayout
        fun act(res: Int, label: String, f: () -> Unit) = icon(res, BrowserService.TEXT).also {
            it.scaleType = ImageView.ScaleType.CENTER
            it.background = pressable(dp(18))
            it.contentDescription = label
            it.setOnClickListener { f() }
            row.addView(it, LinearLayout.LayoutParams(dp(40), dp(36)))
        }
        act(R.drawable.ccm_copy, "Copy link") {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Link", url))
            Toast.makeText(ctx, "Link copied", Toast.LENGTH_SHORT).show()
        }
        act(R.drawable.ccm_share, "Share") {
            endEdit()
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
            ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            hide()
        }
        editCurrent.addView(row)
        editCurrent.addView(View(ctx).apply { setBackgroundColor(BrowserService.LINE) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max(1, dp(1) / 2 + 1)).apply { topMargin = dp(6); bottomMargin = dp(6) })
    }

    /** The tabs, from the tab count: yours (with + for a new one), then each group's. */
    private fun tabsMenu() {
        val box = menuBox()
        val head = LinearLayout(ctx)
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(10), dp(2), 0, 0)
        val yours = line(11f, BrowserService.MUTED)
        yours.text = "YOUR TABS"
        yours.letterSpacing = 0.04f
        head.addView(yours, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val plus = icon(R.drawable.ccm_plus, BrowserService.TEXT)
        plus.scaleType = ImageView.ScaleType.CENTER
        plus.background = pressable(dp(18))
        plus.contentDescription = "New tab"
        plus.setOnClickListener { newTab() }
        head.addView(plus, LinearLayout.LayoutParams(dp(40), dp(36)))
        box.addView(head)

        val scroll = maxHeightScroll((panel.height * 0.6f).toInt())
        val list = LinearLayout(ctx)
        list.orientation = LinearLayout.VERTICAL
        tabList = list
        scroll.addView(list)
        box.addView(scroll)
        fillTabList()
        showMenu(box, bar.width - dp(16), false)
        menu?.setOnDismissListener {
            tabList = null
            typed = null
        }
    }

    /** A new tab, with its address ready to type in. */
    private fun newTab() {
        menu?.dismiss()
        send(BrowserService.MSG_NEW)
        bar.postDelayed({ if (tab == BROWSER && viewer == null) startEdit() }, 150) // once the new tab is the current one
    }

    /** Find in page: the find row in place of the address, its field focused. */
    private fun startFind() {
        finding = true
        findField.setText("")
        findCount.text = ""
        syncBar()
        surface.clearFocus()
        findField.requestFocus()
        findField.post { ctx.getSystemService(InputMethodManager::class.java).showSoftInput(findField, 0) }
    }

    private fun endFind() {
        if (!finding) return
        finding = false
        send2(BrowserService.MSG_FIND, "q" to "", "dir" to "clear")
        ctx.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(findField.windowToken, 0)
        findField.clearFocus()
        syncBar()
        if (isOpen) focusTab()
    }

    private fun maxHeightScroll(maxHeight: Int) = object : ScrollView(ctx) {
        override fun onMeasure(w: Int, h: Int) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }
    }

    private fun fillTabList() {
        val tabList = tabList ?: return
        if (editing) return // the list is the address's suggestions
        tabList.removeAllViews()
        val all = tabState.optJSONArray("tabs") ?: return
        val tabs = (0 until all.length()).map { all.optJSONObject(it) }
        // The tabs in no group ("Your tabs", whose heading is above the list), then each group's.
        val groups = LinkedHashMap<String, JSONObject?>()
        groups[""] = null
        tabState.optJSONArray("groups")?.let { gs -> for (i in 0 until gs.length()) groups[gs.optJSONObject(i).optString("id")] = gs.optJSONObject(i) }
        for ((gid, g) in groups) {
            var any = false
            for (t in tabs) {
                if (t.optString("group") != gid) continue
                if (!any && g != null) {
                    // The group's heading: its title, ✳ for an agent session's.
                    val head = line(11f, BrowserService.MUTED)
                    val agent = g.optString("owner") == BrowserService.AGENT
                    head.text = (if (agent) "✳  " else "") + g.optString("title").uppercase(Locale.ROOT)
                    head.letterSpacing = 0.04f
                    head.setPadding(dp(10), dp(8), dp(10), dp(3))
                    tabList.addView(head)
                }
                any = true
                val id = t.optString("id")
                val host = host(t.optString("url"))
                tabList.addView(menuItem(R.drawable.ccm_tab_browser, tabName(t), host.ifEmpty { null },
                    id == tabState.optString("current"),
                    {
                        send(BrowserService.MSG_SELECT, "id", id)
                        menu?.dismiss()
                    },
                    { send(BrowserService.MSG_CLOSE, "id", id) }))
            }
        }
    }

    private fun menuBox() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
    }

    /** A menu row: icon, name (and a second line), highlighted when it's the current one; × when it can close. */
    private fun menuItem(iconRes: Int, name: String, sub: String?, on: Boolean, click: () -> Unit, close: (() -> Unit)?): View {
        val row = LinearLayout(ctx)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(10), dp(9), if (close == null) dp(10) else 0, dp(9))
        row.minimumHeight = dp(44)
        val bg = rounded(if (on) Color.argb(18, 255, 255, 255) else Color.TRANSPARENT, dp(11))
        row.background = RippleDrawable(ColorStateList.valueOf(Color.argb(30, 255, 255, 255)), bg, rounded(Color.WHITE, dp(11)))
        row.addView(icon(iconRes, BrowserService.MUTED), LinearLayout.LayoutParams(dp(20), dp(20)))
        val text = LinearLayout(ctx)
        text.orientation = LinearLayout.VERTICAL
        text.addView(line(14f, BrowserService.TEXT).apply { this.text = name })
        if (sub != null) text.addView(line(11.5f, BrowserService.MUTED).apply { this.text = sub })
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        if (close != null) {
            val x = label("×", 18)
            x.setTextColor(BrowserService.MUTED)
            x.contentDescription = "Close tab"
            x.setOnClickListener { close() }
            row.addView(x, LinearLayout.LayoutParams(dp(40), dp(36)))
        }
        row.setOnClickListener { click() }
        return row
    }

    /** A menu under the top bar, over a dimmed screen; a tap outside closes it. It's a window of its own over every app. */
    private fun showMenu(content: View, width: Int, typing: Boolean) {
        menu?.dismiss()
        val d = Dialog(ctx)
        menu = d
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(content)
        d.setCanceledOnTouchOutside(true)
        val w = d.window!!
        w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        w.setBackgroundDrawable(rounded(BrowserService.FIELD, dp(16)))
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        val at = IntArray(2)
        bar.getLocationOnScreen(at)
        val lp = w.attributes
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = at[0] + dp(8)
        lp.y = at[1] + bar.height - dp(2)
        lp.width = width
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.dimAmount = 0.3f
        lp.windowAnimations = android.R.style.Animation_Dialog
        w.attributes = lp
        w.setSoftInputMode(if (typing) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING else WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        d.show()
        refocus = true // for when the menu is gone
    }

    private fun line(sp: Float, color: Int) = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun icon(res: Int, color: Int) = ImageView(ctx).apply {
        setImageResource(res)
        setColorFilter(color)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun pressable(radius: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(Color.argb(30, 255, 255, 255)), null, rounded(Color.WHITE, radius))

    private fun label(text: String, sp: Int) = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
        setTextColor(BrowserService.TEXT)
        gravity = Gravity.CENTER
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()

    // The embedded browser. The binding comes back by itself when the browser's process restarts (BIND_AUTO_CREATE);
    // if Android drops it altogether (onBindingDied), it's made anew.

    private fun bind() {
        bound = ctx.bindService(Intent(ctx, BrowserService::class.java), this, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        service = Messenger(binder)
        attached = false
        // Known to the browser at once, before there's a surface: "show" from an agent then brings the screen in.
        val hello = Message.obtain(null, BrowserService.MSG_CLIENT)
        hello.replyTo = replies
        try {
            service?.send(hello)
        } catch (e: Exception) {
            Log.e(TAG, "client", e)
        }
        attachOrResize()
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
        attached = false
    }

    override fun onBindingDied(name: ComponentName) {
        service = null
        attached = false
        if (!bound) return
        ctx.unbindService(this)
        bound = false
        if (current === this) Handler(Looper.getMainLooper()).postDelayed({ if (current === this && !bound) bind() }, 500)
    }

    private fun attachOrResize() {
        val svc = service ?: return
        if (surface.width == 0 || surface.height == 0 || surface.display == null) return
        val m = Message.obtain(null, if (attached) BrowserService.MSG_RESIZE else BrowserService.MSG_ATTACH)
        val b = m.data
        b.putInt("width", surface.width)
        b.putInt("height", surface.height)
        if (!attached) {
            b.putBinder("hostToken", surface.hostToken)
            b.putInt("displayId", surface.display.displayId)
            m.replyTo = replies
        }
        try {
            svc.send(m)
        } catch (e: Exception) {
            Log.e(TAG, "send", e)
        }
    }

    private fun onReply(m: Message): Boolean {
        when (m.what) {
            BrowserService.MSG_ATTACH -> {
                @Suppress("DEPRECATION")
                val pkg = m.data.getParcelable<SurfaceControlViewHost.SurfacePackage>("surface")
                if (pkg != null) {
                    surface.setChildSurfacePackage(pkg)
                    attached = true
                }
            }
            BrowserService.MSG_TABS -> {
                val was = tabState.optString("current")
                try {
                    tabState = JSONObject(m.data.getString("json") ?: "{}")
                } catch (e: Exception) {
                    Log.d(TAG, "tabs: $e")
                }
                if (finding && tabState.optString("current") != was) endFind() // the find was on the other tab's page
                showTabs()
            }
            BrowserService.MSG_SUGGESTIONS -> {
                if (m.data.getInt("seq") == suggestSeq && tabList != null && (typed != null || editing)) {
                    try {
                        showSuggestions(JSONArray(m.data.getString("json")))
                    } catch (e: Exception) {
                        Log.d(TAG, "suggestions: $e")
                    }
                }
            }
            BrowserService.MSG_FOUND -> if (finding) {
                val n = m.data.getInt("count")
                findCount.text = if (findField.text.isEmpty()) "" else if (n == 0) "0/0" else "${m.data.getInt("index") + 1}/$n"
            }
            BrowserService.MSG_SHOW -> {
                closeViewer()
                select(BROWSER)
                animateTo(1f)
            }
            BrowserService.MSG_BACK_KEY -> if (isOpen) back()
        }
        return true
    }

    private fun send2(what: Int, vararg data: Pair<String, String>) {
        val svc = service ?: return
        val m = Message.obtain(null, what)
        for ((k, v) in data) m.data.putString(k, v)
        try {
            svc.send(m)
        } catch (e: Exception) {
            Log.e(TAG, "send", e)
        }
    }

    private fun send(what: Int, key: String? = null, value: String? = null) {
        val svc = service ?: return
        val m = Message.obtain(null, what)
        if (key != null) m.data.putString(key, value)
        try {
            svc.send(m)
        } catch (e: Exception) {
            Log.e(TAG, "send", e)
        }
    }

    companion object {
        private const val TAG = "Screen"
        var current: Screen? = null // for AppWeb and LinkActivity
            private set
        private val NAMES = arrayOf("Files", "Artifacts", "Browser", "Desktop", "Devbox", "Terminal", "Clipboard", "Schedules")
        private val ICONS = intArrayOf(R.drawable.ccm_tab_files, R.drawable.ccm_tab_artifacts, R.drawable.ccm_tab_browser,
            R.drawable.ccm_tab_desktop, R.drawable.ccm_tab_devbox, R.drawable.ccm_tab_terminal, R.drawable.ccm_tab_clipboard,
            R.drawable.ccm_tab_schedules)
        private const val FILES = 0
        private const val ARTIFACTS = 1
        private const val BROWSER = 2
        private const val DESKTOP = 3
        private const val DEVBOX = 4
        private const val TERMINAL = 5
        private const val CLIPBOARD = 6
        private const val SCHEDULES = 7
        private const val BAR_DP = 48
        private const val TITLE_PAD = 6
        private const val BAR_START = 10
        private const val PULL_DP = 110

        private fun tabName(t: JSONObject): String {
            val title = t.optString("title")
            val url = t.optString("url")
            if (title.isNotEmpty() && title != url && !url.endsWith(title)) return title
            return host(url).ifEmpty { "New tab" }
        }

        /** An address as the bar shows it: no scheme, no "www.", no trailing slash. */
        private fun shownUrl(url: String): String = url.replaceFirst(Regex("^https?://(www\\.)?"), "").replaceFirst(Regex("/$"), "")

        private fun host(url: String?): String = if (url.isNullOrEmpty()) "" else Uri.parse(url).host ?: ""
    }
}
