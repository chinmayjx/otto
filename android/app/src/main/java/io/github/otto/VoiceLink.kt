package io.github.otto

import android.content.Context
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A call with the assistant, whatever does the listening and speaking: a WebRTC call with a realtime voice
 * (VoiceCall), or the phone's own speech recognizer and text-to-speech (VoiceSpeech). The box decides which
 * (GET /voice/assistant, box/bots' voice server). What the pill shows is read from here each frame.
 */
interface VoiceLink {
    /** The box's word for the call: connecting, listening, thinking, speaking, error, ended. */
    val boxState: String
    /** The assistant is working on something (thinking). */
    val working: Boolean
    val connected: Boolean
    val muted: Boolean
    /** The microphone's and the voice's levels, 0 to 1. */
    val micLevel: Float
    val outLevel: Float
    fun start()
    fun mute(on: Boolean)
    fun end()

    interface Listener {
        /** The call can't go on: [message] says why. Everything is already let go of. */
        fun failed(message: String)
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** Which assistant the box has: {bot, agent, provider, mode, bots}, bot null when none. Blocking. */
        fun assistant(): JSONObject {
            val r = Clips.client().newBuilder().readTimeout(10, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url("${Clips.BASE}/voice/assistant").build()).execute()
            val text = r.use { it.body?.string() ?: "" }
            if (!r.isSuccessful) throw IOException(text.trim().removePrefix("voice: ").ifEmpty { "the box said ${r.code}" })
            return JSONObject(text)
        }

        /** A POST to the voice server with a JSON body; its reply's text. Blocking. */
        fun post(path: String, body: JSONObject = JSONObject(), timeout: Long = 30): String {
            val r = Clips.client().newBuilder().readTimeout(timeout, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url("${Clips.BASE}$path").post(body.toString().toRequestBody(JSON)).build()).execute()
            val text = r.use { it.body?.string() ?: "" }
            if (!r.isSuccessful) throw IOException(text.trim().removePrefix("voice: ").ifEmpty { "the box said ${r.code}" })
            return text
        }

        fun create(ctx: Context, mode: String, events: Listener): VoiceLink =
            if (mode == "android") VoiceSpeech(ctx, events) else VoiceCall(ctx, events)
    }
}

/**
 * The box's events for a call, by long poll, until it ends ([ended] true) or fails. A few misses in a row (the
 * tunnel down) end it. [onEvent] gets each event; it returns false to stop.
 */
class VoiceEvents(private val id: String, private val ended: () -> Boolean, private val fail: (String) -> Unit,
                  private val onEvent: (JSONObject) -> Unit) {
    @Volatile private var call: Call? = null

    fun cancel() { call?.cancel() }

    fun run() {
        val http = Clips.client()
        var after = 0
        var misses = 0
        while (!ended()) {
            try {
                val c = http.newCall(Request.Builder().url("${Clips.BASE}/voice/sessions/$id/events?after=$after").build())
                call = c
                val r = c.execute()
                val text = r.use { it.body?.string() ?: "" }
                if (r.code == 404) return fail("The box no longer has this call")
                if (!r.isSuccessful) throw IOException(text.trim())
                misses = 0
                val o = JSONObject(text)
                val list = o.getJSONArray("events")
                for (i in 0 until list.length()) {
                    val e = list.getJSONObject(i)
                    after = e.getInt("n")
                    if (e.optString("type") == "error") return fail(e.optString("message").ifEmpty { "The call failed" })
                    onEvent(e)
                }
                if (o.optString("state") == "ended") return fail("The call ended on the box")
            } catch (e: IOException) {
                if (ended()) return
                if (++misses >= 3) return fail("Lost the box: ${e.message}")
                Thread.sleep(1000L * misses)
            }
        }
    }
}
