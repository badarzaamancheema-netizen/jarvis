package com.jarvis.assistant

import android.content.Context
import com.jarvis.core.DEFAULT_MODEL

/** Settings typed in on the phone. Kept in app-private storage, never backed up (allowBackup=false). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    var userName: String
        get() = sp.getString("user_name", "sir")?.ifBlank { "sir" } ?: "sir"
        set(v) = sp.edit().putString("user_name", v.trim()).apply()

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL)?.ifBlank { DEFAULT_MODEL } ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v.trim()).apply()

    /** Whether the user left "always listen" on, so the button shows the right state. */
    var listening: Boolean
        get() = sp.getBoolean("listening", false)
        set(v) = sp.edit().putBoolean("listening", v).apply()
}
