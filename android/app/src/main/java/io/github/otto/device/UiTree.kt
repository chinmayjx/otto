package io.github.otto.device

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.Locale

/** Reads the UI tree: a compact text dump, uiautomator-style XML, and finding elements by text, id or description. */
object UiTree {
    /** Element selector, as the box sends it: any of text, id, desc; index picks among several matches. */
    class Selector(var text: String? = null, var id: String? = null, var desc: String? = null, var index: Int = 0) {
        fun empty() = text == null && id == null && desc == null

        override fun toString() = listOfNotNull(
            text?.let { "text \"$it\"" },
            id?.let { "id $it" },
            desc?.let { "desc \"$it\"" },
        ).joinToString(", ")
    }

    /** Which windows a dump shows: by default app windows and whatever has focus, not the status/nav bars. */
    fun windows(a: AgentAccessibility, all: Boolean): List<AccessibilityWindowInfo> = a.windowsTopFirst().filter { w ->
        all || w.type == AccessibilityWindowInfo.TYPE_APPLICATION || w.isActive || w.isFocused
    }

    fun text(a: AgentAccessibility, all: Boolean): String {
        val sb = StringBuilder()
        for (w in windows(a, all)) {
            val root = w.root ?: continue
            val r = Rect()
            w.getBoundsInScreen(r)
            sb.append("# window ").append(windowType(w.type))
                .append(" ").append(root.packageName)
                .append(if (w.title != null) " \"${w.title}\"" else "")
                .append(if (w.isActive) " active" else "")
                .append(" ").append(bounds(r)).append('\n')
            dumpText(root, 0, sb, all)
        }
        return sb.toString()
    }

    private fun dumpText(n: AccessibilityNodeInfo, depth: Int, sb: StringBuilder, all: Boolean) {
        if (!all && !n.isVisibleToUser) return
        val show = all || interesting(n)
        if (show) {
            repeat(depth) { sb.append("  ") }
            sb.append(shortClass(n.className))
            val t = n.text
            if (!t.isNullOrEmpty()) sb.append(" \"").append(oneLine(t)).append('"')
            if (n.isShowingHintText) sb.append(" (hint)")
            val d = n.contentDescription
            if (!d.isNullOrEmpty()) sb.append(" desc=\"").append(oneLine(d)).append('"')
            n.viewIdResourceName?.let { sb.append(" id=").append(it) }
            val r = Rect()
            n.getBoundsInScreen(r)
            sb.append(' ').append(bounds(r))
            if (n.isClickable) sb.append(" click")
            if (n.isLongClickable) sb.append(" long")
            if (n.isScrollable) sb.append(" scroll")
            if (n.isEditable) sb.append(" edit")
            if (n.isFocused) sb.append(" focused")
            if (n.isCheckable) sb.append(if (n.isChecked) " checked" else " unchecked")
            if (n.isSelected) sb.append(" selected")
            if (!n.isEnabled) sb.append(" disabled")
            if (n.isPassword) sb.append(" password")
            sb.append('\n')
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { dumpText(it, if (show) depth + 1 else depth, sb, all) }
    }

    private fun interesting(n: AccessibilityNodeInfo) =
        !n.text.isNullOrEmpty() || !n.contentDescription.isNullOrEmpty() || n.viewIdResourceName != null ||
            n.isClickable || n.isLongClickable || n.isScrollable || n.isEditable || n.isCheckable

    fun xml(a: AgentAccessibility, all: Boolean): String {
        val sb = StringBuilder("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n<hierarchy>\n")
        for (w in windows(a, all)) w.root?.let { dumpXml(it, 0, 1, sb) }
        return sb.append("</hierarchy>\n").toString()
    }

    private fun dumpXml(n: AccessibilityNodeInfo, index: Int, depth: Int, sb: StringBuilder) {
        val r = Rect()
        n.getBoundsInScreen(r)
        repeat(depth) { sb.append("  ") }
        sb.append("<node index=\"").append(index).append('"')
            .append(attr("text", n.text))
            .append(attr("resource-id", n.viewIdResourceName))
            .append(attr("class", n.className))
            .append(attr("package", n.packageName))
            .append(attr("content-desc", n.contentDescription))
            .append(" checkable=\"").append(n.isCheckable).append('"')
            .append(" checked=\"").append(n.isChecked).append('"')
            .append(" clickable=\"").append(n.isClickable).append('"')
            .append(" enabled=\"").append(n.isEnabled).append('"')
            .append(" focusable=\"").append(n.isFocusable).append('"')
            .append(" focused=\"").append(n.isFocused).append('"')
            .append(" scrollable=\"").append(n.isScrollable).append('"')
            .append(" long-clickable=\"").append(n.isLongClickable).append('"')
            .append(" password=\"").append(n.isPassword).append('"')
            .append(" selected=\"").append(n.isSelected).append('"')
            .append(" bounds=\"").append(bounds(r)).append('"')
        if (n.childCount == 0) {
            sb.append(" />\n")
            return
        }
        sb.append(">\n")
        for (i in 0 until n.childCount) n.getChild(i)?.let { dumpXml(it, i, depth + 1, sb) }
        repeat(depth) { sb.append("  ") }
        sb.append("</node>\n")
    }

    /** Visible elements matching the selector, top window first; exact text matches before partial ones. */
    fun find(a: AgentAccessibility, s: Selector): List<AccessibilityNodeInfo> {
        val exact = ArrayList<AccessibilityNodeInfo>()
        val partial = ArrayList<AccessibilityNodeInfo>()
        for (w in windows(a, false)) w.root?.let { collect(it, s, exact, partial) }
        return exact + partial
    }

    private fun collect(n: AccessibilityNodeInfo, s: Selector, exact: MutableList<AccessibilityNodeInfo>, partial: MutableList<AccessibilityNodeInfo>) {
        if (!n.isVisibleToUser) return
        when (match(n, s)) {
            2 -> exact.add(n)
            1 -> partial.add(n)
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, s, exact, partial) }
    }

    /** 0 no match, 1 partial (text/desc contained), 2 exact. */
    private fun match(n: AccessibilityNodeInfo, s: Selector): Int {
        var score = 2
        s.id?.let { want ->
            val id = n.viewIdResourceName
            if (id == null || !(id == want || id.endsWith(":id/$want"))) return 0
        }
        s.text?.let {
            val m = textMatch(n.text, it)
            if (m == 0) return 0
            score = minOf(score, m)
        }
        s.desc?.let {
            val m = textMatch(n.contentDescription, it)
            if (m == 0) return 0
            score = minOf(score, m)
        }
        return score
    }

    private fun textMatch(have: CharSequence?, want: String): Int {
        if (have == null) return 0
        val h = have.toString().trim().lowercase(Locale.ROOT)
        val w = want.trim().lowercase(Locale.ROOT)
        return if (h == w) 2 else if (h.contains(w)) 1 else 0
    }

    fun focusedInput(a: AgentAccessibility): AccessibilityNodeInfo? {
        for (w in a.windowsTopFirst()) {
            val root = w.root ?: continue
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        }
        return null
    }

    fun describe(n: AccessibilityNodeInfo): String {
        val r = Rect()
        n.getBoundsInScreen(r)
        val label = when {
            n.text != null -> "\"" + oneLine(n.text) + "\""
            n.contentDescription != null -> "desc \"" + oneLine(n.contentDescription) + "\""
            else -> n.viewIdResourceName ?: ""
        }
        return shortClass(n.className) + " " + label + " " + bounds(r)
    }

    fun bounds(r: Rect) = "[${r.left},${r.top}][${r.right},${r.bottom}]"

    private fun shortClass(c: CharSequence?): String {
        if (c == null) return "View"
        val s = c.toString()
        return s.substring(s.lastIndexOf('.') + 1)
    }

    private fun oneLine(t: CharSequence): String {
        val s = t.toString().replace("\n", "\\n").replace("\"", "\\\"")
        return if (s.length > 300) s.substring(0, 300) + "…" else s
    }

    private fun attr(name: String, v: CharSequence?): String {
        val s = v?.toString()?.replace("&", "&amp;")?.replace("\"", "&quot;")?.replace("<", "&lt;")?.replace(">", "&gt;")?.replace("\n", "&#10;") ?: ""
        return " $name=\"$s\""
    }

    private fun windowType(t: Int) = when (t) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "keyboard"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "overlay"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "divider"
        AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY -> "magnifier"
        else -> "type$t"
    }
}
