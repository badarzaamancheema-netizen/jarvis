package com.jarvis.assistant

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Text-to-speech with a completion callback that always fires exactly once, on the main thread. */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(context.applicationContext, this)
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private val pending = mutableListOf<Pair<String, String>>()
    private var ready = false

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        pickVoice()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) = finish(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finish(utteranceId)
            override fun onError(utteranceId: String, errorCode: Int) = finish(utteranceId)
        })
        main.post {
            ready = true
            pending.forEach { (id, text) -> speakNow(text, id) }
            pending.clear()
        }
    }

    /** Prefer a British English voice, the closest to the films' J.A.R.V.I.S. */
    private fun pickVoice() {
        val voices = runCatching { tts.voices }.getOrNull().orEmpty().filter { !it.isNetworkConnectionRequired }
        val british = voices.filter { it.locale.language == "en" && it.locale.country == "GB" }
        // Google's en-GB voices "rjs" and "fis" are the male ones.
        val choice = british.firstOrNull { "rjs" in it.name } ?: british.firstOrNull { "fis" in it.name } ?: british.firstOrNull()
        if (choice != null) tts.voice = choice else tts.language = Locale.UK
    }

    fun say(text: String, onDone: (() -> Unit)? = null) {
        val id = UUID.randomUUID().toString()
        if (onDone != null) {
            callbacks[id] = onDone
            // Some engines never report completion; don't leave Jarvis stuck.
            main.postDelayed({ finish(id) }, 4000L + text.length * 90L)
        }
        main.post { if (ready) speakNow(text, id) else pending += id to text }
    }

    fun stop() = tts.stop()

    private fun speakNow(text: String, id: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun finish(id: String) {
        val callback = callbacks.remove(id) ?: return
        main.post(callback)
    }
}
