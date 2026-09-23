package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener as AndroidRecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.jarvis.core.Wake
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Listens for "Hi Jarvis" / "Jarvis open" in the background, including with the
 * screen off, using an offline recognizer restricted to those few phrases (so no
 * audio leaves the phone while waiting). After the wake phrase it hands over to
 * Google's speech recognizer for the actual command, asks Claude, and speaks the reply.
 */
class WakeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: JarvisApp

    private var model: Model? = null
    private var voskService: SpeechService? = null
    private var voskRecognizer: Recognizer? = null
    private var androidRecognizer: SpeechRecognizer? = null
    private var listenWhenReady = false
    private var busy = false // between wake and going back to wake-listening

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = application as JarvisApp
        try {
            startForeground(NOTIFICATION_ID, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Android 14+ refuses to start microphone services from the background
            // (e.g. when restarted after being killed). The user reopens Jarvis to resume.
            Log.w(TAG, "Couldn't start in foreground", e)
            stopSelf()
            return
        }
        running = true
        setStatus("Loading wake word…")
        Thread {
            try {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val loaded = Model(VoskModel.ensure(this).absolutePath)
                main.post {
                    if (!running) {
                        loaded.close()
                        return@post
                    }
                    model = loaded
                    if (listenWhenReady) onWake() else listenForWake()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Wake word model failed to load", e)
                main.post { setStatus("Wake word failed to load: ${e.message}") }
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                app.prefs.listening = false
                stopSelf()
            }
            ACTION_LISTEN_NOW -> if (model == null) listenWhenReady = true else if (!busy) onWake()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        stopVosk()
        androidRecognizer?.destroy()
        androidRecognizer = null
        model?.close()
        model = null
        app.setStatus("Idle")
        super.onDestroy()
    }

    // ---- 1. Waiting for "Hi Jarvis" (offline, grammar-restricted) ----

    private fun listenForWake() {
        busy = false
        val m = model ?: return
        stopVosk()
        try {
            val rec = Recognizer(m, SAMPLE_RATE, Wake.GRAMMAR)
            voskRecognizer = rec
            voskService = SpeechService(rec, SAMPLE_RATE).also {
                it.startListening(object : VoskListener() {
                    override fun onResult(hypothesis: String?) {
                        if (!busy && Wake.isWakePhrase(Wake.voskText(hypothesis))) onWake()
                    }

                    override fun onError(exception: Exception?) {
                        super.onError(exception)
                        main.postDelayed({ if (running && !busy) listenForWake() }, 3000)
                    }
                })
            }
            setStatus("Listening for “Hi Jarvis”")
        } catch (e: Exception) {
            Log.e(TAG, "Mic unavailable", e)
            setStatus("Microphone busy. Retrying…")
            main.postDelayed({ if (running && !busy) listenForWake() }, 3000)
        }
    }

    private fun stopVosk() {
        voskService?.let {
            it.stop()
            it.shutdown()
        }
        voskService = null
        voskRecognizer?.close()
        voskRecognizer = null
    }

    // ---- 2. Woken: acknowledge and capture the command ----

    private fun onWake() {
        busy = true
        stopVosk()
        setStatus("Yes?")
        app.speaker.say("Yes, ${app.prefs.userName}?") { listenForCommand(followUp = false) }
    }

    private fun listenForCommand(followUp: Boolean) {
        if (!running) return
        setStatus(if (followUp) "Anything else?" else "Listening…")
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            listenForCommandOffline(followUp)
            return
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        androidRecognizer = recognizer
        recognizer.setRecognitionListener(object : AndroidRecognitionListener {
            override fun onResults(results: Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                finishAndroidRecognizer()
                handleCommand(text, followUp)
            }

            override fun onError(error: Int) {
                finishAndroidRecognizer()
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> handleCommand(null, followUp)
                    // Google's recognizer can refuse when the phone is locked or offline;
                    // fall back to the on-device model.
                    else -> listenForCommandOffline(followUp)
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1),
        )
    }

    private fun finishAndroidRecognizer() {
        androidRecognizer?.destroy()
        androidRecognizer = null
    }

    /** Less accurate than Google's recognizer, but works offline and on the lock screen. */
    private fun listenForCommandOffline(followUp: Boolean) {
        val m = model ?: return listenForWake()
        stopVosk()
        try {
            val rec = Recognizer(m, SAMPLE_RATE)
            voskRecognizer = rec
            var done = false
            voskService = SpeechService(rec, SAMPLE_RATE).also {
                it.startListening(object : VoskListener() {
                    override fun onResult(hypothesis: String?) {
                        val text = Wake.voskText(hypothesis)
                        if (done || text.isBlank()) return
                        done = true
                        stopVosk()
                        handleCommand(text, followUp)
                    }

                    override fun onTimeout() {
                        if (done) return
                        done = true
                        stopVosk()
                        handleCommand(null, followUp)
                    }
                }, COMMAND_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Offline command recognition failed", e)
            listenForWake()
        }
    }

    // ---- 3. Ask Claude, speak, then allow one follow-up without the wake word ----

    private fun handleCommand(text: String?, followUp: Boolean) {
        if (!running) return
        val command = text?.trim().orEmpty()
        if (command.isEmpty()) {
            if (!followUp) app.speaker.say("I didn't catch that.")
            listenForWake()
            return
        }
        if (Wake.isWakePhrase(command) && command.split(" ").size <= 3) {
            onWake()
            return
        }
        setStatus("Thinking…")
        app.ask(command) { reply ->
            if (!running) return@ask
            setStatus("Speaking…")
            app.speaker.say(reply) { listenForCommand(followUp = true) }
        }
    }

    // ---- Notification ----

    private fun setStatus(text: String) {
        app.setStatus(text)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val talk = PendingIntent.getService(
            this, 2, Intent(this, WakeService::class.java).setAction(ACTION_LISTEN_NOW), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, JarvisApp.CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Talk", talk).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private abstract class VoskListener : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {}
        override fun onResult(hypothesis: String?) {}
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) {
            Log.w(TAG, "Vosk error", exception)
        }
        override fun onTimeout() {}
    }

    companion object {
        private const val TAG = "JarvisWake"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE = 16000f
        private const val COMMAND_TIMEOUT_MS = 8000
        const val ACTION_STOP = "com.jarvis.assistant.STOP"
        const val ACTION_LISTEN_NOW = "com.jarvis.assistant.LISTEN_NOW"

        @Volatile
        var running = false
            private set

        fun start(context: Context, listenNow: Boolean = false) {
            val intent = Intent(context, WakeService::class.java)
            if (listenNow) intent.action = ACTION_LISTEN_NOW
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeService::class.java))
        }
    }
}
