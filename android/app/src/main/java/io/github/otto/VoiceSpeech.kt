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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sin

/**
 * A call with the assistant through the phone's own speech (box/bots' otto_voice channel with the "android"
 * provider): Android's speech recognizer listens in a loop and each sentence it hears goes to the box, and the
 * assistant's words come back as events, said with text-to-speech. While it speaks, the recognizer rests, so it
 * doesn't hear itself. Nothing in between: everything said goes to the assistant.
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
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val waiting = ArrayList<String>() // words that came before text-to-speech was ready
    private var pending = 0 // utterances queued or being said
    private var session: String? = null
    private var poll: VoiceEvents? = null

    override fun start() {
        main.post {
            tts = TextToSpeech(ctx) { status ->
                Log.i(TAG, "text-to-speech: $status")
                main.post {
                    ttsReady = status == TextToSpeech.SUCCESS
                    if (!ttsReady) return@post fail("Text-to-speech isn't available on this phone")
                    waiting.forEach(::say)
                    waiting.clear()
                }
            }.apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setOnUtteranceProgressListener(progress)
            }
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
        main.post { if (on) rest() else listen() }
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
            tts?.stop()
            tts?.shutdown()
            tts = null
            micLevel = 0f
            speaking = false
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
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName))
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
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            val id = session
            if (text.isNotEmpty() && id != null && !muted) {
                Thread {
                    try { VoiceLink.post("/voice/sessions/$id/heard", JSONObject().put("text", text)) } catch (e: Exception) { Log.w(TAG, "heard: $e") }
                }.start()
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
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // --- Speaking (main thread) ----------------------------------------------------------------------------------

    private fun say(text: String) {
        if (ended) return
        val t = tts
        if (t == null || !ttsReady) { waiting.add(text); return }
        pending++
        speaking = true
        rest()
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "otto-${System.nanoTime()}")
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) = main.post { done() }.let {}
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = main.post { done() }.let {}
    }

    private fun done() {
        pending = (pending - 1).coerceAtLeast(0)
        if (pending > 0) return
        speaking = false
        listen()
    }

    companion object {
        private const val TAG = "VoiceSpeech"
    }
}
