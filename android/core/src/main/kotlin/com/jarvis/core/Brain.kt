package com.jarvis.core

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.WebSearchTool20260209
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

const val DEFAULT_MODEL = "claude-opus-5"

/**
 * The Claude conversation loop: sends the user's words plus Jarvis's tools,
 * runs any tools Claude asks for, and repeats until Claude answers in text.
 */
class Brain(
    private val client: AnthropicClient,
    private val tools: JarvisTools,
    private val model: String = DEFAULT_MODEL,
    private val userName: String = "sir",
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    private val systemPrompt = """
        You are Jarvis, a witty, imperturbably composed personal AI assistant in the mold of Tony Stark's J.A.R.V.I.S., running on the user's Android phone. Address the user as "$userName".
        Your replies are spoken aloud: keep them to one or two short sentences unless asked for detail, and never use markdown, lists, links or emoji. Latency-sensitive; begin your visible answer immediately.
        Use your tools rather than guessing: web_search for anything current (news, weather, scores, prices), the phone tools to open apps and websites, call, text, WhatsApp, navigate, play things on YouTube, set alarms and timers, and control the flashlight and volume, and the task, note and reminder tools to keep track of things.
        To call or text someone by name, look them up with find_contact first; if there are several numbers, ask which one.
        Each user message starts with the phone's local time in brackets; use it for reminders and anything time-related.
        Never claim you did something unless a tool result says it happened.
    """.trimIndent()

    private val history = mutableListOf<MessageParam>()
    private var lastAsk: ZonedDateTime? = null

    @Synchronized
    fun reset() {
        history.clear()
    }

    /** Sends one user turn and returns Jarvis's spoken reply. Throws on network/API errors. */
    @Synchronized
    fun ask(userText: String): String {
        val now = clock()
        // Start fresh after a long pause or a long conversation: keeps requests
        // cheap and fast, and old context rarely matters to a voice assistant.
        if (lastAsk?.plusMinutes(15)?.isBefore(now) == true || history.size > 40) history.clear()
        lastAsk = now

        val startSize = history.size
        history.add(userMessage("[${now.format(TIME_FORMAT)}]\n$userText"))
        try {
            repeat(MAX_ROUNDS) {
                val response = client.messages().create(buildParams())
                val stop = response.stopReason().orElse(null)

                if (stop == StopReason.REFUSAL) {
                    rollback(startSize)
                    return "I'm afraid I can't help with that one, $userName."
                }
                if (stop == StopReason.MAX_TOKENS) {
                    rollback(startSize)
                    return "That answer ran too long. Ask me for a shorter version."
                }

                assistantMessage(response)?.let { history.add(it) }

                when (stop) {
                    // Server-side web search paused a long turn; send it back to continue.
                    StopReason.PAUSE_TURN -> return@repeat
                    StopReason.TOOL_USE -> history.add(runTools(response))
                    else -> return spokenText(response)
                }
            }
            rollback(startSize)
            return "That took too many steps. Could you try asking another way?"
        } catch (e: Exception) {
            // Leave history exactly as it was so the next request is still valid.
            rollback(startSize)
            throw e
        }
    }

    private fun buildParams(): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(4096L)
            .systemOfTextBlockParams(
                listOf(TextBlockParam.builder().text(systemPrompt).cacheControl(CacheControlEphemeral.builder().build()).build()),
            )
            .messages(history.toList())
            // Voice assistant: fast answers matter more than deep deliberation.
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .addTool(WebSearchTool20260209.builder().maxUses(3L).build())
            // If Claude Opus 5 declines a request, the API retries it on
            // Anthropic's recommended fallback model instead of refusing.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        tools.definitions.forEach { builder.addTool(it) }
        return builder.build()
    }

    private fun runTools(response: Message): MessageParam {
        val results = response.content().mapNotNull { block -> block.toolUse().orElse(null) }.map { call ->
            val input = runCatching { call._input().convert(Map::class.java) }.getOrNull() ?: emptyMap<String, Any>()
            val (result, isError) = tools.run(call.name(), input)
            ContentBlockParam.ofToolResult(
                ToolResultBlockParam.builder().toolUseId(call.id()).content(result).isError(isError).build(),
            )
        }
        return MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build()
    }

    private fun rollback(size: Int) {
        while (history.size > size) history.removeAt(history.size - 1)
    }

    private companion object {
        const val MAX_ROUNDS = 8
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm, VV", Locale.ENGLISH)

        fun userMessage(text: String): MessageParam =
            MessageParam.builder().role(MessageParam.Role.USER).content(text).build()

        /** The assistant turn to replay next request, including thinking and server-tool blocks. */
        fun assistantMessage(response: Message): MessageParam? {
            // Blocks this SDK version can't convert (e.g. newer block types) are skipped.
            val blocks = response.content().mapNotNull { runCatching { it.toParam() }.getOrNull() }
            if (blocks.isEmpty()) return null
            return MessageParam.builder().role(MessageParam.Role.ASSISTANT).contentOfBlockParams(blocks).build()
        }

        fun spokenText(response: Message): String =
            response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString(" ").trim()
                .ifEmpty { "Done." }
    }
}
