package com.example.policy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.GeminiModelClient
import com.example.data.ai.GeminiRepository
import com.example.data.local.ActionLogDao
import com.example.data.local.ActionLogEntity
import com.example.data.local.ActionLogRepository
import com.example.executor.CommandExecutor
import com.example.executor.GameActionDecision
import com.example.executor.GameGestureAction
import com.example.game.GeneralGameEngine
import com.example.model.AgentPlan
import com.example.model.AgentPlanStep
import com.example.model.ScreenSnapshot
import com.example.model.StepActionType
import com.example.security.AppCheckTokenClient
import com.example.service.GameElementBound
import com.example.service.GameScreenObservation
import com.example.service.PhoneAgentAccessibilityService
import com.example.verifier.ScreenVerifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SensitiveActionGateTest {

    private fun createDeps(): Triple<Context, GeminiRepository, Pair<ActionLogRepository, MutableList<ActionLogEntity>>> {
        val context: Context = ApplicationProvider.getApplicationContext()
        val fakeAppCheck = object : AppCheckTokenClient {
            override suspend fun getToken(forceRefresh: Boolean) = "test-token"
            override suspend fun clearStoredTokens() {}
        }
        val fakeClient = object : GeminiModelClient {
            override suspend fun generatePlanJson(prompt: String) = "{}"
            override suspend fun generateAudioPlanJson(audioBytes: ByteArray, mimeType: String, prompt: String) = "{}"
            override suspend fun generateVerificationJson(prompt: String) = "{}"
            override suspend fun generateCategorizationJson(prompt: String) = "{}"
            override suspend fun generateGeneralArabicText(prompt: String) = "{}"
        }
        val recordedLogs = mutableListOf<ActionLogEntity>()
        val fakeDao = object : ActionLogDao {
            override fun observeAllLogs(): Flow<List<ActionLogEntity>> = flowOf(recordedLogs.toList())
            override fun observePagedLogs(limit: Int): Flow<List<ActionLogEntity>> =
                flowOf(recordedLogs.take(limit))
            override fun observeLogsCount(): Flow<Int> = flowOf(recordedLogs.size)
            override suspend fun insertLog(log: ActionLogEntity): Long {
                recordedLogs.add(log)
                return recordedLogs.size.toLong()
            }
            override suspend fun updateStatus(id: Long, status: String, canUndo: Boolean) {}
            override suspend fun markUndone(id: Long, status: String) {}
            override suspend fun clearAllLogs() { recordedLogs.clear() }
        }
        return Triple(
            context,
            GeminiRepository(fakeAppCheck, fakeClient),
            ActionLogRepository(fakeDao) to recordedLogs
        )
    }

    @Test
    fun `approving send message step does NOT auto-approve a second sensitive step and shows nextStep`() = runBlocking {
        val step1 = AgentPlanStep(
            stepNumber = 1,
            titleAr = "أرسل رسالة",
            descriptionAr = "إرسال رسالة نصية لوالدي",
            actionType = StepActionType.SEND_MESSAGE,
            targetAppName = "واتساب",
            targetQueryOrText = "السلام عليكم",
            requiresApproval = true
        )
        val step2 = AgentPlanStep(
            stepNumber = 2,
            titleAr = "تأكيد تغيير الإعداد",
            descriptionAr = "تعديل إعداد الصوت",
            actionType = StepActionType.CHANGE_SETTING,
            targetQueryOrText = "الصوت",
            requiresApproval = true
        )
        val plan = AgentPlan(
            originalCommand = "أرسل رسالة ثم غيّر الصوت",
            understandingSummaryAr = "تنفيذ خطوتين حساستين",
            spokenReplyAr = "جهزت الخطتين",
            steps = listOf(step1, step2)
        )

        val promptedSteps = mutableListOf<SensitiveActionRequest>()
        val gate = SensitiveActionGate(
            initialApprovalHandler = { request ->
                promptedSteps.add(request)
                // المستخدم وافق على الخطوة 1 فقط ورفض الخطوة 2
                request.step?.stepNumber == 1
            }
        )

        val auth1 = gate.authorizeStep(step = step1, plan = plan, nextStep = step2)
        assertTrue(auth1.approved)
        assertNotNull(auth1.approvalToken)
        assertEquals(step2, promptedSteps.first().nextStep)

        // تمرير توكن الخطوة 1 للخطوة 2 لا يمررها أبداً بل يطلب موافقة مستقلة
        val auth2 = gate.authorizeStep(
            step = step2,
            plan = plan,
            nextStep = null,
            existingToken = auth1.approvalToken
        )
        assertFalse("Second sensitive step must prompt independently and fail when rejected", auth2.approved)
        assertEquals(2, promptedSteps.size)
        assertEquals(2, promptedSteps[1].step?.stepNumber)

        // وإعادة استخدام نفس توكن الخطوة 1 حتى مع الخطوة 1 نفسها بعد استهلاكه يطلب موافقة جديدة
        val consumedStep1 = auth1.approvalToken!!.consumeIfValidFor(step1)
        assertTrue(consumedStep1)
        assertFalse(auth1.approvalToken!!.consumeIfValidFor(step1))
    }

    @Test
    fun `CommandExecutor enforces unified gate fail-closed when approval handler rejects or is absent`() = runBlocking {
        val (context, geminiRepo, logPair) = createDeps()
        val (logRepo, recordedLogs) = logPair
        val gate = SensitiveActionGate() // بدون معالج موافقة -> Fail-Closed
        val executor = CommandExecutor(
            context = context,
            actionLogRepository = logRepo,
            geminiRepository = geminiRepo,
            screenVerifier = ScreenVerifier(context, geminiRepo),
            sensitiveActionGate = gate
        )
        val sendStep = AgentPlanStep(
            stepNumber = 1,
            titleAr = "أرسل رسالة",
            descriptionAr = "إرسال رسالة لوالدي",
            actionType = StepActionType.SEND_MESSAGE,
            targetQueryOrText = "مرحبا",
            requiresApproval = true
        )

        val outcome = executor.executeStep(sendStep, emptyList())
        assertFalse(outcome.success)
        assertTrue(outcome.verification.sensitiveUiDetected)
        assertEquals("USER_REJECTED_SENSITIVE_SEND_MESSAGE", recordedLogs.last().actionType)
    }

    @Test
    fun `MiniChat directive click send requests approval and never executes directly`() = runBlocking {
        val (context, geminiRepo, logPair) = createDeps()
        val (logRepo, recordedLogs) = logPair
        val capturedRequests = mutableListOf<SensitiveActionRequest>()
        val gate = SensitiveActionGate(
            initialApprovalHandler = { req ->
                capturedRequests.add(req)
                false
            }
        )
        val engine = GeneralGameEngine(
            context = context,
            geminiRepository = geminiRepo,
            actionLogRepository = logRepo,
            sensitiveActionGate = gate
        )
        val directive = GeminiRepository.MiniChatDirectiveResult(
            shouldAct = true,
            action = "TAP_TEXT",
            targetText = "إرسال",
            inputText = "",
            x = 0.5f,
            y = 0.5f,
            endX = 0.5f,
            endY = 0.2f,
            continuePlaying = false,
            replyOneLineAr = "سأنقر إرسال"
        )

        val result = engine.executeMiniChatDirectiveWithGate(
            service = PhoneAgentAccessibilityService(),
            directive = directive,
            observation = null
        )

        assertFalse(result.executed)
        assertTrue(result.blockedByGate)
        assertEquals(1, capturedRequests.size)
        assertEquals(SensitiveActionSource.MINI_CHAT, capturedRequests.first().source)
        assertEquals("MINI_CHAT_USER_REJECTED", recordedLogs.last().actionType)
    }

    @Test
    fun `Gameplay loop TAP_XY targeting observed confirm button requires approval`() = runBlocking {
        val (context, geminiRepo, logPair) = createDeps()
        val (logRepo, recordedLogs) = logPair
        val capturedRequests = mutableListOf<SensitiveActionRequest>()
        val gate = SensitiveActionGate(
            initialApprovalHandler = { req ->
                capturedRequests.add(req)
                false
            }
        )
        val engine = GeneralGameEngine(
            context = context,
            geminiRepository = geminiRepo,
            actionLogRepository = logRepo,
            sensitiveActionGate = gate
        )
        val obs = GameScreenObservation(
            snapshot = ScreenSnapshot(
                packageName = "com.example.someapp",
                visibleTexts = listOf("تأكيد"),
                clickableLabels = listOf("تأكيد"),
                editableHints = emptyList(),
                hasSensitiveSecurityField = false
            ),
            elements = listOf(
                GameElementBound(
                    label = "تأكيد",
                    centerXRatio = 0.5f,
                    centerYRatio = 0.8f,
                    isClickable = true
                )
            )
        )
        val decision = GameActionDecision(
            action = GameGestureAction.TAP_XY,
            targetText = "",
            x = 0.5f,
            y = 0.8f,
            reasonAr = "النقر على الزر السفلي"
        )

        val result = engine.executeGameDecisionWithGate(
            service = PhoneAgentAccessibilityService(),
            decision = decision,
            observation = obs
        )

        assertFalse(result.executed)
        assertTrue(result.blockedByGate)
        assertEquals(1, capturedRequests.size)
        assertEquals(SensitiveActionSource.GAMEPLAY_LOOP, capturedRequests.first().source)
        assertEquals("تأكيد", capturedRequests.first().targetText)
        assertEquals("GAME_USER_REJECTED", recordedLogs.last().actionType)
    }

    @Test
    fun `sensitive tap list detects send delete confirm and pay`() {
        val gate = SensitiveActionGate()
        assertTrue(gate.isSensitiveTapTarget("إرسال"))
        assertTrue(gate.isSensitiveTapTarget("حذف"))
        assertTrue(gate.isSensitiveTapTarget("تأكيد"))
        assertTrue(gate.isSensitiveTapTarget("دفع"))
        assertTrue(gate.isSensitiveTapTarget("Send"))
        assertTrue(gate.isSensitiveTapTarget("Delete"))
        assertTrue(gate.isSensitiveTapTarget("Confirm"))
        assertTrue(gate.isSensitiveTapTarget("Pay"))
        assertFalse(gate.isSensitiveTapTarget("ابدأ اللعب"))
        assertFalse(gate.isSensitiveTapTarget("إعدادات"))
    }

    @Test
    fun `approval timeout defaults to 120 seconds and auto-cancels when no response arrives`() = runBlocking {
        assertEquals(120_000L, SensitiveActionGate.DEFAULT_APPROVAL_TIMEOUT_MS)

        val shortTimeoutGate = SensitiveActionGate(
            initialApprovalHandler = {
                delay(2_000L)
                true
            },
            approvalTimeoutMs = 50L
        )
        val sensitiveStep = AgentPlanStep(
            stepNumber = 1,
            titleAr = "أرسل رسالة",
            descriptionAr = "إرسال رسالة",
            actionType = StepActionType.SEND_MESSAGE,
            targetQueryOrText = "مرحبا",
            requiresApproval = true
        )

        val result = shortTimeoutGate.authorizeStep(sensitiveStep)
        assertFalse(result.approved)
        assertTrue(result.timedOut)
        assertNull(result.approvalToken)
    }
}
