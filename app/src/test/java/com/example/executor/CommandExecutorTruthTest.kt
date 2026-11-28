package com.example.executor

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.GeminiModelClient
import com.example.data.ai.GeminiRepository
import com.example.data.local.ActionLogRepository
import com.example.data.local.ActionStatus
import com.example.data.local.PhoneAgentDatabase
import com.example.model.AgentPlanStep
import com.example.model.StepActionType
import com.example.model.StepVerificationStatus
import com.example.policy.AgentPolicy
import com.example.policy.SensitiveActionGate
import com.example.security.AppCheckTokenClient
import com.example.verifier.ScreenVerifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommandExecutorTruthTest {

    private lateinit var context: Context
    private lateinit var db: PhoneAgentDatabase
    private lateinit var actionLogRepository: ActionLogRepository
    private lateinit var executor: CommandExecutor

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PhoneAgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        actionLogRepository = ActionLogRepository(db.actionLogDao())

        val fakeAppCheck = object : AppCheckTokenClient {
            override suspend fun getToken(forceRefresh: Boolean) = "test-token"
            override suspend fun clearStoredTokens() {}
        }
        val fakeClient = object : GeminiModelClient {
            override suspend fun generatePlanJson(prompt: String) = "{}"
            override suspend fun generateAudioPlanJson(audioBytes: ByteArray, mimeType: String, prompt: String) = "{}"
            override suspend fun generateVerificationJson(prompt: String) = """{"verified":true}"""
            override suspend fun generateCategorizationJson(prompt: String) = "{}"
            override suspend fun generateGeneralArabicText(prompt: String) = "{}"
        }
        val geminiRepo = GeminiRepository(appCheckClient = fakeAppCheck, modelClient = fakeClient)
        val verifier = ScreenVerifier(context, geminiRepo)
        val policy = AgentPolicy()
        val gate = SensitiveActionGate(policy)
        executor = CommandExecutor(
            context = context,
            actionLogRepository = actionLogRepository,
            geminiRepository = geminiRepo,
            screenVerifier = verifier,
            agentPolicy = policy,
            sensitiveActionGate = gate
        )
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `F30 cancelScheduledReminder returns false when pendingIntent does not exist and true only when actually cancelled`() {
        assertFalse(executor.cancelScheduledReminder(998877))

        val reminderId = 445566
        val intent = Intent(context, ReminderReceiver::class.java)
        PendingIntent.getBroadcast(
            context,
            reminderId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        assertTrue(executor.cancelScheduledReminder(reminderId))
        assertFalse(executor.cancelScheduledReminder(reminderId))
    }

    @Test
    fun `F12 and F03 SET_ALARM reports honest UI-open message, never says تم ضبط المنبه, and logs UNVERIFIED without snapshot`() = runBlocking {
        val step = AgentPlanStep(
            stepNumber = 1,
            titleAr = "ضبط منبه صباحي",
            descriptionAr = "ضبط منبه 7:30",
            actionType = StepActionType.SET_ALARM,
            hour = 7,
            minute = 30,
            targetQueryOrText = "استيقاظ"
        )

        val outcome = executor.executeStep(step = step, installedApps = emptyList())

        assertFalse(outcome.success)
        assertTrue(outcome.unableToVerify)
        assertEquals(StepVerificationStatus.UNABLE_TO_VERIFY, outcome.verification.status)
        assertFalse(outcome.messageAr.contains("تم ضبط المنبه"))
        assertTrue(outcome.messageAr.contains("فُتحت واجهة المنبه"))
        assertTrue(outcome.messageAr.contains("أكمل الإعداد والتأكيد"))

        val logs = actionLogRepository.allLogs.first()
        assertEquals(1, logs.size)
        assertEquals(ActionStatus.UNVERIFIED.name, logs.first().status)
    }

    @Test
    fun `F12 SEND_MESSAGE and CHANGE_SETTING report honest UI-open messages and UNABLE_TO_VERIFY without snapshot`() = runBlocking {
        val msgStep = AgentPlanStep(
            stepNumber = 1,
            titleAr = "إرسال رسالة",
            descriptionAr = "فتح واجهة الرسالة",
            actionType = StepActionType.SEND_MESSAGE,
            targetQueryOrText = "مرحباً بك",
            requiresApproval = true
        )
        val policy = AgentPolicy()
        val gate = SensitiveActionGate(policy)
        gate.setApprovalHandler { true }
        val localRepo = GeminiRepository(
            appCheckClient = object : AppCheckTokenClient {
                override suspend fun getToken(forceRefresh: Boolean) = "token"
                override suspend fun clearStoredTokens() {}
            },
            modelClient = object : GeminiModelClient {
                override suspend fun generatePlanJson(prompt: String) = "{}"
                override suspend fun generateAudioPlanJson(audioBytes: ByteArray, mimeType: String, prompt: String) = "{}"
                override suspend fun generateVerificationJson(prompt: String) = "{}"
                override suspend fun generateCategorizationJson(prompt: String) = "{}"
                override suspend fun generateGeneralArabicText(prompt: String) = "{}"
            }
        )
        val localExecutor = CommandExecutor(
            context = context,
            actionLogRepository = actionLogRepository,
            geminiRepository = localRepo,
            screenVerifier = ScreenVerifier(context, localRepo),
            agentPolicy = policy,
            sensitiveActionGate = gate
        )
        val approvedToken = gate.authorizeStep(msgStep).approvalToken

        val msgOutcome = localExecutor.executeStep(msgStep, emptyList(), approvedToken)
        assertFalse(msgOutcome.success)
        assertTrue(msgOutcome.unableToVerify)
        assertTrue(msgOutcome.messageAr.contains("فُتحت واجهة الرسالة"))
        assertTrue(msgOutcome.messageAr.contains("راجع وأرسل يدوياً"))

        val settingStep = AgentPlanStep(
            stepNumber = 2,
            titleAr = "فتح الواي فاي",
            descriptionAr = "إعدادات الواي فاي",
            actionType = StepActionType.CHANGE_SETTING,
            targetQueryOrText = "wifi"
        )
        val settingOutcome = localExecutor.executeStep(settingStep, emptyList())
        assertFalse(settingOutcome.success)
        assertTrue(settingOutcome.unableToVerify)
        assertTrue(settingOutcome.messageAr.contains("فُتحت واجهة الإعدادات"))
    }

    @Test
    fun `SET_REMINDER without snapshot does not silently roll back scheduled alarm and reports UNVERIFIED`() = runBlocking {
        val step = AgentPlanStep(
            stepNumber = 1,
            titleAr = "تذكير شرب الماء",
            descriptionAr = "تذكير بعد 10 دقائق",
            actionType = StepActionType.SET_REMINDER,
            hour = 10,
            minute = 30,
            targetQueryOrText = "شرب الماء"
        )

        val outcome = executor.executeStep(step, emptyList())

        assertFalse(outcome.success)
        assertTrue(outcome.unableToVerify)
        assertFalse(outcome.rolledBack)
        assertEquals(StepVerificationStatus.UNABLE_TO_VERIFY, outcome.verification.status)
        val logs = actionLogRepository.allLogs.first()
        assertEquals(ActionStatus.UNVERIFIED.name, logs.first().status)
    }
}
