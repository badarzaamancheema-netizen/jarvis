package com.jarvis.assistant

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import com.jarvis.core.PhoneActions
import java.time.LocalDateTime
import java.time.ZoneId

class AndroidPhone(private val context: Context) : PhoneActions {
    private val app get() = context.applicationContext as JarvisApp

    /**
     * Android blocks apps from opening other apps from the background. If Jarvis's
     * screen is open, or the user granted "Display over other apps", we open it
     * directly; otherwise we post a notification the user can tap.
     */
    private fun launch(intent: Intent, label: String): String {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (app.activityVisible || Settings.canDrawOverlays(context)) {
            return try {
                context.startActivity(intent)
                "$label."
            } catch (_: ActivityNotFoundException) {
                "No app on this phone can do that."
            } catch (e: SecurityException) {
                "Android blocked that: ${e.message}"
            }
        }
        postTapToOpen(intent, label)
        return "$label: Android only allows this from a tap, so it's waiting in the notifications. Tell the user to tap it."
    }

    private fun postTapToOpen(intent: Intent, label: String) {
        val pending = PendingIntent.getActivity(
            context, label.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, JarvisApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Jarvis")
            .setContentText("Tap: $label")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(label.hashCode(), notification)
    }

    override fun openUrl(url: String, label: String): String =
        launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)), label)

    override fun dial(number: String): String =
        launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))), "Dialing $number")

    override fun composeSms(number: String, message: String): String =
        launch(
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", message),
            "Text to $number ready",
        )

    override fun openApp(name: String): String {
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        ).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val wanted = name.trim().lowercase()
        val match = apps.firstOrNull { it.first.lowercase() == wanted }
            ?: apps.firstOrNull { it.first.lowercase().startsWith(wanted) }
            ?: apps.firstOrNull { wanted in it.first.lowercase() }
            ?: return "There's no app called $name on this phone."
        val intent = pm.getLaunchIntentForPackage(match.second) ?: return "I can't open ${match.first}."
        return launch(intent, "Opening ${match.first}")
    }

    override fun findContact(name: String): String {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "I don't have permission to read contacts. The user can allow it on the Jarvis screen under Settings."
        }
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name))
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
        )
        val found = linkedSetOf<String>()
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext() && found.size < 6) {
                val type = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, c.getInt(2), "").toString()
                found += "${c.getString(0)}: ${c.getString(1)} ($type)"
            }
        }
        return if (found.isEmpty()) "No contact matches '$name'." else found.joinToString("\n")
    }

    override fun setAlarm(hour: Int, minute: Int, label: String): String =
        launch(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
            "Alarm set for %02d:%02d".format(hour, minute),
        )

    override fun setTimer(seconds: Int, label: String): String =
        launch(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
            "Timer started for $seconds seconds",
        )

    override fun setFlashlight(on: Boolean): String {
        val cm = context.getSystemService(CameraManager::class.java)
        return try {
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "This phone has no flashlight."
            cm.setTorchMode(id, on)
            if (on) "Flashlight on." else "Flashlight off."
        } catch (e: Exception) {
            "I couldn't switch the flashlight: ${e.message}"
        }
    }

    override fun setVolume(percent: Int): String {
        val am = context.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * percent + 50) / 100, 0)
        return "Media volume set to $percent percent."
    }

    override fun batteryStatus(): String {
        val bm = context.getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "Battery is at $level percent" + if (bm.isCharging) " and charging." else ", not charging."
    }

    override fun scheduleReminder(id: Long, text: String, at: LocalDateTime) {
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pending = PendingIntent.getBroadcast(
            context,
            id.toInt(),
            Intent(context, ReminderReceiver::class.java).putExtra("id", id).putExtra("text", text),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = context.getSystemService(AlarmManager::class.java)
        // Exact timing needs the "Alarms & reminders" permission on Android 12+;
        // without it Android may deliver the reminder a few minutes late.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
        }
    }
}
