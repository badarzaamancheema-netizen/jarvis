package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime

/** Fires when a reminder is due: shows it and, if possible, says it out loud. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as JarvisApp
        val id = intent.getLongExtra("id", -1)
        val text = intent.getStringExtra("text") ?: return
        app.store.markReminderFired(id)
        showReminder(context, id, text)
        app.addLine("Jarvis", "Reminder: $text")
        app.speaker.say("Reminder, ${app.prefs.userName}: $text")
    }

    companion object {
        fun showReminder(context: Context, id: Long, text: String) {
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = Notification.Builder(context, JarvisApp.CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Reminder")
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(("reminder$id").hashCode(), notification)
        }
    }
}

/** Android forgets scheduled alarms on reboot, so put the reminders back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as JarvisApp
        val now = LocalDateTime.now()
        for (reminder in app.store.pendingReminders()) {
            val due = runCatching { LocalDateTime.parse(reminder.dueAt) }.getOrNull() ?: continue
            if (due.isAfter(now)) {
                app.phone.scheduleReminder(reminder.id, reminder.text, due)
            } else {
                app.store.markReminderFired(reminder.id)
                ReminderReceiver.showReminder(context, reminder.id, reminder.text)
            }
        }
    }
}
