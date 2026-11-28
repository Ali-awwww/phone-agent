package com.example.data.local

import com.example.policy.ForbiddenCategory
import com.example.security.PrivacyPreferences
import com.example.security.SensitiveDataScrubber
import kotlinx.coroutines.flow.Flow

class UserMemoryRepository(
    private val memoryDao: UserMemoryDao,
    private val habitDao: HabitLogDao,
    private val summaryDao: ConversationSummaryDao? = null,
    private val privacyPreferences: PrivacyPreferences? = null
) {
    val allMemories: Flow<List<UserMemoryEntity>> = memoryDao.observeAllMemories()
    val allHabits: Flow<List<HabitLogEntity>> = habitDao.observeAllHabits()
    val allSummaries: Flow<List<ConversationSummaryEntity>>? = summaryDao?.observeAllSummaries()

    @Volatile
    private var cachedMemoryContext: String = ""

    suspend fun rememberFact(
        fact: String,
        source: String = SOURCE_CONVERSATION,
        confidence: Float = 1.0f
    ): Long {
        if (privacyPreferences?.isPrivacyModeEnabled() == true) {
            return -1L
        }
        val cleanFact = SensitiveDataScrubber.scrub(fact).trim()
        if (cleanFact.isBlank()) return -1L

        val existing = memoryDao.getAllMemories().firstOrNull {
            it.fact.equals(cleanFact, ignoreCase = true)
        }
        val id = existing?.id ?: memoryDao.insertMemory(
            UserMemoryEntity(
                fact = cleanFact,
                source = source,
                confidence = confidence.coerceIn(0f, 1f)
            )
        )
        refreshCachedContext()
        return id
    }

    suspend fun forgetFact(id: Long) {
        memoryDao.deleteMemory(id)
        refreshCachedContext()
    }

    suspend fun clearMemory() {
        memoryDao.clearAllMemories()
        summaryDao?.clearAllSummaries()
        cachedMemoryContext = ""
    }

    suspend fun getAllMemories(): List<UserMemoryEntity> {
        return memoryDao.getAllMemories()
    }

    suspend fun saveConversationSummary(
        summaryText: String,
        turnsCount: Int,
        summaryType: String = SUMMARY_TYPE_TRUNCATION,
        fromTimestamp: Long = System.currentTimeMillis(),
        toTimestamp: Long = System.currentTimeMillis(),
        alsoSaveAsMemoryFact: Boolean = (summaryType == SUMMARY_TYPE_PERIODIC_20)
    ): Long {
        if (privacyPreferences?.isPrivacyModeEnabled() == true) {
            return -1L
        }
        val cleanSummary = SensitiveDataScrubber.scrub(summaryText).trim()
        if (cleanSummary.isBlank()) return -1L

        val summaryId = summaryDao?.insertSummary(
            ConversationSummaryEntity(
                summaryText = cleanSummary,
                turnsCount = turnsCount.coerceAtLeast(1),
                summaryType = summaryType,
                fromTimestamp = fromTimestamp,
                toTimestamp = toTimestamp
            )
        ) ?: 0L

        if (alsoSaveAsMemoryFact) {
            val sourceTag = if (summaryType == SUMMARY_TYPE_PERIODIC_20) {
                SOURCE_PERIODIC_SUMMARY
            } else {
                SOURCE_CONVERSATION_SUMMARY
            }
            memoryDao.insertMemory(
                UserMemoryEntity(
                    fact = cleanSummary.take(400),
                    source = sourceTag,
                    confidence = 0.9f
                )
            )
            refreshCachedContext()
        }
        return summaryId
    }

    suspend fun getRecentConversationSummaries(limit: Int = 5): List<ConversationSummaryEntity> {
        if (privacyPreferences?.isPrivacyModeEnabled() == true) return emptyList()
        return summaryDao?.getRecentSummaries(limit.coerceAtLeast(1)) ?: emptyList()
    }

    suspend fun recordHabit(
        actionName: String,
        hourOfDay: Int,
        dayOfWeek: Int,
        timestamp: Long = System.currentTimeMillis()
    ) {
        if (privacyPreferences?.isPrivacyModeEnabled() == true) return
        val cleanAction = SensitiveDataScrubber.scrub(actionName).trim()
        if (cleanAction.isBlank()) return
        val safeHour = hourOfDay.coerceIn(0, 23)
        val safeDay = dayOfWeek.coerceIn(1, 7)

        val existing = habitDao.findHabitSlot(cleanAction, safeHour, safeDay)
        if (existing != null) {
            habitDao.incrementHabitFrequency(
                id = existing.id,
                delta = 1,
                lastTimestamp = maxOf(existing.lastTimestamp, timestamp)
            )
        } else {
            habitDao.insertHabit(
                HabitLogEntity(
                    actionName = cleanAction,
                    hourOfDay = safeHour,
                    dayOfWeek = safeDay,
                    frequencyCount = 1,
                    lastTimestamp = timestamp
                )
            )
        }
    }

    suspend fun getTopHabits(): List<HabitLogEntity> {
        return habitDao.getAllHabits()
    }

    suspend fun clearHabits() {
        habitDao.clearAllHabits()
    }

    suspend fun buildConciseMemoryContext(maxChars: Int = 900): String {
        if (privacyPreferences?.isPrivacyModeEnabled() == true) {
            cachedMemoryContext = ""
            return ""
        }
        val memories = memoryDao.getAllMemories()
        val formatted = formatMemoriesList(memories, maxChars)
        cachedMemoryContext = formatted
        return formatted
    }

    fun formatMemoryContext(): String {
        if (privacyPreferences?.isPrivacyModeEnabled() == true) return ""
        return cachedMemoryContext.ifBlank {
            "الذاكرة الدائمة وتفضيلات المستخدم نشطة ومحفوظة محلياً."
        }
    }

    private suspend fun refreshCachedContext() {
        val memories = memoryDao.getAllMemories()
        cachedMemoryContext = formatMemoriesList(memories, 900)
    }

    private fun formatMemoriesList(memories: List<UserMemoryEntity>, maxChars: Int): String {
        if (memories.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("حقائق وملخصات محفوظة عن المستخدم:")
        for (item in memories.take(12)) {
            val clean = SensitiveDataScrubber.scrub(item.fact).trim()
            if (clean.isBlank()) continue
            val line = "- $clean"
            if (sb.length + line.length + 1 > maxChars) break
            sb.appendLine(line)
        }
        return sb.toString().trim()
    }

    companion object {
        const val PERIODIC_SUMMARY_INTERVAL_TURNS = 20
        const val SOURCE_CONVERSATION = "CONVERSATION"
        const val SOURCE_PREFERENCE = "PREFERENCE"
        const val SOURCE_PERIODIC_SUMMARY = "LITE_SUMMARY_20"
        const val SOURCE_CONVERSATION_SUMMARY = "CONVERSATION_SUMMARY"
        const val SUMMARY_TYPE_TRUNCATION = "TRUNCATION"
        const val SUMMARY_TYPE_PERIODIC_20 = "PERIODIC_20"

        fun explainPolicyRejection(
            actionType: String,
            category: ForbiddenCategory? = null
        ): Pair<String, String> {
            val resolvedCategory = category ?: inferCategoryFromTag(actionType)
            return when (resolvedCategory) {
                ForbiddenCategory.PASSWORDS ->
                    "عذراً، لا أستطيع قراءة أو كتابة كلمات المرور أو رموز التحقق (OTP) لأنها تخالف سياسات الأمان وحماية حساباتك." to
                        "بدلاً من ذلك، يمكنني فتح صفحة تسجيل الدخول لك فقط لتُدخل كلمة المرور أو تستخدم مدير كلمات المرور بنفسك بأمان."

                ForbiddenCategory.BIOMETRICS ->
                    "عذراً، لا يمكنني استخدام البصمة أو التحقق بالوجه نيابةً عنك وفق سياسات الأمان لأن هذه الخطوة تثبت هويتك الشخصية وحدك." to
                        "بدلاً من ذلك، يمكنك إتمام التحقق بالبصمة بيدك أولاً ثم أتابع معك الخطوات التالية بعده."

                ForbiddenCategory.PAYMENTS ->
                    "عذراً، أمتنع عن إتمام المدفوعات أو التعامل مع البطاقات البنكية التزاماً بـسياسات الأمان لمنع أي عملية مالية غير مقصودة." to
                        "بدلاً من ذلك، أستطيع فتح شاشة الدفع أو الفاتورة لك لتراجع المبلغ والتفاصيل وتؤكد الدفع بنفسك."

                ForbiddenCategory.PURCHASES ->
                    "عذراً، لا أنفّذ عمليات الشراء وتأكيد الطلبات تلقائياً حفاظاً على أموالك ووفق سياسات الأمان." to
                        "بدلاً من ذلك، يمكنني فتح التطبيق والبحث عن المنتج الذي تريده، ثم تراجعه وتتم الشراء بنفسك."

                ForbiddenCategory.MONEY_TRANSFER ->
                    "عذراً، تحويل الأموال أو إرسال الرصيد ممنوع تنفيذياً وفق سياسات الأمان لحماية حساباتك المالية." to
                        "بدلاً من ذلك، يمكنك فتح تطبيق البنك أو المحفظة وطلب الإجراء يدوياً بعد التحقق من المستلم والمبلغ."

                ForbiddenCategory.DATA_DELETION ->
                    "عذراً، لا أنفّذ حذف البيانات الشخصية أو الصور أو المحادثات أو تهيئة الجهاز لأن سياسات الأمان تمنع الحذف غير القابل للاسترجاع." to
                        "بدلاً من ذلك، يمكنك طلب «فحص الملفات» لتنظيف المجلدات الفارغة والملفات المؤقتة عبر سلة مهملات آمنة لمدة 7 أيام، أو حذف العنصر يدوياً."

                ForbiddenCategory.ACCOUNT_SETTINGS ->
                    "عذراً، تعديل إعدادات الحسابات أو تسجيل الخروج محظور وفق سياسات الأمان لتجنب فقدان وصولك إلى حساباتك." to
                        "بدلاً من ذلك، يمكنني فتح شاشة الإعدادات العامة لك لتراجع خيارات الحساب وتعدّلها بنفسك بأمان."

                ForbiddenCategory.LOCK_SCREEN_BYPASS ->
                    "عذراً، لا يمكنني تجاوز قفل الشاشة أو تعطيل الحماية لأن ذلك يخالف سياسات الأمان وخصوصية جهازك." to
                        "بدلاً من ذلك، افتح قفل الشاشة بطريقتك المعتادة ثم اطلب مني المهمة التي تريد تنفيذها."

                null -> when {
                    actionType.contains("POLICY_BLOCKED", ignoreCase = true) ||
                        actionType.contains("POLICY_REJECTED", ignoreCase = true) ->
                        "عذراً، لا يمكن تنفيذ هذا الإجراء لأنه يتعارض مع سياسات الأمان وحماية الخصوصية." to
                            "بدلاً من ذلك، يمكنك تنفيذ الخطوة الحساسة يدوياً بنفسك أو طلب مهمة آمنة مثل فتح التطبيق أو ضبط تذكير."

                    actionType.contains("TIMEOUT", ignoreCase = true) ->
                        "أُلغي الإجراء احترازياً وفق سياسات الأمان بسبب انتهاء مهلة انتظار موافقتك الصريحة." to
                            "بدلاً من ذلك، يمكنك إعادة إرسال الأمر والموافقة عند ظهور بطاقة التأكيد متى كنت مستعداً."

                    actionType.contains("USER_REJECTED", ignoreCase = true) ->
                        "توقفت عن تنفيذ الإجراء بناءً على قرارك المباشر واحتراماً لـسياسات الأمان والتحكم الكامل بيدك." to
                            "بدلاً من ذلك، يمكنك تعديل صيغة الطلب أو تنفيذ الجزء الذي تريده يدوياً."

                    else ->
                        "عذراً، تعذر إتمام هذا الإجراء التزاماً بـسياسات الأمان والتحقق الفعلي." to
                            "بدلاً من ذلك، يرجى التحقق من الأذونات أو تجربة أمر آمن مثل «افتح الإعدادات» أو «ماذا في شاشتي؟»."
                }
            }
        }

        fun formatPlainPolicyRejection(
            actionType: String,
            category: ForbiddenCategory? = null
        ): String {
            val (reason, alternative) = explainPolicyRejection(actionType, category)
            return "$reason\n$alternative"
        }

        private fun inferCategoryFromTag(actionType: String): ForbiddenCategory? {
            val upper = actionType.uppercase()
            return ForbiddenCategory.entries.firstOrNull { upper.contains(it.name) }
        }
    }
}
