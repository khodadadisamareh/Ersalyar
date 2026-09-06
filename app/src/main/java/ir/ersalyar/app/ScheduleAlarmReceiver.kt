package ir.ersalyar.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("schedule_id", -1)
        if (id < 0) return
        val session = Session(context)
        val end = session.subscriptionEnd
        val active = session.subscriptionActive
        if (!active || end.isNullOrBlank()) { LocalScheduler.cancel(context, id); return }
        try {
            val expiry = java.time.OffsetDateTime.parse(end).toInstant()
            if (!expiry.isAfter(java.time.Instant.now())) { LocalScheduler.cancel(context, id); return }
        } catch (_: Exception) {
            LocalScheduler.cancel(context, id); return
        }
        val send = Intent(context, MessageAccessibilityService::class.java).apply {
            action = MessageAccessibilityService.ACTION_RUN_SCHEDULE
            putExtra("schedule_id", id)
        }
        MessageAccessibilityService.sendCommand(context, send)
    }
}
