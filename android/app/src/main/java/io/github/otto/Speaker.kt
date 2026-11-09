package io.github.otto

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Otto's voice in Android mode: text-to-speech rendered to audio first, then played by this class as normal media
 * (full quality), so it knows exactly what the phone is playing at each moment. That's the echo canceller's
 * reference ([reference], at 16 kHz): BargeIn takes the phone's own voice out of the microphone with it and hears
 * the user talking over it. Sentences are said one after another, on a thread of its own; [stop] cuts it off.
 *
 * [events] is called on that thread: speaking(true) when the first sound starts, speaking(false) when it has said
 * everything it was given (or was stopped).
 */
class Speaker(ctx: Context, private val events: (speaking: Boolean) -> Unit, failed: (String) -> Unit) {
    private val dir = File(ctx.cacheDir, "speech").apply { mkdirs() }
    private val queue = LinkedBlockingQueue<String>()
    private val done = ConcurrentHashMap<String, CountDownLatch>()
    @Volatile private var ready = false
    @Volatile private var closed = false
    @Volatile private var generation = 0 // bumped by stop(): what's being said or rendered is dropped
    @Volatile private var track: AudioTrack? = null
    @Volatile private var ref: ShortArray? = null // what's being played, at 16 kHz
    @Volatile private var rate = 0
    @Volatile var speaking = false; private set

    private val tts: TextToSpeech = TextToSpeech(ctx) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (!ready) failed("Text-to-speech isn't available on this phone")
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { id?.let { done.remove(it)?.countDown() } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { id?.let { done.remove(it)?.countDown() } }
        })
    }

    private val thread = Thread(::run, "speaker").apply { start() }

    fun say(text: String) { queue.add(text) }

    /** Stops now: what's playing and whatever was waiting. */
    fun stop() {
        generation++
        queue.clear()
        try { track?.pause(); track?.flush() } catch (_: Exception) {}
    }

    fun close() {
        closed = true
        stop()
        thread.interrupt()
        tts.shutdown()
    }

    /**
     * The last [out].size samples the phone has played, at 16 kHz, as of now (silence when it isn't playing): what the
     * microphone is about to hear back, a little ahead of its echo.
     */
    fun reference(out: ShortArray) {
        val t = track
        val r = ref
        if (t == null || r == null || rate == 0) return out.fill(0)
        val end = try { (t.playbackHeadPosition.toLong() * REF_RATE / rate).toInt() } catch (_: Exception) { return out.fill(0) }
        val start = end - out.size
        for (i in out.indices) {
            val j = start + i
            out[i] = if (j in r.indices) r[j] else 0
        }
    }

    private fun run() {
        while (!closed) {
            val text = try { queue.take() } catch (_: InterruptedException) { return }
            val gen = generation
            try {
                while (!ready && !closed) Thread.sleep(50)
                val pcm = render(text) ?: continue
                if (gen != generation) continue
                play(pcm, gen)
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                Log.w(TAG, "say: $e")
            }
            if (queue.isEmpty() && speaking) { speaking = false; events(false) }
        }
    }

    private class Pcm(val samples: ShortArray, val rate: Int)

    /** The text as 16-bit mono PCM, through a WAV file. */
    private fun render(text: String): Pcm? {
        val id = "s${System.nanoTime()}"
        val file = File(dir, "$id.wav")
        val latch = CountDownLatch(1)
        done[id] = latch
        if (tts.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) { done.remove(id); return null }
        latch.await(30, TimeUnit.SECONDS)
        return try { wav(file) } finally { file.delete() }
    }

    private fun wav(file: File): Pcm? {
        RandomAccessFile(file, "r").use { f ->
            val bytes = ByteArray(f.length().toInt()).also { f.readFully(it) }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var rate = 0; var channels = 1; var bits = 16
            var pos = 12
            while (pos + 8 <= bytes.size) {
                val tag = String(bytes, pos, 4)
                val size = b.getInt(pos + 4)
                if (tag == "fmt ") { channels = b.getShort(pos + 10).toInt(); rate = b.getInt(pos + 12); bits = b.getShort(pos + 22).toInt() }
                if (tag == "data") {
                    if (bits != 16 || rate == 0) return null
                    val n = minOf(size, bytes.size - pos - 8) / 2 / channels
                    val out = ShortArray(n)
                    for (i in 0 until n) out[i] = b.getShort(pos + 8 + i * 2 * channels)
                    return Pcm(out, rate)
                }
                pos += 8 + size + (size and 1)
            }
        }
        return null
    }

    private fun play(pcm: Pcm, gen: Int) {
        val min = AudioTrack.getMinBufferSize(pcm.rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(pcm.rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, pcm.rate / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        ref = resample(pcm.samples, pcm.rate)
        rate = pcm.rate
        track = t
        try {
            t.play()
            if (!speaking) { speaking = true; events(true) }
            var off = 0
            val chunk = pcm.rate / 50
            while (off < pcm.samples.size && gen == generation && !closed) {
                val n = t.write(pcm.samples, off, minOf(chunk, pcm.samples.size - off))
                if (n <= 0) break
                off += n
            }
            // Until the last of it has been heard.
            while (gen == generation && !closed && t.playbackHeadPosition < pcm.samples.size) Thread.sleep(20)
        } finally {
            track = null
            ref = null
            try { t.stop() } catch (_: Exception) {}
            t.release()
        }
    }

    /** To 16 kHz, linearly: for the echo canceller's reference only, not for listening. */
    private fun resample(s: ShortArray, from: Int): ShortArray {
        if (from == REF_RATE) return s
        val n = (s.size.toLong() * REF_RATE / from).toInt()
        val out = ShortArray(n)
        val step = from.toDouble() / REF_RATE
        for (i in 0 until n) {
            val x = i * step
            val j = x.toInt()
            val f = x - j
            val a = s[minOf(j, s.size - 1)].toInt()
            val c = s[minOf(j + 1, s.size - 1)].toInt()
            out[i] = (a + (c - a) * f).toInt().toShort()
        }
        return out
    }

    companion object {
        private const val TAG = "Speaker"
        const val REF_RATE = 16000
    }
}
