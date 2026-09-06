package ir.ersalyar.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object LocalScheduler {
    private const val ACTION_SEND = "ir.ersalyar.app.ACTION_SEND_SCHEDULE"
    private const val EXTRA_ID = "schedule_id"

    fun schedule(context: Context, id: Int, time: String) {
        val parts = time.trim().split(":")
        if (parts.size != 2) return
        val now = LocalDateTime.now()
        var next = now.withHour(parts[0].toIntOrNull() ?: return).withMinute(parts[1].toIntOrNull() ?: return).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
            action = ACTION_SEND
            putExtra(EXTRA_ID, id)
        }
        val pi = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
    }

    fun cancel(context: Context, id: Int) {
        val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply { action = ACTION_SEND }
        val pi = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
    }
}
