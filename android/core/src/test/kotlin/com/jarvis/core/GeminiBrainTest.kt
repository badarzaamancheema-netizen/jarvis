package com.jarvis.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeminiBrainTest {
    private val mapper = ObjectMapper()
    private val server = MockWebServer()
    private val phone = FakePhone()
    private val requests = mutableListOf<Pair<String, JsonNode?>>()
    private val generateReplies = ArrayDeque<(String) -> MockResponse>()

    private val modelList = """{"models":[
        {"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]},
        {"name":"models/gemini-3.8-flash-preview","supportedGenerationMethods":["generateContent"]},
        {"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent","countTokens"]},
        {"name":"models/gemini-3.8-flash-lite","supportedGenerationMethods":["generateContent"]},
        {"name":"models/gemini-3.8-pro","supportedGenerationMethods":["generateContent"]},
        {"name":"models/gemini-3.8-flash-image","supportedGenerationMethods":["generateContent"]},
        {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]}]}"""

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val body = request.body.readUtf8().takeIf { it.isNotEmpty() }?.let { mapper.readTree(it) }
                synchronized(requests) { requests += path to body }
                if (request.getHeader("x-goog-api-key") != "free-key") return error(400, "API key not valid. Please pass a valid API key.")
                if (path.startsWith("/v1beta/models?")) return json(modelList)
                return generateReplies.removeFirstOrNull()?.invoke(path) ?: error(500, "no reply queued")
            }
        }
        server.start()
    }

    private fun brain(key: String = "free-key", model: String = "") = GeminiBrain(
        apiKey = key,
        tools = JarvisTools(Store(File(Files.createTempDirectory("g").toFile(), "s.json")), phone) { LocalDateTime.of(2026, 9, 23, 18, 0) },
        preferredModel = model,
        userName = "boss",
        baseUrl = server.url("/").toString(),
        clock = { ZonedDateTime.of(2026, 9, 23, 18, 0, 0, 0, ZoneId.of("Asia/Karachi")) },
    )

    @AfterTest
    fun stop() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("content-type", "application/json").setBody(body)
    private fun error(code: Int, message: String) =
        json("""{"error":{"code":$code,"message":"$message","status":"X"}}""").setResponseCode(code)
    private fun reply(finish: String, vararg parts: String) = generateReplies.addLast {
        json("""{"candidates":[{"content":{"role":"model","parts":[${parts.joinToString(",")}]},"finishReason":"$finish"}]}""")
    }
    private fun generateCalls() = requests.filter { it.first.contains(":generateContent") }

    @Test
    fun `picks newest stable flash, then preview, then lite, and skips other models`() {
        assertEquals(
            listOf("gemini-3.8-flash", "gemini-3.8-flash-preview", "gemini-2.5-flash", "gemini-3.8-flash-lite"),
            GeminiBrain.pickModels(mapper.readTree(modelList)),
        )
    }

    @Test
    fun `runs tools, replays thought signatures, and answers`() {
        reply(
            "STOP",
            """{"functionCall":{"id":"call_1","name":"set_timer","args":{"seconds":300}},"thoughtSignature":"sig-abc"}""",
        )
        reply("STOP", """{"text":"Five minutes, boss.","thoughtSignature":"sig-def"}""")

        val b = brain()
        assertEquals("Five minutes, boss.", b.ask("five minute timer"))
        assertEquals(listOf("timer 300"), phone.calls)
        assertEquals("gemini-3.8-flash", b.activeModel)

        val (path, first) = generateCalls()[0]
        assertEquals("/v1beta/models/gemini-3.8-flash:generateContent", path)
        assertTrue("boss" in first!!["systemInstruction"]["parts"][0]["text"].asText())
        assertEquals("[Wednesday 23 September 2026, 18:00, Asia/Karachi]\nfive minute timer", first["contents"][0]["parts"][0]["text"].asText())
        val decls = first["tools"][0]["functionDeclarations"]
        assertTrue(decls.any { it["name"].asText() == "web_search" })
        assertFalse(decls.first { it["name"].asText() == "battery_status" }.has("parameters"), "no-arg tools must omit parameters")
        assertEquals("integer", decls.first { it["name"].asText() == "set_timer" }["parameters"]["properties"]["seconds"]["type"].asText())

        val second = generateCalls()[1].second!!["contents"]
        assertEquals("sig-abc", second[1]["parts"][0]["thoughtSignature"].asText())
        val fr = second[2]["parts"][0]["functionResponse"]
        assertEquals("set_timer", fr["name"].asText())
        assertEquals("call_1", fr["id"].asText())
        assertEquals("timer", fr["response"]["result"].asText())
    }

    @Test
    fun `web search runs as a separate grounded request`() {
        reply("STOP", """{"functionCall":{"name":"web_search","args":{"query":"weather lahore"}}}""")
        reply("STOP", """{"text":"thinking...","thought":true}""", """{"text":"Lahore: 31C, sunny."}""")
        reply("STOP", """{"text":"Thirty-one degrees and sunny."}""")

        assertEquals("Thirty-one degrees and sunny.", brain().ask("weather in lahore"))
        val search = generateCalls()[1].second!!
        assertTrue(search["tools"][0].has("google_search"))
        assertFalse(search.has("systemInstruction"))
        val fr = generateCalls()[2].second!!["contents"][2]["parts"][0]["functionResponse"]
        assertEquals("Lahore: 31C, sunny.", fr["response"]["result"].asText())
    }

    @Test
    fun `out of free quota on one model falls through to the next and sticks`() {
        generateReplies.addLast { error(429, "Resource has been exhausted (e.g. check quota).") }
        reply("STOP", """{"text":"Hello."}""")
        reply("STOP", """{"text":"Again."}""")

        val b = brain()
        assertEquals("Hello.", b.ask("hi"))
        assertEquals("Again.", b.ask("again"))
        assertEquals(
            listOf(
                "/v1beta/models/gemini-3.8-flash:generateContent",
                "/v1beta/models/gemini-3.8-flash-preview:generateContent",
                "/v1beta/models/gemini-3.8-flash-preview:generateContent",
            ),
            generateCalls().map { it.first },
        )
    }

    @Test
    fun `all models out of quota gives a spoken quota message and clean history`() {
        repeat(4) { generateReplies.addLast { error(429, "Resource has been exhausted") } }
        val b = brain()
        val e = assertFailsWith<GeminiException> { b.ask("hi") }
        assertTrue("free Gemini allowance" in spokenError(e))
        reply("STOP", """{"text":"Back."}""")
        assertEquals("Back.", b.ask("hi again"))
        assertEquals(1, generateCalls().last().second!!["contents"].size())
    }

    @Test
    fun `blocked prompt is declined and rolled back`() {
        generateReplies.addLast { json("""{"promptFeedback":{"blockReason":"SAFETY"}}""") }
        reply("STOP", """{"text":"Hello."}""")
        val b = brain()
        assertEquals("I'm afraid I can't help with that one, boss.", b.ask("bad"))
        b.ask("hello")
        assertEquals(1, generateCalls().last().second!!["contents"].size())
    }

    @Test
    fun `bad key is reported, not hidden behind model guessing`() {
        val e = assertFailsWith<GeminiException> { brain(key = "wrong").ask("hi") }
        assertEquals("Your Gemini API key was rejected. Check it in Jarvis settings.", spokenError(e))
    }

    @Test
    fun `explicit model skips discovery`() {
        reply("STOP", """{"text":"Hi."}""")
        brain(model = "models/gemini-2.5-flash").ask("hi")
        assertEquals(listOf("/v1beta/models/gemini-2.5-flash:generateContent"), requests.map { it.first })
    }
}
