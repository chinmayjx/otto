package io.github.otto

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One live voice call with Codex on the box (otto/box/voice). The phone makes a WebRTC call with its microphone and
 * hands the offer to the box, which starts a Codex realtime session with it and hands back OpenAI's answer: from
 * then on the audio goes straight between the phone and OpenAI, and WebRTC plays the voice as it comes. The box
 * reports what it sees (listening, speaking, a Codex turn running, errors) as events, read by long poll through the
 * tunnel's box API, like clips (Clips.client).
 *
 * What the orb shows is read from here each frame: [boxState], [working], [connected], [muted], and the levels of
 * the microphone ([micLevel]) and of the voice ([outLevel]), 0 to 1. WebRTC work runs on one thread of its own.
 */
class VoiceCall(private val ctx: Context, private val events: Listener) {
    interface Listener {
        /** Something the user should see in a few words (what Codex is doing, a caption). */
        fun note(text: String) {}
        /** The call can't go on: [message] says why. Everything is already let go of. */
        fun failed(message: String)
    }

    @Volatile var boxState = "connecting"; private set
    @Volatile var working = false; private set
    @Volatile var connected = false; private set
    @Volatile var micLevel = 0f; private set
    @Volatile var outLevel = 0f; private set
    @Volatile var muted = false; private set
    @Volatile var ended = false; private set

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "voice") }
    private val http = Clips.client()
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private var adm: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var source: AudioSource? = null
    private var mic: AudioTrack? = null
    private var channel: DataChannel? = null
    private var focus: AudioFocusRequest? = null
    private var oldMode = AudioManager.MODE_NORMAL
    private var session: String? = null
    private var poll: Call? = null
    private val gathered = CountDownLatch(1)

    fun start() = worker.execute {
        try {
            connect()
        } catch (e: Exception) {
            Log.w(TAG, "start: $e")
            fail(e.message ?: e.toString())
        }
    }

    /** Mute: the microphone's track is turned off, so nothing it hears is sent. */
    fun mute(on: Boolean) {
        muted = on
        worker.execute {
            mic?.setEnabled(!on)
            adm?.setMicrophoneMute(on)
        }
    }

    /** Ends the call here and on the box. */
    fun end() {
        if (ended) return
        ended = true
        poll?.cancel()
        worker.execute {
            session?.let { id ->
                try {
                    http.newBuilder().readTimeout(5, TimeUnit.SECONDS).build()
                        .newCall(Request.Builder().url("${Clips.BASE}/voice/sessions/$id/stop").post(EMPTY).build()).execute().close()
                } catch (e: IOException) {
                    Log.w(TAG, "stop: $e") // the box ends a call nobody watches by itself
                }
            }
            release()
        }
        worker.shutdown()
    }

    private fun fail(message: String) {
        if (ended) return
        end()
        events.failed(message)
    }

    // --- Setting up --------------------------------------------------------------------------------------------

    private fun connect() {
        init(ctx)
        routeAudio()
        val adm = JavaAudioDeviceModule.builder(ctx)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setSamplesReadyCallback { s -> micLevel = if (muted) 0f else level(s.data) }
            .createAudioDeviceModule()
        this.adm = adm
        val factory = PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory()
        this.factory = factory
        val constraints = MediaConstraints().apply {
            for (k in listOf("googEchoCancellation", "googNoiseSuppression", "googAutoGainControl", "googHighpassFilter")) {
                mandatory.add(MediaConstraints.KeyValuePair(k, "true"))
            }
        }
        val source = factory.createAudioSource(constraints)
        this.source = source
        val mic = factory.createAudioTrack("mic", source)
        mic.setEnabled(!muted)
        this.mic = mic
        val config = PeerConnection.RTCConfiguration(listOf(PeerConnection.IceServer.builder(STUN).createIceServer())).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = factory.createPeerConnection(config, observer) ?: throw IOException("WebRTC: no peer connection")
        this.pc = pc
        pc.addTrack(mic, listOf("otto"))
        // OpenAI's events come on this channel too; the box's events are enough for the orb, so it's only opened.
        channel = pc.createDataChannel("oai-events", DataChannel.Init())

        val offer = Sdp().also { pc.createOffer(it, MediaConstraints()) }.await()
        Sdp().also { pc.setLocalDescription(it, offer) }.await()
        // The whole offer at once, candidates and all (no trickle): a moment, or what there is by then.
        gathered.await(3, TimeUnit.SECONDS)
        if (ended) return
        val sdp = pc.localDescription?.description ?: throw IOException("WebRTC: no offer")

        val body = JSONObject().put("sdp", sdp).toString().toRequestBody(JSON)
        val r = http.newCall(Request.Builder().url("${Clips.BASE}/voice/sessions").post(body).build()).execute()
        val text = r.use { it.body?.string() ?: "" }
        if (!r.isSuccessful) throw IOException(text.trim().removePrefix("voice: ").ifEmpty { "the box said ${r.code}" })
        val o = JSONObject(text)
        session = o.getString("id")
        if (ended) return // end() is queued behind this, and stops it on the box too
        Sdp().also { pc.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, o.getString("sdp"))) }.await()
        Thread({ pollLoop(o.getString("id")) }, "voice-events").start()
        levels()
    }

    /** Voice calls want the communication mode (the echo canceller works in it), on the speaker unless a headset is in. */
    private fun routeAudio() {
        oldMode = audio.mode
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build().also { audio.requestAudioFocus(it) }
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val devices = audio.availableCommunicationDevices
        val headset = devices.firstOrNull { it.type in HEADSETS }
        (headset ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER })?.let { audio.setCommunicationDevice(it) }
    }

    private fun release() {
        try {
            channel?.close()
            channel?.dispose()
            pc?.close()
            pc?.dispose()
            mic?.dispose()
            source?.dispose()
            factory?.dispose()
            adm?.release()
        } catch (e: Exception) {
            Log.w(TAG, "release: $e")
        }
        channel = null; pc = null; mic = null; source = null; factory = null; adm = null
        audio.clearCommunicationDevice()
        audio.mode = oldMode
        focus?.let { audio.abandonAudioFocusRequest(it) }
        micLevel = 0f
        outLevel = 0f
    }

    // --- While it's on ------------------------------------------------------------------------------------------

    /** The box's events, by long poll, until the call ends. A few misses in a row (the tunnel down) end it. */
    private fun pollLoop(id: String) {
        var after = 0
        var misses = 0
        while (!ended) {
            try {
                val call = http.newCall(Request.Builder().url("${Clips.BASE}/voice/sessions/$id/events?after=$after").build())
                poll = call
                val r = call.execute()
                val text = r.use { it.body?.string() ?: "" }
                if (r.code == 404) return fail("The box no longer has this call")
                if (!r.isSuccessful) throw IOException(text.trim())
                misses = 0
                val o = JSONObject(text)
                val list = o.getJSONArray("events")
                for (i in 0 until list.length()) {
                    val e = list.getJSONObject(i)
                    after = e.getInt("n")
                    when (e.optString("type")) {
                        "state" -> {
                            boxState = e.optString("state")
                            working = e.optBoolean("working")
                        }
                        "activity" -> events.note(e.optString("text"))
                        "error" -> return fail(e.optString("message").ifEmpty { "The call failed" })
                    }
                }
                if (o.optString("state") == "ended") return fail("The call ended on the box")
            } catch (e: IOException) {
                if (ended) return
                if (++misses >= 3) return fail("Lost the box: ${e.message}")
                Thread.sleep(1000L * misses)
            }
        }
    }

    /** The voice's level, from WebRTC's stats, ten times a second. */
    private fun levels() {
        val pc = pc ?: return
        if (ended) return
        pc.getStats { report ->
            val inbound = report.statsMap.values.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "audio" }
            outLevel = ((inbound?.members?.get("audioLevel") as? Number)?.toFloat() ?: 0f).let { min(1f, sqrt(it) * 1.2f) }
        }
        try {
            worker.execute { Thread.sleep(100); levels() }
        } catch (_: Exception) {} // shut down: the call is over
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
            if (s == PeerConnection.IceGatheringState.COMPLETE) gathered.countDown()
        }

        override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "webrtc $s")
            when (s) {
                PeerConnection.PeerConnectionState.CONNECTED -> connected = true
                PeerConnection.PeerConnectionState.FAILED -> fail("Lost the audio connection")
                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                    connected = false
                    // Often back in a moment (a network change); if not, it's over.
                    Thread {
                        Thread.sleep(6000)
                        if (pc?.connectionState() == PeerConnection.PeerConnectionState.DISCONNECTED) fail("Lost the audio connection")
                    }.start()
                }
                else -> {}
            }
        }

        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceCandidate(c: IceCandidate) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onAddStream(s: MediaStream) {}
        override fun onRemoveStream(s: MediaStream) {}
        override fun onDataChannel(d: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
    }

    /** WebRTC's callbacks for offers and answers, waited for. */
    private class Sdp : SdpObserver {
        private val done = CountDownLatch(1)
        private var desc: SessionDescription? = null
        private var error: String? = null

        override fun onCreateSuccess(d: SessionDescription) { desc = d; done.countDown() }
        override fun onSetSuccess() { done.countDown() }
        override fun onCreateFailure(e: String?) { error = e ?: "failed"; done.countDown() }
        override fun onSetFailure(e: String?) { error = e ?: "failed"; done.countDown() }

        fun await(): SessionDescription {
            if (!done.await(10, TimeUnit.SECONDS)) throw IOException("WebRTC: timed out")
            error?.let { throw IOException("WebRTC: $it") }
            return desc ?: SessionDescription(SessionDescription.Type.OFFER, "")
        }
    }

    companion object {
        private const val TAG = "VoiceCall"
        private const val STUN = "stun:stun.l.google.com:19302"
        private val JSON = "application/json".toMediaType()
        private val EMPTY = ByteArray(0).toRequestBody(null)
        private val HEADSETS = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)
        private var initialized = false

        @Synchronized
        private fun init(ctx: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions())
            initialized = true
        }

        /** A buffer of 16-bit PCM's loudness, 0 to 1, spread so speech fills most of it. */
        private fun level(pcm: ByteArray): Float {
            var sum = 0.0
            val n = pcm.size / 2
            if (n == 0) return 0f
            for (i in 0 until n) {
                val v = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xff)).toShort().toDouble()
                sum += v * v
            }
            val rms = sqrt(sum / n) / 32768.0
            return min(1.0, sqrt(rms) * 2.2).toFloat()
        }
    }
}
