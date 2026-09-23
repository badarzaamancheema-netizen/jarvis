package com.jarvis.core

import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolsTest {
    private val now = LocalDateTime.of(2026, 9, 23, 18, 0)
    private val dir: File = Files.createTempDirectory("jarvis").toFile()
    private val phone = FakePhone()
    private val tools = JarvisTools(Store(File(dir, "store.json")), phone) { now }

    @Test
    fun `tool names are unique`() {
        val names = tools.definitions.map { it.name() }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `tasks round trip and survive reload`() {
        assertEquals("Added task #1: buy milk" to false, tools.run("add_task", mapOf("description" to "buy milk")))
        assertEquals("#1: buy milk", tools.run("list_tasks", emptyMap<String, Any>()).first)
        assertEquals("Done.", tools.run("complete_task", mapOf("task_id" to 1)).first)
        assertEquals("No open tasks.", tools.run("list_tasks", emptyMap<String, Any>()).first)
        val reloaded = Store(File(dir, "store.json"))
        assertTrue(reloaded.openTasks().isEmpty())
        tools.run("add_note", mapOf("content" to "wifi is hunter2"))
        assertEquals("wifi is hunter2", Store(File(dir, "store.json")).notes().first().content)
    }

    @Test
    fun `reminders are stored and scheduled on the phone`() {
        val (result, error) = tools.run("add_reminder", mapOf("text" to "stretch", "due_at" to "2026-09-23T18:30:00"))
        assertFalse(error, result)
        assertEquals(listOf("remind 1 stretch 2026-09-23T18:30"), phone.calls)
        assertEquals("#1 at 2026-09-23T18:30: stretch", tools.run("list_reminders", emptyMap<String, Any>()).first)
    }

    @Test
    fun `reminders in the past or with garbage times are rejected`() {
        assertTrue(tools.run("add_reminder", mapOf("text" to "x", "due_at" to "2026-09-23T17:00:00")).second)
        assertTrue(tools.run("add_reminder", mapOf("text" to "x", "due_at" to "tomorrow")).second)
        assertTrue(phone.calls.isEmpty())
    }

    @Test
    fun `phone numbers are cleaned and names are refused`() {
        tools.run("call", mapOf("number" to "+92 300-123 4567"))
        assertEquals("dial +923001234567", phone.calls.last())
        val (msg, error) = tools.run("call", mapOf("number" to "mom"))
        assertTrue(error)
        assertTrue("find_contact" in msg)
    }

    @Test
    fun `url tools build safe links`() {
        tools.run("play_youtube", mapOf("query" to "lofi beats"))
        tools.run("navigate", mapOf("destination" to "Liberty Market, Lahore"))
        tools.run("send_whatsapp", mapOf("message" to "on my way", "number" to "+92 300 1234567"))
        tools.run("open_website", mapOf("url" to "example.com"))
        assertEquals(
            listOf(
                "url https://www.youtube.com/results?search_query=lofi+beats",
                "url https://www.google.com/maps/dir/?api=1&destination=Liberty+Market%2C+Lahore",
                "url https://wa.me/923001234567?text=on%20my%20way",
                "url https://example.com",
            ),
            phone.calls,
        )
        assertTrue(tools.run("open_website", mapOf("url" to "javascript:alert(1)")).second)
        assertTrue(tools.run("open_website", mapOf("url" to "file:///sdcard/x")).second)
        assertEquals("https://localhost:8080", Links.webUrl("localhost:8080"))
        assertNull(Links.cleanNumber("12"))
    }

    @Test
    fun `alarm timer torch and volume validate input`() {
        assertTrue(tools.run("set_alarm", mapOf("hour" to 25, "minute" to 0)).second)
        tools.run("set_alarm", mapOf("hour" to 7, "minute" to 30))
        tools.run("set_timer", mapOf("seconds" to 300))
        tools.run("flashlight", mapOf("on" to true))
        tools.run("set_volume", mapOf("percent" to 150))
        assertEquals(listOf("alarm 7:30 Jarvis alarm", "timer 300", "torch true", "volume 100"), phone.calls)
    }

    @Test
    fun `unknown tools and missing input are errors, not crashes`() {
        assertTrue(tools.run("nope", emptyMap<String, Any>()).second)
        assertTrue(tools.run("add_task", emptyMap<String, Any>()).second)
    }

    @Test
    fun `wake phrases`() {
        for (p in listOf("hi jarvis", "hey jarvis", "jarvis open", "jarvis", "Hi, Jarvis!")) assertTrue(Wake.isWakePhrase(p), p)
        for (p in listOf("", "hi there", "open youtube", "jarvisville")) assertFalse(Wake.isWakePhrase(p), p)
        assertEquals("hi jarvis", Wake.voskText("{\n  \"text\" : \"hi jarvis\"\n}"))
        assertEquals("", Wake.voskText("{\"text\" : \"\"}"))
        assertTrue(Wake.GRAMMAR.startsWith("[\"hi jarvis\"") && Wake.GRAMMAR.endsWith("\"[unk]\"]"))
    }
}
