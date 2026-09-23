package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var app: JarvisApp
    private val refresh: () -> Unit = { render() }

    private val status by lazy { findViewById<TextView>(R.id.status) }
    private val toggle by lazy { findViewById<Button>(R.id.toggleListen) }
    private val log by lazy { findViewById<LinearLayout>(R.id.log) }
    private val logScroll by lazy { findViewById<ScrollView>(R.id.logScroll) }
    private val settings by lazy { findViewById<View>(R.id.settings) }
    private val input by lazy { findViewById<EditText>(R.id.input) }

    /** What to do once the microphone permission is granted. */
    private var afterMicPermission: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as JarvisApp
        setContentView(R.layout.activity_main)

        toggle.setOnClickListener {
            if (WakeService.running) {
                app.prefs.listening = false
                WakeService.stop(this)
                render()
            } else {
                withMic { app.prefs.listening = true; WakeService.start(this) }
            }
        }
        findViewById<Button>(R.id.talkNow).setOnClickListener {
            withMic { app.prefs.listening = true; WakeService.start(this, listenNow = true) }
        }
        findViewById<Button>(R.id.send).setOnClickListener { sendTyped() }
        input.setOnEditorActionListener { _, actionId, _ ->
            (actionId == EditorInfo.IME_ACTION_SEND).also { if (it) sendTyped() }
        }
        findViewById<Button>(R.id.settingsBtn).setOnClickListener {
            settings.visibility = if (settings.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            fillSettings()
        }
        findViewById<Button>(R.id.save).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.newChat).setOnClickListener {
            app.resetConversation()
            app.log.clear()
            render()
        }
        findViewById<Button>(R.id.permOverlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.permContacts).setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
        }
        findViewById<Button>(R.id.permBattery).setOnClickListener {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                toast("Already allowed.")
            } else {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }
        findViewById<Button>(R.id.permAlarms).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            ) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            } else {
                toast("Already allowed.")
            }
        }

        if (app.prefs.apiKey.isBlank()) {
            settings.visibility = View.VISIBLE
            fillSettings()
            app.addLine("Jarvis", "Welcome. Paste your Anthropic API key in the settings above and tap Save.")
        }
        handleLaunch(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunch(intent)
    }

    /** Opened by "Hey Google, open Jarvis" or the assistant gesture: start listening right away. */
    private fun handleLaunch(intent: Intent?) {
        if (intent?.action == Intent.ACTION_ASSIST && app.prefs.apiKey.isNotBlank() && hasMic()) {
            app.prefs.listening = true
            WakeService.start(this, listenNow = true)
        } else if (app.prefs.listening && !WakeService.running && hasMic() && app.prefs.apiKey.isNotBlank()) {
            // Resume after the phone restarted or Android stopped the service.
            WakeService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        app.activityVisible = true
        app.addListener(refresh)
        render()
    }

    override fun onPause() {
        app.activityVisible = false
        app.removeListener(refresh)
        super.onPause()
    }

    private fun sendTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        app.ask(text) { reply -> app.speaker.say(reply) }
    }

    private fun fillSettings() {
        findViewById<EditText>(R.id.apiKey).setText(app.prefs.apiKey)
        findViewById<EditText>(R.id.userName).setText(app.prefs.userName)
        findViewById<EditText>(R.id.model).setText(app.prefs.model)
    }

    private fun saveSettings() {
        val key = findViewById<EditText>(R.id.apiKey).text.toString().trim()
        if (!key.startsWith("sk-ant-")) {
            toast("That doesn't look like an Anthropic API key (they start with sk-ant-).")
            return
        }
        app.prefs.apiKey = key
        app.prefs.userName = findViewById<EditText>(R.id.userName).text.toString()
        app.prefs.model = findViewById<EditText>(R.id.model).text.toString()
        settings.visibility = View.GONE
        toast("Saved.")
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun withMic(action: () -> Unit) {
        if (app.prefs.apiKey.isBlank()) {
            settings.visibility = View.VISIBLE
            fillSettings()
            toast("Add your Anthropic API key first.")
            return
        }
        val needed = mutableListOf<String>()
        if (!hasMic()) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isEmpty()) {
            action()
        } else {
            afterMicPermission = action
            requestPermissions(needed.toTypedArray(), REQ_MIC)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (hasMic()) afterMicPermission?.invoke() else toast("Jarvis needs the microphone to hear you.")
            afterMicPermission = null
        }
    }

    private fun render() {
        status.text = app.status
        toggle.text = if (WakeService.running) "Stop listening" else "Start listening for “Hi Jarvis”"
        log.removeAllViews()
        for (line in app.log) {
            val you = line.who == "You"
            val bubble = TextView(this).apply {
                text = line.text
                textSize = 15f
                setTextColor(getColor(if (you) R.color.on_accent else R.color.text))
                setBackgroundResource(if (you) R.drawable.bubble_you else R.drawable.panel)
                setPadding(dp(12), dp(9), dp(12), dp(9))
                setTextIsSelectable(true)
            }
            log.addView(
                bubble,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = if (you) Gravity.END else Gravity.START
                    topMargin = dp(8)
                    if (you) leftMargin = dp(48) else rightMargin = dp(48)
                },
            )
        }
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private companion object {
        const val REQ_MIC = 1
        const val REQ_CONTACTS = 2
    }
}
