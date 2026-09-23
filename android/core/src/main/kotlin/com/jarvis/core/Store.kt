package com.jarvis.core

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.time.LocalDateTime

// Plain classes with defaults so Jackson can build them without the Kotlin module.
class Task(var id: Long = 0, var description: String = "", var done: Boolean = false, var createdAt: String = "")
class Reminder(var id: Long = 0, var text: String = "", var dueAt: String = "", var fired: Boolean = false)
class Note(var id: Long = 0, var content: String = "", var createdAt: String = "")

class StoreData(
    var nextId: Long = 1,
    var tasks: MutableList<Task> = mutableListOf(),
    var reminders: MutableList<Reminder> = mutableListOf(),
    var notes: MutableList<Note> = mutableListOf(),
)

/** Tasks, reminders and notes, kept in one small JSON file on the phone. */
class Store(private val file: File) {
    private val mapper = ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private var data: StoreData = load()

    private fun load(): StoreData =
        if (file.exists()) {
            runCatching { mapper.readValue(file, StoreData::class.java) }.getOrElse { StoreData() }
        } else {
            StoreData()
        }

    private fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        mapper.writeValue(tmp, data)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun nextId(): Long = data.nextId++

    @Synchronized
    fun addTask(description: String, now: LocalDateTime): Task =
        Task(nextId(), description, false, now.toString()).also { data.tasks.add(it); save() }

    @Synchronized
    fun openTasks(): List<Task> = data.tasks.filter { !it.done }

    @Synchronized
    fun completeTask(id: Long): Boolean {
        val task = data.tasks.find { it.id == id && !it.done } ?: return false
        task.done = true
        save()
        return true
    }

    @Synchronized
    fun addReminder(text: String, dueAt: LocalDateTime): Reminder =
        Reminder(nextId(), text, dueAt.toString(), false).also { data.reminders.add(it); save() }

    @Synchronized
    fun pendingReminders(): List<Reminder> = data.reminders.filter { !it.fired }.sortedBy { it.dueAt }

    @Synchronized
    fun markReminderFired(id: Long) {
        data.reminders.find { it.id == id }?.let { it.fired = true; save() }
    }

    @Synchronized
    fun addNote(content: String, now: LocalDateTime): Note =
        Note(nextId(), content, now.toString()).also { data.notes.add(it); save() }

    @Synchronized
    fun notes(): List<Note> = data.notes.sortedByDescending { it.id }
}
