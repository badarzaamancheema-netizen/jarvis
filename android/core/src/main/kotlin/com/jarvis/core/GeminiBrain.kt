package com.jarvis.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration
import java.time.ZonedDateTime

/** An error response from the Gemini API. */
class GeminiException(val httpStatus: Int, message: String) : RuntimeException(message)

/**
 * Jarvis's brain on Google's Gemini API, which has a free tier (a Google account
 * and a free key from aistudio.google.com; no card). Same tools and persona as the
 * Claude brain. Talks to the REST API directly.
 *
 * Google renames and retires Gemini models often, so by default the model is
 * picked from the live model list (newest Flash first, then Flash-Lite). If one
 * model's free quota runs out, the next one is tried.
 */
class GeminiBrain(
    private val apiKey: String,
    private val tools: JarvisTools,
    private val preferredModel: String = "",
    private val userName: String = "sir",
    private val baseUrl: String = "https://generativelanguage.googleapis.com/",
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(Duration.ofSeconds(90)).build(),
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
) : Assistant {
    private val mapper = ObjectMapper()
    private val contents = mutableListOf<JsonNode>()
    private var lastAsk: ZonedDateTime? = null
    private var models: List<String>? = null

    /** The model that answered most recently, for display. */
    var activeModel: String? = null
        private set

    private val functionDeclarations: ArrayNode = mapper.createArrayNode().apply {
        (tools.specs + WEB_SEARCH).forEach { spec ->
            add(
                mapper.createObjectNode().apply {
                    put("name", spec.name)
                    put("description", spec.description)
                    // Gemini rejects an object schema with no properties, so leave it out.
                    if (spec.properties.isNotEmpty()) {
                        set<JsonNode>(
                            "parameters",
                            mapper.valueToTree(
                                mapOf("type" to "object", "properties" to spec.properties, "required" to spec.required),
                            ),
                        )
                    }
                },
            )
        }
    }

    @Synchronized
    override fun reset() {
        contents.clear()
    }

    @Synchronized
    override fun ask(userText: String): String {
        val now = clock()
        if (lastAsk?.plusMinutes(15)?.isBefore(now) == true || contents.size > 40) contents.clear()
        lastAsk = now

        val startSize = contents.size
        contents.add(textContent("user", Persona.stamp(now, userText)))
        try {
            repeat(MAX_ROUNDS) {
                val response = generate(requestBody(contents, withTools = true))
                val candidate = response.path("candidates").path(0)
                val finish = candidate.path("finishReason").asText("")
                if (candidate.isMissingNode || finish in BLOCKED) {
                    rollback(startSize)
                    return "I'm afraid I can't help with that one, $userName."
                }
                if (finish == "MAX_TOKENS") {
                    rollback(startSize)
                    return "That answer ran too long. Ask me for a shorter version."
                }

                val parts = candidate.path("content").path("parts")
                // Replay the model's turn exactly, including any thought signatures,
                // which newer Gemini models require alongside function calls.
                if (parts.size() > 0) {
                    contents.add(mapper.createObjectNode().put("role", "model").set<JsonNode>("parts", parts))
                }

                val calls = parts.filter { it.has("functionCall") }.map { it["functionCall"] }
                if (calls.isEmpty()) {
                    return parts.filter { !it.path("thought").asBoolean(false) && it.has("text") }
                        .joinToString(" ") { it["text"].asText() }.trim().ifEmpty { "Done." }
                }
                contents.add(functionResponses(calls))
            }
            rollback(startSize)
            return "That took too many steps. Could you try asking another way?"
        } catch (e: Exception) {
            rollback(startSize)
            throw e
        }
    }

    private fun functionResponses(calls: List<JsonNode>): JsonNode {
        val parts = mapper.createArrayNode()
        for (call in calls) {
            val name = call.path("name").asText()
            @Suppress("UNCHECKED_CAST")
            val args = mapper.convertValue(call.path("args"), Map::class.java) as? Map<String, Any?> ?: emptyMap()
            val (result, isError) =
                if (name == WEB_SEARCH.name) webSearch(args["query"]?.toString().orEmpty()) else tools.run(name, args)
            val response = mapper.createObjectNode().put("name", name)
            call.get("id")?.let { response.set<JsonNode>("id", it) }
            response.set<JsonNode>("response", mapper.createObjectNode().put(if (isError) "error" else "result", result))
            parts.add(mapper.createObjectNode().set<JsonNode>("functionResponse", response))
        }
        return mapper.createObjectNode().put("role", "user").set("parts", parts)
    }

    /**
     * Grounded search runs as its own request: older Gemini models can't mix
     * Google Search with function calling in one request.
     */
    private fun webSearch(query: String): Pair<String, Boolean> {
        if (query.isBlank()) return "Missing search query." to true
        return try {
            val body = mapper.createObjectNode().apply {
                set<JsonNode>(
                    "contents",
                    mapper.createArrayNode().add(
                        textContent("user", "Search the web and answer briefly with the key facts, numbers and dates: $query"),
                    ),
                )
                set<JsonNode>("tools", mapper.createArrayNode().add(mapper.createObjectNode().set<JsonNode>("google_search", mapper.createObjectNode())))
            }
            val parts = generate(body).path("candidates").path(0).path("content").path("parts")
            val text = parts.filter { !it.path("thought").asBoolean(false) }.joinToString(" ") { it.path("text").asText() }.trim()
            if (text.isEmpty()) "The search returned nothing." to true else text to false
        } catch (e: Exception) {
            "Web search failed: ${e.message}" to true
        }
    }

    private fun requestBody(history: List<JsonNode>, withTools: Boolean): ObjectNode =
        mapper.createObjectNode().apply {
            set<JsonNode>(
                "systemInstruction",
                mapper.createObjectNode().set<JsonNode>("parts", mapper.createArrayNode().add(mapper.createObjectNode().put("text", Persona.systemPrompt(userName)))),
            )
            set<JsonNode>("contents", mapper.createArrayNode().addAll(history))
            if (withTools) {
                set<JsonNode>("tools", mapper.createArrayNode().add(mapper.createObjectNode().set<JsonNode>("functionDeclarations", functionDeclarations)))
            }
        }

    /** POSTs to the first model that works; moves on when one is missing or out of free quota. */
    private fun generate(body: JsonNode): JsonNode {
        val candidates = modelCandidates()
        var lastError: GeminiException? = null
        for (model in candidates) {
            try {
                val result = post("v1beta/models/$model:generateContent", body)
                if (model != activeModel) {
                    activeModel = model
                    // Keep using the model that works.
                    models = listOf(model) + candidates.filter { it != model }
                }
                return result
            } catch (e: GeminiException) {
                if (e.httpStatus != 404 && e.httpStatus != 429) throw e
                lastError = e
            }
        }
        throw lastError ?: GeminiException(404, "No Gemini model available.")
    }

    private fun modelCandidates(): List<String> {
        if (preferredModel.isNotBlank()) return listOf(preferredModel.trim().removePrefix("models/"))
        models?.let { return it }
        val discovered = try {
            pickModels(get("v1beta/models?pageSize=1000"))
        } catch (e: GeminiException) {
            if (e.httpStatus in 400..403) throw e // bad key: say so rather than guessing models
            emptyList()
        }
        return discovered.ifEmpty { FALLBACK_MODELS }.also { models = it }
    }

    private fun post(path: String, body: JsonNode): JsonNode = execute(
        Request.Builder().url(baseUrl + path).post(mapper.writeValueAsString(body).toRequestBody(JSON)),
    )

    private fun get(path: String): JsonNode = execute(Request.Builder().url(baseUrl + path).get())

    private fun execute(builder: Request.Builder): JsonNode {
        val request = builder.header("x-goog-api-key", apiKey).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { mapper.readTree(text).path("error").path("message").asText() }.getOrNull()
                throw GeminiException(response.code, message?.ifBlank { null } ?: "HTTP ${response.code}")
            }
            return mapper.readTree(text)
        }
    }

    private fun textContent(role: String, text: String): ObjectNode =
        mapper.createObjectNode().put("role", role)
            .set("parts", mapper.createArrayNode().add(mapper.createObjectNode().put("text", text)))

    private fun rollback(size: Int) {
        while (contents.size > size) contents.removeAt(contents.size - 1)
    }

    companion object {
        private const val MAX_ROUNDS = 8
        private val JSON = "application/json".toMediaType()
        private val BLOCKED = setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION", "IMAGE_SAFETY")
        val FALLBACK_MODELS = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite")

        private val WEB_SEARCH = ToolSpec(
            "web_search",
            "Search the web for anything current: news, weather, sports scores, prices, opening hours, facts you're unsure of.",
            mapOf("query" to mapOf("type" to "string")),
            listOf("query"),
        )

        private val FLASH = Regex("^gemini-(\\d+(?:\\.\\d+)?)-flash(-lite)?(-preview)?$")

        /** Newest Flash first (stable before preview), then Flash-Lite as the extra-quota fallback. */
        internal fun pickModels(list: JsonNode): List<String> =
            list.path("models")
                .filter { m -> m.path("supportedGenerationMethods").any { it.asText() == "generateContent" } }
                .mapNotNull { m ->
                    val id = m.path("name").asText().removePrefix("models/")
                    FLASH.matchEntire(id)?.let { match -> Triple(id, match.groupValues[1].toDouble(), match) }
                }
                .sortedWith(
                    compareBy<Triple<String, Double, MatchResult>> { it.third.groupValues[2].isNotEmpty() } // lite last
                        .thenByDescending { it.second }
                        .thenBy { it.third.groupValues[3].isNotEmpty() }, // stable before preview
                )
                .map { it.first }
    }
}
