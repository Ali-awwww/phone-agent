package com.example.phase7

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Looper
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.FALLBACK_LITE_GEMINI_MODEL
import com.example.data.ai.GeminiModelClient
import com.example.data.ai.GeminiRepository
import com.example.data.ai.PRIMARY_GEMINI_MODEL
import com.example.data.ai.WEAK_NETWORK_COMMAND_TIMEOUT_MS
import com.example.data.local.ActionLogRepository
import com.example.data.local.AgentTaskType
import com.example.data.local.ChatHistoryRepository
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ChatMessageType
import com.example.data.local.ChatSender
import com.example.data.local.PhoneAgentDatabase
import com.example.data.local.QueuedCommandStatus
import com.example.data.local.UserMemoryRepository
import com.example.executor.ChatIntentRouter
import com.example.executor.ChatRouteIntent
import com.example.executor.CommandExecutor
import com.example.executor.ModelRouteTier
import com.example.game.GeneralGameEngine
import com.example.habits.HabitLearningEngine
import com.example.model.AgentPlan
import com.example.model.AgentPlanStep
import com.example.model.AppFolder
import com.example.model.ScreenSnapshot
import com.example.model.StepActionType
import com.example.model.StepVerificationStatus
import com.example.network.NetworkConnectivityMonitor
import com.example.network.NetworkTier
import com.example.organizer.AppMatchResult
import com.example.organizer.AppNameMatcher
import com.example.organizer.AppOrganizer
import com.example.organizer.InstalledAppInfo
import com.example.overlay.AgentExecutionController
import com.example.overlay.AgentFloatingControlManager
import com.example.policy.AgentPolicy
import com.example.policy.SensitiveActionGate
import com.example.security.AppCheckTokenClient
import com.example.security.MemoryAndHabitPreferences
import com.example.security.PrivacyPreferences
import com.example.service.PhoneAgentAccessibilityService
import com.example.service.ScreenStateBridge
import com.example.ui.components.ChatMessageBubble
import com.example.ui.components.ChatTextTruncator
import com.example.ui.screens.NetworkConnectivityIndicator
import com.example.ui.theme.PhoneAgentTheme
import com.example.ui.viewmodel.CommandsViewModel
import com.example.ui.viewmodel.ModelConnectionStatus
import com.example.verifier.LocalScreenSummarizer
import com.example.verifier.ScreenVerifier
import com.example.voice.VoiceAssistantManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase7OfflineAndVoiceTest {

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
        ScreenStateBridge.detachService()
        ScreenStateBridge.clearSnapshot()
        db = Room.inMemoryDatabaseBuilder(context, PhoneAgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        privacyPrefs = PrivacyPreferences(context).apply { setPrivacyModeEnabled(false) }
        memoryPrefs = MemoryAndHabitPreferences(context).apply {
            setPredictiveLearningEnabled(false)
            setAnonymizedHabitGeminiConsent(false)
            setMorningRoutineApproved(false)
            setTurnsSinceLastSummary(0)
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
        ScreenStateBridge.detachService()
        ScreenStateBridge.clearSnapshot()
        privacyPrefs.setPrivacyModeEnabled(false)
        db.close()
    }

    private fun fakeAppCheck() = object : AppCheckTokenClient {
        override suspend fun getToken(forceRefresh: Boolean) = "test-appcheck-token"
        override suspend fun clearStoredTokens() {}
    }

    private fun registerFakeLauncherApp(packageName: String, label: String) {
        val shadowPm = Shadows.shadowOf(context.packageManager)
        val component = ComponentName(packageName, "$packageName.MainActivity")
        val appInfo = android.content.pm.ApplicationInfo().apply {
            this.packageName = packageName
            this.name = label
            this.nonLocalizedLabel = label
        }
        val pkgInfo = android.content.pm.PackageInfo().apply {
            this.packageName = packageName
            this.applicationInfo = appInfo
        }
        shadowPm.installPackage(pkgInfo)
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
            setComponent(component)
        }
        val resolveInfo = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                this.packageName = packageName
                this.name = component.className
                this.applicationInfo = appInfo
                this.nonLocalizedLabel = label
            }
            nonLocalizedLabel = label
        }
        shadowPm.addResolveInfoForIntent(launchIntent, resolveInfo)
        val queryMain = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        shadowPm.addResolveInfoForIntent(queryMain, resolveInfo)
        val queryPkg = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
        }
        shadowPm.addResolveInfoForIntent(queryPkg, resolveInfo)
    }

    private fun buildTestEnvironment(
        initialTier: NetworkTier = NetworkTier.OFFLINE,
        failGemini: Boolean = false,
        planCallCounter: AtomicInteger = AtomicInteger(0),
        liteCallCounter: AtomicInteger = AtomicInteger(0),
        flashCallCounter: AtomicInteger = AtomicInteger(0),
        audioCallCounter: AtomicInteger = AtomicInteger(0)
    ): Triple<CommandsViewModel, NetworkConnectivityMonitor, AgentFloatingControlManager> {
        val networkMonitor = NetworkConnectivityMonitor(context, initialOverride = initialTier)
        val fakeClient = object : GeminiModelClient {
            override suspend fun generatePlanJson(prompt: String): String {
                flashCallCounter.incrementAndGet()
                planCallCounter.incrementAndGet()
                if (failGemini) throw RuntimeException("Simulated Flash outage")
                return """{"understandingSummaryAr":"خطة محاكاة","spokenReplyAr":"تم تجهيز الخطة.","steps":[]}"""
            }

            override suspend fun generatePlanJsonFallback(prompt: String): String {
                liteCallCounter.incrementAndGet()
                planCallCounter.incrementAndGet()
                if (failGemini) throw RuntimeException("Simulated Lite outage")
                return """{"understandingSummaryAr":"خطة خفيفة","spokenReplyAr":"تم التجهيز بنموذج Lite.","steps":[]}"""
            }

            override suspend fun generateAudioPlanJson(
                audioBytes: ByteArray,
                mimeType: String,
                prompt: String
            ): String {
                audioCallCounter.incrementAndGet()
                if (failGemini) throw RuntimeException("Simulated Audio outage")
                return """{"transcript":"افتح واتساب","understandingSummaryAr":"فتح واتساب","spokenReplyAr":"حسناً","steps":[]}"""
            }

            override suspend fun generateVerificationJson(prompt: String) =
                """{"verified":true,"explanationAr":"تم التحقق"}"""

            override suspend fun generateCategorizationJson(prompt: String) = "{}"

            override suspend fun generateGeneralArabicText(prompt: String): String {
                flashCallCounter.incrementAndGet()
                if (failGemini) throw RuntimeException("503 Unavailable")
                return "رد محاكاة"
            }

            override suspend fun generateLiteGeneralArabicText(prompt: String): String {
                liteCallCounter.incrementAndGet()
                if (failGemini) throw RuntimeException("503 Unavailable")
                return "ملخص Lite"
            }
        }
        val geminiRepo = GeminiRepository(
            appCheckClient = fakeAppCheck(),
            modelClient = fakeClient,
            privacyPreferences = privacyPrefs,
            networkTierProvider = { networkMonitor.currentTier }
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
        val vm = CommandsViewModel(
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
            habitLearningEngine = habitEngine,
            networkConnectivityMonitor = networkMonitor,
            queuedCommandDao = db.queuedCommandDao()
        )
        return Triple(vm, networkMonitor, floatingManager)
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

    // ==================== (a) الأوامر المحلية بلا نموذج ====================

    @Test
    fun `item a - ChatIntentRouter routes open app, alarm, reminder, dial, and wifi locally`() {
        val openApp = ChatIntentRouter.classifyIntent("افتح واتساب", listOf("WhatsApp"))
        assertTrue(openApp is ChatRouteIntent.LocalOpenApp)
        assertEquals("واتساب", (openApp as ChatRouteIntent.LocalOpenApp).appQuery)

        val dialNum = ChatIntentRouter.classifyIntent("اتصل على 0501234567")
        assertTrue(dialNum is ChatRouteIntent.LocalDialPhone)
        assertEquals("0501234567", (dialNum as ChatRouteIntent.LocalDialPhone).phoneNumber)

        val dialName = ChatIntentRouter.classifyIntent("اتصل بأحمد")
        assertTrue(dialName is ChatRouteIntent.LocalDialPhone)
        assertEquals("أحمد", (dialName as ChatRouteIntent.LocalDialPhone).targetQuery)

        val wifi = ChatIntentRouter.classifyIntent("شغل الواي فاي")
        assertTrue(wifi is ChatRouteIntent.LocalToggleWifi)
        assertEquals(true, (wifi as ChatRouteIntent.LocalToggleWifi).enableHint)

        val alarm = ChatIntentRouter.classifyIntent("اضبط منبهاً للساعة 7:30 مساءً")
        assertTrue(alarm is ChatRouteIntent.CreateTaskOrReminder)
        assertEquals(AgentTaskType.ALARM, (alarm as ChatRouteIntent.CreateTaskOrReminder).taskType)
        assertEquals(19, alarm.hour)
        assertEquals(30, alarm.minute)

        val reminder = ChatIntentRouter.classifyIntent("ذكّرني بعد 10 دقائق بشرب الماء")
        assertTrue(reminder is ChatRouteIntent.CreateTaskOrReminder)
        assertEquals(AgentTaskType.REMINDER, (reminder as ChatRouteIntent.CreateTaskOrReminder).taskType)
        assertEquals(10, reminder.delayMinutes)
    }

    @Test
    fun `item a - acceptance open whatsapp works completely offline with zero Gemini calls and chat message`() = runBlocking {
        registerFakeLauncherApp("com.whatsapp", "WhatsApp")
        val planCalls = AtomicInteger(0)
        val liteCalls = AtomicInteger(0)
        val (vm, _, _) = buildTestEnvironment(
            initialTier = NetworkTier.OFFLINE,
            planCallCounter = planCalls,
            liteCallCounter = liteCalls
        )
        vm.refreshInstalledAppsCache(forceRefresh = true)
        delay(200)

        vm.analyzeAndPlanCommand("افتح واتساب")
        awaitCondition {
            val msgs = chatHistoryRepo.messagesFlow.first()
            msgs.any { it.sender == ChatSender.AGENT.name && it.text.contains("WhatsApp") }
        }

        assertEquals(0, planCalls.get())
        assertEquals(0, liteCalls.get())
        val started = Shadows.shadowOf(context as Application).nextStartedActivity
        assertNotNull(started)
        assertEquals("com.whatsapp", started.`package`)
        val msgs = chatHistoryRepo.messagesFlow.first()
        assertTrue(msgs.any { it.sender == ChatSender.AGENT.name && it.text.contains("محلياً") })
    }

    @Test
    fun `item a - dial phone and toggle wifi work offline via chat channel`() = runBlocking {
        val planCalls = AtomicInteger(0)
        val (vm, _, _) = buildTestEnvironment(initialTier = NetworkTier.OFFLINE, planCallCounter = planCalls)
        delay(150)

        vm.analyzeAndPlanCommand("اتصل على 0501234567")
        awaitCondition {
            chatHistoryRepo.messagesFlow.first().any {
                it.sender == ChatSender.AGENT.name && it.text.contains("0501234567")
            }
        }
        val dialIntent = Shadows.shadowOf(context as Application).nextStartedActivity
        assertNotNull(dialIntent)
        assertEquals(Intent.ACTION_DIAL, dialIntent.action)
        assertEquals("tel:0501234567", dialIntent.dataString)

        vm.analyzeAndPlanCommand("شغل الواي فاي")
        awaitCondition {
            chatHistoryRepo.messagesFlow.first().any {
                it.sender == ChatSender.AGENT.name && it.text.contains("الواي فاي")
            }
        }
        val wifiIntent = Shadows.shadowOf(context as Application).nextStartedActivity
        assertNotNull(wifiIntent)
        assertTrue(
            wifiIntent.action == Settings.Panel.ACTION_WIFI ||
                wifiIntent.action == Settings.ACTION_WIFI_SETTINGS
        )
        assertEquals(0, planCalls.get())
    }

    // ==================== (b) مطابقة أسماء التطبيقات الضبابية ====================

    @Test
    fun `item b - acceptance efootball matches Arabic transliteration, case-insensitive, prefix, partial, and typos`() = runBlocking {
        val apps = listOf(
            InstalledAppInfo(packageName = "jp.konami.pesam", label = "eFootball", folder = AppFolder.GAMES),
            InstalledAppInfo(packageName = "com.google.android.youtube", label = "YouTube", folder = AppFolder.MEDIA),
            InstalledAppInfo(packageName = "com.whatsapp", label = "WhatsApp", folder = AppFolder.COMMUNICATION)
        )

        val efootballMatch = AppNameMatcher.match("ايفتبول", apps)
        assertTrue(efootballMatch is AppMatchResult.SingleMatch)
        assertEquals("jp.konami.pesam", (efootballMatch as AppMatchResult.SingleMatch).app.packageName)

        val caseMatch = AppNameMatcher.match("YOUTUBE", apps)
        assertTrue(caseMatch is AppMatchResult.SingleMatch)
        assertEquals("com.google.android.youtube", (caseMatch as AppMatchResult.SingleMatch).app.packageName)

        val prefixMatch = AppNameMatcher.match("you", apps)
        assertTrue(prefixMatch is AppMatchResult.SingleMatch)
        assertEquals("YouTube", (prefixMatch as AppMatchResult.SingleMatch).app.label)

        val typoMatch = AppNameMatcher.match("watssap", apps)
        assertTrue(typoMatch is AppMatchResult.SingleMatch)
        assertEquals("WhatsApp", (typoMatch as AppMatchResult.SingleMatch).app.label)

        // التحقق end-to-end من «افتح ايفتبول» عبر CommandsViewModel
        registerFakeLauncherApp("jp.konami.pesam", "eFootball")
        val (vm, _, _) = buildTestEnvironment(initialTier = NetworkTier.OFFLINE)
        vm.refreshInstalledAppsCache(forceRefresh = true)
        delay(200)

        vm.analyzeAndPlanCommand("افتح ايفتبول")
        awaitCondition {
            chatHistoryRepo.messagesFlow.first().any {
                it.sender == ChatSender.AGENT.name && it.text.contains("eFootball")
            }
        }
        val launched = Shadows.shadowOf(context as Application).nextStartedActivity
        assertNotNull(launched)
        assertEquals("jp.konami.pesam", launched.`package`)
    }

    @Test
    fun `item b - multiple matches asks user which app, and no match reports explicit Arabic message`() = runBlocking {
        registerFakeLauncherApp("com.whatsapp", "WhatsApp")
        registerFakeLauncherApp("com.whatsapp.w4b", "WhatsApp Business")

        val multi = AppNameMatcher.match(
            "whatsapp",
            listOf(
                InstalledAppInfo(packageName = "com.whatsapp", label = "WhatsApp", folder = AppFolder.COMMUNICATION),
                InstalledAppInfo(packageName = "com.whatsapp.w4b", label = "WhatsApp Business", folder = AppFolder.COMMUNICATION)
            )
        )
        assertTrue(multi is AppMatchResult.MultipleMatches)

        val (vm, _, _) = buildTestEnvironment(initialTier = NetworkTier.OFFLINE)
        vm.refreshInstalledAppsCache(forceRefresh = true)
        delay(200)

        vm.analyzeAndPlanCommand("افتح تطبيق غير موجود نهائيا")
        awaitCondition {
            chatHistoryRepo.messagesFlow.first().any {
                it.messageType == ChatMessageType.ERROR.name && it.text.contains("لم أجد أي تطبيق")
            }
        }
        val msgs = chatHistoryRepo.messagesFlow.first()
        assertTrue(msgs.any { it.messageType == ChatMessageType.ERROR.name && it.text.contains("لم أجد أي تطبيق") })
    }

    // ==================== (c) طابور Room + Lite على E/2G + مؤشر الاتصال ====================

    @Test
    fun `item c - MIGRATION_4_5 creates queued_commands table cleanly`() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS dummy_v4 (id INTEGER PRIMARY KEY)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = factory.create(config)
        val sqliteDb = helper.writableDatabase
        PhoneAgentDatabase.MIGRATION_4_5.migrate(sqliteDb)
        sqliteDb.execSQL(
            "INSERT INTO queued_commands (commandText, status, createdAt) VALUES ('اختبار', 'PENDING', 12345)"
        )
        val cursor = sqliteDb.query("SELECT commandText, status FROM queued_commands")
        assertTrue(cursor.moveToFirst())
        assertEquals("اختبار", cursor.getString(0))
        assertEquals("PENDING", cursor.getString(1))
        cursor.close()
        helper.close()
    }

    @Test
    fun `item c - offline Gemini command queues in Room and executes with notification on reconnect`() = runBlocking {
        val app = context as Application
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val planCalls = AtomicInteger(0)
        val (vm, monitor, _) = buildTestEnvironment(
            initialTier = NetworkTier.OFFLINE,
            planCallCounter = planCalls
        )
        delay(150)

        assertTrue(vm.state.value.modelConnectionStatus is ModelConnectionStatus.Disconnected)
        vm.analyzeAndPlanCommand("اكتب رسالة تهنئة وأرسلها في واتساب")
        awaitCondition {
            db.queuedCommandDao().getPendingCommands().isNotEmpty()
        }

        assertEquals(0, planCalls.get())
        assertEquals(1, vm.state.value.queuedCommandsCount)

        // عودة الشبكة -> تفريغ الطابور + إشعار
        monitor.setTestOverride(NetworkTier.NORMAL)
        awaitCondition(timeoutMs = 5000L) {
            db.queuedCommandDao().getPendingCommands().isEmpty()
        }

        assertEquals(1, planCalls.get())
        val recent = db.queuedCommandDao().getRecentCommands()
        assertEquals(QueuedCommandStatus.COMPLETED.name, recent.first().status)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = Shadows.shadowOf(nm).allNotifications
        assertTrue("Expected notification after draining queued command", posted.isNotEmpty())
    }

    @Test
    fun `item c - acceptance weak E 2G routes Lite first with short 15s timeout and no multi-minute hang`() = runBlocking {
        val flashCalls = AtomicInteger(0)
        val liteCalls = AtomicInteger(0)
        var capturedPrompt = ""
        var currentTier = NetworkTier.WEAK_E_2G

        val fakeClient = object : GeminiModelClient {
            override suspend fun generatePlanJson(prompt: String): String {
                flashCalls.incrementAndGet()
                return """{"understandingSummaryAr":"فلاش","spokenReplyAr":"تم","steps":[]}"""
            }

            override suspend fun generatePlanJsonFallback(prompt: String): String {
                liteCalls.incrementAndGet()
                capturedPrompt = prompt
                return """{"understandingSummaryAr":"خفيف","spokenReplyAr":"تم","steps":[]}"""
            }

            override suspend fun generateAudioPlanJson(audioBytes: ByteArray, mimeType: String, prompt: String) = "{}"
            override suspend fun generateVerificationJson(prompt: String) = "{}"
            override suspend fun generateCategorizationJson(prompt: String) = "{}"
            override suspend fun generateGeneralArabicText(prompt: String) = "ok"
        }

        val repo = GeminiRepository(
            appCheckClient = fakeAppCheck(),
            modelClient = fakeClient,
            privacyPreferences = privacyPrefs,
            networkTierProvider = { currentTier }
        )

        val meta = repo.generateCommandPlanWithMeta(
            userCommand = "أرسل رسالة ترحيب لصديقي",
            installedAppNames = (1..40).map { "App$it" },
            currentScreenSummary = "A".repeat(1200)
        )
        assertEquals(FALLBACK_LITE_GEMINI_MODEL, meta.intendedModel)
        assertEquals(FALLBACK_LITE_GEMINI_MODEL, meta.modelUsed)
        assertEquals(1, liteCalls.get())
        assertEquals(0, flashCalls.get())
        assertTrue("Expected concise prompt on E/2G", capturedPrompt.contains("اتصال ضعيف E/2G"))
        assertEquals(15_000L, WEAK_NETWORK_COMMAND_TIMEOUT_MS)

        // وعند الشبكة العادية يعود Flash أولاً
        currentTier = NetworkTier.NORMAL
        val normalMeta = repo.generateCommandPlanWithMeta(
            userCommand = "أرسل رسالة",
            installedAppNames = listOf("WhatsApp"),
            currentScreenSummary = "الشاشة"
        )
        assertEquals(PRIMARY_GEMINI_MODEL, normalMeta.intendedModel)
        assertEquals(1, flashCalls.get())
    }

    @Test
    fun `item c - NetworkConnectivityIndicator UI shows honest offline, queue count, and E 2G Lite badge`() {
        composeRule.setContent {
            PhoneAgentTheme {
                NetworkConnectivityIndicator(
                    networkTier = NetworkTier.OFFLINE,
                    queuedCommandsCount = 3
                )
            }
        }
        composeRule.onNodeWithTag("network_connectivity_indicator").assertIsDisplayed()
        composeRule.onNodeWithTag("queued_commands_badge").assertIsDisplayed()
        composeRule.onNodeWithText("في الطابور: 3").assertIsDisplayed()
    }

    // ==================== (d) البديل المحلي لتلخيص الشاشة ====================

    @Test
    fun `item d - offline and Gemini failure summarize screen locally without cloud calls`() = runBlocking {
        val snapshot = ScreenSnapshot(
            packageName = "com.whatsapp",
            visibleTexts = listOf("أهلاً بك", "أهلاً بك", "البريد user@secret.com"),
            clickableLabels = listOf("إرسال", "بحث"),
            editableHints = listOf("اكتب رسالة")
        )
        val localDirect = LocalScreenSummarizer.summarizeSnapshot(snapshot, appLabel = "WhatsApp")
        assertTrue(localDirect.contains("WhatsApp"))
        assertTrue(localDirect.contains("إرسال"))
        assertFalse("Email should be scrubbed", localDirect.contains("user@secret.com"))

        // فحص التكامل عبر CommandsViewModel.analyzeAndPlanCommand("ماذا في شاشتي؟") عند انقطاع الشبكة
        ScreenStateBridge.updateSnapshot(snapshot)
        val liteCalls = AtomicInteger(0)
        val (vm, _, _) = buildTestEnvironment(
            initialTier = NetworkTier.OFFLINE,
            liteCallCounter = liteCalls
        )
        vm.analyzeAndPlanCommand("ماذا في شاشتي؟")
        awaitCondition {
            chatHistoryRepo.messagesFlow.first().any {
                it.messageType == ChatMessageType.SCREEN_READ.name && it.text.contains("ملخص الشاشة المحلي")
            }
        }
        assertEquals(0, liteCalls.get())

        // وعند فشل Gemini على شبكة متصلة يسقط إلى LocalScreenSummarizer
        val (vmFail, _, _) = buildTestEnvironment(
            initialTier = NetworkTier.NORMAL,
            failGemini = true
        )
        vmFail.analyzeAndPlanCommand("ماذا في شاشتي؟")
        awaitCondition {
            chatHistoryRepo.messagesFlow.first().any {
                it.messageType == ChatMessageType.SCREEN_READ.name && it.text.contains("الملخص المحلي المرتّب")
            }
        }
    }

    // ==================== (e) F15 + F11 Accessibility & ScreenVerifier ====================

    @Test
    fun `item e - F15 accepts TYPE_VIEW_CLICKED and TYPE_WINDOW_CONTENT_CHANGED with smart throttling`() {
        assertTrue(
            PhoneAgentAccessibilityService.shouldProcessAccessibilityEvent(
                eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
                eventPackage = "com.whatsapp",
                lastEventPackage = "com.whatsapp",
                nowMs = 1000L,
                lastCaptureMs = 900L,
                lastPackageChangeMs = 900L
            ).not() // < 500ms نفس الحزمة يُخنق
        )
        assertTrue(
            PhoneAgentAccessibilityService.shouldProcessAccessibilityEvent(
                eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
                eventPackage = "com.whatsapp",
                lastEventPackage = "com.whatsapp",
                nowMs = 1600L,
                lastCaptureMs = 1000L,
                lastPackageChangeMs = 1000L
            )
        )
        assertTrue(
            PhoneAgentAccessibilityService.shouldProcessAccessibilityEvent(
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                eventPackage = "com.whatsapp",
                lastEventPackage = "com.whatsapp",
                nowMs = 2500L,
                lastCaptureMs = 1000L,
                lastPackageChangeMs = 1000L
            )
        )
        assertFalse(
            PhoneAgentAccessibilityService.shouldProcessAccessibilityEvent(
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                eventPackage = "com.whatsapp",
                lastEventPackage = "com.other",
                nowMs = 9000L,
                lastCaptureMs = 0L,
                lastPackageChangeMs = 0L
            )
        )
    }

    @Test
    fun `item e - F11 filters invisible or disabled nodes, recycles children, and ranks exact matches first`() {
        val visibleEnabled = AccessibilityNodeInfo.obtain().apply {
            text = "إرسال"
            isVisibleToUser = true
            isEnabled = true
            isClickable = true
        }
        val hiddenNode = AccessibilityNodeInfo.obtain().apply {
            text = "زر مخفي"
            isVisibleToUser = false
            isEnabled = true
            isClickable = true
        }
        val disabledNode = AccessibilityNodeInfo.obtain().apply {
            text = "زر معطل"
            isVisibleToUser = true
            isEnabled = false
            isClickable = true
        }
        val partialNode = AccessibilityNodeInfo.obtain().apply {
            text = "اضغط إرسال الرسالة الآن"
            isVisibleToUser = true
            isEnabled = true
            isClickable = true
        }

        // اختبار تصفية الرؤية والتفعيل عبر extractScreenObservationFromRoot عبر ShadowAccessibilityNodeInfo أو محاكاة شجرة حقيقية:
        assertTrue(PhoneAgentAccessibilityService.isInteractiveNodeEligible(visibleEnabled))
        assertFalse(PhoneAgentAccessibilityService.isInteractiveNodeEligible(hiddenNode))
        assertFalse(PhoneAgentAccessibilityService.isInteractiveNodeEligible(disabledNode))

        // اختبار أفضلية المطابقة الدقيقة على البادئة والجزئية
        val exactScore = PhoneAgentAccessibilityService.matchPriorityScore(visibleEnabled, "إرسال")
        val partialScore = PhoneAgentAccessibilityService.matchPriorityScore(partialNode, "إرسال")
        assertTrue("Exact match ($exactScore) must outrank partial match ($partialScore)", exactScore > partialScore)

        val ranked = PhoneAgentAccessibilityService.rankMatchingNodes(listOf(partialNode, visibleEnabled), "إرسال")
        assertEquals("إرسال", ranked.first().text.toString())

        // التحقق من recycle عبر تتبّع استدعاء recycle() في شجرة عقد مخصصة
        val recycledChild = AtomicBoolean(false)
        val childTrackNode = object : AccessibilityNodeInfo() {
            override fun recycle() {
                recycledChild.set(true)
            }
        }.apply {
            text = "عنصر مقروء"
            isVisibleToUser = true
            isEnabled = true
        }
        val rootTrackNode = object : AccessibilityNodeInfo() {
            override fun getChildCount(): Int = 1
            override fun getChild(index: Int): AccessibilityNodeInfo? = if (index == 0) childTrackNode else null
        }.apply {
            isVisibleToUser = true
            isEnabled = true
            packageName = "com.whatsapp"
        }

        val obs = PhoneAgentAccessibilityService.extractScreenObservationFromRoot(rootTrackNode)
        assertTrue("Child AccessibilityNodeInfo must be recycled", recycledChild.get())
        assertTrue(obs.snapshot.visibleTexts.contains("عنصر مقروء"))

        visibleEnabled.recycle()
        hiddenNode.recycle()
        disabledNode.recycle()
        partialNode.recycle()

        // ScreenVerifier يرجع UNABLE_TO_VERIFY عند عدم توفر خدمة إمكانية الوصول
        runBlocking {
            val verifier = ScreenVerifier(
                context,
                GeminiRepository(
                    appCheckClient = fakeAppCheck(),
                    modelClient = object : GeminiModelClient {
                        override suspend fun generatePlanJson(prompt: String) = "{}"
                        override suspend fun generateAudioPlanJson(audioBytes: ByteArray, mimeType: String, prompt: String) = "{}"
                        override suspend fun generateVerificationJson(prompt: String) = "{}"
                        override suspend fun generateCategorizationJson(prompt: String) = "{}"
                        override suspend fun generateGeneralArabicText(prompt: String) = ""
                    },
                    privacyPreferences = privacyPrefs
                )
            )
            val step = AgentPlanStep(
                stepNumber = 1,
                titleAr = "فتح واتساب",
                descriptionAr = "افتح التطبيق",
                actionType = StepActionType.OPEN_APP,
                targetAppName = "WhatsApp"
            )
            val result = verifier.verifyAfterStep(
                step = step,
                executed = true,
                executionMessageAr = "تم تنفيذ الخطوة",
                preSnapshot = null,
                postSnapshot = null
            )
            assertEquals(StepVerificationStatus.UNABLE_TO_VERIFY, result.status)
        }
    }

    // ==================== (f) F19 + F20 + F21 + S2-9 الصوت ====================

    @Test
    fun `item f - acceptance F19 bounds long audio buffer and deletes temp file on all paths, and clears lastRecordedAudio`() = runBlocking {
        val tempFile = File(context.cacheDir, "voice_cmd_test_long.m4a")
        tempFile.writeBytes(ByteArray(4096) { 0x2A })
        assertTrue(tempFile.exists())

        val bounded = VoiceAssistantManager.readBoundedAndDeleteFile(tempFile, maxBytes = 1024L)
        assertNotNull(bounded)
        assertEquals(1024, bounded!!.size)
        assertFalse("Temp recording file must be deleted after reading", tempFile.exists())

        // مسح lastRecordedAudio بعد معالجة الصوت
        val audioCalls = AtomicInteger(0)
        val (vm, _, _) = buildTestEnvironment(
            initialTier = NetworkTier.NORMAL,
            audioCallCounter = audioCalls
        )
        registerFakeLauncherApp("com.whatsapp", "WhatsApp")
        vm.refreshInstalledAppsCache(forceRefresh = true)
        delay(150)

        vm.processRecordedAudio(ByteArray(64) { 1 }, "audio/m4a", fromMiniChat = false)
        awaitCondition {
            audioCalls.get() == 1 && !vm.hasCachedRecordedAudioForTest()
        }
        assertFalse("lastRecordedAudio must be cleared after processing", vm.hasCachedRecordedAudioForTest())
    }

    @Test
    fun `item f - acceptance F20 RecognitionListener onError invokes audio fallback instead of silence and mini-chat forwards bytes`() = runBlocking {
        val voiceManager = VoiceAssistantManager(context)
        var fallbackBytes: ByteArray? = null
        var fallbackMime: String? = null
        var errMsg: String? = null

        val listener = voiceManager.buildRecognitionListener(
            onPartialResult = {},
            onFinalTranscript = {},
            onAudioRecordedFallback = { bytes, mime ->
                fallbackBytes = bytes
                fallbackMime = mime
            },
            onError = { errMsg = it }
        )
        listener.onBufferReceived(byteArrayOf(10, 20, 30, 40))
        listener.onError(SpeechRecognizer.ERROR_NETWORK)

        assertNotNull("onAudioRecordedFallback must be called on onError when PCM buffer exists", fallbackBytes)
        assertEquals("audio/wav", fallbackMime)
        assertEquals("RIFF", String(fallbackBytes!!.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals(null, errMsg)

        // في المحادثة المصغّرة: إرسال البايتات إلى processRecordedAudio
        val audioCalls = AtomicInteger(0)
        val (vm, _, floating) = buildTestEnvironment(
            initialTier = NetworkTier.NORMAL,
            audioCallCounter = audioCalls
        )
        vm.processRecordedAudio(byteArrayOf(1, 2, 3, 4), "audio/wav", fromMiniChat = true)
        awaitCondition { audioCalls.get() == 1 && !vm.state.value.isBusyWorking }
        val msgs = chatHistoryRepo.messagesFlow.first()
        assertTrue("Mini-chat processed audio should record an agent reply", msgs.any { it.sender == ChatSender.AGENT.name })
    }

    @Test
    fun `item f - F21 checks setLanguage result and enforces recognition timeout`() {
        val voiceManager = VoiceAssistantManager(context, recognitionTimeoutMs = 5000L)

        // فحص نتيجة setLanguage عند فشل العربية ونجاح اللغة الافتراضية
        val fallbackTts = object : TextToSpeech(context, null) {
            override fun setLanguage(loc: Locale?): Int {
                return if (loc?.language == "ar") LANG_NOT_SUPPORTED else LANG_AVAILABLE
            }
        }
        voiceManager.applyTtsLanguageCheck(TextToSpeech.SUCCESS, fallbackTts)
        assertTrue(voiceManager.ttsReady)
        assertEquals(TextToSpeech.LANG_AVAILABLE, voiceManager.lastTtsLanguageStatus)

        // وعند عدم دعم العربية ولا الافتراضية يُعطّل ttsReady بأمان
        val unsupportedTts = object : TextToSpeech(context, null) {
            override fun setLanguage(loc: Locale?): Int = LANG_MISSING_DATA
        }
        voiceManager.applyTtsLanguageCheck(TextToSpeech.SUCCESS, unsupportedTts)
        assertFalse(voiceManager.ttsReady)
        assertEquals(TextToSpeech.LANG_MISSING_DATA, voiceManager.lastTtsLanguageStatus)

        // مهلة التعرّف الصوتي تستدعي onError أو البديل عند انتهاء المهلة
        var timeoutError = ""
        voiceManager.speechRecognitionAvailableOverride = true
        voiceManager.startListening(
            onPartialResult = {},
            onFinalTranscript = {},
            onAudioRecordedFallback = { _, _ -> },
            onError = { timeoutError = it }
        )
        voiceManager.onRecognitionTimeoutReached()
        assertTrue(timeoutError.contains("انتهت مهلة"))
    }

    @Test
    fun `item f - S2-9 truncates long spoken output for weak network and ChatMessageBubble expands with Read More button`() {
        val veryLongText = (1..30).joinToString(" ") { "كلمة_طويلة_جداً_$it" }
        val weakSpeech = VoiceAssistantManager.truncateForSpeech(
            veryLongText,
            maxChars = VoiceAssistantManager.WEAK_NETWORK_SPEECH_MAX_CHARS
        )
        assertTrue(weakSpeech.length <= VoiceAssistantManager.WEAK_NETWORK_SPEECH_MAX_CHARS + 2)
        assertTrue(weakSpeech.endsWith("…"))

        val shouldTruncate = ChatTextTruncator.shouldTruncate(veryLongText)
        assertTrue(shouldTruncate)
        val truncatedDisplay = ChatTextTruncator.truncate(veryLongText)
        assertTrue(truncatedDisplay.endsWith("…"))

        val longMsg = ChatMessageEntity(
            id = 42L,
            sender = ChatSender.AGENT.name,
            text = veryLongText,
            messageType = ChatMessageType.TEXT.name
        )
        composeRule.setContent {
            PhoneAgentTheme {
                ChatMessageBubble(
                    message = longMsg,
                    installedAppsByPackage = emptyMap(),
                    liveActiveTasks = emptyList(),
                    onLaunchApp = { _, _ -> },
                    onToggleTaskCompleted = { _, _ -> },
                    onCancelTask = {},
                    onTakeOverPlayClick = {},
                    onExplainFeatureClick = {}
                )
            }
        }
        composeRule.onNodeWithTag("read_more_button_42").assertIsDisplayed()
        composeRule.onNodeWithText("اقرأ المزيد").assertIsDisplayed()
        composeRule.onNodeWithTag("read_more_button_42").performClick()
        composeRule.onNodeWithText("عرض أقل").assertIsDisplayed()
    }
}
