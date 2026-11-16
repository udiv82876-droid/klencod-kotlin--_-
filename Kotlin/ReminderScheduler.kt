package com.alzubair.omri

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

object ReminderScheduler {
    private const val CHANNEL_ID = "omri_reminders"
    private const val DAILY_ID = 71001
    private const val BIRTHDAY_ID = 71002
    private const val BEFORE_BIRTHDAY_ID = 71003

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "إشعارات عُمري", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "تذكيرات العمر والمواعيد" }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun showNow(context: Context, title: String, message: String, id: Int = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()) {
        createChannel(context)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL_ID)
                      else @Suppress("DEPRECATION") Notification.Builder(context)
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun alarm(context: Context, id: Int, time: Long, title: String, message: String, kind: String) {
        if (time <= System.currentTimeMillis()) return
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("id", id)
            putExtra("title", title)
            putExtra("message", message)
            putExtra("kind", kind)
        }
        val pending = PendingIntent.getBroadcast(
            context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= 23) manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
        else manager.set(AlarmManager.RTC_WAKEUP, time, pending)
    }

    fun cancel(context: Context, id: Int) {
        val pending = PendingIntent.getBroadcast(
            context, id, Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pending != null) {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending)
            pending.cancel()
        }
        val prefs = context.getSharedPreferences("omri_native", Context.MODE_PRIVATE)
        val old = try { JSONArray(prefs.getString("custom", "[]")) } catch (_: Exception) { JSONArray() }
        val kept = JSONArray()
        for (i in 0 until old.length()) {
            val item = old.optJSONObject(i) ?: continue
            if (item.optString("id").hashCode() != id) kept.put(item)
        }
        prefs.edit().putString("custom", kept.toString()).apply()
    }

    fun scheduleCustom(context: Context, item: JSONObject) {
        val epoch = item.optLong("epoch", 0L)
        if (epoch <= System.currentTimeMillis()) return
        val idString = item.optString("id", epoch.toString())
        val id = idString.hashCode()
        alarm(context, id, epoch, "عُمري | My Age", item.optString("message", "لديك تذكير"), "custom")
        val prefs = context.getSharedPreferences("omri_native", Context.MODE_PRIVATE)
        val old = try { JSONArray(prefs.getString("custom", "[]")) } catch (_: Exception) { JSONArray() }
        val kept = JSONArray()
        for (i in 0 until old.length()) {
            val existing = old.optJSONObject(i) ?: continue
            if (existing.optString("id") != idString) kept.put(existing)
        }
        kept.put(item)
        prefs.edit().putString("custom", kept.toString()).apply()
    }

    fun rescheduleAll(context: Context) {
        val prefs = context.getSharedPreferences("omri_native", Context.MODE_PRIVATE)
        val settings = try { JSONObject(prefs.getString("settings", "{}")) } catch (_: Exception) { JSONObject() }

        cancelAlarmOnly(context, DAILY_ID)
        cancelAlarmOnly(context, BIRTHDAY_ID)
        cancelAlarmOnly(context, BEFORE_BIRTHDAY_ID)

        if (!settings.optBoolean("notifyEnabled", false)) {
            val custom = try { JSONArray(prefs.getString("custom", "[]")) } catch (_: Exception) { JSONArray() }
            for (i in 0 until custom.length()) custom.optJSONObject(i)?.let { cancelAlarmOnly(context, it.optString("id").hashCode()) }
            return
        }

        if (settings.optBoolean("daily", false)) {
            val parts = settings.optString("dailyTime", "08:00").split(":")
            val next = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, parts.getOrNull(0)?.toIntOrNull() ?: 8)
                set(Calendar.MINUTE, parts.getOrNull(1)?.toIntOrNull() ?: 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            alarm(context, DAILY_ID, next.timeInMillis, "عُمري | My Age", "لا تنسَ متابعة تفاصيل عمرك اليوم", "daily")
        }

        scheduleBirthday(context, settings, prefs)

        val custom = try { JSONArray(prefs.getString("custom", "[]")) } catch (_: Exception) { JSONArray() }
        for (i in 0 until custom.length()) {
            val item = custom.optJSONObject(i) ?: continue
            val epoch = item.optLong("epoch", 0L)
            if (epoch > System.currentTimeMillis()) {
                alarm(context, item.optString("id").hashCode(), epoch, "عُمري | My Age",
                    item.optString("message", "لديك تذكير"), "custom")
            }
        }
    }

    private fun cancelAlarmOnly(context: Context, id: Int) {
        val pending = PendingIntent.getBroadcast(
            context, id, Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending)
        pending.cancel()
    }

    private fun scheduleBirthday(context: Context, settings: JSONObject, prefs: android.content.SharedPreferences) {
        val raw = prefs.getString("birthDate", "") ?: return
        val parts = raw.split("-").mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return
        val month = parts[1]
        val day = parts[2]
        if (month !in 1..12 || day !in 1..31) return

        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.DAY_OF_MONTH, minOf(day, getActualMaximum(Calendar.DAY_OF_MONTH)))
            set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.YEAR, 1)
        }

        if (settings.optBoolean("birthdayNotice", false)) {
            alarm(context, BIRTHDAY_ID, next.timeInMillis, "عيد ميلاد سعيد 🎂",
                "كل عام وأنت بخير من تطبيق عُمري", "birthday")
        }
        if (settings.optBoolean("beforeBirthday", false)) {
            val before = (next.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, -settings.optInt("beforeDays", 1).coerceAtLeast(1))
            }
            if (before.timeInMillis > now.timeInMillis) {
                alarm(context, BEFORE_BIRTHDAY_ID, before.timeInMillis, "اقترب عيد ميلادك 🎉",
                    "باقي أيام قليلة على عيد ميلادك", "before_birthday")
            }
        }
    }

    fun onAlarmDelivered(context: Context, id: Int, kind: String, title: String, message: String) {
        showNow(context, title, message, id)
        when (kind) {
            "daily" -> {
                val next = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
                alarm(context, DAILY_ID, next.timeInMillis, title, message, "daily")
            }
            "birthday", "before_birthday" -> rescheduleAll(context)
        }
    }
}
