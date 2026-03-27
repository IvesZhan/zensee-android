package com.zensee.android

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.app.Notification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.lang.SecurityException
import java.time.LocalDateTime
import java.time.ZoneId

data class ReminderState(
    val enabled: Boolean,
    val hour: Int,
    val minute: Int
) {
    fun subtitleText(context: Context): String {
        return if (!enabled) {
            context.getString(R.string.reminder_disabled)
        } else {
            context.getString(R.string.reminder_every_day_time, String.format("%02d:%02d", hour, minute))
        }
    }
}

object ReminderManager {
    private val LEGACY_CHANNEL_IDS = listOf(
        "zensee_reminder",
        "zensee_reminder_v2"
    )
    private const val REMINDER_NOTIFICATION_ID_BASE = 20_000
    private const val PREFS_NAME = "zensee_reminder"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_HOUR = "hour"
    private const val KEY_MINUTE = "minute"
    private const val ACTION_REMINDER = "com.zensee.android.action.REMINDER"

    const val CHANNEL_ID = "zensee_reminder_v3"

    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext
        ensureChannel()
    }

    fun state(): ReminderState {
        val prefs = prefs()
        return ReminderState(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            hour = prefs.getInt(KEY_HOUR, 21),
            minute = prefs.getInt(KEY_MINUTE, 0)
        )
    }

    fun save(enabled: Boolean, hour: Int, minute: Int) {
        prefs().edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putInt(KEY_HOUR, hour)
            .putInt(KEY_MINUTE, minute)
            .apply()

        cancel()
        if (enabled) {
            schedule(hour, minute, allowCurrentMinuteGrace = true)
        }
    }

    fun canScheduleExactAlarms(): Boolean {
        val manager = alarmManager()
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
    }

    fun rescheduleFromReceiver() {
        val current = state()
        if (current.enabled) {
            schedule(current.hour, current.minute, allowCurrentMinuteGrace = false)
        }
    }

    fun ensureScheduledIfNeeded() {
        val current = state()
        if (current.enabled) {
            schedule(current.hour, current.minute, allowCurrentMinuteGrace = false)
        } else {
            cancel()
        }
    }

    fun nextNotificationId(nowMillis: Long = System.currentTimeMillis()): Int {
        return REMINDER_NOTIFICATION_ID_BASE + ((nowMillis / 60_000L) % 100_000L).toInt()
    }

    fun deliverScheduledNotification() {
        rescheduleFromReceiver()
        postNotification()
    }

    private fun schedule(
        hour: Int,
        minute: Int,
        allowCurrentMinuteGrace: Boolean
    ) {
        val nextDateTime = nextTriggerTime(hour, minute, allowCurrentMinuteGrace)
        val pendingIntent = reminderPendingIntent()
        val triggerAtMillis = nextDateTime.atZone(zoneId()).toInstant().toEpochMilli()
        val alarmManager = alarmManager()
        val showIntent = PendingIntent.getActivity(
            appContext,
            1004,
            Intent(appContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canScheduleExactAlarms()) {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent),
                    pendingIntent
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        }
    }

    private fun cancel() {
        alarmManager().cancel(reminderPendingIntent())
    }

    private fun reminderPendingIntent(): PendingIntent {
        return PendingIntent.getBroadcast(
            appContext,
            1001,
            Intent(appContext, ReminderReceiver::class.java).setAction(ACTION_REMINDER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun nextTriggerTime(
        hour: Int,
        minute: Int,
        allowCurrentMinuteGrace: Boolean
    ): LocalDateTime {
        val now = LocalDateTime.now(zoneId())
        var candidate = now
            .withHour(hour)
            .withMinute(minute)
            .withSecond(0)
            .withNano(0)
        if (!candidate.isAfter(now)) {
            if (allowCurrentMinuteGrace &&
                candidate.hour == now.hour &&
                candidate.minute == now.minute
            ) {
                return now.plusSeconds(3).withNano(0)
            }
            candidate = candidate.plusDays(1)
        }
        return candidate
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java)
        LEGACY_CHANNEL_IDS.forEach { legacyId ->
            manager.getNotificationChannel(legacyId)?.let {
                manager.deleteNotificationChannel(legacyId)
            }
        }
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        val hasExpectedDefaults = existing != null &&
            existing.importance >= NotificationManager.IMPORTANCE_HIGH &&
            existing.shouldVibrate() &&
            existing.lockscreenVisibility == Notification.VISIBILITY_PUBLIC &&
            existing.sound != null
        if (hasExpectedDefaults) return
        if (existing != null) {
            manager.deleteNotificationChannel(CHANNEL_ID)
        }

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = appContext.getString(R.string.reminder_channel_description)
                enableVibration(true)
                enableLights(true)
                lightColor = Color.parseColor("#D1A95B")
                vibrationPattern = longArrayOf(0L, 250L, 180L, 250L)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        )
    }

    private fun postNotification() {
        ensureChannel()
        val notificationId = nextNotificationId()
        val title = appContext.getString(R.string.reminder_notification_title)
        val body = appContext.getString(R.string.reminder_notification_body)
        val contentIntent = PendingIntent.getActivity(
            appContext,
            1002,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .setVibrate(longArrayOf(0L, 250L, 180L, 250L))
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        val notification = notificationBuilder.build()

        if (ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU
        ) {
            NotificationManagerCompat.from(appContext).notify(
                notificationId,
                notification
            )
        }
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun alarmManager() = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun zoneId(): ZoneId = ZoneId.systemDefault()
}
