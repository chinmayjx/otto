package io.github.otto

import android.content.Context

/** What the handle does on a long press, as the user set it on Otto's main screen (MainActivity). */
object HandleSettings {
    const val ASSISTANT = "assistant" // calls the assistant; during a call, hangs up
    const val LENS = "lens"
    const val NOTHING = "nothing" // a long press is a tap

    val CHOICES = listOf(ASSISTANT to "Call the assistant", LENS to "The lens", NOTHING to "Nothing")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("handle", Context.MODE_PRIVATE)

    fun longPress(ctx: Context): String = prefs(ctx).getString("long_press", ASSISTANT)?.takeIf { v -> CHOICES.any { it.first == v } } ?: ASSISTANT

    fun setLongPress(ctx: Context, v: String) = prefs(ctx).edit().putString("long_press", v).apply()

    fun label(v: String) = CHOICES.first { it.first == v }.second
}
