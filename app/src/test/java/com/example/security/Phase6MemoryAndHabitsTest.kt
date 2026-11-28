package com.example.security

import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.chat.ChatTurn
import com.example.data.ai.GeminiModelClient
import com.example.data.ai.GeminiRepository
import com.example.data.local.ActionLogEntity
import com.example.data.local.ActionLogRepository
import com.example.data.local.ActionStatus
import com.example.data.local.AgentTaskEntity
import com.example.data.local.AgentTaskType
import com.example.data.local.ChatHistoryRepository
import com.example.data.local.HabitLogEntity
import com.example.data.local.PhoneAgentDatabase
import com.example.data.local.UserMemoryEntity
import com.example.data.local.UserMemoryRepository
import com.example.executor.CommandExecutor
import com.example.game.GeneralGameEngine
import com.example.habits.HabitLearningEngine
import com.example.habits.PredictiveSuggestion
import com.example.organizer.AppOrganizer
import com.example.overlay.AgentExecutionController
import com.example.overlay.AgentFloatingControlManager
import com.example.policy.AgentPolicy
import com.example.policy.ForbiddenCategory
import com.example.policy.SensitiveActionGate
import com.example.ui.components.MemoryAndHabitsManagementSection
import com.example.ui.components.PredictiveSuggestionCard
import com.example.ui.theme.PhoneAgentTheme
import com.example.ui.viewmodel.CommandsViewModel
import com.example.ui.viewmodel.PermissionsViewModel
import com.example.verifier.ScreenVerifier
import com.example.voice.VoiceAssistantManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase6MemoryAndHabitsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: PhoneAgentDatabase
    private lateinit var privacyPrefs: PrivacyPreferences
    private lateinit var memoryPrefs: MemoryAndHabitPreferences
    private lateinit var userMemoryRepo: UserMemoryRepository
    private lateinit var actionLogRepo: ActionLogRepository
    private lateinit var chatHistoryRepo: ChatHistoryRepository
    private lateinit var habitEngine: HabitLearningEngine

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PhoneAgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        privacyPrefs = PrivacyPreferences(context).apply { setPrivacyModeEnabled(false) }
        memoryPrefs = MemoryAndHabitPreferences(context).apply {
            setPredictiveLearningEnabled(false)
            setAnonymizedHabitGeminiConsent(false)
            setMorningRoutineApproved(false)
            setTurnsSinceLastSummary(0)
            setLastSyncedActionLogTimestamp(0L)
            setLastSyncedUsageTimestamp(0L)
            clearDismissedSuggestionKeys()
        }
        userMemoryRepo = UserMemoryRepository(
            memoryDao = db.userMemoryDao(),
            habitDao = db.habitLogDao(),
            summaryDao = db.conversationSummaryDao(),
            privacyPreferences = privacyPrefs
        )
        actionLogRepo = ActionLogRepository(db.actionLogDao(), privacyPrefs)
        chatHistoryRepo = ChatHistoryRepository(db.chatMessageDao(), db.agentTaskDao())
        habitEngine = HabitLearningEngine(
            appContext = context,
            userMemoryRepository = userMemoryRepo,
            actionLogDao = db.actionLogDao(),
            memoryPrefs = memoryPrefs,
            privacyPreferences = privacyPrefs,
            agentPolicy = AgentPolicy()
        )
    }

    @After
    fun tearDown() {
        privacyPrefs.setPrivacyModeEnabled(false)
        db.close()
    }

    private fun fakeAppCheck() = object : AppCheckTokenClient {
        override suspend fun getToken(forceRefresh: Boolean) = "test-appcheck-token"
        override suspend fun clearStoredTokens() {}
    }

    private fun buildTestCommandsViewModel(
        liteReplies: MutableList<String> = mutableListOf("ملخص تجريبي من Lite"),
        liteCallCounter: AtomicInteger = AtomicInteger(0),
        recordedLitePrompts: MutableList<String> = mutableListOf()
    ): CommandsViewModel {
        val fakeClient = object : GeminiModelClient {
            override suspend fun generatePlanJson(prompt: String) =
                """{"understandingSummaryAr":"خطة","spokenReplyAr":"حسناً","steps":[]}"""

            override suspend fun generateAudioPlanJson(
                audioBytes: ByteArray,
                mimeType: String,
                prompt: String
            ) = """{"transcript":"","understandingSummaryAr":"","spokenReplyAr":"","steps":[]}"""

            override suspend fun generateVerificationJson(prompt: String) =
                """{"verified":false,"explanationAr":"غير متاح"}"""

            override suspend fun generateCategorizationJson(prompt: String) = "{}"

            override suspend fun generateGeneralArabicText(prompt: String): String {
                liteCallCounter.incrementAndGet()
                recordedLitePrompts.add(prompt)
                return if (liteReplies.size > 1) liteReplies.removeAt(0) else liteReplies.firstOrNull() ?: ""
            }
        }
        val geminiRepo = GeminiRepository(
            appCheckClient = fakeAppCheck(),
            modelClient = fakeClient,
            privacyPreferences = privacyPrefs
        )
        val policy = AgentPolicy()
        val gate = SensitiveActionGate(policy)
        val verifier = ScreenVerifier(context, geminiRepo)
        val executor = CommandExecutor(
            context = context,
            actionLogRepository = actionLogRepo,
            geminiRepository = geminiRepo,
            screenVerifier = verifier,
            agentPolicy = policy,
            sensitiveActionGate = gate,
            privacyPreferences = privacyPrefs
        )
        val organizer = AppOrganizer(
            context = context,
            appOrganizationDao = db.appOrganizationDao(),
            actionLogRepository = actionLogRepo,
            geminiRepository = geminiRepo,
            privacyPreferences = privacyPrefs
        )
        val executionController = AgentExecutionController()
        val floatingManager = AgentFloatingControlManager(context)
        val gameEngine = GeneralGameEngine(
            context = context,
            geminiRepository = geminiRepo,
            actionLogRepository = actionLogRepo,
            agentPolicy = policy,
            executionController = executionController,
            floatingControlManager = floatingManager,
            sensitiveActionGate = gate,
            privacyPreferences = privacyPrefs
        )
        return CommandsViewModel(
            geminiRepository = geminiRepo,
            commandExecutor = executor,
            actionLogRepository = actionLogRepo,
            appOrganizer = organizer,
            screenVerifier = verifier,
            voiceManager = VoiceAssistantManager(context),
            chatHistoryRepository = chatHistoryRepo,
            generalGameEngine = gameEngine,
            executionController = executionController,
            floatingControlManager = floatingManager,
            appContext = context,
            agentPolicy = policy,
            sensitiveActionGate = gate,
            privacyPreferences = privacyPrefs,
            userMemoryRepository = userMemoryRepo,
            memoryAndHabitPreferences = memoryPrefs,
            habitLearningEngine = habitEngine
        )
    }

    private suspend fun awaitCondition(
        timeoutMs: Long = 4000L,
        predicate: suspend () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            delay(25)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `S2-1 periodic Lite summary triggers at 20 turns, persists to user_memory and conversation_summaries, and injects concise context`() = runBlocking {
        val liteCalls = AtomicInteger(0)
        chatHistoryRepo.addUserMessage("رتّب ألعابي")
        chatHistoryRepo.addAgentMessage("تم ترتيب الألعاب في مجلد الألعاب.")
        val vm = buildTestCommandsViewModel(
            liteReplies = mutableListOf("المستخدم يفضل المنبه الصباحي عند 7:00 ويرتّب ألعابه بانتظام."),
            liteCallCounter = liteCalls
        )
        delay(250)

        memoryPrefs.setTurnsSinceLastSummary(UserMemoryRepository.PERIODIC_SUMMARY_INTERVAL_TURNS - 1)
        vm.processConversationWindowAndMilestones()

        val savedMemories = userMemoryRepo.getAllMemories()
        val summaries = userMemoryRepo.getRecentConversationSummaries(5)
        assertTrue(savedMemories.any { it.source == UserMemoryRepository.SOURCE_PERIODIC_SUMMARY })
        assertTrue(summaries.any { it.summaryType == UserMemoryRepository.SUMMARY_TYPE_PERIODIC_20 })
        assertEquals(0, memoryPrefs.getTurnsSinceLastSummary())

        val concise = userMemoryRepo.buildConciseMemoryContext()
        assertTrue(concise.contains("حقائق وملخصات محفوظة عن المستخدم"))
        assertTrue(concise.contains("المنبه الصباحي"))
    }

    @Test
    fun `F18 processConversationWindowAndMilestones evicts oldest turns when exceeding 30 and saves Lite summary to Room`() = runBlocking {
        val liteCalls = AtomicInteger(0)
        val recordedPrompts = mutableListOf<String>()

        // تخزين 30 رسالة في Room ليتم تحميلها في conversationTurns عند التهيئة
        for (i in 1..30) {
            chatHistoryRepo.addUserMessage("رسالة قديمة رقم $i")
        }
        val vmWithHistory = buildTestCommandsViewModel(
            liteReplies = mutableListOf("ملخص أقدم الرسائل المقتطعة أو ملخص اليوم"),
            liteCallCounter = liteCalls,
            recordedLitePrompts = recordedPrompts
        )
        awaitCondition {
            // الانتظار قليلاً ليتم تحميل الـ 30 رسالة في init
            delay(150)
            true
        }
        // إضافة دور جديد يتجاوز الحد (30 -> 32) لتحفيز F18 تلقائياً عبر recordConversationTurn
        vmWithHistory.handleSummarizeMyDay(emptyList(), "لخّص يومي")
        awaitCondition {
            userMemoryRepo.getRecentConversationSummaries(10)
                .any { it.summaryType == UserMemoryRepository.SUMMARY_TYPE_TRUNCATION }
        }

        val storedSummaries = userMemoryRepo.getRecentConversationSummaries(10)
        assertTrue(
            "Expected evicted oldest turns to be summarized into Room conversation_summaries",
            storedSummaries.any { it.summaryType == UserMemoryRepository.SUMMARY_TYPE_TRUNCATION }
        )
        assertTrue(
            recordedPrompts.any { it.contains("لخّص الأدوار القديمة") }
        )
    }

    @Test
    fun `habit_logs increments frequency on matching slot and syncs from action_logs`() = runBlocking {
        val nowCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 15)
        }
        val nowMillis = nowCal.timeInMillis

        db.actionLogDao().insertLog(
            ActionLogEntity(
                title = "تشغيل «واتساب» من الدردشة",
                details = "package=com.whatsapp",
                status = ActionStatus.SUCCESS.name,
                actionType = "CHAT_LAUNCH_APP",
                timestamp = nowMillis - 10_000L
            )
        )
        db.actionLogDao().insertLog(
            ActionLogEntity(
                title = "تشغيل «واتساب» من الدردشة",
                details = "package=com.whatsapp",
                status = ActionStatus.SUCCESS.name,
                actionType = "CHAT_LAUNCH_APP",
                timestamp = nowMillis - 5_000L
            )
        )

        val syncedCount = habitEngine.syncFromActionLogs(nowMillis)
        assertEquals(2, syncedCount)

        val habits = userMemoryRepo.getTopHabits()
        val whatsappHabit = habits.firstOrNull { it.actionName == "فتح تطبيق: واتساب" }
        assertNotNull("Expected WhatsApp launch habit to be aggregated", whatsappHabit)
        assertEquals(2, whatsappHabit?.frequencyCount)
        assertEquals(8, whatsappHabit?.hourOfDay)

        // التحقق من أن syncFromUsageStats يتوقف بأمان عند عدم توفر الإذن
        assertEquals(0, habitEngine.syncFromUsageStats(emptyList(), nowMillis))
    }

    @Test
    fun `morning routine asks one-time approval first and runs immediately afterwards`() = runBlocking {
        val vm = buildTestCommandsViewModel()
        assertFalse(memoryPrefs.isMorningRoutineApproved())

        // المرة الأولى: يعرض موافقة أول مرة ولا ينفذ مباشرة
        vm.analyzeAndPlanCommand("الروتين الصباحي")
        awaitCondition { vm.state.value.pendingMorningRoutineApproval }
        assertTrue("First call must prompt for one-time approval", vm.state.value.pendingMorningRoutineApproval)
        assertFalse(vm.state.value.morningRoutineApproved)

        // بعد الموافقة: يُعتمد الروتين وينفذ الخطوات
        vm.approveMorningRoutine()
        awaitCondition {
            vm.state.value.morningRoutineApproved &&
                !vm.state.value.pendingMorningRoutineApproval &&
                vm.state.value.lastSpokenReply.contains("تم تنفيذ «الروتين الصباحي»")
        }
        assertFalse(vm.state.value.pendingMorningRoutineApproval)
        assertTrue(vm.state.value.morningRoutineApproved)
        assertTrue(vm.state.value.lastSpokenReply.contains("تم تنفيذ «الروتين الصباحي»"))

        // الأمر التالي بأمر واحد ينفّذ مباشرة دون إعادة سؤال الموافقة
        vm.analyzeAndPlanCommand("الروتين الصباحي")
        awaitCondition {
            !vm.state.value.isBusyWorking &&
                vm.state.value.lastSpokenReply.contains("تم تنفيذ «الروتين الصباحي»")
        }
        assertFalse(vm.state.value.pendingMorningRoutineApproval)
        assertTrue(vm.state.value.lastSpokenReply.contains("تم تنفيذ «الروتين الصباحي»"))
    }

    @Test
    fun `predictive learning emits dismissible non-auto-executing suggestion and sends only anonymized summary to Gemini with explicit consent`() = runBlocking {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val day = HabitLearningEngine.calendarDayToIso(cal.get(Calendar.DAY_OF_WEEK))

        // تسجيل عادة متكررة مرتين في الساعة الحالية
        repeat(2) {
            userMemoryRepo.recordHabit(
                actionName = "فتح تطبيق: واتساب",
                hourOfDay = hour,
                dayOfWeek = day
            )
        }

        val liteCalls = AtomicInteger(0)
        val sentPrompts = mutableListOf<String>()
        val vm = buildTestCommandsViewModel(
            liteReplies = mutableListOf("يبدو أنك تفتح تطبيق التواصل غالباً الآن — يمكنك تجهيز الأمر."),
            liteCallCounter = liteCalls,
            recordedLitePrompts = sentPrompts
        )
        awaitCondition {
            delay(50)
            true
        }
        val habitPromptFilter: (String) -> Boolean = { it.contains("ملخص أنماط استخدام مجهول الهوية") }

        // 1) بدون تفعيل التعلّم التنبؤي -> لا اقتراح
        vm.refreshPredictiveSuggestion(cal.timeInMillis)
        assertNull(vm.state.value.activePredictiveSuggestion)
        assertEquals(0, sentPrompts.count(habitPromptFilter))

        // 2) تفعيل التعلّم التنبؤي محلياً فقط بدون موافقة Gemini -> اقتراح محلي، و0 نداء عادات لـ Gemini
        memoryPrefs.setPredictiveLearningEnabled(true)
        vm.refreshPredictiveSuggestion(cal.timeInMillis)
        val localSuggestion = vm.state.value.activePredictiveSuggestion
        assertNotNull(localSuggestion)
        assertFalse(localSuggestion!!.isFromGemini)
        assertEquals("افتح واتساب", localSuggestion.suggestedCommand)
        assertEquals(0, sentPrompts.count(habitPromptFilter))

        // 3) الضغط على «تجهيز الأمر» يملأ مربع النص فقط ولا ينفذ تلقائياً
        vm.applyPredictiveSuggestion()
        assertEquals("افتح واتساب", vm.state.value.inputText)
        assertNull(vm.state.value.activePredictiveSuggestion)
        assertFalse(vm.state.value.isExecuting)

        // 4) تفعيل الموافقة المعلنة لمشاركة ملخص مجهول الهوية مع Gemini -> يرسل ملخصاً مجهولاً فقط
        memoryPrefs.setAnonymizedHabitGeminiConsent(true)
        vm.refreshPredictiveSuggestion(cal.timeInMillis)
        val geminiSuggestion = vm.state.value.activePredictiveSuggestion
        assertNotNull(geminiSuggestion)
        assertTrue(geminiSuggestion!!.isFromGemini)
        val habitPrompts = sentPrompts.filter(habitPromptFilter)
        assertEquals(1, habitPrompts.size)
        val promptSent = habitPrompts.single()
        assertTrue(promptSent.contains("تطبيق تواصل"))
        assertFalse("Raw personal app name must NOT be sent in anonymized summary", promptSent.contains("واتساب"))

        // 5) الإغلاق يحفظ مفتاح التجاهل ولا يظهر مرة أخرى
        vm.dismissPredictiveSuggestion()
        assertNull(vm.state.value.activePredictiveSuggestion)
        vm.refreshPredictiveSuggestion(cal.timeInMillis)
        assertNull(vm.state.value.activePredictiveSuggestion)
    }

    @Test
    fun `summarize my day aggregates logs and tasks and respects Privacy Mode`() = runBlocking {
        val liteCalls = AtomicInteger(0)
        val sentPrompts = mutableListOf<String>()
        val vm = buildTestCommandsViewModel(
            liteReplies = mutableListOf("أنجزت اليوم تذكيرين ورتّبت تطبيقاتك، وتبقّت مهمة واحدة."),
            liteCallCounter = liteCalls,
            recordedLitePrompts = sentPrompts
        )
        actionLogRepo.logAction("ضبط منبه 7 صباحاً", "تم بنجاح", ActionStatus.SUCCESS, "CHAT_ALARM")
        chatHistoryRepo.createTask(
            AgentTaskEntity(title = "مراجعة التقويم", taskType = AgentTaskType.TASK.name)
        )

        val daySummaryPromptFilter: (String) -> Boolean = { it.contains("ملخص نشاط اليوم") }
        vm.handleSummarizeMyDay(emptyList(), "لخّص يومي")
        assertEquals(1, sentPrompts.count(daySummaryPromptFilter))
        assertTrue(vm.state.value.lastSpokenReply.contains("أنجزت اليوم"))

        // في وضع الخصوصية يعمل محلياً دون إرسال لـ Gemini
        privacyPrefs.setPrivacyModeEnabled(true)
        vm.handleSummarizeMyDay(emptyList(), "لخّص يومي")
        assertEquals(1, sentPrompts.count(daySummaryPromptFilter))
        assertTrue(vm.state.value.lastSpokenReply.contains("وضع الخصوصية مفعّل"))
    }

    @Test
    fun `all eight policy rejections return plain-language reason and concrete alternative`() {
        val policy = AgentPolicy()
        val commands = listOf(
            "أدخل كلمة المرور تلقائياً" to ForbiddenCategory.PASSWORDS,
            "استخدم البصمة لتأكيد الدخول" to ForbiddenCategory.BIOMETRICS,
            "قم بالدفع الإلكتروني للفاتورة" to ForbiddenCategory.PAYMENTS,
            "أتمم عملية شراء الآن" to ForbiddenCategory.PURCHASES,
            "أرسل أموالاً لصديقي" to ForbiddenCategory.MONEY_TRANSFER,
            "احذف الصور والبيانات" to ForbiddenCategory.DATA_DELETION,
            "غيّر إعدادات الحسابات" to ForbiddenCategory.ACCOUNT_SETTINGS,
            "تجاوز قفل الشاشة" to ForbiddenCategory.LOCK_SCREEN_BYPASS
        )

        for ((cmd, category) in commands) {
            val decision = policy.evaluateCommand(cmd)
            assertFalse(decision.allowed)
            assertEquals(category, decision.violationCategory)
            assertTrue("Expected plain reason in Arabic for $category", decision.reasonAr.contains("سياسات الأمان"))
            assertTrue("Expected clear alternative for $category", decision.reasonAr.contains("بدلاً من ذلك"))

            val (reason, alternative) = UserMemoryRepository.explainPolicyRejection("POLICY_BLOCKED", category)
            assertTrue(reason.contains("سياسات الأمان"))
            assertTrue(alternative.contains("بدلاً من ذلك"))
        }
    }

    @Test
    fun `UI forget single fact and clear all memory buttons render and execute properly`() {
        var forgottenId: Long? = null
        var clearedAll = false
        val memories = listOf(
            UserMemoryEntity(id = 101L, fact = "أحب العمل صباحاً", source = UserMemoryRepository.SOURCE_CONVERSATION),
            UserMemoryEntity(id = 102L, fact = "لعبتي المفضلة الشطرنج", source = UserMemoryRepository.SOURCE_PERIODIC_SUMMARY)
        )

        composeRule.setContent {
            PhoneAgentTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    MemoryAndHabitsManagementSection(
                        memories = memories,
                        habits = listOf(
                            HabitLogEntity(
                                id = 1L,
                                actionName = "الروتين الصباحي",
                                hourOfDay = 8,
                                dayOfWeek = 1,
                                frequencyCount = 3
                            )
                        ),
                        isPrivacyModeEnabled = false,
                        predictiveLearningEnabled = true,
                        anonymizedHabitGeminiConsent = false,
                        morningRoutineApproved = true,
                        onTogglePredictiveLearning = {},
                        onToggleAnonymizedGeminiConsent = {},
                        onResetMorningRoutineApproval = {},
                        onForgetMemoryFact = { forgottenId = it },
                        onClearAllMemory = { clearedAll = true },
                        onClearAllHabits = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("forget_fact_button_101").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(101L, forgottenId)

        composeRule.onNodeWithTag("clear_all_memory_button").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("confirm_clear_memory_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTrue(clearedAll)
    }

    @Test
    fun `UI predictive suggestion card is dismissible and prepare button never auto-executes`() {
        var prepared = false
        var dismissed = false
        val suggestion = PredictiveSuggestion(
            key = "open_whatsapp@9",
            titleAr = "اقتراح ذكي معتاد (صباحاً)",
            suggestedCommand = "افتح واتساب",
            reasonAr = "لاحظت تكرار فتح التطبيق في هذا الوقت.",
            isFromGemini = false
        )

        composeRule.setContent {
            PhoneAgentTheme {
                PredictiveSuggestionCard(
                    suggestion = suggestion,
                    onPrepareCommand = { prepared = true },
                    onDismiss = { dismissed = true }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("تجهيز الأمر").assertIsDisplayed()
        composeRule.onNodeWithTag("apply_predictive_suggestion_button").performClick()
        composeRule.waitForIdle()
        assertTrue(prepared)

        composeRule.onNodeWithTag("dismiss_predictive_suggestion_button").performClick()
        composeRule.waitForIdle()
        assertTrue(dismissed)
    }

    @Test
    fun `PermissionsViewModel clears single fact and all user memory cleanly`() = runBlocking {
        val vm = PermissionsViewModel(
            appContext = context,
            privacyPreferences = privacyPrefs,
            userMemoryRepository = userMemoryRepo,
            memoryAndHabitPreferences = memoryPrefs
        )
        val id1 = userMemoryRepo.rememberFact("حقيقة 1", UserMemoryRepository.SOURCE_CONVERSATION)
        userMemoryRepo.rememberFact("حقيقة 2", UserMemoryRepository.SOURCE_PREFERENCE)
        assertEquals(2, userMemoryRepo.getAllMemories().size)

        vm.forgetMemoryFact(id1)
        delay(100)
        assertEquals(1, userMemoryRepo.getAllMemories().size)

        vm.clearAllUserMemory()
        delay(100)
        assertTrue(userMemoryRepo.getAllMemories().isEmpty())
    }
}
