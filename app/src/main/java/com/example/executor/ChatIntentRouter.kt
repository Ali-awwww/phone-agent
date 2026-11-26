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

    data object ShowActionHistory : ChatRouteIntent

    data object PromptReminderDetails : ChatRouteIntent

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

enum class ModelRouteTier(val labelAr: String) {
    LITE("سؤال عام / نقاش (Lite)"),
    FLASH("استخدام الهاتف / تنفيذ (Flash)")
}

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

    fun classifyIntent(
        rawInput: String,
        knownAppNames: List<String> = emptyList()
    ): ChatRouteIntent {
        val raw = rawInput.trim()
        val norm = normalizeDigitsAndArabic(raw).lowercase()

        // 1. قراءة الشاشة الحالية
        if (isScreenReadRequest(norm)) {
            return ChatRouteIntent.ReadScreen
        }

        // 1.5 عرض سجل ما فعله الوكيل («ماذا فعلت؟»)
        if (isActionHistoryRequest(norm)) {
            return ChatRouteIntent.ShowActionHistory
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
        detectTaskIntentOrPrompt(raw, norm)?.let { return it }

        // 5. أوامر الهاتف التنفيذية داخل التطبيقات والإعدادات (Flash)
        if (isPhoneUsageCommand(norm, knownAppNames)) {
            return ChatRouteIntent.ExecuteCommandPlan(raw)
        }

        // 6. النقاشات والأسئلة العامة والاستشارات والتحيات (Lite)
        return ChatRouteIntent.ConversationalChat(raw)
    }

    /**
     * تصنيف خفيف للنية لاختيار النموذج المناسب عبر Firebase AI Logic:
     * - نقاش / سؤال عام / استشارة / معرفة عامة → ModelRouteTier.LITE
     * - استخدام الهاتف (فتح تطبيق، تنفيذ أمر، اللعب، قراءة الشاشة، تنفيذ مهمة) → ModelRouteTier.FLASH
     */
    fun classifyModelTier(
        rawInput: String,
        knownAppNames: List<String> = emptyList()
    ): ModelRouteTier {
        return when (classifyIntent(rawInput, knownAppNames)) {
            is ChatRouteIntent.ConversationalChat -> ModelRouteTier.LITE
            is ChatRouteIntent.ReadScreen,
            is ChatRouteIntent.StartGameplay,
            is ChatRouteIntent.ExecuteCommandPlan,
            is ChatRouteIntent.CreateTaskOrReminder,
            is ChatRouteIntent.ListTasksAndReminders,
            is ChatRouteIntent.CancelTaskOrReminder,
            is ChatRouteIntent.ShowApps,
            is ChatRouteIntent.ShowActionHistory,
            is ChatRouteIntent.PromptReminderDetails -> ModelRouteTier.FLASH
        }
    }

    private fun isActionHistoryRequest(norm: String): Boolean {
        val cleaned = norm.replace("؟", "").replace("?", "").trim()
        val phrases = listOf(
            "ماذا فعلت",
            "ما الذي فعلته",
            "ايش سويت",
            "وش سويت",
            "ماذا نفذت",
            "اعرض السجل",
            "عرض السجل",
            "سجل الافعال",
            "سجل افعالي",
            "اخر الافعال",
            "what did you do",
            "action log"
        )
        return phrases.any { cleaned.contains(it) }
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

    private fun detectTaskIntentOrPrompt(raw: String, norm: String): ChatRouteIntent? {
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

        val extracted = extractTaskTitleRaw(raw)
        if (extracted.isBlank() && delayMinutes == null && parsedHour == null) {
            return ChatRouteIntent.PromptReminderDetails
        }

        val cleanTitle = extracted.ifBlank { raw.trim() }
        return ChatRouteIntent.CreateTaskOrReminder(
            title = cleanTitle,
            taskType = taskType,
            delayMinutes = delayMinutes,
            hour = parsedHour,
            minute = parsedMinute
        )
    }

    private fun extractTaskTitleRaw(raw: String): String {
        return raw
            .replace(Regex("^(?:من فضلك\\s+)?(?:ذكّرني|ذكرني|نبّهني|نبهني|أضف مهمة|اضف مهمة|أنشئ مهمة|انشئ مهمة|مهمة جديدة|مهمة:|اضبط تذكيراً|اضبط تذكير|اضبط منبهاً|اضبط منبه|ضبط منبه)\\s*"), "")
            .replace(Regex("(?:بعد\\s+[\\d٠-٩]+\\s*(?:دقيقة|دقائق|ساعة|ساعات)|بعد\\s+(?:نصف ساعة|ربع ساعة|ساعة|ساعتين|دقيقة))"), "")
            .replace(Regex("(?:على\\s+|في\\s+)?(?:الساعة\\s*)?[\\d٠-٩]{1,2}(?::[\\d٠-٩]{2})?\\s*(?:صباحاً|صباحا|مساءً|مساء|فجراً|فجرا|عصراً|عصرا|ليلاً|ليلا)?"), "")
            .trim()
            .replace(Regex("^ب(?:ـ)?\\s*(?=\\S)"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isPhoneUsageCommand(
        norm: String,
        knownAppNames: List<String>
    ): Boolean {
        val phoneActionVerbs = listOf(
            Regex("(?:^|\\s)(?:افتح|شغل|ابحث|اضغط|انقر|مرر|اسحب|تصفح|اضبط|فعل|عطل|اوقف|ثبت|انقل|اخف|اخفاء|صنف|تصنيف|ارفع|اخفض|خفض|زد|نقص|كتم|اكتم|اقفل|اغلق|سكر|اتصل\\s+ب|ارسل\\s+(?:رسالة|واتساب)|جرب\\s+(?:تطبيق|التطبيق|برنامج)|افحص\\s+(?:تطبيق|التطبيق|الشاشة)|لخص\\s+(?:تطبيق|التطبيق|الشاشة)|اقرا\\s+(?:تطبيق|التطبيق|الشاشة|شاشتي))"),
            Regex("(?:رتب|نظم|تنظيم)\\s+(?:تطبيقات|التطبيقات|تطبيقاتي|العابي|الالعاب|مجلد|المجلدات|الشاشة)"),
            Regex("(?:اكتب|ادخل)\\s+(?:في|داخل)\\s+(?:حقل|مربع|التطبيق|الشاشة)"),
            Regex("\\b(?:open|launch|search\\s+(?:in|on|for)|turn\\s+(?:on|off)|enable|disable|toggle|tap|click|swipe|scroll|arrange|organize\\s+apps|send\\s+message|set\\s+alarm)\\b", RegexOption.IGNORE_CASE)
        )
        if (phoneActionVerbs.any { it.containsMatchIn(norm) }) {
            return true
        }

        val deviceControlKeywords = listOf(
            Regex("(?:واي\\s*فاي|بلوتوث|السطوع|سطوع\\s*الشاشة|مستوي\\s*الصوت|صوت\\s*(?:الجهاز|الهاتف)|وضع\\s*الطيران|الكشاف|اعدادات\\s*(?:الجهاز|الهاتف|النظام)|\\bwifi\\b|\\bbluetooth\\b|\\bbrightness\\b|\\bvolume\\b|\\bflashlight\\b)")
        )
        if (deviceControlKeywords.any { it.containsMatchIn(norm) }) {
            return true
        }

        // إذا ذكر المستخدم اسماً لتطبيق مثبت ولم يكن سؤالاً معرفياً عاماً («ما هو ...» / «اشرح ...»)
        val isInformationalQuestion = Regex("^(?:ما\\s*(?:هو|هي|معني|فائدة|الفرق)|كيف\\s+يعمل|حدثني\\s+عن|اخبرني\\s+عن|اشرح|معلومات\\s+عن|what\\s+is)\\b")
            .containsMatchIn(norm)
        if (!isInformationalQuestion) {
            val mentionsInstalledApp = knownAppNames.any { appLabel ->
                val clean = normalizeDigitsAndArabic(appLabel).lowercase()
                clean.length >= 2 && norm.contains(clean)
            }
            if (mentionsInstalledApp) return true
        }

        return false
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
