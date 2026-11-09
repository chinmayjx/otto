package io.github.otto

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Dictation for the collector's mic: the microphone streamed to the box's live transcriber (the voice server's
 * WebSocket /voice/transcribe, box/bots transcribe.ts) on a tunnel pipe, like clips (Clips.client), and the words
 * back as they come: a draft of the phrase being written down, then its final text. 16-bit mono PCM at 24 kHz, sent
 * every 200 ms. While it records, DictationService keeps Otto a microphone foreground service, so the lens (an
 * overlay over another app) gets the microphone too. [stop] lets the last phrase come in; [cancel] drops it. The
 * listener is called on the main thread; [Listener.ended] once, at the end, with the reason if it failed.
 */
class Dictation(private val ctx: Context, private val on: Listener) {
    interface Listener {
        fun draft(text: String)
        fun final(text: String)
        fun ended(error: String?)
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var ws: WebSocket? = null
    @Volatile private var recording = false
    @Volatile private var over = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    /** Starts recording and streaming. The app must hold the microphone permission. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return finish("Otto needs the microphone: allow it in Otto's app settings")
        }
        DictationService.start(ctx)
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 4))
        } catch (e: Exception) {
            return finish("Can't open the microphone: ${e.message}")
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return finish("Can't open the microphone")
        }
        record = r
        val client = Clips.client().newBuilder().readTimeout(0, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
        ws = client.newWebSocket(Request.Builder().url("${Clips.BASE}/voice/transcribe").build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val e = try { JSONObject(text) } catch (_: Exception) { return }
                when (e.optString("type")) {
                    "draft" -> main.post { if (!over) on.draft(e.optString("text")) }
                    "final" -> main.post { if (!over) on.final(e.optString("text")) }
                    "error" -> finish(e.optString("message").ifEmpty { "Dictation failed" })
                    "done" -> finish(null)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val why = response?.let { r -> r.body?.string()?.trim()?.removePrefix("voice: ")?.ifEmpty { null } ?: "the box said ${r.code}" }
                finish(if (over) null else "Lost the box: ${why ?: t.message ?: t}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(null)
        })
        recording = true
        r.startRecording()
        thread = Thread({ pump(r) }, "dictation").also { it.start() }
    }

    /** Stops recording; the last phrase still comes in, then [Listener.ended]. */
    fun stop() {
        if (!recording) return
        recording = false
        ws?.send(JSONObject().put("type", "stop").toString())
        // If the box never answers, the dictation ends anyway.
        main.postDelayed({ finish(null) }, 8000)
    }

    /** Stops at once, dropping what's still being written down. */
    fun cancel() {
        recording = false
        finish(null)
    }

    private fun pump(r: AudioRecord) {
        val buf = ByteArray(CHUNK)
        var silencedChecked = false
        val started = System.currentTimeMillis()
        try {
            while (recording) {
                var n = 0
                while (n < buf.size && recording) {
                    val got = r.read(buf, n, buf.size - n)
                    if (got <= 0) break
                    n += got
                }
                if (n > 0) ws?.send(buf.copyOf(n).toByteString())
                // Android gives an app in the background silence, not an error: say so rather than send nothing.
                if (!silencedChecked && System.currentTimeMillis() - started > 600) {
                    silencedChecked = true
                    if (r.activeRecordingConfiguration?.isClientSilenced == true) {
                        finish("Android gave the lens no microphone: open Otto once, then try again")
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "pump: $e")
        } finally {
            try { r.stop() } catch (_: Exception) {}
            r.release()
        }
    }

    private fun finish(error: String?) {
        if (over) return
        over = true
        recording = false
        ws?.let { if (error != null) it.cancel() else it.close(1000, null) }
        ws = null
        DictationService.stop(ctx)
        main.post { on.ended(error) }
    }

    val active get() = !over

    companion object {
        private const val TAG = "Dictation"
        private const val RATE = 24_000
        private const val CHUNK = RATE * 2 / 5 // 200 ms of 16-bit samples
    }
}

/** Keeps Otto a microphone foreground service while it dictates (see Dictation). */
class DictationService : Service() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Dictation", NotificationManager.IMPORTANCE_LOW))
        }
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ccm_mic)
            .setContentTitle("Dictating")
            .setOngoing(true)
            .build()
        try {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            Log.w("Dictation", "foreground: $e") // the recording itself says if Android silenced it
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ID = 4
        private const val CHANNEL = "dictation"

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, DictationService::class.java))
            } catch (e: Exception) {
                Log.w("Dictation", "service: $e")
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, DictationService::class.java))
        }
    }
}
