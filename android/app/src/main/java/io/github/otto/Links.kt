package io.github.otto

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * The hosts the app knows, all from pairing: the box's hub, and the artifact hub (over the tailnet, and its public
 * address if it has one, plus any extra hosts built in with otto.extraLinkHosts).
 */
object Links {
    private fun sameOrigin(u: Uri, base: String): Boolean {
        if (base.isEmpty()) return false
        val b = Uri.parse(base)
        val host = u.host ?: return false
        return host.equals(b.host, ignoreCase = true) && port(u) == port(b)
    }

    private fun port(u: Uri) = if (u.port != -1) u.port else if (u.scheme == "http") 80 else 443

    private val extraHosts: List<String> by lazy {
        BuildConfig.EXTRA_LINK_HOSTS.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun isHub(ctx: Context, u: Uri): Boolean = Pairing.load(ctx)?.let { sameOrigin(u, it.hubUrl) } == true

    /** The artifact hub, over the tailnet or public. */
    fun isArtifactHost(ctx: Context, u: Uri): Boolean {
        val p = Pairing.load(ctx)
        if (p != null && (sameOrigin(u, p.artifactsUrl) || sameOrigin(u, p.publicArtifactsUrl))) return true
        val host = u.host ?: return false
        return extraHosts.any { it.equals(host, ignoreCase = true) }
    }

    /** A link this app opens itself (the rest go on to the browser). */
    fun isOurs(ctx: Context, u: Uri) = isHub(ctx, u) || isArtifactHost(ctx, u)

    /** An artifact link's route in the app's viewer (web/): /a/<id>[/v<n>], /p/<id>[/v<n>] or /v/<version id>. */
    fun viewerHash(u: Uri): String? {
        val path = u.pathSegments
        if (path.size < 2 || !path[1].matches(Regex("[\\w-]{10,}"))) return null
        val scope = path[0]
        val id = path[1]
        if (scope == "v" && path.size == 2) return "view/$id"
        if (scope != "a" && scope != "p") return null
        if (path.size == 2) return "artifact/$id"
        return if (path.size == 3 && path[2].matches(Regex("v\\d+"))) "artifact/$id/${path[2]}" else null
    }

    fun isViewer(hash: String?) =
        hash != null && (hash.startsWith("artifact/") || hash.startsWith("view/") || hash.startsWith("file?"))

    /**
     * A link that isn't ours, to the browser: the one a plain https link goes to (the user's default), never this
     * app. With no default browser set, a chooser without this app.
     */
    fun openElsewhere(ctx: Context, url: String) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pm = ctx.packageManager
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
        val browser = pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        if (browser != null && browser != "android" && browser != ctx.packageName) {
            try {
                ctx.startActivity(Intent(view).setPackage(browser))
                return
            } catch (_: Exception) {
            }
        }
        val ours = pm.queryIntentActivities(view, 0)
            .filter { it.activityInfo.packageName == ctx.packageName }
            .map { android.content.ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
        val chooser = Intent.createChooser(view, null)
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, ours.toTypedArray())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(chooser)
    }
}
