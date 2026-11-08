package io.github.otto.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Line icons, the web UI's own (web/ui.js), drawn on a 24-unit grid. */
object Ic {
    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"
    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r $r" + "v${h - 2 * r}a$r $r 0 0 1 ${-r} $r" + "h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}" +
            "v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (p in paths) {
            b.addPath(addPathNodes(p), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
        return b.build()
    }

    val back = icon("back", "M15 18 9 12l6-6")
    val download = icon("download", "M12 4v11m-5-5 5 5 5-5M5 20h14")
    val more = icon("more", circle(12f, 5f, 1f), circle(12f, 12f, 1f), circle(12f, 19f, 1f))
    val latest = icon("latest", "M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5")
    val search = icon("search", circle(11f, 11f, 7f), "m20 20-3.5-3.5")
    val share = icon("share", circle(16.5f, 6f, 2f), circle(7.5f, 12f, 2f), circle(16.5f, 18f, 2f), "m9.2 11 5.6-3.9m-5.6 5.8 5.6 3.9")
    val link = icon("link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1")
    val open = icon("open", "M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5")
    val trash = icon("trash", "M4 7h16M9 7V4h6v3m-8 0 1 13h8l1-13")
    val lock = icon("lock", rect(5f, 11f, 14f, 9f, 1.5f), "M8 11V8a4 4 0 0 1 8 0v3")
    val folder = icon("folder", "M3.5 7A2 2 0 0 1 5.5 5h3.6l2 2h7.4a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z")
    val file = icon("file", "M7 3h7l5 5v11a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M14 3v5h5")
    val copy = icon("copy", rect(8f, 8f, 12f, 12f, 1.5f), "M16 8V5.5A1.5 1.5 0 0 0 14.5 4h-9A1.5 1.5 0 0 0 4 5.5v9A1.5 1.5 0 0 0 5.5 16H8")
    val close = icon("close", "M6 6l12 12M18 6 6 18")
    val check = icon("check", "m5 12.5 4.5 4.5L19 7.5")
    val power = icon("power", "M12 3v9", "M6.3 6.8a8 8 0 1 0 11.4 0")
    val refresh = icon("refresh", "M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5")
    val chevron = icon("chevron", "m9 6 6 6-6 6")
    val wrap = icon("wrap", "M4 6h16M4 12h13a3 3 0 0 1 0 6h-4m2-2-2 2 2 2M4 18h5")
}
