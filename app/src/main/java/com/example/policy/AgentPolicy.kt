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
                Regex("(?:ال)?رمز\\s*(?:ال)?(?:سري|تحقق|تاكيد|امان|تفعيل)"),
                Regex("(?:ال)?كود\\s*(?:ال)?(?:سري|تحقق|تاكيد|امان|تفعيل)"),
                Regex("(?:ال)?رقم\\s*(?:ال)?سري"),
                Regex("\\bpasswords?\\b", RegexOption.IGNORE_CASE),
                Regex("\\bpasscodes?\\b", RegexOption.IGNORE_CASE),
                Regex("\\bpin(?:\\s*code)?\\b", RegexOption.IGNORE_CASE),
                Regex("\\botp\\b", RegexOption.IGNORE_CASE),
                Regex("\\bcvv\\b", RegexOption.IGNORE_CASE),
                Regex("\\b(?:verification|security)\\s*code\\b", RegexOption.IGNORE_CASE),
                Regex("\\b2fa\\b", RegexOption.IGNORE_CASE)
            )
        ),
        ForbiddenRule(
            category = ForbiddenCategory.BIOMETRICS,
            patterns = listOf(
                Regex("(?:[بلوف])?(?:ال)?بصم(?:ة|ات)"),
                Regex("بصم[ةه]\\s*(?:الاصبع|الوجه|العين)"),
                Regex("(?:تاكيد|تفعيل|فتح)\\s*(?:بال)?(?:بصم[ةه]|وجه)"),
                Regex("التعرف\\s*علي\\s*الوجه"),
                Regex("\\bfingerprint\\b", RegexOption.IGNORE_CASE),
                Regex("\\bbiometric\\b", RegexOption.IGNORE_CASE),
                Regex("\\b(?:face|touch)\\s*id\\b", RegexOption.IGNORE_CASE)
            )
        ),
        ForbiddenRule(
            category = ForbiddenCategory.MONEY_TRANSFER,
            patterns = listOf(
                Regex("(?:ارسل|ارسال|تحويل|حول|سداد)\\s*(?:(?:ال)?اموالا?|(?:ال)?فلوس|مالا?|(?:ال)?نقود|مبلغا?|رصيدا?|حوال[ةه])"),
                Regex("(?:ارسل|ارسال|تحويل|حول)\\s+\\d+\\s*(?:ريال|دولار|درهم|جنيه|دينار|يورو)"),
                Regex("حوال[ةه](?:\\s*مالي[ةه])?"),
                Regex("تحويل\\s*(?:بنكي|مالي|رصيد|مبلغ|فلوس|مال)"),
                Regex("\\bsend\\s*money\\b", RegexOption.IGNORE_CASE),
                Regex("\\btransfer\\s*(?:money|funds)\\b", RegexOption.IGNORE_CASE),
                Regex("\\bwire\\s*transfer\\b", RegexOption.IGNORE_CASE),
                Regex("\\b(?:iban|swift|paypal|zelle|venmo)\\b", RegexOption.IGNORE_CASE)
            )
        ),
        ForbiddenRule(
            category = ForbiddenCategory.PAYMENTS,
            patterns = listOf(
                Regex("(?:^|\\s)(?:[بلوف])?(?:(?:ال)?دفع|ادفع|سدد|تسديد)(?:\\s|$)"),
                Regex("(?:[بلوف])?(?:ال)?دفع\\s+(?:ال)?(?:مالي|الكتروني|فاتور[ةه]|فواتير|مبلغ)"),
                Regex("بطاق[ةه]\\s*(?:بنكي[ةه]|ائتمان|ائتماني[ةه]|مدي|فيزا)"),
                Regex("(?:اتمام|تاكيد)\\s*(?:ال)?دفع"),
                Regex("(?:^|\\s)(?:فاتور[ةه]|فواتير|ابل\\s*باي)(?:\\s|$)"),
                Regex("\\bpay(?:ment|ing)?\\b", RegexOption.IGNORE_CASE),
                Regex("\\bcheckout\\b", RegexOption.IGNORE_CASE),
                Regex("\\bcredit\\s*card\\b", RegexOption.IGNORE_CASE),
                Regex("\\b(?:apple|google)\\s*pay\\b", RegexOption.IGNORE_CASE)
            )
        ),
        ForbiddenRule(
            category = ForbiddenCategory.PURCHASES,
            patterns = listOf(
                Regex("(?:^|\\s)(?:[بلوف])?(?:(?:ال)?شراء|اشتري|اشتر|شراء\\s*من|اطلب\\s*شراء)(?:\\s|$)"),
                Regex("(?:اتمام|تاكيد)\\s*(?:ال)?(?:شراء|طلب)"),
                Regex("عملي[ةه]\\s*شراء"),
                Regex("\\bpurchase\\b", RegexOption.IGNORE_CASE),
                Regex("\\bbuy\\b", RegexOption.IGNORE_CASE),
                Regex("\\bplace\\s*order\\b", RegexOption.IGNORE_CASE)
            )
        ),
        ForbiddenRule(
            category = ForbiddenCategory.DATA_DELETION,
            patterns = listOf(
                Regex("(?:حذف|احذف|مسح|امسح|ازل|ازال[ةه]|تصفير|فرمت[ةه]?|فورمات|محو|اتلاف)\\s*(?:كل\\s*)?(?:(?:ال)?بيانات|(?:ال)?ملفات|(?:ال)?صور|(?:ال)?فيديو(?:هات)?|(?:ال)?رسائل|(?:ال)?محادثات|(?:ال)?سجل|(?:ال)?جهات|جهات\\s*الاتصال|(?:ال)?تطبيق(?:ات)?|(?:ال)?برنامج|(?:ال)?جهاز|(?:ال)?ذاكر[ةه]|(?:ال)?حساب|هذا|هذه|ذلك|تلك|الكل|الجميع|كل\\s*شيء)"),
                Regex("(?:^|\\s)(?:فرمت[ةه]?|فورمات|تصفير|اتلاف)(?:\\s|$)"),
                Regex("(?:الغاء|الغي|ازال[ةه]|حذف|احذف)\\s*(?:ال)?تثبيت"),
                Regex("حذف\\s*(?:ال)?بيانات"),
                Regex("مسح\\s*(?:ال)?بيانات"),
                Regex("اعاد[ةه]\\s*ضبط\\s*المصنع"),
                Regex("\\bdelete\\s*(?:data|files|messages|photos|account|all|this|that|everything|app)\\b", RegexOption.IGNORE_CASE),
                Regex("\\buninstall\\b", RegexOption.IGNORE_CASE),
                Regex("\\bwipe\\b", RegexOption.IGNORE_CASE),
                Regex("\\bformat(?:\\s+(?:device|phone|storage|disk|card))?\\b", RegexOption.IGNORE_CASE),
                Regex("\\bfactory\\s*reset\\b", RegexOption.IGNORE_CASE),
                Regex("\\bclear\\s*data\\b", RegexOption.IGNORE_CASE),
                Regex("\\berase\\b", RegexOption.IGNORE_CASE)
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
                Regex("\\blog\\s*out\\b", RegexOption.IGNORE_CASE),
                Regex("\\bsign\\s*out\\b", RegexOption.IGNORE_CASE)
            )
        ),
        ForbiddenRule(
            category = ForbiddenCategory.LOCK_SCREEN_BYPASS,
            patterns = listOf(
                Regex("(?:تجاوز|تخطي|كسر|فك|فتح|الغاء|ازل|ازال[ةه]|تعطيل)\\s*(?:قفل\\s*(?:ال)?شاش[ةه]|شاش[ةه]\\s*(?:ال)?قفل|(?:ال)?قفل|(?:ال)?شاش[ةه]\\s*المقفل[ةه])"),
                Regex("تجاوز\\s*قفل\\s*الشاش[ةه]"),
                Regex("تخطي\\s*(?:ال)?قفل"),
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
            if (!(forbidden == ForbiddenCategory.DATA_DELETION && isAllowedFileManagementCleanup(trimmed))) {
                val plainReason = com.example.data.local.UserMemoryRepository.formatPlainPolicyRejection(
                    actionType = "POLICY_BLOCKED_${forbidden.name}",
                    category = forbidden
                )
                return PolicyDecision(
                    allowed = false,
                    reasonAr = plainReason,
                    violationCategory = forbidden
                )
            }
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

    fun isAllowedFileManagementCleanup(text: String): Boolean {
        val normalized = normalizeArabic(text).lowercase()
        if (normalized.isBlank()) return false

        // حظر صريح لأي محاولة تمس بيانات التطبيقات أو الصور أو الرسائل أو جهات الاتصال أو التطبيقات أو الفورمات أو أوامر الحذف المبهمة
        val blockedTargets = listOf(
            "بيانات",
            "صور",
            "الصور",
            "فيديو",
            "رسائل",
            "محادثات",
            "جهات",
            "واتساب",
            "حساب",
            "تطبيق",
            "التطبيق",
            "تطبيقات",
            "تثبيت",
            "مصنع",
            "فورمات",
            "فرمتة",
            "فرمته",
            "فرمت",
            "تصفير",
            "احذف هذا",
            "حذف هذا",
            "احذف الكل",
            "حذف الكل",
            "امسح الكل",
            "android/data",
            "android/obb",
            "android/media",
            "factory reset",
            "wipe",
            "clear data",
            "uninstall",
            "format",
            "delete this",
            "photos",
            "messages",
            "contacts"
        )
        if (blockedTargets.any { normalized.contains(it) }) {
            return false
        }

        val allowedPatterns = listOf(
            Regex("(?:مجلد|مجلدات)\\s*(?:ال)?فارغ[ةه]?"),
            Regex("(?:فحص|تنظيف|مسح|حذف)\\s*(?:ال)?مجلدات\\s*(?:ال)?فارغ[ةه]?"),
            Regex("(?:فحص|تنظيف)\\s*(?:ال)?(?:ملفات|تخزين|هاتف)"),
            Regex("(?:حذف|تنظيف)\\s*(?:ال)?ملفات\\s*(?:ال)?(?:مؤقت[ةه]?|مكرر[ةه]?|كبير[ةه]?|زائد[ةه]?)"),
            Regex("ذاكر[ةه]\\s*(?:ال)?تخزين\\s*(?:ال)?مؤقت"),
            Regex("سل[ةه]\\s*(?:ال)?مهملات"),
            Regex("\\b(?:empty\\s+folders?|clean\\s+empty\\s+folders?|file\\s+management|scan\\s+files|temp(?:orary)?\\s+files|duplicate\\s+files|cache\\s+files)\\b", RegexOption.IGNORE_CASE)
        )
        return allowedPatterns.any { it.containsMatchIn(normalized) }
    }

    fun validateFileCleanupBatch(
        paths: List<String>,
        approvedByUser: Boolean,
        appPackageName: String = "",
        trashRootPath: String? = null
    ): PolicyDecision {
        if (!approvedByUser) {
            return PolicyDecision(
                allowed = false,
                reasonAr = "لا يمكن حذف أو نقل أي ملفات قبل موافقة المستخدم الصريحة على هذه الدفعة تحديداً.",
                violationCategory = ForbiddenCategory.DATA_DELETION
            )
        }
        if (paths.isEmpty()) {
            return PolicyDecision(
                allowed = false,
                reasonAr = "الدفعة المحددة فارغة ولا تحتوي على عناصر."
            )
        }
        for (path in paths) {
            if (com.example.files.PhoneFileScanner.isExcludedPath(path, appPackageName, trashRootPath)) {
                return PolicyDecision(
                    allowed = false,
                    reasonAr = "تم إيقاف الدفعة لأنها تتضمن مساراً محمياً من النظام أو مجلدات Android: $path",
                    violationCategory = ForbiddenCategory.DATA_DELETION
                )
            }
        }
        return PolicyDecision(
            allowed = true,
            reasonAr = "دفعة تنظيف الملفات معتمدة من المستخدم وآمنة للتنفيذ."
        )
    }

    fun validateAction(
        actionType: StepActionType?,
        targetText: String = "",
        description: String = "",
        targetAppName: String = "",
        expectedScreenHint: String = "",
        safetyReason: String = "",
        actionTitleAr: String = ""
    ): PolicyDecision {
        val combinedText = listOf(
            actionTitleAr,
            description,
            targetAppName,
            targetText,
            expectedScreenHint,
            safetyReason
        ).filter { it.isNotBlank() }.joinToString(" ")

        // فحص مباشر على الهدف منفرداً ثم على السياق المجمّع (مع عدم السماح باستثناء الملفات في مسار التنفيذ العام)
        val forbidden = findForbiddenCategory(targetText) ?: findForbiddenCategory(combinedText)
        if (forbidden != null) {
            val label = actionTitleAr.ifBlank { targetText.ifBlank { description } }.take(60)
            val plainReason = com.example.data.local.UserMemoryRepository.formatPlainPolicyRejection(
                actionType = "POLICY_BLOCKED_${forbidden.name}",
                category = forbidden
            )
            return PolicyDecision(
                allowed = false,
                reasonAr = "تم إيقاف الإجراء «$label»:\n$plainReason",
                violationCategory = forbidden
            )
        }

        val normalizedActionScope = normalizeArabic("$targetText $description $targetAppName $expectedScreenHint").lowercase()

        when (actionType) {
            StepActionType.CHANGE_SETTING -> {
                if (normalizedActionScope.contains("قفل") ||
                    normalizedActionScope.contains("lock")
                ) {
                    return PolicyDecision(
                        allowed = false,
                        reasonAr = com.example.data.local.UserMemoryRepository.formatPlainPolicyRejection(
                            actionType = "POLICY_BLOCKED_LOCK_SCREEN_BYPASS",
                            category = ForbiddenCategory.LOCK_SCREEN_BYPASS
                        ),
                        violationCategory = ForbiddenCategory.LOCK_SCREEN_BYPASS
                    )
                }
                if (normalizedActionScope.contains("حساب") ||
                    normalizedActionScope.contains("امان") ||
                    normalizedActionScope.contains("account") ||
                    normalizedActionScope.contains("security")
                ) {
                    return PolicyDecision(
                        allowed = false,
                        reasonAr = com.example.data.local.UserMemoryRepository.formatPlainPolicyRejection(
                            actionType = "POLICY_BLOCKED_ACCOUNT_SETTINGS",
                            category = ForbiddenCategory.ACCOUNT_SETTINGS
                        ),
                        violationCategory = ForbiddenCategory.ACCOUNT_SETTINGS
                    )
                }
                if (normalizedActionScope.contains("مصنع") ||
                    normalizedActionScope.contains("factory") ||
                    normalizedActionScope.contains("reset")
                ) {
                    return PolicyDecision(
                        allowed = false,
                        reasonAr = com.example.data.local.UserMemoryRepository.formatPlainPolicyRejection(
                            actionType = "POLICY_BLOCKED_DATA_DELETION",
                            category = ForbiddenCategory.DATA_DELETION
                        ),
                        violationCategory = ForbiddenCategory.DATA_DELETION
                    )
                }
            }
            StepActionType.INPUT_TEXT -> {
                if (normalizedActionScope.contains("سري") ||
                    normalizedActionScope.contains("otp") ||
                    normalizedActionScope.contains("pin") ||
                    normalizedActionScope.contains("cvv") ||
                    normalizedActionScope.contains("تحقق")
                ) {
                    return PolicyDecision(
                        allowed = false,
                        reasonAr = com.example.data.local.UserMemoryRepository.formatPlainPolicyRejection(
                            actionType = "POLICY_BLOCKED_PASSWORDS",
                            category = ForbiddenCategory.PASSWORDS
                        ),
                        violationCategory = ForbiddenCategory.PASSWORDS
                    )
                }
            }
            else -> Unit
        }

        return PolicyDecision(
            allowed = true,
            reasonAr = "الإجراء متوافق مع سياسة الوكيل."
        )
    }

    fun validateStep(step: AgentPlanStep): PolicyDecision {
        return validateAction(
            actionType = step.actionType,
            targetText = step.targetQueryOrText,
            description = step.descriptionAr,
            targetAppName = step.targetAppName,
            expectedScreenHint = step.expectedScreenHint,
            safetyReason = step.safetyReasonAr,
            actionTitleAr = step.titleAr
        )
    }

    fun validatePlan(plan: AgentPlan): PolicyDecision {
        val commandCheck = findForbiddenCategory(plan.originalCommand)
        if (commandCheck != null) {
            return PolicyDecision(
                allowed = false,
                reasonAr = com.example.data.local.UserMemoryRepository.formatPlainPolicyRejection(
                    actionType = "POLICY_BLOCKED_${commandCheck.name}",
                    category = commandCheck
                ),
                violationCategory = commandCheck
            )
        }

        val summaryCheck = findForbiddenCategory(
            "${plan.understandingSummaryAr} ${plan.spokenReplyAr}"
        )
        if (summaryCheck != null) {
            return PolicyDecision(
                allowed = false,
                reasonAr = com.example.data.local.UserMemoryRepository.formatPlainPolicyRejection(
                    actionType = "POLICY_BLOCKED_${summaryCheck.name}",
                    category = summaryCheck
                ),
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

    internal fun normalizeArabic(input: String): String {
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
