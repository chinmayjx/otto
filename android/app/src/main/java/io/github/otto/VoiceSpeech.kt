package io.github.otto

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sin

/**
 * A call with the assistant through the phone's own speech (box/bots' otto_voice channel with the "android"
 * provider): Android's speech recognizer listens in a loop and what it hears goes to the box, and the assistant's
 * words come back as events, said with text-to-speech. While it speaks, the recognizer rests, so it doesn't hear
 * itself; instead a microphone of its own listens while it speaks (BargeIn), through an echo canceller fed with
 * what the phone is playing (Speaker renders the speech and plays it itself, as normal media, at full quality), and
 * the user talking over it stops it and hands back to the recognizer. A tap on the pill stops it too.
 *
 * Not every scrap goes: a result the recognizer isn't sure of, or a single word that isn't an answer ("so",
 * a cough heard as "the"), is dropped; and pieces are held for a moment and joined, so a pause doesn't cut a
 * sentence in two (the user starting to talk again holds them longer).
 */
class VoiceSpeech(private val ctx: Context, private val events: VoiceLink.Listener) : VoiceLink {
    @Volatile private var state = "connecting"
    @Volatile private var speaking = false
    override val boxState get() = if (speaking) "speaking" else state
    override val working get() = state == "thinking"
    @Volatile override var connected = false; private set
    @Volatile override var muted = false; private set
    @Volatile override var micLevel = 0f; private set
    override val outLevel get() = if (speaking) 0.3f + 0.3f * abs(sin(System.nanoTime() / 9e7)).toFloat() else 0f
    @Volatile private var ended = false

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var speaker: Speaker? = null
    private var session: String? = null
    private var poll: VoiceEvents? = null
    private val said = StringBuilder() // heard, waiting a moment for the rest of the sentence
    private val flush = Runnable { send() }
    private var barge: BargeIn? = null
    private var echo: BargeIn.State? = null // the echo canceller, learned once per call
    private val audio = ctx.getSystemService(android.media.AudioManager::class.java)
    private var hushed = false // the recognizer's beeps' streams are muted

    override fun start() {
        main.post {
            speaker = Speaker(ctx, { on -> main.post { spoke(on) } }, { m -> main.post { fail(m) } })
        }
        Thread({
            try {
                val id = JSONObject(VoiceLink.post("/voice/sessions")).getString("id")
                session = id
                if (ended) return@Thread
                connected = true
                main.post { listen() }
                val ev = VoiceEvents(id, { ended }, ::fail) { e ->
                    when (e.optString("type")) {
                        "state" -> state = e.optString("state")
                        "speak" -> e.optString("text").takeIf { it.isNotBlank() }?.let { t -> main.post { say(t) } }
                    }
                }
                poll = ev
                ev.run()
            } catch (e: Exception) {
                Log.w(TAG, "start: $e")
                fail(e.message ?: e.toString())
            }
        }, "voice-speech").start()
    }

    override fun mute(on: Boolean) {
        muted = on
        main.post { if (on) { rest(); stopBarge() } else listen() }
    }

    override fun end() {
        if (ended) return
        ended = true
        poll?.cancel()
        session?.let { id ->
            Thread {
                try { VoiceLink.post("/voice/sessions/$id/stop", timeout = 5) } catch (e: Exception) { Log.w(TAG, "stop: $e") }
            }.start()
        }
        main.post {
            recognizer?.destroy()
            recognizer = null
            speaker?.close()
            speaker = null
            stopBarge()
            // The listener lets go of the canceller within a frame or two.
            echo?.let { e -> main.postDelayed({ e.close() }, 500) }
            echo = null
            quiet(false)
            micLevel = 0f
            speaking = false
            main.removeCallbacks(flush)
        }
    }

    /**
     * The recognizer beeps each time it starts and stops, and it restarts after every sentence: while it listens,
     * the streams the beeps play on are muted. Never while Otto speaks (its voice is on the media stream), and
     * always undone when the call ends.
     */
    private fun quiet(on: Boolean) {
        if (on == hushed) return
        hushed = on
        for (s in BEEPS) {
            try {
                audio.adjustStreamVolume(s, if (on) android.media.AudioManager.ADJUST_MUTE else android.media.AudioManager.ADJUST_UNMUTE, 0)
            } catch (e: SecurityException) {
                Log.w(TAG, "mute $s: $e") // the notification stream can need Do Not Disturb access
            }
        }
    }

    private fun fail(message: String) {
        if (ended) return
        end()
        events.failed(message)
    }

    // --- Listening (main thread) ---------------------------------------------------------------------------------

    private fun listen() {
        if (ended || muted || speaking || listening || !connected) return
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) return fail("No speech recognizer on this phone")
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(ctx).also {
            it.setRecognitionListener(heard)
            recognizer = it
        }
        listening = true
        quiet(true)
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L))
    }

    private fun rest() {
        listening = false
        micLevel = 0f
        recognizer?.cancel()
    }

    private val heard = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            listening = false
            micLevel = 0f
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val score = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull() ?: -1f
            val text = texts?.firstOrNull()?.trim().orEmpty()
            if (worth(text, score) && !muted) {
                if (said.isNotEmpty()) said.append(' ')
                said.append(text)
                main.removeCallbacks(flush)
                main.postDelayed(flush, HOLD_MS)
            }
            main.post { listen() }
        }

        override fun onError(error: Int) {
            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) Log.i(TAG, "recognizer error $error")
            listening = false
            micLevel = 0f
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail("The assistant needs the microphone")
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_NETWORK ->
                    main.postDelayed({ listen() }, 800)
                else -> main.postDelayed({ listen() }, 150) // nothing heard, a timeout, a cancel: listen again
            }
        }

        override fun onRmsChanged(rmsdB: Float) { if (listening) micLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onReadyForSpeech(params: Bundle?) {}
        // Talking again: what's held waits for the rest.
        override fun onBeginningOfSpeech() { if (said.isNotEmpty()) { main.removeCallbacks(flush); main.postDelayed(flush, HOLD_MS + 4000) } }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** Whether a result is worth sending: the recognizer sure enough of it, and more than a stray word. */
    private fun worth(text: String, score: Float): Boolean {
        if (text.isEmpty()) return false
        if (score in 0f..MIN_CONFIDENCE && score != 0f) return false // 0 or -1: the recognizer gave no score
        val words = text.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.size >= 2 || words.first().trim('.', ',', '!', '?') in ANSWERS
    }

    /** What's held goes to the box as one sentence. */
    private fun send() {
        val text = said.toString().trim()
        said.clear()
        val id = session ?: return
        if (text.isEmpty() || ended) return
        Thread {
            try { VoiceLink.post("/voice/sessions/$id/heard", JSONObject().put("text", text)) } catch (e: Exception) { Log.w(TAG, "heard: $e") }
        }.start()
    }

    // --- Speaking (main thread) ----------------------------------------------------------------------------------

    private fun say(text: String) {
        val s = speaker ?: return
        if (ended) return
        rest()
        quiet(false)
        s.say(text)
    }

    /** The speaker started or finished: listen over it for the user (BargeIn), or go back to the recognizer. */
    private fun spoke(on: Boolean) {
        if (ended) return
        if (on) {
            speaking = true
            rest()
            val state = echo ?: BargeIn.State().also { echo = it }
            if (barge == null && !muted) barge = BargeIn(state, { speaker?.reference(it) ?: it.fill(0) }) {
                main.post { if (speaking) { Log.i(TAG, "barge-in"); hush() } }
            }
        } else {
            speaking = false
            stopBarge()
            main.postDelayed({ listen() }, 150)
        }
    }

    /** A tap on the pill while it talks: stop, and listen. */
    override fun interrupt() { main.post { hush() } }

    /** Stops talking and listens (the microphone a moment later, once the barge-in listener has let go of it). */
    private fun hush() {
        speaker?.stop()
        speaking = false
        stopBarge()
        main.postDelayed({ listen() }, 150)
    }

    private fun stopBarge() {
        barge?.stop()
        barge = null
    }

    companion object {
        private const val TAG = "VoiceSpeech"
        private const val HOLD_MS = 2000L
        private const val MIN_CONFIDENCE = 0.35f
        private val BEEPS = intArrayOf(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.STREAM_SYSTEM,
            android.media.AudioManager.STREAM_NOTIFICATION)
        /** One-word results that are answers, not noise. */
        private val ANSWERS = setOf("yes", "no", "yeah", "yep", "nope", "ok", "okay", "stop", "wait", "thanks", "sure", "cancel", "continue", "done")
    }
}
