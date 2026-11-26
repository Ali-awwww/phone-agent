package com.example.executor

import com.example.data.local.AgentTaskType
import com.example.model.AppFolder
import java.util.Calendar

sealed interface ChatRouteIntent {
    data class ShowApps(
        val folderFilter: AppFolder? = null,
        val searchQuery: String = "",
        val titleAr: String = "التطبيقات المثبتة"
    ) : ChatRouteIntent

    data object ReadScreen : ChatRouteIntent

    data object ListTasksAndReminders : ChatRouteIntent

    data class CancelTaskOrReminder(val queryText: String) : ChatRouteIntent

    data class CreateTaskOrReminder(
        val title: String,
        val taskType: AgentTaskType,
        val delayMinutes: Int? = null,
        val hour: Int? = null,
        val minute: Int? = null
    ) : ChatRouteIntent

    data class StartGameplay(val goalText: String) : ChatRouteIntent

    data class ConversationalChat(val message: String) : ChatRouteIntent

    data class ExecuteCommandPlan(val command: String) : ChatRouteIntent
}

enum class GameGestureAction {
    TAP_TEXT,
    TAP_XY,
    SWIPE,
    SCROLL,
    BACK,
    WAIT,
    DONE
}

data class GameActionDecision(
    val action: GameGestureAction,
    val targetText: String = "",
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val endX: Float = 0.5f,
    val endY: Float = 0.25f,
    val reasonAr: String = ""
)

object ChatIntentRouter {

    fun normalizeDigitsAndArabic(input: String): String {
        val asciiDigits = buildString(input.length) {
            for (ch in input) {
                when (ch) {
                    in '٠'..'٩' -> append(('0'.code + (ch.code - '٠'.code)).toChar())
                    in '۰'..'۹' -> append(('0'.code + (ch.code - '۰'.code)).toChar())
                    else -> append(ch)
                }
            }
        }
        return asciiDigits
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ى', 'ي')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun classifyIntent(rawInput: String): ChatRouteIntent {
        val raw = rawInput.trim()
        val norm = normalizeDigitsAndArabic(raw).lowercase()

        // 1. قراءة الشاشة الحالية
        if (isScreenReadRequest(norm)) {
            return ChatRouteIntent.ReadScreen
        }

        // 2. وضع اللعب العام المجاني
        if (isGameplayRequest(norm)) {
            return ChatRouteIntent.StartGameplay(raw)
        }

        // 3. عرض التطبيقات والألعاب من الدردشة
        detectShowAppsIntent(norm)?.let { return it }

        // 4. إدارة التذكيرات والمهام والمنبهات (عرض / إلغاء / إنشاء)
        if (isListTasksRequest(norm)) {
            return ChatRouteIntent.ListTasksAndReminders
        }
        detectCancelTaskRequest(norm)?.let { return it }
        detectCreateTaskOrReminder(raw, norm)?.let { return it }

        // 5. تحيات أو دردشة تفاعلية بالعربية
        if (isConversationalOrGreeting(norm)) {
            return ChatRouteIntent.ConversationalChat(raw)
        }

        // 6. أوامر تنفيذية داخل التطبيقات (تمر عبر AgentPolicy + Gemini + ScreenVerifier)
        return ChatRouteIntent.ExecuteCommandPlan(raw)
    }

    private fun isScreenReadRequest(norm: String): Boolean {
        val phrases = listOf(
            "ماذا في شاشتي",
            "ما في شاشتي",
            "ايش في شاشتي",
            "وش في شاشتي",
            "اقرا الشاشة",
            "اقرا شاشتي",
            "ماذا على الشاشة",
            "ما على الشاشة",
            "اشرح الشاشة",
            "وصف الشاشة",
            "what is on my screen",
            "read screen"
        )
        return phrases.any { norm.contains(it) }
    }

    private fun isGameplayRequest(norm: String): Boolean {
        // استبعاد طلبات مثل «اعرض ألعابي» التي تعني عرض قائمة الألعاب
        if (norm.contains("اعرض") || norm.contains("عرض") || norm.contains("قائمة")) {
            return false
        }
        val playPhrases = listOf(
            "تولي اللعب",
            "العب معي",
            "العب مع الوكيل",
            "وضع اللعب",
            "محرك اللعب",
            "شغل اللعب",
            "ابدا اللعب",
            "العب هذه اللعبة",
            "العب اللعبة",
            "play with agent",
            "play game"
        )
        return playPhrases.any { norm.contains(it) } ||
            Regex("^(?:العب|ساعدني في اللعب)(?:\\s|$)").containsMatchIn(norm)
    }

    private fun detectShowAppsIntent(norm: String): ChatRouteIntent.ShowApps? {
        val asksToShow = listOf("اعرض", "عرض", "اظهر", "اظهار", "ورني", "قائمة", "ما هي", "وش عندي", "show", "list")
            .any { norm.contains(it) }
        val directShorthand = norm in listOf("العابي", "الالعاب", "تطبيقاتي", "التطبيقات")
        if (!asksToShow && !directShorthand) return null

        return when {
            norm.contains("العاب") || norm.contains("لعبة") || norm.contains("games") ->
                ChatRouteIntent.ShowApps(
                    folderFilter = AppFolder.GAMES,
                    titleAr = "ألعابك المثبتة"
                )
            norm.contains("تواصل") || norm.contains("محادثة") ->
                ChatRouteIntent.ShowApps(
                    folderFilter = AppFolder.COMMUNICATION,
                    titleAr = "تطبيقات التواصل"
                )
            norm.contains("عمل") || norm.contains("مكتب") ->
                ChatRouteIntent.ShowApps(
                    folderFilter = AppFolder.WORK,
                    titleAr = "تطبيقات العمل"
                )
            norm.contains("تسوق") || norm.contains("متاجر") ->
                ChatRouteIntent.ShowApps(
                    folderFilter = AppFolder.SHOPPING,
                    titleAr = "تطبيقات التسوق"
                )
            norm.contains("وسائط") || norm.contains("فيديو") || norm.contains("موسيقي") ->
                ChatRouteIntent.ShowApps(
                    folderFilter = AppFolder.MEDIA,
                    titleAr = "تطبيقات الوسائط"
                )
            norm.contains("ادوات") ->
                ChatRouteIntent.ShowApps(
                    folderFilter = AppFolder.TOOLS,
                    titleAr = "تطبيقات الأدوات"
                )
            norm.contains("تطبيقات") || norm.contains("تطبيقاتي") || norm.contains("apps") ->
                ChatRouteIntent.ShowApps(
                    folderFilter = null,
                    titleAr = "تطبيقاتك المثبتة"
                )
            else -> null
        }
    }

    private fun isListTasksRequest(norm: String): Boolean {
        val listWords = listOf("اعرض", "عرض", "ما هي", "وش", "قائمة", "شوف", "اظهر", "list", "show")
        val targetWords = listOf("تذكير", "تذكيرات", "مهام", "مهمات", "منبه", "منبهات", "reminder", "task", "alarm")
        val matchesShorthand = norm in listOf("تذكيراتي", "مهامي", "التذكيرات", "المهام", "المنبهات", "منبهاتي")
        return matchesShorthand || (listWords.any { norm.contains(it) } && targetWords.any { norm.contains(it) })
    }

    private fun detectCancelTaskRequest(norm: String): ChatRouteIntent.CancelTaskOrReminder? {
        val cancelWords = listOf("الغي", "الغ", "الغاء", "اوقف", "ايقاف", "ازل", "ازالة", "cancel", "stop")
        val targetWords = listOf("تذكير", "مهمة", "منبه", "reminder", "task", "alarm")
        if (cancelWords.any { norm.contains(it) } && targetWords.any { norm.contains(it) }) {
            val cleaned = norm
                .replace(Regex("^(?:الغي|الغ|الغاء|اوقف|ايقاف|ازل|ازالة|cancel|stop)\\s*"), "")
                .replace(Regex("^(?:ال)?(?:تذكير|مهمة|منبه|reminder|task|alarm)\\s*"), "")
                .trim()
            return ChatRouteIntent.CancelTaskOrReminder(cleaned)
        }
        return null
    }

    private fun detectCreateTaskOrReminder(raw: String, norm: String): ChatRouteIntent.CreateTaskOrReminder? {
        val isReminder = Regex("(?:ذكرني|نبهني|تذكير|اضبط تذكير|انشئ تذكير|remind me)").containsMatchIn(norm)
        val isAlarm = Regex("(?:منبه|اضبط منبه|ضبط منبه|set alarm)").containsMatchIn(norm)
        val isTask = Regex("(?:اضف مهمة|انشئ مهمة|مهمة جديدة|سجل مهمة|مهمة:|add task)").containsMatchIn(norm)

        if (!isReminder && !isAlarm && !isTask) return null

        val taskType = when {
            isAlarm -> AgentTaskType.ALARM
            isTask && !isReminder -> AgentTaskType.TASK
            else -> AgentTaskType.REMINDER
        }

        // استخراج تأخير نسبي: «بعد 10 دقائق» أو «بعد ساعة» أو «بعد ساعتين»
        var delayMinutes: Int? = null
        val minsMatch = Regex("بعد\\s+(\\d+)\\s*(?:دقيقة|دقائق|دقيق[ةه]|min)").find(norm)
        val hoursMatch = Regex("بعد\\s+(\\d+)\\s*(?:ساعة|ساعات|hour)").find(norm)
        when {
            minsMatch != null -> delayMinutes = minsMatch.groupValues[1].toIntOrNull()?.coerceAtLeast(1)
            hoursMatch != null -> delayMinutes = (hoursMatch.groupValues[1].toIntOrNull() ?: 1).coerceAtLeast(1) * 60
            norm.contains("بعد نصف ساعة") -> delayMinutes = 30
            norm.contains("بعد ربع ساعة") -> delayMinutes = 15
            norm.contains("بعد ساعة") -> delayMinutes = 60
            norm.contains("بعد ساعتين") -> delayMinutes = 120
            norm.contains("بعد دقيقة") -> delayMinutes = 1
        }

        // استخراج وقت محدد: «7:30» أو «الساعة 8» أو «الساعة 19:00»
        var parsedHour: Int? = null
        var parsedMinute: Int? = null
        if (delayMinutes == null) {
            val hhmmMatch = Regex("(?:الساعة\\s*)?(\\d{1,2}):(\\d{2})").find(norm)
            val hourOnlyMatch = Regex("الساعة\\s*(\\d{1,2})(?:\\s|$)").find(norm)
            if (hhmmMatch != null) {
                parsedHour = hhmmMatch.groupValues[1].toIntOrNull()
                parsedMinute = hhmmMatch.groupValues[2].toIntOrNull()
            } else if (hourOnlyMatch != null) {
                parsedHour = hourOnlyMatch.groupValues[1].toIntOrNull()
                parsedMinute = 0
            }

            if (parsedHour != null) {
                val isPm = norm.contains("مساء") || norm.contains("عصرا") || norm.contains("ليلا") || norm.contains("pm")
                val isAm = norm.contains("صباحا") || norm.contains("فجرا") || norm.contains("am")
                if (isPm && parsedHour in 1..11) parsedHour += 12
                if (isAm && parsedHour == 12) parsedHour = 0
                parsedHour = parsedHour.takeIf { it in 0..23 }
                parsedMinute = (parsedMinute ?: 0).takeIf { it in 0..59 }
            }
        }

        val cleanTitle = extractTaskTitle(raw)
        return ChatRouteIntent.CreateTaskOrReminder(
            title = cleanTitle,
            taskType = taskType,
            delayMinutes = delayMinutes,
            hour = parsedHour,
            minute = parsedMinute
        )
    }

    private fun extractTaskTitle(raw: String): String {
        val stripped = raw
            .replace(Regex("^(?:من فضلك\\s+)?(?:ذكّرني|ذكرني|نبّهني|نبهني|أضف مهمة|اضف مهمة|أنشئ مهمة|انشئ مهمة|مهمة جديدة|مهمة:|اضبط تذكيراً|اضبط تذكير|اضبط منبهاً|اضبط منبه|ضبط منبه)\\s*"), "")
            .replace(Regex("(?:بعد\\s+[\\d٠-٩]+\\s*(?:دقيقة|دقائق|ساعة|ساعات)|بعد\\s+(?:نصف ساعة|ربع ساعة|ساعة|ساعتين|دقيقة))"), "")
            .replace(Regex("(?:على\\s+|في\\s+)?(?:الساعة\\s*)?[\\d٠-٩]{1,2}(?::[\\d٠-٩]{2})?\\s*(?:صباحاً|صباحا|مساءً|مساء|فجراً|فجرا|عصراً|عصرا|ليلاً|ليلا)?"), "")
            .trim()
            .replace(Regex("^ب(?:ـ)?\\s*(?=\\S)"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return stripped.ifBlank { raw.trim() }
    }

    private fun isConversationalOrGreeting(norm: String): Boolean {
        val conversationalPhrases = listOf(
            "مرحبا", "اهلا", "السلام عليكم", "صباح الخير", "مساء الخير",
            "شكرا", "مساعدة", "ساعدني", "كيف تعمل", "من انت", "عرفني",
            "كيف حالك", "من هو", "من هي", "ما هو", "ما هي", "تكلم معي",
            "حدثني", "هل انت", "الو", "سلام", "يا وكيل"
        )
        val asksAppAction = listOf("افتح", "شغل", "اضبط", "العب", "اعرض", "ذكرني", "نبهني", "اقرا الشاشة", "ماذا في شاشتي")
            .any { norm.contains(it) }
        return !asksAppAction && (conversationalPhrases.any { norm.contains(it) } || norm.endsWith("؟") || norm.endsWith("?"))
    }

    fun computeDueMillis(
        delayMinutes: Int?,
        hour: Int?,
        minute: Int?,
        nowMillis: Long = System.currentTimeMillis()
    ): Long {
        if (delayMinutes != null) {
            return nowMillis + delayMinutes * 60_000L
        }
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
        // افتراضياً بعد 10 دقائق إذا لم يُحدَّد وقت
        return nowMillis + 10 * 60_000L
    }

    /**
     * Parses a JSON directive returned by Gemini during the general gameplay observe-and-act loop.
     */
    fun parseGameActionDecision(rawText: String): GameActionDecision {
        val cleaned = rawText
            .replace("```json", "")
            .replace("```", "")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val jsonSlice = cleaned.substring(start, end + 1)
            runCatching {
                val actionMatch = Regex("\"action\"\\s*:\\s*\"([A-Za-z_]+)\"").find(jsonSlice)
                val actionStr = actionMatch?.groupValues?.getOrNull(1)?.uppercase() ?: "WAIT"
                val action = GameGestureAction.entries.firstOrNull { it.name == actionStr }
                    ?: GameGestureAction.WAIT

                val textMatch = Regex("\"text\"\\s*:\\s*\"([^\"]*)\"").find(jsonSlice)
                val reasonMatch = Regex("\"reasonAr\"\\s*:\\s*\"([^\"]*)\"").find(jsonSlice)
                val xMatch = Regex("\"x\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").find(jsonSlice)
                val yMatch = Regex("\"y\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").find(jsonSlice)
                val endXMatch = Regex("\"endX\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").find(jsonSlice)
                val endYMatch = Regex("\"endY\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").find(jsonSlice)

                return GameActionDecision(
                    action = action,
                    targetText = textMatch?.groupValues?.getOrNull(1).orEmpty(),
                    x = xMatch?.groupValues?.getOrNull(1)?.toFloatOrNull()?.coerceIn(0.02f, 0.98f) ?: 0.5f,
                    y = yMatch?.groupValues?.getOrNull(1)?.toFloatOrNull()?.coerceIn(0.02f, 0.98f) ?: 0.5f,
                    endX = endXMatch?.groupValues?.getOrNull(1)?.toFloatOrNull()?.coerceIn(0.02f, 0.98f) ?: 0.5f,
                    endY = endYMatch?.groupValues?.getOrNull(1)?.toFloatOrNull()?.coerceIn(0.02f, 0.98f) ?: 0.25f,
                    reasonAr = reasonMatch?.groupValues?.getOrNull(1).orEmpty()
                )
            }
        }
        return GameActionDecision(
            action = GameGestureAction.WAIT,
            reasonAr = rawText.take(120).ifBlank { "مراقبة الشاشة الحالية قبل الخطوة التالية." }
        )
    }
}
