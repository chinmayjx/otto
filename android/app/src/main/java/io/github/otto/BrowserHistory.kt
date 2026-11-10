package io.github.otto

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

/**
 * The browser's history, for the address bar's suggestions (WebView keeps none an app can search). Only the
 * user's own tabs are recorded: agents' browsing isn't the user's.
 */
class BrowserHistory(context: Context) : SQLiteOpenHelper(context, "history.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history (url TEXT PRIMARY KEY, title TEXT NOT NULL DEFAULT '', visits INTEGER NOT NULL, last INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX history_last ON history (last)")
    }

    override fun onUpgrade(db: SQLiteDatabase, from: Int, to: Int) {}

    fun visit(url: String?, reload: Boolean) {
        if (!worthKeeping(url)) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.execSQL("INSERT INTO history (url, visits, last) VALUES (?, 1, ?) "
            + "ON CONFLICT(url) DO UPDATE SET visits = visits + ?, last = excluded.last", arrayOf<Any?>(url, now, if (reload) 0 else 1))
        if (now % 50 == 0L) db.execSQL("DELETE FROM history WHERE url NOT IN (SELECT url FROM history ORDER BY last DESC LIMIT $KEEP)")
    }

    fun title(url: String?, title: String?) {
        if (!worthKeeping(url) || title.isNullOrEmpty()) return
        writableDatabase.update("history", ContentValues().apply { put("title", title) }, "url = ?", arrayOf(url))
    }

    /**
     * Pages whose address or title has every word of the query, best first: the address starting with the query
     * (after the scheme and www.), then how often and how recently they were visited. Pairs of (url, title).
     */
    fun search(query: String, limit: Int): List<Pair<String, String>> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return emptyList()
        fun like(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val words = q.split(Regex("\\s+"))
        val where = words.joinToString(" AND ") { "(lower(url) LIKE ? ESCAPE '\\' OR lower(title) LIKE ? ESCAPE '\\')" }
        val args = words.flatMap { listOf("%${like(it)}%", "%${like(it)}%") } + "${like(q)}%"
        val bare = "replace(replace(replace(lower(url), 'https://', ''), 'http://', ''), 'www.', '')"
        // Frecency: visits, discounted by age in days.
        val now = System.currentTimeMillis()
        val sql = "SELECT url, title FROM history WHERE $where ORDER BY ($bare LIKE ? ESCAPE '\\') DESC, " +
            "visits * 1.0 / (1 + ($now - last) / 86400000.0) DESC LIMIT $limit"
        val out = ArrayList<Pair<String, String>>()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) to c.getString(1))
        }
        return out
    }

    /** The pages visited last, newest first: pairs of (url, title). */
    fun recent(limit: Int): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        readableDatabase.rawQuery("SELECT url, title FROM history ORDER BY last DESC LIMIT $limit", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) to c.getString(1))
        }
        return out
    }

    companion object {
        private const val KEEP = 5000
        fun worthKeeping(url: String?) = url != null && (url.startsWith("http://") || url.startsWith("https://"))
    }
}
