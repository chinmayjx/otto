package io.github.otto

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast

/**
 * The assistant: a voice call with the bot on the box that is the user's assistant (box/bots' otto_voice channel),
 * through a realtime voice (VoiceCall) or the phone's own speech (VoiceSpeech), as the box says, with its pill over
 * every app (VoicePill). A
 * foreground service of the microphone type, so the call goes on while other apps are in front and with the screen
 * off. Started from the handle's tray, through VoiceActivity (a microphone service has to be started from something
 * the user sees, and that's where Android asks for the microphone the first time). One call at a time; ending it
 * (the pill's ×, or the notification's End) lets go of everything: the call on the box, WebRTC, the microphone, the
 * pill and the service.
 */
class VoiceService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var pill: VoicePill? = null
    private var call: VoiceLink? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        pill = VoicePill(this, ::tapped, ::end).also { it.show() }
        Events.add(this, "assistant on")
        begin()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END) end()
        return START_NOT_STICKY // a call isn't brought back by itself after the process went
    }

    private var starting = false

    private fun begin() {
        val pill = pill ?: return
        if (starting) return
        starting = true
        pill.error = null
        val listener = object : VoiceLink.Listener {
            override fun failed(message: String) = main.post {
                if (call == null) return@post
                call = null
                pill.call = null
                pill.error = message
                Toast.makeText(this@VoiceService, "Assistant: $message", Toast.LENGTH_LONG).show()
            }.let {}
        }
        // The box says which bot is the assistant and how it speaks; the pill shows Connecting meanwhile.
        Thread {
            val mode = try {
                val a = VoiceLink.assistant()
                if (a.isNull("bot")) throw java.io.IOException("No assistant yet: on the box, run otto bot voice <bot>")
                a.optString("mode")
            } catch (e: Exception) {
                main.post {
                    starting = false
                    if (this.pill == null) return@post
                    pill.error = e.message ?: "Can't reach the box"
                    Toast.makeText(this, "Assistant: ${pill.error}", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            main.post {
                starting = false
                if (this.pill == null || call != null) return@post
                call = VoiceLink.create(this, mode, listener).also {
                    pill.call = it
                    it.start()
                }
            }
        }.start()
    }

    /** The pill tapped: while the voice talks, stop it; otherwise mute or unmute; after an error, try again. */
    private fun tapped() {
        val c = call
        when {
            c == null -> begin()
            c.talking && !c.muted -> c.interrupt()
            else -> c.mute(!c.muted)
        }
    }

    private fun end() {
        call?.end()
        call = null
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        call?.end()
        call = null
        pill?.dismiss()
        pill = null
        Events.add(this, "assistant off")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Assistant", NotificationManager.IMPORTANCE_LOW))
        }
        val end = PendingIntent.getService(this, 3, Intent(this, VoiceService::class.java).setAction(ACTION_END), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ccm_mic)
            .setContentTitle("Assistant")
            .setContentText("A voice call with your assistant")
            .addAction(Notification.Action.Builder(null, "End", end).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val ID = 2
        private const val CHANNEL = "voice"
        private const val ACTION_END = "end"

        @Volatile
        var running = false
            private set

        /** Ends the call that's on (a long press on the handle). */
        fun hangUp(ctx: Context) {
            if (running) ctx.startService(Intent(ctx, VoiceService::class.java).setAction(ACTION_END))
        }

        /** From the tray or the handle: a call, unless there's one on already (its pill is on screen). */
        fun start(ctx: Context) {
            if (running) return Toast.makeText(ctx, "The assistant is already on", Toast.LENGTH_SHORT).show()
            ctx.startActivity(Intent(ctx, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
    }
}

/**
 * Invisible, for a moment: asks for the microphone if Otto doesn't have it yet, then starts the assistant's service
 * from the foreground, as Android wants for a service that uses the microphone.
 */
class VoiceActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) go()
        else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) go()
        else {
            Toast.makeText(this, "The assistant needs the microphone", Toast.LENGTH_LONG).show()
            done()
        }
    }

    private fun go() {
        if (!VoiceService.running) startForegroundService(Intent(this, VoiceService::class.java))
        done()
    }

    private fun done() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
