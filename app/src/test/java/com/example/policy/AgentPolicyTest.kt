package com.example.policy

import com.example.model.AgentPlan
import com.example.model.AgentPlanStep
import com.example.model.StepActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPolicyTest {

    private val policy = AgentPolicy()

    @Test
    fun `allows in-scope commands across all three permitted areas`() {
        val inScopeCommands = listOf(
            "رتّب تطبيقاتي في مجلدات",
            "رتّب تطبيقات التسوق في مجلد خاص",
            "افتح تطبيق الساعة واضبط منبهاً على الساعة السابعة",
            "أرسل رسالة لوالدي عبر واتساب",
            "افتح إعدادات الواي فاي",
            "جرّب تطبيق الخرائط ولخّص محتواه بالعربية"
        )

        for (command in inScopeCommands) {
            val decision = policy.evaluateCommand(command)
            assertTrue("Expected command to be allowed: $command -> ${decision.reasonAr}", decision.allowed)
        }
    }

    @Test
    fun `allows general off-phone questions discussions and advice after removing phone-only scope restriction`() {
        val generalQuestions = listOf(
            "ما هي عاصمة فرنسا؟",
            "اكتب لي قصيدة جميلة عن البحر",
            "ما هو ناتج 45 ضرب 12؟",
            "اشرح لي نظرية النسبية",
            "بماذا تنصحني لتنظيم وقتي في الدراسة؟"
        )

        for (question in generalQuestions) {
            val decision = policy.evaluateCommand(question)
            assertTrue("Expected general question/discussion to be allowed: $question -> ${decision.reasonAr}", decision.allowed)
        }
    }

    @Test
    fun `rejects all eight forbidden items individually`() {
        val forbiddenCases = listOf(
            "أدخل كلمة المرور تلقائياً" to ForbiddenCategory.PASSWORDS,
            "استخدم البصمة لتأكيد الدخول" to ForbiddenCategory.BIOMETRICS,
            "قم بالدفع الإلكتروني للفاتورة الآن" to ForbiddenCategory.PAYMENTS,
            "نفّذ عملية شراء لهذا المنتج فوراً" to ForbiddenCategory.PURCHASES,
            "أرسل أموالاً بقيمة 200 ريال لصديقي" to ForbiddenCategory.MONEY_TRANSFER,
            "قم بحذف البيانات والصور من التطبيق" to ForbiddenCategory.DATA_DELETION,
            "غيّر إعدادات الحسابات المرتبطة بجوجل" to ForbiddenCategory.ACCOUNT_SETTINGS,
            "قم بتجاوز قفل الشاشة للدخول مباشرة" to ForbiddenCategory.LOCK_SCREEN_BYPASS
        )

        for ((command, expectedCategory) in forbiddenCases) {
            val decision = policy.evaluateCommand(command)
            assertFalse("Expected forbidden command to be rejected: $command", decision.allowed)
            assertEquals(
                "Expected category ${expectedCategory.titleAr} for: $command",
                expectedCategory,
                decision.violationCategory
            )
        }
    }

    @Test
    fun `rejects Gemini plan when any step contains a forbidden action`() {
        val unsafePlan = AgentPlan(
            originalCommand = "افتح الإعدادات",
            understandingSummaryAr = "فتح الإعدادات وإتمام إجراء داخل الحساب",
            spokenReplyAr = "سأفتح الإعدادات أولاً",
            steps = listOf(
                AgentPlanStep(
                    stepNumber = 1,
                    titleAr = "فتح تطبيق الإعدادات",
                    descriptionAr = "فتح شاشة الإعدادات",
                    actionType = StepActionType.OPEN_APP,
                    targetAppName = "الإعدادات"
                ),
                AgentPlanStep(
                    stepNumber = 2,
                    titleAr = "إرسال أموال للمستخدم",
                    descriptionAr = "تحويل مبلغ 100 ريال عبر المحفظة",
                    actionType = StepActionType.TAP_ELEMENT,
                    targetQueryOrText = "إرسال أموال"
                )
            )
        )

        val planDecision = policy.validatePlan(unsafePlan)
        assertFalse("Plan with forbidden step must be rejected", planDecision.allowed)
        assertEquals(ForbiddenCategory.MONEY_TRANSFER, planDecision.violationCategory)
    }

    @Test
    fun `rejects CHANGE_SETTING step targeting account settings`() {
        val accountStep = AgentPlanStep(
            stepNumber = 1,
            titleAr = "تعديل خيار",
            descriptionAr = "الانتقال لتعديل الحساب الشخصي",
            actionType = StepActionType.CHANGE_SETTING,
            targetQueryOrText = "إعدادات الحساب"
        )

        val stepDecision = policy.validateStep(accountStep)
        assertFalse(stepDecision.allowed)
        assertEquals(ForbiddenCategory.ACCOUNT_SETTINGS, stepDecision.violationCategory)
    }

    @Test
    fun `allows valid safe plan without forbidden steps`() {
        val safePlan = AgentPlan(
            originalCommand = "افتح المنبه على الساعة السابعة",
            understandingSummaryAr = "ضبط منبه الساعة السابعة صباحاً",
            spokenReplyAr = "سأفتح تطبيق الساعة لضبط المنبه على السابعة.",
            steps = listOf(
                AgentPlanStep(
                    stepNumber = 1,
                    titleAr = "ضبط المنبه",
                    descriptionAr = "ضبط منبه على الساعة 07:00",
                    actionType = StepActionType.SET_ALARM,
                    hour = 7,
                    minute = 0
                )
            )
        )

        val decision = policy.validatePlan(safePlan)
        assertTrue("Safe plan should be allowed: ${decision.reasonAr}", decision.allowed)
    }

    @Test
    fun `allows narrow file cleanup requests while still blocking data and photo deletion`() {
        assertTrue(policy.evaluateCommand("افحص الهاتف واحذف المجلدات الفارغة").allowed)
        assertTrue(policy.evaluateCommand("تنظيف الملفات المؤقتة").allowed)
        assertFalse(policy.evaluateCommand("قم بحذف البيانات والصور من التطبيق").allowed)
        assertFalse(policy.evaluateCommand("حذف محادثات واتساب").allowed)
    }

    @Test
    fun `validateFileCleanupBatch requires explicit approval and rejects excluded Android paths`() {
        val unapproved = policy.validateFileCleanupBatch(
            paths = listOf("/storage/emulated/0/Download/EmptyDir"),
            approvedByUser = false
        )
        assertFalse(unapproved.allowed)

        val protectedPath = policy.validateFileCleanupBatch(
            paths = listOf("/storage/emulated/0/Android/data/com.foo"),
            approvedByUser = true
        )
        assertFalse(protectedPath.allowed)

        val valid = policy.validateFileCleanupBatch(
            paths = listOf("/storage/emulated/0/Download/EmptyDir"),
            approvedByUser = true
        )
        assertTrue(valid.allowed)
    }

    @Test
    fun `rejects single-word and synonym forbidden patterns including delete this, format, uninstall, and OTP`() {
        val deleteThis = policy.evaluateCommand("احذف هذا")
        assertFalse(deleteThis.allowed)
        assertEquals(ForbiddenCategory.DATA_DELETION, deleteThis.violationCategory)

        val formatDevice = policy.evaluateCommand("فرمت الهاتف")
        assertFalse(formatDevice.allowed)
        assertEquals(ForbiddenCategory.DATA_DELETION, formatDevice.violationCategory)

        val uninstallApp = policy.evaluateCommand("إلغاء تثبيت واتساب")
        assertFalse(uninstallApp.allowed)
        assertEquals(ForbiddenCategory.DATA_DELETION, uninstallApp.violationCategory)

        val otpRequest = policy.evaluateCommand("أدخل رمز التحقق OTP")
        assertFalse(otpRequest.allowed)
        assertEquals(ForbiddenCategory.PASSWORDS, otpRequest.violationCategory)

        // لا يُسمح بتمرير «احذف هذا» تحت غطاء تنظيف المجلدات الفارغة
        assertFalse(policy.evaluateCommand("افحص المجلدات الفارغة ثم احذف هذا").allowed)
    }

    @Test
    fun `validateAction inspects actionType, targetText, and description accurately`() {
        val inputOtp = policy.validateAction(
            actionType = StepActionType.INPUT_TEXT,
            targetText = "حقل الإدخال",
            description = "اكتب رمز التحقق OTP هنا"
        )
        assertFalse(inputOtp.allowed)
        assertEquals(ForbiddenCategory.PASSWORDS, inputOtp.violationCategory)

        val tapDeleteThis = policy.validateAction(
            actionType = StepActionType.TAP_ELEMENT,
            targetText = "احذف هذا",
            description = "انقر على العنصر"
        )
        assertFalse(tapDeleteThis.allowed)
        assertEquals(ForbiddenCategory.DATA_DELETION, tapDeleteThis.violationCategory)

        // النقر على «إرسال» مسموح في السياسة ليصل إلى بوابة الموافقة الصريحة
        val tapSend = policy.validateAction(
            actionType = StepActionType.TAP_ELEMENT,
            targetText = "إرسال",
            description = "النقر على زر الإرسال"
        )
        assertTrue("Sending UI tap should pass policy so SensitiveActionGate prompts user", tapSend.allowed)
    }
}
