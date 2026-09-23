package com.jarvis.core

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Tool definitions Claude sees, and the dispatcher that runs them. */
class JarvisTools(
    private val store: Store,
    private val phone: PhoneActions,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
) {
    /** Provider-neutral tool list; each AI provider converts it to its own format. */
    val specs: List<ToolSpec> = listOf(
        tool("add_task", "Add an item to the user's to-do list.", mapOf("description" to str()), listOf("description")),
        tool("list_tasks", "List the user's open to-do items with their ids.", emptyMap()),
        tool("complete_task", "Mark a to-do item as done by its id from list_tasks.", mapOf("task_id" to int()), listOf("task_id")),
        tool("add_note", "Save a note for the user to recall later.", mapOf("content" to str()), listOf("content")),
        tool("list_notes", "List the user's saved notes.", emptyMap()),
        tool(
            "add_reminder",
            "Set a reminder that pops up and is spoken at a time. due_at is the user's local time, ISO 8601, e.g. 2026-09-23T18:00:00.",
            mapOf("text" to str(), "due_at" to str()),
            listOf("text", "due_at"),
        ),
        tool("list_reminders", "List pending reminders.", emptyMap()),
        tool("open_app", "Open an app installed on the phone by its name, e.g. YouTube, WhatsApp, Camera, Settings.", mapOf("name" to str()), listOf("name")),
        tool("open_website", "Open a website in the browser.", mapOf("url" to str()), listOf("url")),
        tool(
            "find_contact",
            "Look up a contact's phone numbers by name. Use before calling or texting someone the user names.",
            mapOf("name" to str()),
            listOf("name"),
        ),
        tool("call", "Open the dialer with a phone number ready (the user presses call).", mapOf("number" to str()), listOf("number")),
        tool(
            "send_sms",
            "Open a text message to a number with the message filled in (the user presses send).",
            mapOf("number" to str(), "message" to str()),
            listOf("number", "message"),
        ),
        tool(
            "send_whatsapp",
            "Open WhatsApp with a message filled in. Number is optional, in international format; without it the user picks the chat.",
            mapOf("message" to str(), "number" to str()),
            listOf("message"),
        ),
        tool("navigate", "Start Google Maps directions to a place or address.", mapOf("destination" to str()), listOf("destination")),
        tool("play_youtube", "Search YouTube for a song, video or topic.", mapOf("query" to str()), listOf("query")),
        tool(
            "set_alarm",
            "Set an alarm in the phone's clock app (24-hour time).",
            mapOf("hour" to int(), "minute" to int(), "label" to str()),
            listOf("hour", "minute"),
        ),
        tool("set_timer", "Start a countdown timer.", mapOf("seconds" to int(), "label" to str()), listOf("seconds")),
        tool("flashlight", "Turn the flashlight on or off.", mapOf("on" to mapOf("type" to "boolean")), listOf("on")),
        tool("set_volume", "Set media volume, 0-100.", mapOf("percent" to int()), listOf("percent")),
        tool("battery_status", "Get battery level and whether the phone is charging.", emptyMap()),
    )

    /** The same tools in Anthropic's format. */
    val definitions: List<Tool> by lazy { specs.map { it.toAnthropic() } }

    /** Runs one tool call. Returns (result text, isError). Never throws. */
    fun run(name: String, input: Map<*, *>): Pair<String, Boolean> =
        try {
            dispatch(name, input) to false
        } catch (e: BadInput) {
            (e.message ?: "Invalid input.") to true
        } catch (e: Exception) {
            "The $name tool failed: ${e.message}" to true
        }

    private fun dispatch(name: String, input: Map<*, *>): String {
        fun s(key: String) = (input[key] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: throw BadInput("Missing '$key'.")
        fun sOpt(key: String) = (input[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        fun i(key: String) = (input[key] as? Number)?.toInt() ?: (input[key] as? String)?.toIntOrNull() ?: throw BadInput("Missing number '$key'.")
        fun number(key: String) = Links.cleanNumber(s(key)) ?: throw BadInput("'${input[key]}' isn't a phone number. Use find_contact to look the person up.")

        return when (name) {
            "add_task" -> store.addTask(s("description"), now()).let { "Added task #${it.id}: ${it.description}" }
            "list_tasks" -> store.openTasks().ifEmpty { return "No open tasks." }.joinToString("\n") { "#${it.id}: ${it.description}" }
            "complete_task" -> if (store.completeTask(i("task_id").toLong())) "Done." else "No open task with id ${input["task_id"]}."
            "add_note" -> "Saved note #${store.addNote(s("content"), now()).id}."
            "list_notes" -> store.notes().ifEmpty { return "No saved notes." }.joinToString("\n") { "#${it.id}: ${it.content}" }
            "add_reminder" -> {
                val due = parseTime(s("due_at"))
                if (!due.isAfter(now())) throw BadInput("That time ($due) has already passed. It's now ${now().withNano(0)}.")
                val reminder = store.addReminder(s("text"), due)
                phone.scheduleReminder(reminder.id, reminder.text, due)
                "Reminder #${reminder.id} set for $due."
            }
            "list_reminders" -> store.pendingReminders().ifEmpty { return "No pending reminders." }
                .joinToString("\n") { "#${it.id} at ${it.dueAt}: ${it.text}" }
            "open_app" -> phone.openApp(s("name"))
            "open_website" -> {
                val url = Links.webUrl(s("url")) ?: throw BadInput("I can only open web addresses.")
                phone.openUrl(url, "Opening $url")
            }
            "find_contact" -> phone.findContact(s("name"))
            "call" -> phone.dial(number("number"))
            "send_sms" -> phone.composeSms(number("number"), s("message"))
            "send_whatsapp" -> phone.openUrl(Links.whatsapp(s("message"), sOpt("number")), "Opening WhatsApp")
            "navigate" -> phone.openUrl(Links.directions(s("destination")), "Directions to ${s("destination")}")
            "play_youtube" -> phone.openUrl(Links.youtubeSearch(s("query")), "YouTube: ${s("query")}")
            "set_alarm" -> {
                val hour = i("hour")
                val minute = i("minute")
                if (hour !in 0..23 || minute !in 0..59) throw BadInput("Alarm time must be 00:00-23:59.")
                phone.setAlarm(hour, minute, sOpt("label") ?: "Jarvis alarm")
            }
            "set_timer" -> {
                val seconds = i("seconds")
                if (seconds !in 1..86_400) throw BadInput("Timer must be between 1 second and 24 hours.")
                phone.setTimer(seconds, sOpt("label") ?: "Jarvis timer")
            }
            "flashlight" -> phone.setFlashlight(input["on"] == true || input["on"] == "true")
            "set_volume" -> phone.setVolume(i("percent").coerceIn(0, 100))
            "battery_status" -> phone.batteryStatus()
            else -> throw BadInput("Unknown tool: $name")
        }
    }

    private fun parseTime(text: String): LocalDateTime =
        try {
            LocalDateTime.parse(text)
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(text).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime()
            } catch (_: DateTimeParseException) {
                throw BadInput("Couldn't read the time '$text'. Use ISO 8601 like 2026-09-23T18:00:00.")
            }
        }

    private class BadInput(message: String) : Exception(message)

    private companion object {
        fun str() = mapOf("type" to "string")
        fun int() = mapOf("type" to "integer")

        fun tool(name: String, description: String, props: Map<String, Map<String, String>>, required: List<String> = emptyList()) =
            ToolSpec(name, description, props, required)
    }
}

data class ToolSpec(
    val name: String,
    val description: String,
    val properties: Map<String, Map<String, String>>,
    val required: List<String>,
) {
    fun toAnthropic(): Tool =
        Tool.builder()
            .name(name)
            .description(description)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .apply { properties.forEach { (key, schema) -> putAdditionalProperty(key, JsonValue.from(schema)) } }
                            .build(),
                    )
                    .required(required)
                    .build(),
            )
            .build()
}
