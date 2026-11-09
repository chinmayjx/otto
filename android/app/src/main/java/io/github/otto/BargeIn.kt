package io.github.otto

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Listens while the phone talks (Speaker), for the user talking over it. The microphone, with as little processing
 * as the phone allows (VOICE_RECOGNITION), goes through an echo canceller (Echo) fed with what the phone is
 * playing at that moment ([reference]), which takes the phone's own voice out; what's left is the room and the
 * user. The canceller needs a moment to learn the echo, so nothing counts until it's taking most of it out (the
 * average of what's left, against what came in, under [WORKING]). Then sound it can't take out, clearly above the
 * room and held for a quarter of a second, counts as the user: [heard] runs, once, on this thread. If it never
 * learns the echo, the user can't talk over it, but the phone never stops itself.
 *
 * [level] is the microphone's loudness (after the echo is taken out) for the pill, 0 to 1.
 */
class BargeIn(private val state: State, private val reference: (ShortArray) -> Unit, private val heard: () -> Unit) {
    /** What's learned of the echo, kept for the whole call: after the first reply or so, the next ones start ready. */
    class State : AutoCloseable {
        internal val echo = Echo(FRAME, TAIL, RATE)
        @Volatile internal var ratio = 1.0 // what's left of the phone's voice, on average (1: nothing taken out yet)
        override fun close() = echo.close()
    }

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
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(min, FRAME * 8))
        } catch (e: Exception) {
            Log.w(TAG, "no microphone: $e")
            return
        }
        val echo = state.echo
        try {
            rec.startRecording()
            val mic = ShortArray(FRAME)
            val ref = ShortArray(FRAME)
            val out = ShortArray(FRAME)
            var frames = 0
            var loud = 0 // frames in a row that count as the user
            var micSum = 0.0
            var outSum = 0.0 // for the log: how much the canceller took out
            var ratio = state.ratio
            var armed = ratio < WORKING
            while (running) {
                val n = rec.read(mic, 0, FRAME)
                if (n < FRAME) continue
                reference(ref)
                echo.process(mic, ref, out)
                val before = rms(mic)
                val after = rms(out)
                micSum += before
                outSum += after
                level = minOf(1.0, sqrt(after) * 2.2).toFloat()
                frames++
                if (rms(ref) > 0.01 && before > 0.005) ratio = ratio * 0.97 + (after / before) * 0.03
                if (!armed && ratio < WORKING) {
                    armed = true
                    Log.i(TAG, "the echo is learned after %d ms (%.0f%% of it left)".format(frames * 20, ratio * 100))
                }
                if (frames % 50 == 0) Log.d(TAG, "%.1f s: mic %.3f, with the echo out %.3f, left %.0f%%".format(frames / 50.0, before, after, ratio * 100))
                loud = if (armed && after > BAR && after > before * USER) loud + 1 else 0
                if (loud >= HOLD) {
                    Log.i(TAG, "user talked over it (%.3f with the echo out, %.3f before)".format(after, before))
                    if (running) heard()
                    break
                }
            }
            state.ratio = ratio
            if (frames > 0) Log.i(TAG, "done: mic %.3f on average, %.3f with the echo out, over %d ms".format(micSum / frames, outSum / frames, frames * 20))
        } catch (e: Exception) {
            Log.w(TAG, "listening: $e")
        } finally {
            level = 0f
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }

    private fun rms(s: ShortArray): Double {
        var sum = 0.0
        for (v in s) sum += v.toDouble() * v
        return sqrt(sum / s.size) / 32768.0
    }

    companion object {
        private const val TAG = "BargeIn"
        internal const val RATE = Speaker.REF_RATE
        internal const val FRAME = 320 // 20 ms
        internal const val TAIL = 8192 // an echo up to 512 ms late (the playback and recording paths' delay included)
        private const val WORKING = 0.4 // the canceller is taking out most of the echo
        private const val USER = 0.6 // most of the sound got through the canceller: not the phone's own voice
        private const val HOLD = 12 // 240 ms above the bar
        private const val BAR = 0.035 // the user's voice, with the echo out
    }
}
