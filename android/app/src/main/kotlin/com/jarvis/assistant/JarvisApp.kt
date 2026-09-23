package com.jarvis.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Handler
import android.os.Looper
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.jarvis.core.Assistant
import com.jarvis.core.Brain
import com.jarvis.core.GeminiBrain
import com.jarvis.core.JarvisTools
import com.jarvis.core.Store
import com.jarvis.core.spokenError
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Shared state for the screen, the listening service and the reminder receivers. */
class JarvisApp : Application() {
    lateinit var store: Store
    lateinit var phone: AndroidPhone
    lateinit var speaker: Speaker
    lateinit var prefs: Prefs

    /** Claude requests run one at a time, off the main thread. */
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var brain: Assistant? = null
    private var brainConfig: String? = null

    @Volatile var activityVisible = false

    data class Line(val who: String, val text: String)
    val log = CopyOnWriteArrayList<Line>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    var status: String = "Idle"
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        store = Store(File(filesDir, "jarvis-data.json"))
        phone = AndroidPhone(this)
        speaker = Speaker(this)
        createChannels()
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    private fun changed() = main.post { listeners.forEach { it() } }

    fun addLine(who: String, text: String) {
        log.add(Line(who, text))
        while (log.size > 100) log.removeAt(0)
        changed()
    }

    fun setStatus(text: String) {
        status = text
        changed()
    }

    /** Rebuilt whenever the provider, key, model or name changes in settings. */
    @Synchronized
    private fun currentBrain(): Assistant? {
        val p = prefs
        val key = p.activeKey
        if (key.isBlank()) return null
        val model = if (p.provider == Prefs.CLAUDE) p.claudeModel else p.geminiModel
        val config = "${p.provider}|$key|$model|${p.userName}"
        if (config != brainConfig) {
            val tools = JarvisTools(store, phone)
            brain = if (p.provider == Prefs.CLAUDE) {
                val client = AnthropicOkHttpClient.builder().apiKey(key).timeout(Duration.ofSeconds(90)).build()
                Brain(client, tools, model, p.userName)
            } else {
                GeminiBrain(key, tools, model, p.userName)
            }
            brainConfig = config
        }
        return brain
    }

    fun resetConversation() = worker.execute { currentBrain()?.reset() }

    /** Ask Jarvis something; the reply comes back on the main thread. */
    fun ask(text: String, onReply: (String) -> Unit) {
        addLine("You", text)
        worker.execute {
            val reply = try {
                currentBrain()?.ask(text) ?: "I need an API key first. Open Jarvis and add one in Settings."
            } catch (e: Exception) {
                spokenError(e)
            }
            main.post {
                addLine("Jarvis", reply)
                onReply(reply)
            }
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LISTENING, "Listening", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Jarvis listens for \"Hi Jarvis\""
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Reminders and actions", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders, and apps Jarvis couldn't open by itself"
            },
        )
    }

    companion object {
        const val CHANNEL_LISTENING = "listening"
        const val CHANNEL_ALERTS = "alerts"
    }
}
