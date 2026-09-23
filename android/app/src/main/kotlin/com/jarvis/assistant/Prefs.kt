package com.jarvis.assistant

import android.content.Context
import com.jarvis.core.DEFAULT_MODEL

/** Settings typed in on the phone. Kept in app-private storage, never backed up (allowBackup=false). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    private fun str(key: String, default: String = "") = sp.getString(key, default) ?: default
    private fun put(key: String, value: String) = sp.edit().putString(key, value.trim()).apply()

    /** "gemini" (free tier, the default) or "claude" (paid). */
    var provider: String
        get() = str("provider", GEMINI)
        set(v) = put("provider", v)

    var geminiKey: String
        get() = str("gemini_key")
        set(v) = put("gemini_key", v)

    /** Blank means "pick the newest free Flash model automatically". */
    var geminiModel: String
        get() = str("gemini_model")
        set(v) = put("gemini_model", v)

    var claudeKey: String
        get() = str("api_key")
        set(v) = put("api_key", v)

    var claudeModel: String
        get() = str("model", DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }
        set(v) = put("model", v)

    val activeKey: String get() = if (provider == CLAUDE) claudeKey else geminiKey

    var userName: String
        get() = str("user_name", "sir").ifBlank { "sir" }
        set(v) = put("user_name", v)

    /** Whether the user left "always listen" on, so the button shows the right state. */
    var listening: Boolean
        get() = sp.getBoolean("listening", false)
        set(v) = sp.edit().putBoolean("listening", v).apply()

    companion object {
        const val GEMINI = "gemini"
        const val CLAUDE = "claude"
    }
}
