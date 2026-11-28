package com.example.executor

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.data.local.AgentTaskEntity
import com.example.data.local.PhoneAgentDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ReminderPolicyManager {

    /**
     * F34: فحص دوري لإذن المنبه الدقيق (Exact Alarm Permission)
     */
    fun checkExactAlarmPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            return alarmManager?.canScheduleExactAlarms() ?: true
        }
        return true
    }

    /**
     * F33: فحص التذكيرات الفائتة التي لم تُعرض بسبب رفض الإذن أو انغلاق التطبيق، وعرضها عند الفتح.
     */
    suspend fun checkAndProcessMissedReminders(context: Context): List<AgentTaskEntity> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        val db = PhoneAgentDatabase.getInstance(appContext)
        val activeTasks = db.agentTaskDao().getActiveTasks()
        val now = System.currentTimeMillis()
        val missedTasks = mutableListOf<AgentTaskEntity>()

        for (task in activeTasks) {
            if (task.isCompleted || task.isCancelled) continue
            val due = task.dueAtMillis ?: continue
            if (due < now && !task.isMissed) {
                val missedTask = task.copy(isMissed = true)
                db.agentTaskDao().insertTask(missedTask)
                missedTasks.add(missedTask)
                if (canNotify) {
                    ReminderReceiver.ensureReminderChannel(appContext)
                    val reminderId = task.id.toInt()
                    val receiverIntent = Intent(appContext, ReminderReceiver::class.java).apply {
                        putExtra(ReminderReceiver.EXTRA_TITLE, "تذكير فائت: ${task.title}")
                        putExtra(ReminderReceiver.EXTRA_MESSAGE, task.title)
                        putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, reminderId)
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        appContext,
                        reminderId,
                        receiverIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    runCatching { pendingIntent.send() }
                }
            }
        }
        missedTasks
    }

    /**
     * S2-2: اقتراحات استباقية مبنية على الاستخدام وملخص صباحي اختياري (قابلة للتجاهل ولا تُنفذ تلقائياً).
     */
    fun getProactiveSuggestions(): List<ProactiveSuggestion> {
        return listOf(
            ProactiveSuggestion(
                id = "morning_summary",
                titleAr = "ملخص صباحي ذكي",
                descriptionAr = "هل ترغب في تلخيص مهامك وجدولك لهذا اليوم بضغطة زر؟",
                dismissible = true
            ),
            ProactiveSuggestion(
                id = "clean_storage_tip",
                titleAr = "توصية تنظيم التخزين",
                descriptionAr = "تم رصد مجلدات مؤقتة غير محجوزة، هل تود فحص الملفات الآن؟",
                dismissible = true
            ),
            ProactiveSuggestion(
                id = "whatsapp_conditional",
                titleAr = "مهمة مشروطة بـ واتساب",
                descriptionAr = "يمكنك تفعيل تذكير يظهر فور فتحك لتطبيق واتساب.",
                dismissible = true
            )
        )
    }
}

data class ProactiveSuggestion(
    val id: String,
    val titleAr: String,
    val descriptionAr: String,
    val dismissible: Boolean = true
)
