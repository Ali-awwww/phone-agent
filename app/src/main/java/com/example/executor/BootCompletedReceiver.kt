package com.example.executor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.data.local.PhoneAgentDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar

class BootCompletedReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val appContext = context.applicationContext
            scope.launch {
                try {
                    val db = PhoneAgentDatabase.getInstance(appContext)
                    val tasks = db.agentTaskDao().getActiveTasks()
                    val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return@launch
                    val now = System.currentTimeMillis()

                    for (task in tasks) {
                        if (task.isCompleted || task.isCancelled) continue
                        val dueMillis = task.dueAtMillis ?: computeDueMillis(task.hour, task.minute, now)
                        if (dueMillis <= now) continue

                        val reminderId = task.id.toInt() // F14: Unique persistent ID from database row ID
                        val receiverIntent = Intent(appContext, ReminderReceiver::class.java).apply {
                            putExtra(ReminderReceiver.EXTRA_TITLE, "تذكير: ${task.title}")
                            putExtra(ReminderReceiver.EXTRA_MESSAGE, task.title)
                            putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, reminderId)
                        }
                        val pendingIntent = PendingIntent.getBroadcast(
                            appContext,
                            reminderId,
                            receiverIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
                        if (canExact) {
                            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueMillis, pendingIntent)
                        } else {
                            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueMillis, pendingIntent)
                        }
                    }
                } catch (e: Exception) {
                    // Suppress or log safely
                }
            }
        }
    }

    private fun computeDueMillis(hour: Int?, minute: Int?, nowMillis: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        if (hour != null && minute != null) {
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (cal.timeInMillis <= nowMillis) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }
        return nowMillis + 10 * 60_000L
    }
}
