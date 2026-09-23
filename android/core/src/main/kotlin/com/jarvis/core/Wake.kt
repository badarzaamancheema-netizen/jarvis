package com.jarvis.core

object Wake {
    // Common ways speech recognizers spell "Jarvis".
    private val NAME = Regex("\\b(jarvis|jarvi|jarves|javis|jervis|jarbis)\\b", RegexOption.IGNORE_CASE)

    /** Phrases the offline wake-word recognizer is restricted to. "[unk]" soaks up everything else. */
    val GRAMMAR: String = listOf("hi jarvis", "hey jarvis", "hello jarvis", "okay jarvis", "jarvis open", "jarvis", "[unk]")
        .joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

    fun isWakePhrase(text: String?): Boolean = text != null && NAME.containsMatchIn(text)

    /** Pulls "text" out of a Vosk result like {"text" : "hi jarvis"}. */
    fun voskText(json: String?): String {
        if (json == null) return ""
        return Regex("\"text\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)?.trim() ?: ""
    }
}
