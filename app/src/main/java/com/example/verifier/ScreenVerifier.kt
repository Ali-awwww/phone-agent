package com.example.verifier

import android.content.Context
import android.content.Intent
import com.example.data.ai.GeminiRepository
import com.example.model.AgentPlan
import com.example.model.AgentPlanStep
import com.example.model.ScreenSnapshot
import com.example.model.ScreenVerificationResult
import com.example.model.StepActionType
import com.example.model.StepStatus
import com.example.model.StepVerificationStatus
import com.example.organizer.InstalledAppInfo
import com.example.service.ScreenStateBridge

data class PreExecutionCheck(
    val allowed: Boolean,
    val verificationStatus: StepVerificationStatus,
    val reasonAr: String,
    val matchedApp: InstalledAppInfo? = null
)

class ScreenVerifier(
    private val context: Context,
    private val geminiRepository: GeminiRepository
) {
    companion object {
        const val MSG_ELEMENT_NOT_FOUND = "لم أجد هذا العنصر على الشاشة، لن أخترع خطوة غير موجودة"
        private val PHONE_NUMBER_REGEX = Regex("""\+?\d[\d\s\-]{5,}\d""")
    }

    /**
     * فحص الخطة المولدة من Gemini ضد الواقع الفعلي (PackageManager + مدخلات المستخدم + الشاشة الحالية)
     * لمنع أي هلوسة في أسماء التطبيقات أو الأرقام.
     */
    fun groundPlanAgainstReality(
        plan: AgentPlan,
        installedApps: List<InstalledAppInfo>
    ): AgentPlan {
        val warnings = mutableListOf<String>()
        val commandDigits = extractDigitSequences(plan.originalCommand)

        val groundedSteps = plan.steps.map { step ->
            var updated = step

            // التحقق من عدم اختراع أرقام هواتف في خطوات المراسلة
            if (step.actionType == StepActionType.SEND_MESSAGE) {
                val stepNumbers = extractDigitSequences(step.targetQueryOrText + " " + step.descriptionAr)
                val inventedNumbers = stepNumbers.filter { num ->
                    num.length >= 6 && commandDigits.none { it.contains(num) || num.contains(it) }
                }
                if (inventedNumbers.isNotEmpty()) {
                    warnings.add("تم رصد رقم غير موجود في طلبك الأصلي (${inventedNumbers.joinToString("، ")})؛ سيُترك المستلم لاختيارك اليدوي.")
                    updated = updated.copy(
                        requiresApproval = true,
                        safetyReasonAr = "تنبيه تحقق: تم منع استخدام رقم غير مذكور في طلبك؛ يرجى اختيار جهة الاتصال بنفسك.",
                        verificationResultAr = "تم حجب رقم غير موثق من خطة الذكاء الاصطناعي."
                    )
                }
            }

            // مطابقة اسم التطبيق مع PackageManager الفعلي
            if (step.actionType == StepActionType.OPEN_APP ||
                (step.actionType == StepActionType.SEARCH_IN_APP && step.targetAppName.isNotBlank())
            ) {
                val realApp = matchRealInstalledApp(step.targetAppName, installedApps)
                if (realApp != null) {
                    updated = updated.copy(
                        targetAppName = realApp.label,
                        verifiedAppPackage = realApp.packageName
                    )
                } else if (step.actionType == StepActionType.OPEN_APP) {
                    warnings.add("التطبيق «${step.targetAppName}» غير موجود بين التطبيقات المثبتة فعلياً على جهازك.")
                    updated = updated.copy(
                        verificationStatus = StepVerificationStatus.NOT_FOUND_ON_SCREEN,
                        verificationResultAr = MSG_ELEMENT_NOT_FOUND
                    )
                }
            }

            updated
        }

        return plan.copy(
            steps = groundedSteps,
            hallucinationWarnings = warnings
        )
    }

    /**
     * طبقة التحقق الإجبارية قبل تنفيذ أي خطوة:
     * لا تُنفّذ الخطوة إلا بعد قراءة الشاشة الفعلية أو التحقق من PackageManager.
     */
    fun verifyBeforeStep(
        step: AgentPlanStep,
        installedApps: List<InstalledAppInfo>
    ): PreExecutionCheck {
        val snapshot = ScreenStateBridge.getService()?.captureCurrentScreen()
            ?: ScreenStateBridge.latestSnapshot.value

        if (snapshot?.hasSensitiveSecurityField == true) {
            return PreExecutionCheck(
                allowed = false,
                verificationStatus = StepVerificationStatus.VERIFICATION_FAILED,
                reasonAr = "توقف التنفيذ لحماية أمانك: الشاشة الحالية تحتوي على حقل كلمة مرور أو بصمة أو دفع."
            )
        }

        return when (step.actionType) {
            StepActionType.OPEN_APP -> {
                val realApp = matchRealInstalledApp(step.targetAppName, installedApps)
                if (realApp == null) {
                    PreExecutionCheck(
                        allowed = false,
                        verificationStatus = StepVerificationStatus.NOT_FOUND_ON_SCREEN,
                        reasonAr = "$MSG_ELEMENT_NOT_FOUND (التطبيق «${step.targetAppName}» غير مثبت فعلياً في PackageManager)."
                    )
                } else {
                    PreExecutionCheck(
                        allowed = true,
                        verificationStatus = StepVerificationStatus.VERIFIED_ON_SCREEN,
                        reasonAr = "تم التحقق من وجود «${realApp.label}» في التطبيقات المثبتة.",
                        matchedApp = realApp
                    )
                }
            }

            StepActionType.TAP_ELEMENT -> {
                if (snapshot == null) {
                    return PreExecutionCheck(
                        allowed = false,
                        verificationStatus = StepVerificationStatus.NOT_FOUND_ON_SCREEN,
                        reasonAr = "$MSG_ELEMENT_NOT_FOUND (تعذر قراءة الشاشة؛ فعّل خدمة إمكانية الوصول أولاً)."
                    )
                }
                val query = step.targetQueryOrText.trim()
                val found = query.isNotBlank() && (
                    snapshot.clickableLabels.any { it.contains(query, ignoreCase = true) } ||
                        snapshot.visibleTexts.any { it.contains(query, ignoreCase = true) }
                    )
                if (!found) {
                    PreExecutionCheck(
                        allowed = false,
                        verificationStatus = StepVerificationStatus.NOT_FOUND_ON_SCREEN,
                        reasonAr = "$MSG_ELEMENT_NOT_FOUND (العنصر المطلوب: «$query» غير موجود في عناصر الشاشة المقروءة)."
                    )
                } else {
                    PreExecutionCheck(
                        allowed = true,
                        verificationStatus = StepVerificationStatus.VERIFIED_ON_SCREEN,
                        reasonAr = "تم العثور على الزر «$query» فعلياً على الشاشة."
                    )
                }
            }

            StepActionType.INPUT_TEXT -> {
                if (snapshot == null) {
                    return PreExecutionCheck(
                        allowed = false,
                        verificationStatus = StepVerificationStatus.NOT_FOUND_ON_SCREEN,
                        reasonAr = "$MSG_ELEMENT_NOT_FOUND (تعذر قراءة حقول الإدخال؛ فعّل خدمة إمكانية الوصول)."
                    )
                }
                if (snapshot.editableHints.isEmpty()) {
                    return PreExecutionCheck(
                        allowed = false,
                        verificationStatus = StepVerificationStatus.NOT_FOUND_ON_SCREEN,
                        reasonAr = "$MSG_ELEMENT_NOT_FOUND (لا يوجد أي حقل إدخال نصي فعلي على الشاشة الحالية)."
                    )
                }
                PreExecutionCheck(
                    allowed = true,
                    verificationStatus = StepVerificationStatus.VERIFIED_ON_SCREEN,
                    reasonAr = "تم التحقق من وجود حقل إدخال نصي فعلي على الشاشة."
                )
            }

            StepActionType.SEARCH_IN_APP -> {
                val realApp = matchRealInstalledApp(step.targetAppName, installedApps)
                val officialSearchIntent = if (realApp != null) {
                    Intent(Intent.ACTION_SEARCH).apply { setPackage(realApp.packageName) }
                        .resolveActivity(context.packageManager) != null
                } else false

                val hasRealSearchNode = snapshot != null && (
                    snapshot.editableHints.isNotEmpty() ||
                        snapshot.clickableLabels.any {
                            it.contains("بحث", ignoreCase = true) || it.contains("search", ignoreCase = true)
                        }
                    )

                if (!officialSearchIntent && !hasRealSearchNode) {
                    PreExecutionCheck(
                        allowed = false,
                        verificationStatus = StepVerificationStatus.NOT_FOUND_ON_SCREEN,
                        reasonAr = "$MSG_ELEMENT_NOT_FOUND (لا يوجد عنصر بحث على الشاشة ولا يدعم التطبيق البحث الرسمي)."
                    )
                } else {
                    PreExecutionCheck(
                        allowed = true,
                        verificationStatus = StepVerificationStatus.VERIFIED_ON_SCREEN,
                        reasonAr = "تم التحقق من توفر واجهة البحث الفعلية.",
                        matchedApp = realApp
                    )
                }
            }

            StepActionType.SCROLL_SCREEN,
            StepActionType.READ_SCREEN_AND_SUMMARIZE,
            StepActionType.WAIT_AND_VERIFY -> {
                if (snapshot == null) {
                    PreExecutionCheck(
                        allowed = false,
                        verificationStatus = StepVerificationStatus.NOT_FOUND_ON_SCREEN,
                        reasonAr = "$MSG_ELEMENT_NOT_FOUND (خدمة إمكانية الوصول مطلوبة لقراءة الشاشة)."
                    )
                } else {
                    PreExecutionCheck(
                        allowed = true,
                        verificationStatus = StepVerificationStatus.VERIFIED_ON_SCREEN,
                        reasonAr = "تمت قراءة محتوى الشاشة الفعلي (${snapshot.visibleTexts.size} نص)."
                    )
                }
            }

            // واجهات النظام الرسمية (منبه، تذكير، رسالة عبر Intent، إعدادات النظام، إجابة صوتية)
            StepActionType.SET_ALARM,
            StepActionType.SET_REMINDER,
            StepActionType.SEND_MESSAGE,
            StepActionType.CHANGE_SETTING,
            StepActionType.SPEAK_ANSWER -> {
                val matchedApp = matchRealInstalledApp(step.targetAppName, installedApps)
                PreExecutionCheck(
                    allowed = true,
                    verificationStatus = StepVerificationStatus.VERIFIED_ON_SCREEN,
                    reasonAr = "جاهز للتنفيذ عبر واجهة أندرويد الرسمية.",
                    matchedApp = matchedApp
                )
            }
        }
    }

    /**
     * التحقق الإجباري بعد تنفيذ الخطوة بمقارنة النتيجة على الشاشة الفعلية.
     */
    suspend fun verifyAfterStep(
        step: AgentPlanStep,
        executed: Boolean,
        executionMessageAr: String,
        preSnapshot: ScreenSnapshot?,
        postSnapshot: ScreenSnapshot?
    ): ScreenVerificationResult {
        if (!executed) {
            return ScreenVerificationResult(
                verified = false,
                status = StepVerificationStatus.VERIFICATION_FAILED,
                explanationAr = executionMessageAr
            )
        }

        if (postSnapshot?.hasSensitiveSecurityField == true) {
            return ScreenVerificationResult(
                verified = false,
                status = StepVerificationStatus.VERIFICATION_FAILED,
                explanationAr = "رُصد حقل كلمة مرور أو بصمة أو دفع بعد التنفيذ؛ تم إيقاف المتابعة حفاظاً على أمانك.",
                sensitiveUiDetected = true
            )
        }

        // تحقق مباشر وملموس من الحزمة عند فتح تطبيق
        if (step.actionType == StepActionType.OPEN_APP && postSnapshot != null && !step.verifiedAppPackage.isNullOrBlank()) {
            if (postSnapshot.packageName.isNotBlank() &&
                postSnapshot.packageName != context.packageName &&
                postSnapshot.packageName != step.verifiedAppPackage
            ) {
                return ScreenVerificationResult(
                    verified = false,
                    status = StepVerificationStatus.VERIFICATION_FAILED,
                    explanationAr = "التطبيق المفتوح حالياً (${postSnapshot.packageName}) لا يطابق التطبيق المطلوب (${step.verifiedAppPackage})."
                )
            }
        }

        // تحقق مباشر من ظهور النص المُدخل على الشاشة الفعلية
        if (step.actionType == StepActionType.INPUT_TEXT && postSnapshot != null) {
            val typed = step.targetQueryOrText.trim()
            if (typed.isNotBlank() && postSnapshot.visibleTexts.none { it.contains(typed, ignoreCase = true) }) {
                return ScreenVerificationResult(
                    verified = false,
                    status = StepVerificationStatus.VERIFICATION_FAILED,
                    explanationAr = "لم يظهر النص المُدخل «$typed» على الشاشة الفعلية بعد التنفيذ."
                )
            }
        }

        // إذا كانت خدمة الوصول غير متصلة لخطوات Intent الرسمية نعتمد نتيجة النظام مع الصدق التام
        if (postSnapshot == null) {
            val status = if (step.actionType in listOf(
                    StepActionType.SET_ALARM,
                    StepActionType.SET_REMINDER,
                    StepActionType.SEND_MESSAGE,
                    StepActionType.CHANGE_SETTING,
                    StepActionType.OPEN_APP,
                    StepActionType.SPEAK_ANSWER
                )
            ) {
                StepVerificationStatus.VERIFIED_ON_SCREEN
            } else {
                StepVerificationStatus.VERIFICATION_FAILED
            }
            return ScreenVerificationResult(
                verified = status == StepVerificationStatus.VERIFIED_ON_SCREEN,
                status = status,
                explanationAr = if (status == StepVerificationStatus.VERIFIED_ON_SCREEN) {
                    "تم استدعاء واجهة النظام الرسمية بنجاح."
                } else {
                    "تعذر التحقق من الشاشة لأن خدمة إمكانية الوصول غير مفعلة."
                }
            )
        }

        val geminiVerdict = geminiRepository.verifyScreenAfterStep(step, postSnapshot)
        val finalStatus = when {
            geminiVerdict.sensitiveUiDetected -> StepVerificationStatus.VERIFICATION_FAILED
            geminiVerdict.verified -> StepVerificationStatus.VERIFIED_ON_SCREEN
            else -> StepVerificationStatus.VERIFICATION_FAILED
        }
        return geminiVerdict.copy(status = finalStatus)
    }

    fun matchRealInstalledApp(
        appNameOrPackage: String,
        installedApps: List<InstalledAppInfo>
    ): InstalledAppInfo? {
        val query = appNameOrPackage.trim().lowercase()
        if (query.isEmpty()) return null

        installedApps.firstOrNull {
            it.label.lowercase() == query || it.packageName.lowercase() == query
        }?.let { return it }

        installedApps.firstOrNull {
            it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query)
        }?.let { return it }

        val aliasMap = mapOf(
            "واتساب" to listOf("whatsapp"),
            "واتس" to listOf("whatsapp"),
            "يوتيوب" to listOf("youtube"),
            "جوجل" to listOf("google", "chrome"),
            "كروم" to listOf("chrome"),
            "الخرائط" to listOf("maps"),
            "خرائط" to listOf("maps"),
            "الإعدادات" to listOf("settings"),
            "الاعدادات" to listOf("settings"),
            "الرسائل" to listOf("messaging", "mms", "messages"),
            "الهاتف" to listOf("dialer", "phone"),
            "الساعة" to listOf("deskclock", "clock"),
            "الكاميرا" to listOf("camera"),
            "تليجرام" to listOf("telegram"),
            "تيليجرام" to listOf("telegram"),
            "انستقرام" to listOf("instagram"),
            "تويتر" to listOf("twitter", "x.android"),
            "متجر" to listOf("vending", "play")
        )
        for ((arKey, pkgHints) in aliasMap) {
            if (query.contains(arKey)) {
                val match = installedApps.firstOrNull { app ->
                    pkgHints.any { hint ->
                        app.packageName.lowercase().contains(hint) || app.label.lowercase().contains(hint)
                    }
                }
                if (match != null) return match
            }
        }
        return null
    }

    private fun extractDigitSequences(text: String): List<String> {
        return PHONE_NUMBER_REGEX.findAll(text)
            .map { it.value.replace(Regex("[\\s\\-]"), "") }
            .toList()
    }
}
