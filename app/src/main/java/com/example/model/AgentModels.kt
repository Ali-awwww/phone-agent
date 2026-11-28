package com.example.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.WorkOutline
import androidx.compose.ui.graphics.vector.ImageVector

enum class AppFolder(
    val titleAr: String,
    val icon: ImageVector
) {
    COMMUNICATION("تواصل", Icons.Rounded.Chat),
    WORK("عمل", Icons.Rounded.WorkOutline),
    GAMES("ألعاب", Icons.Rounded.SportsEsports),
    SHOPPING("تسوق", Icons.Rounded.ShoppingBag),
    MEDIA("وسائط", Icons.Rounded.PlayCircleOutline),
    TOOLS("أدوات", Icons.Rounded.Build);

    companion object {
        fun fromKey(key: String?): AppFolder {
            return entries.firstOrNull {
                it.name.equals(key, ignoreCase = true) || it.titleAr == key
            } ?: TOOLS
        }
    }
}

enum class StepActionType {
    OPEN_APP,
    SEARCH_IN_APP,
    SET_ALARM,
    SET_REMINDER,
    SEND_MESSAGE,
    CHANGE_SETTING,
    TAP_ELEMENT,
    INPUT_TEXT,
    SCROLL_SCREEN,
    READ_SCREEN_AND_SUMMARIZE,
    WAIT_AND_VERIFY,
    SPEAK_ANSWER;

    companion object {
        fun fromString(value: String?): StepActionType {
            val normalized = value?.trim().orEmpty()
            require(normalized.isNotEmpty()) { "نوع الإجراء مفقود أو فارغ." }
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
                ?: throw IllegalArgumentException("نوع إجراء غير معروف: $normalized")
        }
    }
}

enum class StepStatus(val labelAr: String) {
    PENDING("قيد الانتظار"),
    RUNNING("جاري التنفيذ"),
    AWAITING_APPROVAL("بانتظار الموافقة"),
    VERIFYING("جاري التحقق من الشاشة"),
    COMPLETED("نجح"),
    UNVERIFIED("تعذّر التحقق"),
    CANCELLED("أُلغي"),
    FAILED("تعذر التنفيذ")
}

enum class StepVerificationStatus(val labelAr: String) {
    AWAITING_VERIFICATION("بانتظار التحقق"),
    VERIFIED_ON_SCREEN("تم التحقق من الشاشة"),
    NOT_FOUND_ON_SCREEN("غير موجود على الشاشة"),
    UNABLE_TO_VERIFY("تعذّر التحقق"),
    VERIFICATION_FAILED("فشل التحقق")
}

data class AgentPlanStep(
    val stepNumber: Int,
    val titleAr: String,
    val descriptionAr: String,
    val actionType: StepActionType,
    val targetAppName: String = "",
    val targetQueryOrText: String = "",
    val expectedScreenHint: String = "",
    val requiresApproval: Boolean = false,
    val safetyReasonAr: String = "",
    val hour: Int? = null,
    val minute: Int? = null,
    val status: StepStatus = StepStatus.PENDING,
    val verificationStatus: StepVerificationStatus = StepVerificationStatus.AWAITING_VERIFICATION,
    val verificationResultAr: String? = null,
    val verifiedAppPackage: String? = null
)

data class AgentPlan(
    val originalCommand: String,
    val understandingSummaryAr: String,
    val spokenReplyAr: String,
    val steps: List<AgentPlanStep>,
    val hallucinationWarnings: List<String> = emptyList(),
    val isComplete: Boolean = false,
    val isCancelled: Boolean = false
)

data class ScreenSnapshot(
    val packageName: String,
    val visibleTexts: List<String>,
    val clickableLabels: List<String>,
    val editableHints: List<String>,
    val hasSensitiveSecurityField: Boolean,
    val capturedAt: Long = System.currentTimeMillis()
) {
    fun toCompactPromptText(): String {
        val pkg = packageName.ifBlank { "غير معروف" }
        val scrubbedTexts = visibleTexts.take(35).map { com.example.security.SensitiveDataScrubber.scrub(it) }
        val scrubbedButtons = clickableLabels.take(20).map { com.example.security.SensitiveDataScrubber.scrub(it) }
        val scrubbedInputs = editableHints.take(10).map { com.example.security.SensitiveDataScrubber.scrub(it) }
        val texts = scrubbedTexts.joinToString(" | ")
        val buttons = scrubbedButtons.joinToString(" | ")
        val inputs = scrubbedInputs.joinToString(" | ")
        return buildString {
            appendLine("التطبيق الحالي: $pkg")
            appendLine("النصوص الظاهرة: ${texts.ifBlank { "لا يوجد" }}")
            appendLine("الأزرار القابلة للنقر: ${buttons.ifBlank { "لا يوجد" }}")
            appendLine("حقول الإدخال: ${inputs.ifBlank { "لا يوجد" }}")
            if (hasSensitiveSecurityField) {
                appendLine("تحذير أمان: توجد حقول كلمة مرور أو بصمة أو دفع على الشاشة.")
            }
        }
    }

    fun toMinimalPrivacyPromptText(): String {
        val pkg = packageName.ifBlank { "غير معروف" }
        val buttons = clickableLabels.take(6)
            .map { com.example.security.SensitiveDataScrubber.scrub(it) }
            .filter { it.isNotBlank() }
            .joinToString(" | ")
        return buildString {
            appendLine("التطبيق الحالي: $pkg")
            appendLine("عناصر تفاعلية مقتضبة: ${buttons.ifBlank { "لا يوجد" }}")
            if (hasSensitiveSecurityField) {
                appendLine("تحذير أمان: شاشة حساسة.")
            }
        }
    }
}

data class ScreenVerificationResult(
    val verified: Boolean,
    val status: StepVerificationStatus = if (verified) {
        StepVerificationStatus.VERIFIED_ON_SCREEN
    } else {
        StepVerificationStatus.VERIFICATION_FAILED
    },
    val explanationAr: String,
    val suggestedFixAr: String = "",
    val sensitiveUiDetected: Boolean = false
)
