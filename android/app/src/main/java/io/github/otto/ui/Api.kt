package io.github.otto.ui

import android.content.Context
import android.net.Uri
import io.github.otto.Pairing
import org.json.JSONArray
import org.json.JSONObject

// The services' APIs, as the web UI (otto/web) uses them: the artifact hub's /api/artifacts…, the box's hub's
// /api/fs/…, and the control panel's /api/status|metrics|start|stop. Where they are comes from pairing.

private fun base(s: String) = s.trimEnd('/')

fun artifactsBase(ctx: Context) = base(Pairing.load(ctx)?.artifactsUrl ?: "")
fun publicArtifactsBase(ctx: Context) = base(Pairing.load(ctx)?.let { it.publicArtifactsUrl.ifEmpty { it.artifactsUrl } } ?: "")
fun hubBase(ctx: Context) = base(Pairing.load(ctx)?.hubUrl ?: "")
fun panelBase(ctx: Context) = base(Pairing.load(ctx)?.panelUrl ?: "")

/** An artifact in the list. */
data class ArtifactItem(
    val id: String,
    val title: String,
    val kind: String,
    val label: String,
    val versions: Int,
    val public: Boolean,
    val updated: Double,
    val latestVersion: String,
) {
    companion object {
        fun parseList(text: String): List<ArtifactItem> {
            val a = JSONArray(text)
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                ArtifactItem(
                    id = o.getString("artifact_id"),
                    title = o.optString("title"),
                    kind = o.optString("type"),
                    label = o.optString("label").ifEmpty { o.optString("type") },
                    versions = o.optInt("versions", 1),
                    public = o.optBoolean("public"),
                    updated = o.optDouble("updated", 0.0),
                    latestVersion = o.optString("latest_version"),
                )
            }
        }
    }
}

data class VersionRef(val version: Int, val versionId: String, val note: String, val created: Double)

/** One version of an artifact, as the viewer needs it (GET /api/artifacts/<id>/versions/<n|latest>). */
data class ArtifactVersion(
    val artifactId: String,
    val versionId: String,
    val version: Int,
    val title: String,
    val type: String,
    val filename: String,
    val size: Long,
    val public: Boolean,
    val kind: String,
    val preview: String,
    /** Page sizes in points, for PDFs and office files (null until the hub knows them). */
    val pages: List<Pair<Float, Float>>?,
    val versions: List<VersionRef>,
    /** A version was named, so the viewer stays on it rather than following the latest. */
    val pinned: Boolean,
) {
    val latest get() = versions.maxOfOrNull { it.version } ?: version

    companion object {
        fun parse(text: String, pinned: Boolean): ArtifactVersion {
            val o = JSONObject(text)
            val pages = o.optJSONArray("pages")?.let { a ->
                (0 until a.length()).map { val p = a.getJSONArray(it); p.getDouble(0).toFloat() to p.getDouble(1).toFloat() }
            }
            val vs = o.optJSONArray("versions") ?: JSONArray()
            return ArtifactVersion(
                artifactId = o.getString("artifact_id"),
                versionId = o.getString("version_id"),
                version = o.optInt("version", 1),
                title = o.optString("title"),
                type = o.optString("type"),
                filename = o.optString("filename"),
                size = o.optLong("size"),
                public = o.optBoolean("public"),
                kind = o.optString("kind"),
                preview = o.optString("preview"),
                pages = pages,
                versions = (0 until vs.length()).map { i ->
                    val v = vs.getJSONObject(i)
                    VersionRef(v.optInt("version"), v.optString("version_id"), v.optString("note").takeIf { it != "null" } ?: "",
                        v.optDouble("created", 0.0))
                },
                pinned = pinned,
            )
        }
    }
}

/** What the viewer is asked to show: an artifact (its latest, or version n), one version by id, or a box file. */
sealed class Target {
    data class Artifact(val id: String, val n: Int? = null) : Target()
    data class Version(val id: String) : Target()
    data class File(val path: String) : Target()

    /** The web viewer's route for the same thing (web/, the fallback). */
    val hash: String
        get() = when (this) {
            is Artifact -> "artifact/$id" + (n?.let { "/v$it" } ?: "")
            is Version -> "view/$id"
            is File -> "file?path=" + Uri.encode(path)
        }

    companion object {
        /** From the web viewer's route: artifact/<id>[/v<n>], view/<version id>, file?path=<file>. */
        fun parse(hash: String): Target? {
            if (hash.startsWith("file?")) {
                val path = Uri.parse("x:/?" + hash.substringAfter('?')).getQueryParameter("path") ?: return null
                return File(path)
            }
            val parts = hash.split('/')
            return when {
                parts.size >= 2 && parts[0] == "view" -> Version(parts[1])
                parts.size == 2 && parts[0] == "artifact" -> Artifact(parts[1])
                parts.size == 3 && parts[0] == "artifact" && parts[2].matches(Regex("v\\d+")) -> Artifact(parts[1], parts[2].drop(1).toInt())
                else -> null
            }
        }
    }
}

object ArtifactsApi {
    suspend fun list(ctx: Context): String = Net.text(ctx, "${artifactsBase(ctx)}/api/artifacts?limit=500")

    /** The version's JSON text, and whether it's pinned. As web/artifacts.js load(). */
    suspend fun load(ctx: Context, t: Target): Pair<String, Boolean> {
        val b = artifactsBase(ctx)
        return when (t) {
            is Target.Version -> Net.text(ctx, "$b/api/versions/${Uri.encode(t.id)}") to true
            is Target.Artifact -> try {
                Net.text(ctx, "$b/api/artifacts/${Uri.encode(t.id)}/versions/${t.n ?: "latest"}") to (t.n != null)
            } catch (e: HttpError) {
                if (e.code != 404 || t.n != null) throw e
                Net.text(ctx, "$b/api/versions/${Uri.encode(t.id)}") to true // a share link from before: /p/<version id>
            }
            is Target.File -> throw IllegalArgumentException("not an artifact")
        }
    }

    /** This version's files: /a/<id>/v<n>/<what>. */
    fun files(ctx: Context, v: ArtifactVersion) = "${artifactsBase(ctx)}/a/${v.artifactId}/v${v.version}"
    fun thumb(ctx: Context, versionId: String) = "${artifactsBase(ctx)}/v/$versionId/thumb"

    suspend fun setPublic(ctx: Context, id: String, public: Boolean) =
        Net.send(ctx, "PATCH", "${artifactsBase(ctx)}/api/artifacts/$id", JSONObject().put("public", public).toString())

    suspend fun delete(ctx: Context, id: String) = Net.send(ctx, "DELETE", "${artifactsBase(ctx)}/api/artifacts/$id")
}

data class FsEntry(val name: String, val dir: Boolean, val size: Long?, val mtime: Long, val kind: String)

data class FsListing(val path: String, val parent: String?, val home: String, val entries: List<FsEntry>) {
    companion object {
        fun parse(text: String): FsListing {
            val o = JSONObject(text)
            val a = o.getJSONArray("entries")
            return FsListing(
                path = o.getString("path"),
                parent = o.optString("parent").takeIf { !o.isNull("parent") && it.isNotEmpty() },
                home = o.optString("home"),
                entries = (0 until a.length()).map { i ->
                    val e = a.getJSONObject(i)
                    FsEntry(e.getString("name"), e.optBoolean("dir"), if (e.isNull("size")) null else e.optLong("size"),
                        e.optLong("mtime"), e.optString("kind"))
                },
            )
        }
    }
}

data class FsInfo(val path: String, val name: String, val parent: String, val size: Long, val mtime: Long, val kind: String, val mime: String) {
    companion object {
        fun parse(text: String): FsInfo {
            val o = JSONObject(text)
            return FsInfo(o.getString("path"), o.optString("name"), o.optString("parent"), o.optLong("size"), o.optLong("mtime"),
                o.optString("kind"), o.optString("mime"))
        }
    }
}

object FilesApi {
    fun url(ctx: Context, what: String, path: String, extra: String = "") = "${hubBase(ctx)}/api/fs/$what?path=${Uri.encode(path)}$extra"
    suspend fun list(ctx: Context, path: String) = Net.text(ctx, url(ctx, "list", path))
    suspend fun info(ctx: Context, path: String) = FsInfo.parse(Net.text(ctx, url(ctx, "info", path)))
    fun raw(ctx: Context, path: String) = url(ctx, "raw", path)
}

/** The box's control panel (optional): whether the box runs, its metrics, start and stop. */
data class BoxStatus(val state: String, val name: String, val type: String, val upMinutes: Int) {
    val running get() = state == "running"
    val stopped get() = state == "stopped"
    val label get() = LABELS[state] ?: state

    companion object {
        val LABELS = mapOf("pending" to "Starting…", "running" to "Running", "stopping" to "Stopping…", "stopped" to "Stopped",
            "shutting-down" to "Shutting down…", "terminated" to "Terminated")

        fun parse(text: String): BoxStatus {
            val o = JSONObject(text)
            return BoxStatus(o.optString("state"), o.optString("name"), o.optString("type"), o.optInt("up_minutes"))
        }
    }
}

object PanelApi {
    fun configured(ctx: Context) = panelBase(ctx).isNotEmpty()
    suspend fun status(ctx: Context) = BoxStatus.parse(Net.text(ctx, "${panelBase(ctx)}/api/status"))
    suspend fun metrics(ctx: Context) = JSONObject(Net.text(ctx, "${panelBase(ctx)}/api/metrics"))
    suspend fun act(ctx: Context, what: String) = Net.send(ctx, "POST", "${panelBase(ctx)}/api/$what", headers = mapOf("X-Panel" to "1"))
}
