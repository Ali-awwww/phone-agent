package com.example.habits

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.example.data.local.ActionLogDao
import com.example.data.local.ActionLogEntity
import com.example.data.local.ActionStatus
import com.example.data.local.AgentTaskEntity
import com.example.data.local.HabitLogEntity
import com.example.data.local.UserMemoryRepository
import com.example.organizer.InstalledAppInfo
import com.example.permissions.PermissionHelper
import com.example.policy.AgentPolicy
import com.example.security.MemoryAndHabitPreferences
import com.example.security.PrivacyPreferences
import com.example.security.SensitiveDataScrubber
import java.util.Calendar
import kotlin.math.abs

data class PredictiveSuggestion(
    val key: String,
    val titleAr: String,
    val suggestedCommand: String,
    val reasonAr: String,
    val isFromGemini: Boolean = false
)

/**
 * محرك تعلّم العادات والروتين الصباحي والتنبؤ المحلي (المرحلة 6):
 * - يجمع العادات محلياً من UsageStatsManager وسجل الإجراءات (action_logs).
 * - يكتشف الأنماط المتكررة (تطبيق / أمر / وقت) ويولّد اقتراحاً قابلاً للإغلاق فقط (لا ينفّذ تلقائياً أبداً).
 * - يحفظ بيانات العادات الخام محلياً على الجهاز فقط، ولا يُنتج سوى ملخص مجهول الهوية عند موافقة المستخدم المعلنة.
 */
class HabitLearningEngine(
    private val appContext: Context,
    private val userMemoryRepository: UserMemoryRepository,
    private val actionLogDao: ActionLogDao,
    private val memoryPrefs: MemoryAndHabitPreferences,
    private val privacyPreferences: PrivacyPreferences,
    private val agentPolicy: AgentPolicy = AgentPolicy()
) {

    suspend fun syncHabits(
        installedApps: List<InstalledAppInfo> = emptyList(),
        nowMillis: Long = System.currentTimeMillis()
    ): Int {
        if (privacyPreferences.isPrivacyModeEnabled()) return 0
        val fromLogs = syncFromActionLogs(nowMillis)
        val fromUsage = syncFromUsageStats(installedApps, nowMillis)
        return fromLogs + fromUsage
    }

    suspend fun syncFromActionLogs(nowMillis: Long = System.currentTimeMillis()): Int {
        if (privacyPreferences.isPrivacyModeEnabled()) return 0
        val lastSynced = memoryPrefs.getLastSyncedActionLogTimestamp()
        val recentLogs = actionLogDao.getLogsSince(lastSynced)
            .filter { it.timestamp > lastSynced && it.status == ActionStatus.SUCCESS.name }
        if (recentLogs.isEmpty()) return 0

        var maxTs = lastSynced
        var count = 0
        val cal = Calendar.getInstance()
        for (log in recentLogs) {
            if (log.timestamp > maxTs) maxTs = log.timestamp
            val actionLabel = normalizeActionLogToHabitName(log) ?: continue
            cal.timeInMillis = log.timestamp
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val day = calendarDayToIso(cal.get(Calendar.DAY_OF_WEEK))
            userMemoryRepository.recordHabit(
                actionName = actionLabel,
                hourOfDay = hour,
                dayOfWeek = day,
                timestamp = log.timestamp
            )
            count++
        }
        if (maxTs > lastSynced) {
            memoryPrefs.setLastSyncedActionLogTimestamp(maxTs.coerceAtMost(nowMillis))
        }
        return count
    }

    suspend fun syncFromUsageStats(
        installedApps: List<InstalledAppInfo>,
        nowMillis: Long = System.currentTimeMillis()
    ): Int {
        if (privacyPreferences.isPrivacyModeEnabled()) return 0
        if (!PermissionHelper.isUsageAccessGranted(appContext)) return 0

        val usageStatsManager = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return 0

        val lastSynced = memoryPrefs.getLastSyncedUsageTimestamp()
        val windowStart = if (lastSynced > 0L) {
            maxOf(lastSynced + 1L, nowMillis - 24L * 60L * 60L * 1000L)
        } else {
            nowMillis - 24L * 60L * 60L * 1000L
        }
        if (windowStart >= nowMillis) return 0

        val appLabelsByPackage = installedApps.associate { it.packageName to it.label }
        val recordedEvents = mutableListOf<Pair<String, Long>>()

        runCatching {
            val events = usageStatsManager.queryEvents(windowStart, nowMillis)
            val event = UsageEvents.Event()
            var lastPkg = ""
            var lastPkgTs = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val isForeground = event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    @Suppress("DEPRECATION")
                    (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND)
                if (!isForeground) continue
                val pkg = event.packageName ?: continue
                if (pkg == appContext.packageName || isIgnoredSystemPackage(pkg)) continue
                // تجنب التكرار المتلاحق لنفس التطبيق خلال أقل من دقيقتين
                if (pkg == lastPkg && abs(event.timeStamp - lastPkgTs) < 120_000L) continue
                val label = resolveReadableAppLabel(pkg, appLabelsByPackage) ?: continue
                recordedEvents.add("$PREFIX_OPEN_APP$label" to event.timeStamp)
                lastPkg = pkg
                lastPkgTs = event.timeStamp
            }
        }

        // مسار احتياطي إذا لم تُعد queryEvents أحداثاً على بعض الأجهزة
        if (recordedEvents.isEmpty()) {
            runCatching {
                val stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    windowStart,
                    nowMillis
                ).orEmpty()
                stats
                    .filter {
                        it.lastTimeUsed > windowStart &&
                            it.totalTimeInForeground > 5_000L &&
                            it.packageName != appContext.packageName &&
                            !isIgnoredSystemPackage(it.packageName)
                    }
                    .sortedByDescending { it.lastTimeUsed }
                    .take(8)
                    .forEach { stat ->
                        val label = resolveReadableAppLabel(stat.packageName, appLabelsByPackage)
                        if (label != null) {
                            recordedEvents.add("$PREFIX_OPEN_APP$label" to stat.lastTimeUsed)
                        }
                    }
            }
        }

        var maxTs = lastSynced
        var count = 0
        val cal = Calendar.getInstance()
        for ((habitName, ts) in recordedEvents) {
            if (ts > maxTs) maxTs = ts
            cal.timeInMillis = ts
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val day = calendarDayToIso(cal.get(Calendar.DAY_OF_WEEK))
            userMemoryRepository.recordHabit(
                actionName = habitName,
                hourOfDay = hour,
                dayOfWeek = day,
                timestamp = ts
            )
            count++
        }
        if (maxTs > lastSynced) {
            memoryPrefs.setLastSyncedUsageTimestamp(maxTs.coerceAtMost(nowMillis))
        }
        return count
    }

    suspend fun detectLocalPredictiveSuggestion(
        nowMillis: Long = System.currentTimeMillis()
    ): PredictiveSuggestion? {
        if (privacyPreferences.isPrivacyModeEnabled()) return null
        if (!memoryPrefs.isPredictiveLearningEnabled()) return null

        val habits = userMemoryRepository.getTopHabits()
        return detectPatternFromHabits(
            habits = habits,
            nowMillis = nowMillis,
            dismissedKeys = memoryPrefs.getDismissedSuggestionKeys(),
            agentPolicy = agentPolicy
        )
    }

    suspend fun getAnonymizedSummaryIfConsented(
        nowMillis: Long = System.currentTimeMillis()
    ): Pair<String, String>? {
        if (privacyPreferences.isPrivacyModeEnabled()) return null
        if (!memoryPrefs.isPredictiveLearningEnabled()) return null
        if (!memoryPrefs.isAnonymizedHabitGeminiConsent()) return null

        val habits = userMemoryRepository.getTopHabits()
        if (habits.isEmpty()) return null

        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val summary = buildAnonymizedHabitSummary(habits, hour)
        if (summary.isBlank()) return null
        return summary to timeBucketArabic(hour)
    }

    companion object {
        const val PREFIX_OPEN_APP = "فتح تطبيق: "
        private const val MIN_RECURRING_FREQUENCY = 2

        fun calendarDayToIso(calendarDayOfWeek: Int): Int = when (calendarDayOfWeek) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7
            else -> 1
        }

        fun timeBucketArabic(hourOfDay: Int): String = when (hourOfDay) {
            in 5..11 -> "صباحاً"
            in 12..16 -> "ظهراً"
            in 17..21 -> "مساءً"
            else -> "ليلاً"
        }

        fun normalizeActionLogToHabitName(log: ActionLogEntity): String? {
            if (log.status != ActionStatus.SUCCESS.name) return null
            val type = log.actionType.uppercase()
            if (type.startsWith("MODEL_ROUTE_") ||
                type.startsWith("POLICY_") ||
                type.contains("APPROVAL") ||
                type == "INSTANT_STOP" ||
                type == "CANCEL_CURRENT_TASK"
            ) {
                return null
            }
            val cleanTitle = SensitiveDataScrubber.scrub(log.title).trim()
            if (cleanTitle.isBlank()) return null
            return when {
                type == "MORNING_ROUTINE" -> "الروتين الصباحي"
                type == "SUMMARIZE_MY_DAY" -> "لخّص يومي"
                type == "CHAT_LAUNCH_APP" || type == "OPEN_APP" -> {
                    val extracted = cleanTitle
                        .removePrefix("تشغيل «")
                        .removePrefix("فتح تطبيق «")
                        .substringBefore("»")
                        .trim()
                    if (extracted.isNotBlank()) "$PREFIX_OPEN_APP$extracted" else cleanTitle.take(60)
                }
                type.startsWith("CHAT_ALARM") || type == "SET_ALARM" -> "ضبط منبه"
                type.startsWith("CHAT_REMINDER") || type == "SET_REMINDER" -> "إنشاء تذكير"
                type.startsWith("CHAT_TASK") -> "إضافة مهمة"
                else -> cleanTitle.take(60)
            }
        }

        fun detectPatternFromHabits(
            habits: List<HabitLogEntity>,
            nowMillis: Long,
            dismissedKeys: Set<String> = emptySet(),
            agentPolicy: AgentPolicy = AgentPolicy()
        ): PredictiveSuggestion? {
            if (habits.isEmpty()) return null
            val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
            val currentHour = cal.get(Calendar.HOUR_OF_DAY)
            val currentDay = calendarDayToIso(cal.get(Calendar.DAY_OF_WEEK))

            val candidates = habits.filter { habit ->
                habit.frequencyCount >= MIN_RECURRING_FREQUENCY &&
                    hourDistance(habit.hourOfDay, currentHour) <= 2 &&
                    agentPolicy.evaluateCommand(habit.actionName).allowed
            }.sortedByDescending { habit ->
                val dayBonus = if (habit.dayOfWeek == currentDay) 2 else 0
                val exactHourBonus = if (habit.hourOfDay == currentHour) 3 else 1
                habit.frequencyCount * 10 + dayBonus + exactHourBonus
            }

            for (best in candidates) {
                val command = toSuggestedCommand(best.actionName)
                if (!agentPolicy.evaluateCommand(command).allowed) continue
                val suggestionKey = "${best.actionName}@${best.hourOfDay}"
                if (suggestionKey in dismissedKeys) continue
                val timeBucket = timeBucketArabic(best.hourOfDay)
                return PredictiveSuggestion(
                    key = suggestionKey,
                    titleAr = "اقتراح ذكي معتاد ($timeBucket)",
                    suggestedCommand = command,
                    reasonAr = "لاحظت تكرار «${best.actionName}» (${best.frequencyCount} مرات) في هذا الوقت تقريباً ($timeBucket). هل تود تجهيز هذا الأمر؟ (لن أنفذ شيئاً تلقائياً).",
                    isFromGemini = false
                )
            }
            return null
        }

        /**
         * يبني ملخصاً مجهول الهوية تماماً لعادات الاستخدام (دون أسماء حزم، ودون بيانات شخصية أو نصوص خاصة)
         * لإرساله إلى Gemini فقط عند موافقة المستخدم المعلنة.
         */
        fun buildAnonymizedHabitSummary(habits: List<HabitLogEntity>, currentHour: Int): String {
            if (habits.isEmpty()) return ""
            val currentBucket = timeBucketArabic(currentHour)
            val relevant = habits
                .filter { it.frequencyCount >= MIN_RECURRING_FREQUENCY }
                .sortedByDescending { it.frequencyCount }
                .take(6)
            if (relevant.isEmpty()) return ""

            return buildString {
                appendLine("ملخص أنماط استخدام مجهول الهوية (الفترة الحالية: $currentBucket):")
                relevant.forEach { habit ->
                    val categoryToken = anonymizeActionCategory(habit.actionName)
                    val bucket = timeBucketArabic(habit.hourOfDay)
                    appendLine("- النمط: $categoryToken | الفترة: $bucket | التكرار: ${habit.frequencyCount}")
                }
            }.trim()
        }

        fun anonymizeActionCategory(actionName: String): String {
            val lower = SensitiveDataScrubber.scrub(actionName).lowercase()
            return when {
                lower.contains("روتين صباحي") || lower.contains("morning") -> "إجراء روتيني صباحي"
                lower.contains("منبه") || lower.contains("alarm") -> "إدارة المنبهات"
                lower.contains("تذكير") || lower.contains("مهمة") || lower.contains("reminder") -> "تنظيم المهام والتذكيرات"
                lower.contains("واتساب") || lower.contains("تيليجرام") || lower.contains("تواصل") ||
                    lower.contains("رسالة") || lower.contains("whatsapp") || lower.contains("telegram") -> "تطبيق تواصل"
                lower.contains("يوتيوب") || lower.contains("فيديو") || lower.contains("موسيقى") ||
                    lower.contains("youtube") || lower.contains("spotify") -> "تطبيق وسائط"
                lower.contains("تقويم") || lower.contains("ملاحظات") || lower.contains("بريد") ||
                    lower.contains("calendar") || lower.contains("notes") -> "تطبيق إنتاجية"
                lower.startsWith(PREFIX_OPEN_APP.lowercase()) -> "فتح تطبيق معتاد"
                else -> "أمر استخدام متكرر"
            }
        }

        fun toSuggestedCommand(actionName: String): String {
            val clean = SensitiveDataScrubber.scrub(actionName).trim()
            return when {
                clean.startsWith(PREFIX_OPEN_APP) -> {
                    val app = clean.removePrefix(PREFIX_OPEN_APP).trim()
                    if (app.isNotBlank()) "افتح $app" else "اعرض تطبيقاتي"
                }
                clean == "ضبط منبه" -> "اعرض مهامي"
                clean == "إنشاء تذكير" -> "ذكّرني"
                clean == "إضافة مهمة" -> "اعرض مهامي"
                else -> clean
            }
        }

        fun buildDaySummaryDigest(
            todayLogs: List<ActionLogEntity>,
            tasks: List<AgentTaskEntity>,
            habits: List<HabitLogEntity>
        ): String {
            val succeeded = todayLogs.filter { it.status == ActionStatus.SUCCESS.name }
            val failedOrCancelled = todayLogs.filter {
                it.status == ActionStatus.FAILED.name || it.status == ActionStatus.CANCELLED.name
            }
            val completedTasks = tasks.filter { it.isCompleted && !it.isCancelled }
            val pendingTasks = tasks.filter { !it.isCompleted && !it.isCancelled }
            val topHabits = habits.sortedByDescending { it.frequencyCount }.take(4)

            return buildString {
                appendLine("ملخص نشاط اليوم على الجهاز:")
                appendLine("- الإجراءات الناجحة اليوم: ${succeeded.size}")
                if (succeeded.isNotEmpty()) {
                    appendLine("  أبرزها: ${succeeded.take(5).joinToString("؛ ") { SensitiveDataScrubber.scrub(it.title) }}")
                }
                if (failedOrCancelled.isNotEmpty()) {
                    appendLine("- إجراءات متوقفة أو مرفوضة اليوم: ${failedOrCancelled.size}")
                }
                appendLine("- المهام والتذكيرات المكتملة: ${completedTasks.size} | المتبقية: ${pendingTasks.size}")
                if (pendingTasks.isNotEmpty()) {
                    appendLine("  مهام متبقية: ${pendingTasks.take(4).joinToString("؛ ") { SensitiveDataScrubber.scrub(it.title) }}")
                }
                if (topHabits.isNotEmpty()) {
                    appendLine("- أنماط متكررة مرصودة: ${topHabits.joinToString("؛ ") { "${it.actionName} (${it.frequencyCount})" }}")
                }
            }.trim()
        }

        fun selectMorningFavoriteApp(
            habits: List<HabitLogEntity>,
            installedApps: List<InstalledAppInfo>
        ): InstalledAppInfo? {
            if (installedApps.isEmpty()) return null
            val morningHabits = habits
                .filter { it.hourOfDay in 5..11 && it.actionName.startsWith(PREFIX_OPEN_APP) }
                .sortedByDescending { it.frequencyCount }
            for (habit in morningHabits) {
                val appLabel = habit.actionName.removePrefix(PREFIX_OPEN_APP).trim()
                val match = installedApps.firstOrNull {
                    !it.isHidden && it.label.equals(appLabel, ignoreCase = true)
                }
                if (match != null) return match
            }
            return null
        }

        private fun hourDistance(h1: Int, h2: Int): Int {
            val diff = abs(h1 - h2)
            return minOf(diff, 24 - diff)
        }

        private fun isIgnoredSystemPackage(packageName: String): Boolean {
            return packageName == "com.android.systemui" ||
                packageName.contains("launcher", ignoreCase = true) ||
                packageName == "android"
        }

        private fun resolveReadableAppLabel(
            packageName: String,
            installedByPkg: Map<String, String>
        ): String? {
            installedByPkg[packageName]?.takeIf { it.isNotBlank() }?.let { return it }
            return null
        }
    }
}
