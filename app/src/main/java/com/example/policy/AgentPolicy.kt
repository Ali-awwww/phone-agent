// سياسة المساعد الصارمة — النطاق والممنوعات
package com.example.policy

import com.example.model.AgentPlan
import com.example.model.AgentPlanStep
import com.example.model.StepActionType

enum class ForbiddenCategory(val titleAr: String) {
    PASSWORDS("كلمات المرور"),
    BIOMETRICS("البصمة"),
    PAYMENTS("الدفع"),
    PURCHASES("الشراء"),
    MONEY_TRANSFER("إرسال الأموال"),
    DATA_DELETION("حذف البيانات"),
    ACCOUNT_SETTINGS("تغيير إعدادات الحسابات"),
    LOCK_SCREEN_BYPASS("تجاوز قفل الشاشة")
}

data class PolicyDecision(
    val allowed: Boolean,
    val reasonAr: String,
    val violationCategory: ForbiddenCategory? = null
)

class AgentPolicy {

    companion object {
        val ALLOWED_SCOPES_AR: List<String> = listOf(
            "الإجابة عن الأسئلة العامة والنقاش والاستشارات المعرفية بالعربية",
            "تنظيم التطبيقات (عرض، بحث، تصنيف في مجلدات، نقل، إخفاء)",
            "تنفيذ الأوامر داخل التطبيقات (فتح تطبيق، بحث داخله، منبه، تذكير، رسالة بموافقتك، إعدادات جهاز بسيطة)",
            "تجربة التطبيقات وقراءة الشاشة الحقيقية واللعب كالإنسان خطوة بخطوة"
        )

        val FORBIDDEN_ITEMS_AR: List<String> = ForbiddenCategory.entries.map { it.titleAr }

        const val SUGGESTION_AR: String =
            "يمكنك سؤالي في أي موضوع عام أو استشارة، أو طلب تنفيذ مهام الهاتف مثل: «رتّب تطبيقاتي في مجلدات» أو «افتح المنبه» أو «ماذا في شاشتي؟»."
    }

    private data class ForbiddenRule(
        val category: ForbiddenCategory,
        val patterns: List<Regex>
    )

    private val forbiddenRules: List<ForbiddenRule> = listOf(
        ForbiddenRule(
            category = ForbiddenCategory.PASSWORDS,
            patterns = listOf(
                Regex("كلم(?:ة|ات)\\s*(?:ال)?مرور"),
                Regex("كلم(?:ة|ات)\\s*(?:ال)?سر"),
                Regex("(?:ال)?باسو?ورد"),
                Regex("(?:ال)?رمز\\s*(?:ال)?سري"),
                Regex("\\bpasswords?\\b", RegexOption.IGNORE_CASE),
                Regex("\\bpasscodes?\\b", RegexOption.IGNORE_CASE),
                Regex("\\bpin\\s*code\\b", RegexOption.IGNORE_CASE)
            )
        ),
        ForbiddenRule(
            category = ForbiddenCategory.BIOMETRICS,
            patterns = listOf(
                Regex("(?:[بلوف])?(?:ال)?بصم(?:ة|ات)"),
                Regex("بصم[ةه]\\s*(?:الاصبع|الوجه|العين)"),
                Regex("التعرف\\s*علي\\s*الوجه"),
                Regex("\\bfingerprint\\b", RegexOption.IGNORE_CASE),
                Regex("\\bbiometric\\b", RegexOption.IGNORE_CASE),
                Regex("\\bface\\s*id\\b", RegexOption.IGNORE_CASE)
            )
        ),
        ForbiddenRule(
            category = ForbiddenCategory.MONEY_TRANSFER,
            patterns = listOf(
                Regex("(?:ارسل|ارسال|تحويل|حول|سداد)\\s*(?:(?:ال)?اموالا?|(?:ال)?فلوس|مالا?|(?:ال)?نقود|مبلغا?|رصيدا?|حوال[ةه])"),
                Regex("(?:ارسل|ارسال|تحويل|حول)\\s+\\d+\\s*(?:ريال|دولار|درهم|جنيه|دينار|يورو)"),
                Regex("حوال[ةه]\\s*مالي[ةه]"),
                Regex("تحويل\\s*(?:بنكي|مالي)"),
                Regex("\\bsend\\s*money\\b", RegexOption.IGNORE_CASE),
                Regex("\\btransfer\\s*(?:money|funds)\\b", RegexOption.IGNORE_CASE),
                Regex("\\bwire\\s*transfer\\b", RegexOption.IGNORE_CASE)
            )
        ),
        ForbiddenRule(
            category = ForbiddenCategory.PAYMENTS,
            patterns = listOf(
                Regex("(?:^|\\s)(?:[بلوف])?(?:(?:ال)?دفع|ادفع|سدد|تسديد)(?:\\s|$)"),
                Regex("(?:[بلوف])?(?:ال)?دفع\\s+(?:ال)?(?:مالي|الكتروني|فاتور[ةه]|فواتير|مبلغ)"),
                Regex("بطاق[ةه]\\s*(?:بنكي[ةه]|ائتمان|ائتماني[ةه]|مدي|فيزا)"),
                Regex("(?:اتمام|تاكيد)\\s*(?:ال)?دفع"),
                Regex("\\bpay(?:ment|ing)?\\b", RegexOption.IGNORE_CASE),
                Regex("\\bcheckout\\b", RegexOption.IGNORE_CASE),
                Regex("\\bcredit\\s*card\\b", RegexOption.IGNORE_CASE)
            )
        ),
        ForbiddenRule(
            category = ForbiddenCategory.PURCHASES,
            patterns = listOf(
                Regex("(?:^|\\s)(?:[بلوف])?(?:(?:ال)?شراء|اشتري|اشتر|شراء\\s*من|اطلب\\s*شراء)(?:\\s|$)"),
                Regex("اتمام\\s*(?:ال)?شراء"),
                Regex("عملي[ةه]\\s*شراء"),
                Regex("\\bpurchase\\b", RegexOption.IGNORE_CASE),
                Regex("\\bbuy\\b", RegexOption.IGNORE_CASE),
                Regex("\\bplace\\s*order\\b", RegexOption.IGNORE_CASE)
            )
        ),
        ForbiddenRule(
            category = ForbiddenCategory.DATA_DELETION,
            patterns = listOf(
                Regex("(?:حذف|احذف|مسح|امسح|ازل|ازال[ةه]|تصفير|فرمت[ةه]|فورمات)\\s*(?:كل\\s*)?(?:(?:ال)?بيانات|(?:ال)?ملفات|(?:ال)?صور|(?:ال)?رسائل|(?:ال)?محادثات|(?:ال)?سجل|(?:ال)?جهات|جهات\\s*الاتصال|التطبيق|الجهاز|(?:ال)?ذاكر[ةه]|الحساب)"),
                Regex("حذف\\s*(?:ال)?بيانات"),
                Regex("مسح\\s*(?:ال)?بيانات"),
                Regex("اعاد[ةه]\\s*ضبط\\s*المصنع"),
                Regex("\\bdelete\\s*(?:data|files|messages|photos|account|all)\\b", RegexOption.IGNORE_CASE),
                Regex("\\bwipe\\b", RegexOption.IGNORE_CASE),
                Regex("\\bfactory\\s*reset\\b", RegexOption.IGNORE_CASE),
                Regex("\\bclear\\s*data\\b", RegexOption.IGNORE_CASE)
            )
        ),
        ForbiddenRule(
            category = ForbiddenCategory.ACCOUNT_SETTINGS,
            patterns = listOf(
                Regex("(?:تغيير|تعديل|ضبط|ادار[ةه]|اعدادات|تسجيل\\s*خروج|اضاف[ةه]|ازل|ازال[ةه])\\s*(?:اعدادات\\s*)?(?:ال)?حساب(?:ات)?"),
                Regex("اعدادات\\s*(?:ال)?حساب(?:ات)?"),
                Regex("حساب\\s*(?:جوجل|غوغل|google|البنك|بنكي)", RegexOption.IGNORE_CASE),
                Regex("\\baccount\\s*settings\\b", RegexOption.IGNORE_CASE),
                Regex("\\bchange\\s*account\\b", RegexOption.IGNORE_CASE),
                Regex("\\blog\\s*out\\b", RegexOption.IGNORE_CASE)
            )
        ),
        ForbiddenRule(
            category = ForbiddenCategory.LOCK_SCREEN_BYPASS,
            patterns = listOf(
                Regex("(?:تجاوز|تخطي|كسر|فك|فتح|الغاء|ازل|ازال[ةه]|تعطيل)\\s*(?:قفل\\s*(?:ال)?شاش[ةه]|شاش[ةه]\\s*(?:ال)?قفل|(?:ال)?قفل|(?:ال)?شاش[ةه]\\s*المقفل[ةه])"),
                Regex("تجاوز\\s*قفل\\s*الشاش[ةه]"),
                Regex("بدون\\s*(?:رمز|بصم[ةه]|قفل)"),
                Regex("\\bbypass\\s*(?:lock\\s*screen|screen\\s*lock|lock)\\b", RegexOption.IGNORE_CASE),
                Regex("\\bunlock\\s*without\\b", RegexOption.IGNORE_CASE)
            )
        )
    )

    private val inScopeKeywords: List<Regex> = listOf(
        // 1. تنظيم وعرض التطبيقات والألعاب
        Regex("(?:رتب|نظم|تنظيم|صنف|تصنيف|مجلد|مجلدات|اخف|اخفاء|اظهر|اظهار|اعرض|عرض|تطبيقاتي|تطبيقات|غير\\s*المستخدم[ةه]|العابي|العاب|لعبة)"),
        // 2. تنفيذ الأوامر داخل التطبيقات والمهام والتذكيرات والمنبهات
        Regex("(?:افتح|شغل|ابحث|بحث|منبه|منبهات|تذكير|تذكيرات|تذكيراتي|نبهني|ذكرني|مهمة|مهام|مهامي|الغي|الغاء|ارسل\\s*رسالة|رسالة|اعدادات|واي\\s*فاي|بلوتوث|سطوع|صوت|الصوت|السطوع|اتصال|كاميرا|ساعة|خرائط|ملاحظات|تقويم|متصفح|واتساب|تيليجرام|يوتيوب)"),
        Regex("(?:اضغط|انقر|مرر|اسحب|امسح|تصفح|شاشة|شاشتي|الشاشة|زر|قائمة|(?:اكتب|ادخل)\\s+(?:في|داخل|في\\s+حقل|في\\s+مربع|في\\s+التطبيق|رسالة))"),
        Regex("\\b(?:open|launch|search|alarm|reminder|task|tasks|message|wifi|bluetooth|volume|brightness|settings|scroll|tap|click|swipe|play|game|games)\\b", RegexOption.IGNORE_CASE),
        // 3. تجربة التطبيقات وقراءة الشاشة واللعب العام والدردشة الترحيبية بالوكيل
        Regex("(?:جرب|تجربة|اختبر|اختبار|لخص|تلخيص|ملخص|افحص|فحص|استكشف|محتوي\\s*التطبيق|ميزات\\s*التطبيق|اقرا\\s*الشاشة|اشرح\\s*التطبيق|ماذا\\s*في\\s*شاشتي|ما\\s*في\\s*شاشتي|ماذا\\s*فعلت|سجل|العب|لعب|تولي)"),
        Regex("(?:مرحبا|اهلا|سلام|السلام\\s*عليكم|صباح\\s*الخير|مساء\\s*الخير|شكرا|مساعدة|ساعدني|كيف\\s*حالك|من\\s*انت|عرفني|فحص|اتصال|متصل|تكلم|الوكيل|يا\\s*وكيل)")
    )

    fun findForbiddenCategory(text: String): ForbiddenCategory? {
        val normalized = normalizeArabic(text)
        if (normalized.isBlank()) return null
        for (rule in forbiddenRules) {
            if (rule.patterns.any { it.containsMatchIn(normalized) }) {
                return rule.category
            }
        }
        return null
    }

    fun isPhoneCommandHint(
        command: String,
        knownAppNames: List<String> = emptyList()
    ): Boolean {
        val normalized = normalizeArabic(command)
        if (normalized.isBlank()) return false
        if (inScopeKeywords.any { it.containsMatchIn(normalized) }) return true
        return knownAppNames.any { appLabel ->
            val cleanLabel = normalizeArabic(appLabel)
            cleanLabel.length >= 2 && normalized.contains(cleanLabel, ignoreCase = true)
        }
    }

    fun evaluateCommand(
        command: String,
        knownAppNames: List<String> = emptyList()
    ): PolicyDecision {
        val trimmed = command.trim()
        if (trimmed.isBlank()) {
            return PolicyDecision(
                allowed = false,
                reasonAr = "يرجى كتابة رسالتك أو أمرك أولاً. $SUGGESTION_AR"
            )
        }

        // 1. فحص القائمة الممنوعة الثمانية للأفعال الحساسة/الممنوعة قبل أي اتصال بـ Gemini
        val forbidden = findForbiddenCategory(trimmed)
        if (forbidden != null) {
            return PolicyDecision(
                allowed = false,
                reasonAr = "عذراً، لا أستطيع تنفيذ أي إجراء يتعلق بـ«${forbidden.titleAr}» حفاظاً على أمانك وخصوصيتك. $SUGGESTION_AR",
                violationCategory = forbidden
            )
        }

        // 2. إزالة القيد عن الأسئلة العامة والنقاش والاستشارات:
        // الوكيل يجيب عن أي سؤال عام أو نقاش، وينفذ أوامر الهاتف وفق قواعد الأمان والتحقق من الشاشة.
        val reason = if (isPhoneCommandHint(trimmed, knownAppNames)) {
            "أمر هاتف مسموح وفق سياسة الأمان."
        } else {
            "طلب عام/نقاش مسموح."
        }
        return PolicyDecision(
            allowed = true,
            reasonAr = reason
        )
    }

    fun validateStep(step: AgentPlanStep): PolicyDecision {
        val combinedText = listOf(
            step.titleAr,
            step.descriptionAr,
            step.targetAppName,
            step.targetQueryOrText,
            step.expectedScreenHint,
            step.safetyReasonAr
        ).joinToString(" ")

        val forbidden = findForbiddenCategory(combinedText)
        if (forbidden != null) {
            return PolicyDecision(
                allowed = false,
                reasonAr = "تم إيقاف الخطوة «${step.titleAr}» لأنها تمس إجراءً ممنوعاً (${forbidden.titleAr}). $SUGGESTION_AR",
                violationCategory = forbidden
            )
        }

        if (step.actionType == StepActionType.CHANGE_SETTING) {
            val normalizedSetting = normalizeArabic("${step.targetQueryOrText} ${step.descriptionAr}")
            if (normalizedSetting.contains("حساب") || normalizedSetting.contains("قفل") || normalizedSetting.contains("امان")) {
                val category = if (normalizedSetting.contains("قفل")) {
                    ForbiddenCategory.LOCK_SCREEN_BYPASS
                } else {
                    ForbiddenCategory.ACCOUNT_SETTINGS
                }
                return PolicyDecision(
                    allowed = false,
                    reasonAr = "تم إيقاف الخطوة «${step.titleAr}» لأن تغيير إعدادات الحسابات أو القفل ممنوع وفق سياسة الوكيل. $SUGGESTION_AR",
                    violationCategory = category
                )
            }
        }

        return PolicyDecision(
            allowed = true,
            reasonAr = "الخطوة متوافقة مع سياسة الوكيل."
        )
    }

    fun validatePlan(plan: AgentPlan): PolicyDecision {
        val commandCheck = findForbiddenCategory(plan.originalCommand)
        if (commandCheck != null) {
            return PolicyDecision(
                allowed = false,
                reasonAr = "رُفضت الخطة لأنها تتضمن أمراً ممنوعاً (${commandCheck.titleAr}). $SUGGESTION_AR",
                violationCategory = commandCheck
            )
        }

        val summaryCheck = findForbiddenCategory(
            "${plan.understandingSummaryAr} ${plan.spokenReplyAr}"
        )
        if (summaryCheck != null) {
            return PolicyDecision(
                allowed = false,
                reasonAr = "رُفضت الخطة لأنها تحتوي على إجراء ممنوع (${summaryCheck.titleAr}). $SUGGESTION_AR",
                violationCategory = summaryCheck
            )
        }

        for (step in plan.steps) {
            val stepDecision = validateStep(step)
            if (!stepDecision.allowed) {
                return stepDecision
            }
        }

        return PolicyDecision(
            allowed = true,
            reasonAr = "الخطة متوافقة مع سياسة الوكيل."
        )
    }

    private fun normalizeArabic(input: String): String {
        return input
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "") // إزالة التشكيل
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ى', 'ي')
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
