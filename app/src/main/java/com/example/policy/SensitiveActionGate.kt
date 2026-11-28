package com.example.policy

import com.example.model.AgentPlan
import com.example.model.AgentPlanStep
import com.example.model.StepActionType
import kotlinx.coroutines.withTimeoutOrNull

enum class SensitiveActionSource(val titleAr: String) {
    PLAN_STEP("خطة التنفيذ"),
    MINI_CHAT("شريط الدردشة المصغّر"),
    GAMEPLAY_LOOP("محرك اللعب والتفاعل")
}

data class SensitiveActionRequest(
    val source: SensitiveActionSource,
    val actionType: StepActionType?,
    val titleAr: String,
    val descriptionAr: String,
    val targetText: String = "",
    val targetAppName: String = "",
    val safetyReasonAr: String = "",
    val step: AgentPlanStep? = null,
    val plan: AgentPlan? = null,
    val nextStep: AgentPlanStep? = null
)

/**
 * تصريح موافقة أحادي الاستخدام مرتبط بخطوة واحدة محددة فقط،
 * لا يمكن إعادة استخدامه لخطوة لاحقة في الخطة بأي حال.
 */
class GateApprovalToken internal constructor(
    val stepNumber: Int,
    val actionType: StepActionType,
    val targetQueryOrText: String
) {
    @Volatile
    private var consumed: Boolean = false

    internal fun consumeIfValidFor(step: AgentPlanStep): Boolean {
        synchronized(this) {
            if (consumed) return false
            if (step.stepNumber != stepNumber ||
                step.actionType != actionType ||
                step.targetQueryOrText != targetQueryOrText
            ) {
                return false
            }
            consumed = true
            return true
        }
    }

    val isConsumed: Boolean
        get() = consumed
}

data class GateAuthorizationResult(
    val approved: Boolean,
    val requiresApproval: Boolean,
    val blockedByPolicy: Boolean = false,
    val timedOut: Boolean = false,
    val violationCategory: ForbiddenCategory? = null,
    val reasonAr: String = "",
    val approvalToken: GateApprovalToken? = null
)

fun interface SensitiveApprovalHandler {
    suspend fun requestApproval(request: SensitiveActionRequest): Boolean
}

/**
 * بوابة الموافقة الموحّدة في طبقة التنفيذ:
 * تمر عبرها جميع مسارات التنفيذ (خطوات الخطة، شريط الدردشة المصغّر، وحلقة اللعب)
 * وتفرض فحص السياسة على مستوى الأفعال، وقائمة العناصر الحساسة، ومهلة 120 ثانية،
 * والإغلاق الآمن (Fail-Closed) عند غياب معالج الموافقة أو انتهاء المهلة.
 */
class SensitiveActionGate(
    private val agentPolicy: AgentPolicy = AgentPolicy(),
    initialApprovalHandler: SensitiveApprovalHandler? = null,
    val approvalTimeoutMs: Long = DEFAULT_APPROVAL_TIMEOUT_MS
) {
    companion object {
        const val DEFAULT_APPROVAL_TIMEOUT_MS: Long = 120_000L

        private val SENSITIVE_TAP_PATTERNS: List<Regex> = listOf(
            // العربية: إرسال، حذف، تأكيد، دفع، شراء، تحويل، إزالة، تثبيت، موافق، سماح، نشر
            Regex("(?:^|\\s)(?:ال)?(?:ارسال|ارسل)(?:\\s|$)"),
            Regex("(?:^|\\s)(?:ال)?(?:حذف|احذف)(?:\\s|$)"),
            Regex("(?:^|\\s)(?:ال)?(?:تاكيد|اكد)(?:\\s|$)"),
            Regex("(?:^|\\s)(?:ال)?(?:دفع|ادفع|سدد|تسديد)(?:\\s|$)"),
            Regex("(?:^|\\s)(?:ال)?(?:شراء|اشتري|اشتر)(?:\\s|$)"),
            Regex("(?:^|\\s)(?:ال)?(?:تحويل|حول)(?:\\s|$)"),
            Regex("(?:^|\\s)(?:ال)?(?:ازالة|ازل|الغاء\\s*تثبيت)(?:\\s|$)"),
            Regex("(?:^|\\s)(?:سماح|موافق|تسليم|نشر|مشاركة)(?:\\s|$)"),
            // الإنجليزية
            Regex("\\b(?:send|delete|remove|confirm|pay|purchase|buy|transfer|uninstall|submit|allow|grant|post|publish|erase)\\b", RegexOption.IGNORE_CASE)
        )
    }

    @Volatile
    private var approvalHandler: SensitiveApprovalHandler? = initialApprovalHandler

    fun setApprovalHandler(handler: SensitiveApprovalHandler?) {
        approvalHandler = handler
    }

    fun isSensitiveTapTarget(vararg texts: String): Boolean {
        val combined = texts
            .map { agentPolicy.normalizeArabic(it) }
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (combined.isBlank()) return false
        return SENSITIVE_TAP_PATTERNS.any { it.containsMatchIn(combined) }
    }

    fun requiresApprovalForStep(step: AgentPlanStep): Boolean {
        if (step.requiresApproval) return true
        if (step.actionType == StepActionType.SEND_MESSAGE ||
            step.actionType == StepActionType.CHANGE_SETTING
        ) {
            return true
        }
        return isSensitiveTapTarget(
            step.targetQueryOrText,
            step.titleAr,
            step.descriptionAr
        )
    }

    fun requiresApprovalForGesture(
        action: String,
        targetText: String,
        inputText: String = "",
        reasonAr: String = "",
        observedTargetLabel: String = ""
    ): Boolean {
        if (action.equals("SEND_MESSAGE", ignoreCase = true)) return true
        return isSensitiveTapTarget(targetText, observedTargetLabel, inputText, reasonAr)
    }

    suspend fun authorizeStep(
        step: AgentPlanStep,
        plan: AgentPlan? = null,
        nextStep: AgentPlanStep? = null,
        existingToken: GateApprovalToken? = null
    ): GateAuthorizationResult {
        val policyDecision = agentPolicy.validateStep(step)
        if (!policyDecision.allowed) {
            return GateAuthorizationResult(
                approved = false,
                requiresApproval = false,
                blockedByPolicy = true,
                violationCategory = policyDecision.violationCategory,
                reasonAr = policyDecision.reasonAr
            )
        }

        val needsApproval = requiresApprovalForStep(step)
        if (!needsApproval) {
            return GateAuthorizationResult(
                approved = true,
                requiresApproval = false
            )
        }

        if (existingToken != null && existingToken.consumeIfValidFor(step)) {
            return GateAuthorizationResult(
                approved = true,
                requiresApproval = true,
                approvalToken = existingToken
            )
        }

        val resolvedNextStep = nextStep ?: plan?.steps?.firstOrNull { it.stepNumber > step.stepNumber }
        val request = SensitiveActionRequest(
            source = SensitiveActionSource.PLAN_STEP,
            actionType = step.actionType,
            titleAr = step.titleAr,
            descriptionAr = step.descriptionAr,
            targetText = step.targetQueryOrText,
            targetAppName = step.targetAppName,
            safetyReasonAr = step.safetyReasonAr.ifBlank {
                "إجراء حساس يتطلب موافقة مستقلة قبل التنفيذ."
            },
            step = step,
            plan = plan,
            nextStep = resolvedNextStep
        )
        return requestApprovalWithTimeout(request) {
            GateApprovalToken(
                stepNumber = step.stepNumber,
                actionType = step.actionType,
                targetQueryOrText = step.targetQueryOrText
            )
        }
    }

    suspend fun authorizeGestureAction(
        source: SensitiveActionSource,
        action: String,
        targetText: String,
        inputText: String = "",
        reasonAr: String = "",
        observedTargetLabel: String = "",
        targetAppName: String = ""
    ): GateAuthorizationResult {
        val mappedType = when (action.uppercase()) {
            "TAP_TEXT", "TAP_XY" -> StepActionType.TAP_ELEMENT
            "INPUT_TEXT" -> StepActionType.INPUT_TEXT
            "SCROLL", "SWIPE" -> StepActionType.SCROLL_SCREEN
            else -> null
        }

        val combinedTarget = listOf(targetText, observedTargetLabel, inputText)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")

        val policyDecision = agentPolicy.validateAction(
            actionType = mappedType,
            targetText = combinedTarget,
            description = reasonAr,
            targetAppName = targetAppName,
            actionTitleAr = targetText.ifBlank { action }
        )
        if (!policyDecision.allowed) {
            return GateAuthorizationResult(
                approved = false,
                requiresApproval = false,
                blockedByPolicy = true,
                violationCategory = policyDecision.violationCategory,
                reasonAr = policyDecision.reasonAr
            )
        }

        val needsApproval = requiresApprovalForGesture(
            action = action,
            targetText = targetText,
            inputText = inputText,
            reasonAr = reasonAr,
            observedTargetLabel = observedTargetLabel
        )
        if (!needsApproval) {
            return GateAuthorizationResult(
                approved = true,
                requiresApproval = false
            )
        }

        val label = observedTargetLabel.ifBlank { targetText.ifBlank { inputText.ifBlank { action } } }
        val request = SensitiveActionRequest(
            source = source,
            actionType = mappedType,
            titleAr = "التفاعل مع عنصر حساس: «$label»",
            descriptionAr = reasonAr.ifBlank {
                "يستعد الوكيل لتنفيذ ($action) على «$label» عبر ${source.titleAr}."
            },
            targetText = label,
            targetAppName = targetAppName,
            safetyReasonAr = "النقر أو الإدخال على عناصر حساسة (إرسال/حذف/تأكيد/دفع) يتطلب موافقتك الصريحة."
        )
        return requestApprovalWithTimeout(request)
    }

    private suspend fun requestApprovalWithTimeout(
        request: SensitiveActionRequest,
        tokenFactory: (() -> GateApprovalToken)? = null
    ): GateAuthorizationResult {
        val handler = approvalHandler
            ?: return GateAuthorizationResult(
                approved = false,
                requiresApproval = true,
                reasonAr = "بوابة الموافقة غير متاحة حالياً — رُفض الإجراء الحساس احترازياً (Fail-Closed)."
            )

        val decision = withTimeoutOrNull(approvalTimeoutMs) {
            handler.requestApproval(request)
        }

        return when (decision) {
            true -> GateAuthorizationResult(
                approved = true,
                requiresApproval = true,
                approvalToken = tokenFactory?.invoke()
            )
            false -> GateAuthorizationResult(
                approved = false,
                requiresApproval = true,
                timedOut = false,
                reasonAr = "رفض المستخدم تنفيذ الإجراء الحساس «${request.titleAr}»."
            )
            null -> GateAuthorizationResult(
                approved = false,
                requiresApproval = true,
                timedOut = true,
                reasonAr = "انتهت مهلة الموافقة (${approvalTimeoutMs / 1000} ثانية) وأُلغي الإجراء احترازياً."
            )
        }
    }
}
