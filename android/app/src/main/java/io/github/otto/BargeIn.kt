package io.github.otto

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Listens while the phone talks (VoiceSpeech's text-to-speech), for the user talking over it. The microphone is
 * opened as for a call (VOICE_COMMUNICATION, with the noise suppressor, and the echo canceller where the phone
 * applies one to media too). The phone's own voice still comes back, so it's measured first: from when sound
 * starts, for 0.6 s, its loudest is the echo's level. After that, only sound well above it, held for a quarter of
 * a second, counts as the user. Then [heard] runs, once, on this thread.
 *
 * [level] is the microphone's loudness for the pill, 0 to 1.
 */
class BargeIn(private val heard: () -> Unit) {
    @Volatile var level = 0f; private set
    @Volatile private var running = true
    private val thread = Thread(::run, "barge-in").apply { start() }

    fun stop() {
        running = false
        level = 0f
    }

    @SuppressLint("MissingPermission") // the call has the microphone already
    private fun run() {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(min, FRAME * 8))
        } catch (e: Exception) {
            Log.w(TAG, "no microphone: $e")
            return
        }
        val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } else null
        val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } else null
        try {
            rec.startRecording()
            Log.i(TAG, "listening over the voice (echo canceller ${aec?.enabled ?: "none"})")
            val buf = ShortArray(FRAME)
            var frames = 0
            var echo = 0.0 // the loudest the phone's own voice came through, while learning
            var peak = 0.0 // the loudest after that, for the log
            var loud = 0 // frames in a row above the bar
            while (running) {
                val n = rec.read(buf, 0, FRAME)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                val rms = sqrt(sum / n) / 32768.0
                level = minOf(1.0, sqrt(rms) * 2.2).toFloat()
                if (frames == 0 && rms < START) continue // the speech hasn't reached the speaker yet
                frames++
                if (frames <= LEARN) { echo = max(echo, rms); continue }
                val bar = max(FLOOR, echo * FACTOR)
                peak = max(peak, rms)
                loud = if (rms > bar) loud + 1 else 0
                if (loud >= HOLD) {
                    Log.i(TAG, "user talked over it (rms %.3f, echo %.3f)".format(rms, echo))
                    if (running) heard()
                    break
                }
            }
            Log.i(TAG, "done: echo %.3f, loudest after %.3f".format(echo, peak))
        } catch (e: Exception) {
            Log.w(TAG, "listening: $e")
        } finally {
            level = 0f
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            aec?.release()
            ns?.release()
        }
    }

    companion object {
        private const val TAG = "BargeIn"
        private const val RATE = 16000
        private const val FRAME = 320 // 20 ms
        private const val START = 0.008 // the speech has started coming back
        private const val LEARN = 30 // its first 600 ms: the echo's level
        private const val HOLD = 12 // 240 ms above the bar
        private const val FLOOR = 0.04 // never below this, however quiet the echo
        private const val FACTOR = 2.5 // how much louder than the echo the user has to be
    }
}
