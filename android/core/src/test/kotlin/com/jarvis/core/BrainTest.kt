package com.jarvis.core

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Runs the real Anthropic SDK against a fake API server to check the whole loop. */
class BrainTest {
    private val server = MockWebServer().apply { start() }
    private val mapper = ObjectMapper()
    private val phone = FakePhone()
    private val now = ZonedDateTime.of(2026, 9, 23, 18, 0, 0, 0, ZoneId.of("Asia/Karachi"))
    private val brain = Brain(
        client = AnthropicOkHttpClient.builder().apiKey("test-key").baseUrl(server.url("/").toString()).maxRetries(0).build(),
        tools = JarvisTools(Store(File(Files.createTempDirectory("jarvis").toFile(), "s.json")), phone) { LocalDateTime.of(2026, 9, 23, 18, 0) },
        userName = "boss",
        clock = { now },
    )

    @AfterTest
    fun stop() = server.shutdown()

    private fun reply(stopReason: String, vararg content: String) = server.enqueue(
        MockResponse().setHeader("content-type", "application/json").setBody(
            """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5",
               "content":[${content.joinToString(",")}],"stop_reason":"$stopReason","stop_sequence":null,
               "usage":{"input_tokens":10,"output_tokens":5}}""",
        ),
    )

    private fun text(t: String) = """{"type":"text","text":"$t"}"""
    private fun toolUse(id: String, name: String, input: String) = """{"type":"tool_use","id":"$id","name":"$name","input":$input}"""
    private fun nextRequest(): Pair<JsonNode, okhttp3.Headers> = server.takeRequest().let { mapper.readTree(it.body.readUtf8()) to it.headers }

    @Test
    fun `plain answer, request shape`() {
        reply("end_turn", text("Good evening, boss."))
        assertEquals("Good evening, boss.", brain.ask("hi jarvis"))

        val (body, headers) = nextRequest()
        assertEquals("claude-opus-5", body["model"].asText())
        assertEquals("default", body["fallbacks"].asText())
        assertTrue("server-side-fallback-2026-07-01" in headers.values("anthropic-beta").joinToString())
        assertEquals("low", body["output_config"]["effort"].asText())
        assertTrue(body["tools"].any { it["type"]?.asText() == "web_search_20260209" })
        assertTrue(body["tools"].any { it["name"].asText() == "set_alarm" })
        assertEquals("[Wednesday 23 September 2026, 18:00, Asia/Karachi]\nhi jarvis", body["messages"][0]["content"].asText())
        assertTrue("boss" in body["system"][0]["text"].asText())
    }

    @Test
    fun `runs tools and sends results back`() {
        reply("tool_use", text("Setting that."), toolUse("tu_1", "set_timer", """{"seconds":300}"""))
        reply("end_turn", text("Five minutes, starting now."))

        assertEquals("Five minutes, starting now.", brain.ask("five minute timer"))
        assertEquals(listOf("timer 300"), phone.calls)

        nextRequest()
        val (second, _) = nextRequest()
        val messages = second["messages"]
        assertEquals(3, messages.size())
        assertEquals("tool_use", messages[1]["content"][1]["type"].asText())
        val result = messages[2]["content"][0]
        assertEquals("tool_result", result["type"].asText())
        assertEquals("tu_1", result["tool_use_id"].asText())
    }

    @Test
    fun `web search pause_turn is continued and server blocks replayed`() {
        val serverUse = """{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{"query":"weather lahore"}}"""
        val searchResult = """{"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[{"type":"web_search_result","url":"https://example.com","title":"Weather","encrypted_content":"abc","page_age":null}]}"""
        reply("pause_turn", serverUse, searchResult)
        reply("end_turn", text("Thirty-one degrees and sunny."))

        assertEquals("Thirty-one degrees and sunny.", brain.ask("weather in lahore"))
        nextRequest()
        val (second, _) = nextRequest()
        val replayed = second["messages"][1]["content"]
        assertEquals(listOf("server_tool_use", "web_search_tool_result"), replayed.map { it["type"].asText() })
    }

    @Test
    fun `refusal rolls history back`() {
        reply("refusal")
        assertEquals("I'm afraid I can't help with that one, boss.", brain.ask("something bad"))
        reply("end_turn", text("Hello."))
        brain.ask("hello")
        nextRequest()
        val (body, _) = nextRequest()
        assertEquals(1, body["messages"].size())
    }

    @Test
    fun `api errors propagate and leave history clean`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"type":"error","error":{"type":"api_error","message":"boom"}}"""))
        assertFailsWith<Exception> { brain.ask("hello") }
        reply("end_turn", text("Hello."))
        assertEquals("Hello.", brain.ask("hello again"))
        nextRequest()
        val (body, _) = nextRequest()
        assertEquals(1, body["messages"].size())
    }

    @Test
    fun `unknown block types like fallback markers are tolerated`() {
        reply("end_turn", """{"type":"fallback","from":{"model":"claude-opus-5"},"to":{"model":"claude-opus-4-8"}}""", text("Handled."))
        assertEquals("Handled.", brain.ask("hi"))
        reply("end_turn", text("Again."))
        assertEquals("Again.", brain.ask("again"))
    }
}

class ErrorsTest {
    @Test
    fun `api failures become spoken sentences`() {
        val server = MockWebServer().apply { start() }
        val client = AnthropicOkHttpClient.builder().apiKey("bad").baseUrl(server.url("/").toString()).maxRetries(0).build()
        val brain = Brain(client, JarvisTools(Store(File(Files.createTempDirectory("j").toFile(), "s.json")), FakePhone()))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low"}}"""))
        val first = runCatching { brain.ask("hi") }.exceptionOrNull()!!
        val second = runCatching { brain.ask("hi") }.exceptionOrNull()!!
        assertTrue("API key was rejected" in spokenError(first), spokenError(first))
        assertTrue("out of credit" in spokenError(second), spokenError(second))
        assertEquals("I can't reach the internet right now.", spokenError(java.net.UnknownHostException("api.anthropic.com")))
        server.shutdown()
    }
}
