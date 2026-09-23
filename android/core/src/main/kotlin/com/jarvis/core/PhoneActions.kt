package com.jarvis.core

import java.time.LocalDateTime

/**
 * Things only the phone can do. The Android app implements this; tests use a fake.
 * Every method returns a short sentence describing what happened, which is passed
 * back to Claude as the tool result.
 */
interface PhoneActions {
    fun openUrl(url: String, label: String): String
    fun dial(number: String): String
    fun composeSms(number: String, message: String): String
    fun openApp(name: String): String
    fun findContact(name: String): String
    fun setAlarm(hour: Int, minute: Int, label: String): String
    fun setTimer(seconds: Int, label: String): String
    fun setFlashlight(on: Boolean): String
    fun setVolume(percent: Int): String
    fun batteryStatus(): String
    fun scheduleReminder(id: Long, text: String, at: LocalDateTime)
}

/** URL building and input cleaning, kept here so it's unit-tested. */
object Links {
    /** Digits plus an optional leading +, or null if it isn't a plausible number. */
    fun cleanNumber(raw: String): String? {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length < 3) return null
        return if (trimmed.startsWith("+")) "+$digits" else digits
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun encQuery(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    fun whatsapp(message: String, number: String?): String =
        "https://wa.me/${number?.let { cleanNumber(it)?.removePrefix("+") } ?: ""}?text=${enc(message)}"

    fun directions(destination: String): String =
        "https://www.google.com/maps/dir/?api=1&destination=${encQuery(destination)}"

    fun youtubeSearch(query: String): String =
        "https://www.youtube.com/results?search_query=${encQuery(query)}"

    /** Adds https:// to bare domains; returns null for anything that isn't http(s). */
    fun webUrl(raw: String): String? {
        val url = raw.trim()
        // A colon followed by a digit is a port ("localhost:8080"), not a scheme.
        val hasScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:(?!\\d)").containsMatchIn(url)
        val full = if (hasScheme) url else "https://$url"
        return if (full.startsWith("https://", ignoreCase = true) || full.startsWith("http://", ignoreCase = true)) full else null
    }
}
