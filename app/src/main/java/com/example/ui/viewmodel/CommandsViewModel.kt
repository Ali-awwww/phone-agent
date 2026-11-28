package com.example.ui.viewmodel

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ai.GeminiRepository
import com.example.data.ai.ModelResponseMetadata
import com.example.data.local.ActionLogRepository
import com.example.data.local.ActionStatus
import com.example.data.local.AgentTaskEntity
import com.example.data.local.AgentTaskType
import com.example.data.local.ChatHistoryRepository
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ChatMessageType
import com.example.data.local.ChatSender
import com.example.data.local.HabitLogEntity
import com.example.data.local.PhoneAgentDatabase
import com.example.data.local.UserMemoryEntity
import com.example.data.local.UserMemoryRepository
import com.example.habits.HabitLearningEngine
import com.example.habits.PredictiveSuggestion
import com.example.security.MemoryAndHabitPreferences
import java.util.Calendar
import com.example.executor.ChatIntentRouter
import com.example.executor.ChatRouteIntent
import com.example.executor.CommandExecutor
import com.example.executor.ReminderReceiver
import com.example.executor.TaskCancelResolution
import com.example.executor.TaskCancellationResolver
import com.example.files.FileCleanupBatch
import com.example.files.FileScanReport
import com.example.files.PhoneFileManager
import com.example.files.TrashItemRecord
import com.example.files.formatFileSize
import com.example.game.GeneralGameEngine
import com.example.model.AgentPlan
import com.example.model.AgentPlanStep
import com.example.model.StepActionType
import com.example.model.StepStatus
import com.example.model.StepVerificationStatus
import com.example.organizer.AppOrganizer
import com.example.organizer.InstalledAppInfo
import com.example.overlay.AgentExecutionController
import com.example.overlay.AgentFloatingControlManager
import com.example.overlay.FloatingControlCallbacks
import com.example.policy.AgentPolicy
import com.example.policy.AgentPromptRules
import com.example.policy.SensitiveActionGate
import com.example.policy.SensitiveActionRequest
import com.example.policy.SensitiveActionSource
import com.example.service.ScreenStateBridge
import com.example.verifier.ScreenVerifier
import com.example.voice.VoiceAssistantManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.BuildConfig
import com.example.MainActivity
import com.example.R
import com.example.chat.ChatTurn
import com.example.chat.ConversationMemory
import com.example.data.local.QueuedCommandDao
import com.example.data.local.QueuedCommandEntity
import com.example.data.local.QueuedCommandStatus
import com.example.executor.ModelRouteTier
import com.example.game.ForegroundAppPolicy
import com.example.network.NetworkConnectivityMonitor
import com.example.network.NetworkTier
import com.example.organizer.AppMatchResult
import com.example.organizer.AppNameMatcher
import com.example.security.DisconnectionCategory
import com.example.security.classifyModelError
import com.example.security.getActiveProjectId
import com.example.security.getEffectiveDebugSecret
import com.example.verifier.LocalScreenSummarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "CommandsViewModel"

enum class ErrorRetryType {
    CONNECTION_CHECK,
    COMMAND,
    VOICE,
    GAMEPLAY,
    MINI_CHAT
}

data class CompactErrorState(
    val titleAr: String,
    val summaryAr: String,
    val technicalDetails: String,
    val isAppCheckDebugError: Boolean = false,
    val retryCommand: String? = null,
    val retryType: ErrorRetryType = ErrorRetryType.CONNECTION_CHECK
)

sealed interface ModelConnectionStatus {
    data object NotChecked : ModelConnectionStatus
    data object Checking : ModelConnectionStatus
    data class Connected(val latencyMs: Long, val detailsAr: String) : ModelConnectionStatus
    data class Disconnected(
        val category: DisconnectionCategory,
        val exactError: String,
        val explanationAr: String
    ) : ModelConnectionStatus
}

sealed interface CommandsUiState {
    data object Idle : CommandsUiState
    data object Planning : CommandsUiState
    data class PlanActive(val plan: AgentPlan) : CommandsUiState
    data class Error(val message: String) : CommandsUiState
}

data class CommandsScreenState(
    val inputText: String = "",
    val isListening: Boolean = false,
    val partialTranscript: String = "",
    val voiceConversationMode: Boolean = true,
    val lastSpokenReply: String = "",
    val uiState: CommandsUiState = CommandsUiState.Idle,
    val pendingApprovalStep: AgentPlanStep? = null,
    val pendingApprovalPlan: AgentPlan? = null,
    val pendingApprovalNextStep: AgentPlanStep? = null,
    val pendingApprovalLogId: Long? = null,
    val pendingTaskCancelConfirmation: AgentTaskEntity? = null,
    val isExecuting: Boolean = false,
    val isBusyWorking: Boolean = false,
    val isGameplayRunning: Boolean = false,
    val isPaused: Boolean = false,
    val isHandedOver: Boolean = false,
    val statusNoticeAr: String = "",
    val showPlayFeatureDialog: Boolean = false,
    val showAppPickerForGameplay: Boolean = false,
    val executionTruthReportAr: String? = null,
    val modelConnectionStatus: ModelConnectionStatus = ModelConnectionStatus.NotChecked,
    val appCheckDebugToken: String = "",
    val activeProjectId: String = "",
    val isModelStatusExpanded: Boolean = false,
    val activeError: CompactErrorState? = null,
    val isErrorDetailsExpanded: Boolean = false,
    val lastModelTraceAr: String = "",
    val showFilePermissionDialog: Boolean = false,
    val pendingFileQuery: String? = null,
    val latestFileReport: FileScanReport? = null,
    val pendingFileBatches: List<FileCleanupBatch> = emptyList(),
    val currentFileBatchIndex: Int = 0,
    val showTrashDialog: Boolean = false,
    val trashItems: List<TrashItemRecord> = emptyList(),
    val liveDoingNowAr: String = "",
    val liveNextStepAr: String = "",
    val liveWaitingForUserAr: String = "",
    val isPrivacyModeEnabled: Boolean = false,
    val geminiElapsedSeconds: Int = 0,
    val geminiTimeoutSeconds: Int = (com.example.data.ai.TOTAL_GEMINI_COMMAND_TIMEOUT_MS / 1000L).toInt(),
    val pendingMorningRoutineApproval: Boolean = false,
    val activePredictiveSuggestion: PredictiveSuggestion? = null,
    val showMemoryHabitsDialog: Boolean = false,
    val predictiveLearningEnabled: Boolean = false,
    val anonymizedHabitGeminiConsent: Boolean = false,
    val morningRoutineApproved: Boolean = false,
    val networkTier: NetworkTier = NetworkTier.NORMAL,
    val queuedCommandsCount: Int = 0
) {
    val isAnyTaskRunning: Boolean
        get() = isBusyWorking || uiState is CommandsUiState.Planning || isExecuting || isGameplayRunning

    val currentPendingFileBatch: FileCleanupBatch?
        get() = pendingFileBatches.getOrNull(currentFileBatchIndex)
}

@OptIn(ExperimentalCoroutinesApi::class)
class CommandsViewModel(
    private val geminiRepository: GeminiRepository,
    private val commandExecutor: CommandExecutor,
    private val actionLogRepository: ActionLogRepository,
    private val appOrganizer: AppOrganizer,
    private val screenVerifier: ScreenVerifier,
    private val voiceManager: VoiceAssistantManager,
    private val chatHistoryRepository: ChatHistoryRepository,
    private val generalGameEngine: GeneralGameEngine,
    private val executionController: AgentExecutionController,
    private val floatingControlManager: AgentFloatingControlManager,
    private val appContext: Context,
    private val agentPolicy: AgentPolicy = AgentPolicy(),
    private val sensitiveActionGate: SensitiveActionGate = SensitiveActionGate(agentPolicy),
    private val phoneFileManager: PhoneFileManager = PhoneFileManager(
        context = appContext,
        actionLogRepository = actionLogRepository,
        agentPolicy = agentPolicy
    ),
    private val privacyPreferences: com.example.security.PrivacyPreferences = com.example.security.PrivacyPreferences(appContext),
    private val userMemoryRepository: UserMemoryRepository = PhoneAgentDatabase.getInstance(appContext).let { db ->
        UserMemoryRepository(
            memoryDao = db.userMemoryDao(),
            habitDao = db.habitLogDao(),
            summaryDao = db.conversationSummaryDao(),
            privacyPreferences = privacyPreferences
        )
    },
    private val memoryAndHabitPreferences: MemoryAndHabitPreferences = MemoryAndHabitPreferences(appContext),
    private val habitLearningEngine: HabitLearningEngine = HabitLearningEngine(
        appContext = appContext,
        userMemoryRepository = userMemoryRepository,
        actionLogDao = PhoneAgentDatabase.getInstance(appContext).actionLogDao(),
        memoryPrefs = memoryAndHabitPreferences,
        privacyPreferences = privacyPreferences,
        agentPolicy = agentPolicy
    ),
    private val networkConnectivityMonitor: NetworkConnectivityMonitor = NetworkConnectivityMonitor(appContext),
    private val queuedCommandDao: QueuedCommandDao = PhoneAgentDatabase.getInstance(appContext).queuedCommandDao()
) : ViewModel() {

    private val _state = MutableStateFlow(CommandsScreenState())
    val state: StateFlow<CommandsScreenState> = _state.asStateFlow()

    private val queueDrainMutex = Mutex()
    @Volatile
    private var previousObservedNetworkTier: NetworkTier = networkConnectivityMonitor.currentTier

    val memories: StateFlow<List<UserMemoryEntity>> = userMemoryRepository.allMemories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val habits: StateFlow<List<HabitLogEntity>> = userMemoryRepository.allHabits
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val isAccessibilityConnected: StateFlow<Boolean> = ScreenStateBridge.isServiceConnected

    private val chatPageLimit = MutableStateFlow(ChatHistoryRepository.DEFAULT_CHAT_PAGE_SIZE)

    val messages: StateFlow<List<ChatMessageEntity>> = chatPageLimit
        .flatMapLatest { limit -> chatHistoryRepository.observeRecentMessages(limit) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val hasOlderMessages: StateFlow<Boolean> = combine(
        chatHistoryRepository.messagesCountFlow,
        chatPageLimit
    ) { count, limit -> count > limit }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)

    fun loadOlderMessages() {
        chatPageLimit.value += ChatHistoryRepository.DEFAULT_CHAT_PAGE_SIZE
    }

    val activeTasks: StateFlow<List<AgentTaskEntity>> = chatHistoryRepository.activeTasksFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _installedAppsByPackage = MutableStateFlow<Map<String, InstalledAppInfo>>(emptyMap())
    val installedAppsByPackage: StateFlow<Map<String, InstalledAppInfo>> = _installedAppsByPackage.asStateFlow()

    private var activeTaskJob: Job? = null
    private var executionJob: Job? = null
    private var gameLoopJob: Job? = null
    private var geminiTimerJob: Job? = null
    private var approvalDeferred: CompletableDeferred<Boolean>? = null
    private var cachedInstalledApps: List<InstalledAppInfo> = emptyList()
    private var lastSubmittedCommand: String? = null
    private var lastRecordedAudio: Pair<ByteArray, String>? = null
    private var lastGameplayGoal: String? = null
    private var fileBatchRunMovedCount: Int = 0
    private var fileBatchRunSkippedCount: Int = 0
    private var fileBatchRunFreedBytes: Long = 0L
    private val fileBatchSucceededList = mutableListOf<String>()
    private val fileBatchUnverifiedList = mutableListOf<String>()
    private val fileBatchFailedList = mutableListOf<String>()
    private val conversationTurns = mutableListOf<ChatTurn>()

    private fun startGeminiProgressTimer(
        doingNowAr: String,
        nextStepAr: String,
        waitingAr: String
    ) {
        geminiTimerJob?.cancel()
        val timeoutSec = _state.value.geminiTimeoutSeconds
        _state.update {
            it.copy(
                liveDoingNowAr = doingNowAr,
                liveNextStepAr = nextStepAr,
                liveWaitingForUserAr = waitingAr,
                geminiElapsedSeconds = 1
            )
        }
        geminiTimerJob = viewModelScope.launch {
            var sec = 1
            while (isActive && sec < timeoutSec) {
                delay(1_000L)
                sec++
                _state.update { it.copy(geminiElapsedSeconds = sec) }
            }
        }
    }

    private fun stopGeminiProgressTimer() {
        geminiTimerJob?.cancel()
        geminiTimerJob = null
        _state.update { it.copy(geminiElapsedSeconds = 0) }
    }

    private fun recordConversationTurn(userMessage: String, agentReply: String) {
        if (privacyPreferences.isPrivacyModeEnabled()) {
            conversationTurns.clear()
            return
        }
        val cleanUser = userMessage.trim()
        val cleanReply = agentReply.trim()
        if (cleanUser.isNotEmpty()) {
            conversationTurns.add(ChatTurn("user", cleanUser))
        }
        if (cleanReply.isNotEmpty()) {
            conversationTurns.add(ChatTurn("model", cleanReply))
        }
        if (cleanUser.isEmpty() && cleanReply.isEmpty()) return

        viewModelScope.launch {
            processConversationWindowAndMilestones()
        }
    }

    internal suspend fun processConversationWindowAndMilestones(
        turnsToPeriodicThreshold: Int = UserMemoryRepository.PERIODIC_SUMMARY_INTERVAL_TURNS
    ) {
        if (privacyPreferences.isPrivacyModeEnabled()) {
            conversationTurns.clear()
            return
        }

        // 1) F18: الاقتطاع دائماً من الأقدم + تلخيص الأدوار القديمة في Room بدل حذفها صامتة
        if (conversationTurns.size > MAX_ACTIVE_CONVERSATION_TURNS) {
            val evictCount = (conversationTurns.size - KEEP_AFTER_TRUNCATION_TURNS).coerceAtLeast(2)
            val evicted = synchronized(conversationTurns) {
                if (conversationTurns.size > MAX_ACTIVE_CONVERSATION_TURNS) {
                    val slice = conversationTurns.take(evictCount)
                    repeat(evictCount) { conversationTurns.removeAt(0) }
                    slice
                } else {
                    emptyList()
                }
            }
            if (evicted.isNotEmpty()) {
                val evictedText = ConversationMemory.formatHistory(
                    turns = evicted,
                    maxTurns = evicted.size,
                    maxTotalChars = 3000
                )
                val summary = runCatching {
                    geminiRepository.summarizeConversationWithLite(
                        turnsText = evictedText,
                        isPeriodicMilestone = false
                    ).value
                }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: ConversationMemory.buildDeterministicTurnSummary(evicted)

                userMemoryRepository.saveConversationSummary(
                    summaryText = summary,
                    turnsCount = evicted.size,
                    summaryType = UserMemoryRepository.SUMMARY_TYPE_TRUNCATION,
                    alsoSaveAsMemoryFact = false
                )
            }
        }

        // 2) S2-1: ملخص دوري بالنموذج الخفيف (Lite) كل 20 دور محادثة، يُحفظ ويُحقن كسياق مختصر
        val turnsSinceSummary = memoryAndHabitPreferences.incrementTurnsSinceLastSummary()
        if (turnsSinceSummary >= turnsToPeriodicThreshold) {
            val snapshotTurns = synchronized(conversationTurns) { conversationTurns.toList() }
            if (snapshotTurns.isNotEmpty()) {
                val turnsText = ConversationMemory.formatHistory(
                    turns = snapshotTurns,
                    maxTurns = 20,
                    maxTotalChars = 3800
                )
                val periodicSummary = runCatching {
                    geminiRepository.summarizeConversationWithLite(
                        turnsText = turnsText,
                        isPeriodicMilestone = true
                    ).value
                }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: ConversationMemory.buildDeterministicTurnSummary(snapshotTurns)

                userMemoryRepository.saveConversationSummary(
                    summaryText = periodicSummary,
                    turnsCount = snapshotTurns.size,
                    summaryType = UserMemoryRepository.SUMMARY_TYPE_PERIODIC_20,
                    alsoSaveAsMemoryFact = true
                )
                memoryAndHabitPreferences.setTurnsSinceLastSummary(0)
            }
        }
    }

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_PACKAGE_ADDED ||
                action == Intent.ACTION_PACKAGE_REMOVED ||
                action == Intent.ACTION_PACKAGE_REPLACED
            ) {
                refreshInstalledAppsCache(forceRefresh = true)
            }
        }
    }

    init {
        val debugToken = if (BuildConfig.DEBUG) {
            getEffectiveDebugSecret(appContext)
        } else ""
        val projId = getActiveProjectId(appContext)
        val initialTier = networkConnectivityMonitor.currentTier
        previousObservedNetworkTier = initialTier
        _state.update {
            it.copy(
                appCheckDebugToken = debugToken,
                activeProjectId = projId,
                isPrivacyModeEnabled = privacyPreferences.isPrivacyModeEnabled(),
                predictiveLearningEnabled = memoryAndHabitPreferences.isPredictiveLearningEnabled(),
                anonymizedHabitGeminiConsent = memoryAndHabitPreferences.isAnonymizedHabitGeminiConsent(),
                morningRoutineApproved = memoryAndHabitPreferences.isMorningRoutineApproved(),
                networkTier = initialTier,
                geminiTimeoutSeconds = resolveTimeoutSeconds(initialTier),
                modelConnectionStatus = if (initialTier == NetworkTier.OFFLINE) {
                    ModelConnectionStatus.Disconnected(
                        category = DisconnectionCategory.NETWORK_ERROR,
                        exactError = "Offline (ConnectivityManager)",
                        explanationAr = "لا يوجد اتصال بالإنترنت حالياً — الأوامر المحلية تعمل فوراً، وأوامر Gemini تُحفظ في الطابور."
                    )
                } else {
                    it.modelConnectionStatus
                }
            )
        }
        viewModelScope.launch {
            networkConnectivityMonitor.networkTierFlow.collect { tier ->
                val wasOffline = previousObservedNetworkTier == NetworkTier.OFFLINE
                previousObservedNetworkTier = tier
                _state.update { current ->
                    val nextStatus = when {
                        tier == NetworkTier.OFFLINE -> ModelConnectionStatus.Disconnected(
                            category = DisconnectionCategory.NETWORK_ERROR,
                            exactError = "Offline (ConnectivityManager)",
                            explanationAr = "لا يوجد اتصال بالإنترنت حالياً — الأوامر المحلية تعمل فوراً، وأوامر Gemini تُحفظ في الطابور."
                        )
                        current.modelConnectionStatus is ModelConnectionStatus.Disconnected &&
                            current.modelConnectionStatus.category == DisconnectionCategory.NETWORK_ERROR ->
                            ModelConnectionStatus.NotChecked
                        else -> current.modelConnectionStatus
                    }
                    current.copy(
                        networkTier = tier,
                        geminiTimeoutSeconds = resolveTimeoutSeconds(tier),
                        modelConnectionStatus = nextStatus
                    )
                }
                if (wasOffline && tier != NetworkTier.OFFLINE) {
                    drainPendingQueuedCommands()
                }
            }
        }
        viewModelScope.launch {
            queuedCommandDao.observePendingCount().collect { count ->
                _state.update { it.copy(queuedCommandsCount = count) }
            }
        }
        viewModelScope.launch {
            privacyPreferences.privacyModeEnabled.collect { isEnabled ->
                _state.update {
                    it.copy(
                        isPrivacyModeEnabled = isEnabled,
                        activePredictiveSuggestion = if (isEnabled) null else it.activePredictiveSuggestion
                    )
                }
                if (isEnabled) {
                    conversationTurns.clear()
                    ScreenStateBridge.clearSnapshot()
                }
            }
        }
        viewModelScope.launch {
            memoryAndHabitPreferences.predictiveLearningEnabled.collect { enabled ->
                _state.update {
                    it.copy(
                        predictiveLearningEnabled = enabled,
                        activePredictiveSuggestion = if (!enabled) null else it.activePredictiveSuggestion
                    )
                }
                if (enabled) {
                    refreshPredictiveSuggestion()
                }
            }
        }
        viewModelScope.launch {
            memoryAndHabitPreferences.anonymizedHabitGeminiConsent.collect { consented ->
                _state.update { it.copy(anonymizedHabitGeminiConsent = consented) }
            }
        }
        viewModelScope.launch {
            memoryAndHabitPreferences.morningRoutineApproved.collect { approved ->
                _state.update { it.copy(morningRoutineApproved = approved) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            hydrateConversationWindowFromRoom()
            val installed = runCatching { ensureInstalledAppsLoaded() }.getOrDefault(emptyList())
            runCatching { habitLearningEngine.syncHabits(installed) }
            runCatching { refreshPredictiveSuggestion() }
            if (networkConnectivityMonitor.currentTier != NetworkTier.OFFLINE) {
                runCatching { drainPendingQueuedCommands() }
            }
        }
        observeInstalledApps()
        registerPackageChangeReceiver()
        observeExecutionPauseState()
        observeActiveTaskBridge()
        sensitiveActionGate.setApprovalHandler { request ->
            requestUserApprovalViaUi(request)
        }
        if (BuildConfig.DEBUG && initialTier != NetworkTier.OFFLINE) {
            checkModelConnection(isInitialSelfCheck = true)
        }
    }

    private suspend fun hydrateConversationWindowFromRoom() {
        if (privacyPreferences.isPrivacyModeEnabled()) return
        val recentStored = runCatching {
            chatHistoryRepository.observeRecentMessages(MAX_ACTIVE_CONVERSATION_TURNS).first()
        }.getOrDefault(emptyList())
        if (recentStored.isEmpty()) return
        synchronized(conversationTurns) {
            if (conversationTurns.isEmpty()) {
                recentStored.forEach { msg ->
                    val role = if (msg.sender == ChatSender.USER.name) "user" else "model"
                    if (msg.text.isNotBlank()) {
                        conversationTurns.add(ChatTurn(role, msg.text))
                    }
                }
            }
        }
    }

    fun toggleModelStatusExpanded() {
        _state.update { it.copy(isModelStatusExpanded = !it.isModelStatusExpanded) }
    }

    fun toggleErrorDetailsExpanded() {
        _state.update { it.copy(isErrorDetailsExpanded = !it.isErrorDetailsExpanded) }
    }

    fun dismissActiveError() {
        clearCachedRecordedAudio()
        _state.update {
            it.copy(
                activeError = null,
                isErrorDetailsExpanded = false,
                uiState = if (it.uiState is CommandsUiState.Error) CommandsUiState.Idle else it.uiState
            )
        }
    }

    fun retryActiveError() {
        val error = _state.value.activeError
        _state.update {
            it.copy(
                activeError = null,
                isErrorDetailsExpanded = false,
                uiState = if (it.uiState is CommandsUiState.Error) CommandsUiState.Idle else it.uiState
            )
        }
        when (error?.retryType) {
            ErrorRetryType.COMMAND -> {
                clearCachedRecordedAudio()
                val cmd = error.retryCommand ?: lastSubmittedCommand
                if (!cmd.isNullOrBlank()) {
                    analyzeAndPlanCommand(cmd, addUserBubble = false)
                } else {
                    checkModelConnection()
                }
            }
            ErrorRetryType.VOICE -> {
                val audio = lastRecordedAudio
                if (audio != null) {
                    processRecordedAudio(audio.first, audio.second)
                } else {
                    checkModelConnection()
                }
            }
            ErrorRetryType.GAMEPLAY -> {
                clearCachedRecordedAudio()
                analyzeAndPlanCommand(lastGameplayGoal ?: "العب لعبة", addUserBubble = false)
            }
            ErrorRetryType.MINI_CHAT -> {
                clearCachedRecordedAudio()
                val cmd = error.retryCommand
                if (!cmd.isNullOrBlank()) {
                    submitMiniChatCommand(cmd)
                }
            }
            ErrorRetryType.CONNECTION_CHECK, null -> {
                clearCachedRecordedAudio()
                checkModelConnection()
            }
        }
    }

    fun checkModelConnection(isInitialSelfCheck: Boolean = false) {
        viewModelScope.launch {
            val currentTier = networkConnectivityMonitor.refreshNow()
            if (currentTier == NetworkTier.OFFLINE) {
                val offlineMsg = "لا يوجد اتصال بالإنترنت حالياً — الأوامر المحلية تعمل فوراً، وأوامر Gemini تُحفظ في الطابور."
                _state.update {
                    it.copy(
                        networkTier = NetworkTier.OFFLINE,
                        geminiTimeoutSeconds = resolveTimeoutSeconds(NetworkTier.OFFLINE),
                        statusNoticeAr = "",
                        modelConnectionStatus = ModelConnectionStatus.Disconnected(
                            category = DisconnectionCategory.NETWORK_ERROR,
                            exactError = "Offline (ConnectivityManager)",
                            explanationAr = offlineMsg
                        ),
                        activeError = if (!isInitialSelfCheck) {
                            CompactErrorState(
                                titleAr = DisconnectionCategory.NETWORK_ERROR.titleAr,
                                summaryAr = offlineMsg,
                                technicalDetails = "ConnectivityManager reports OFFLINE",
                                retryType = ErrorRetryType.CONNECTION_CHECK
                            )
                        } else {
                            it.activeError
                        }
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    networkTier = currentTier,
                    geminiTimeoutSeconds = resolveTimeoutSeconds(currentTier),
                    modelConnectionStatus = ModelConnectionStatus.Checking,
                    activeError = if (!isInitialSelfCheck) null else it.activeError,
                    isErrorDetailsExpanded = false
                )
            }
            val startTime = System.currentTimeMillis()
            try {
                val (_, details) = geminiRepository.pingModelTest(
                    onStatusUpdate = { statusMsg ->
                        _state.update { it.copy(statusNoticeAr = statusMsg) }
                    }
                )
                val latency = System.currentTimeMillis() - startTime
                Log.i("ModelConnection", "Self-check SUCCEEDED in ${latency}ms: $details (initial=$isInitialSelfCheck)")
                _state.update {
                    it.copy(
                        modelConnectionStatus = ModelConnectionStatus.Connected(latency, details),
                        statusNoticeAr = "",
                        activeError = null,
                        isErrorDetailsExpanded = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val classification = classifyModelError(e)
                Log.e(
                    "ModelConnection",
                    "Self-check FAILED: category=${classification.category.name}, exactMsg=${classification.exactErrorMessage}, explanation=${classification.explanationAr}, initial=$isInitialSelfCheck",
                    e
                )
                val errorCard = if (!isInitialSelfCheck) {
                    CompactErrorState(
                        titleAr = classification.category.titleAr,
                        summaryAr = classification.explanationAr,
                        technicalDetails = classification.exactErrorMessage,
                        isAppCheckDebugError = classification.category == DisconnectionCategory.APP_CHECK_INVALID,
                        retryType = ErrorRetryType.CONNECTION_CHECK
                    )
                } else {
                    null
                }
                _state.update {
                    it.copy(
                        statusNoticeAr = "",
                        modelConnectionStatus = ModelConnectionStatus.Disconnected(
                            category = classification.category,
                            exactError = classification.exactErrorMessage,
                            explanationAr = classification.explanationAr
                        ),
                        activeError = errorCard ?: it.activeError,
                        isErrorDetailsExpanded = false
                    )
                }
            }
        }
    }

    private fun handleGeminiFailure(
        contextTag: String,
        e: Exception,
        retryType: ErrorRetryType = ErrorRetryType.CONNECTION_CHECK,
        retryCommand: String? = null
    ) {
        Log.e(TAG, "$contextTag failed", e)
        val classification = classifyModelError(e)
        val compactError = CompactErrorState(
            titleAr = classification.category.titleAr,
            summaryAr = classification.explanationAr,
            technicalDetails = classification.exactErrorMessage,
            isAppCheckDebugError = classification.category == DisconnectionCategory.APP_CHECK_INVALID,
            retryCommand = retryCommand,
            retryType = retryType
        )
        _state.update {
            it.copy(
                isBusyWorking = false,
                statusNoticeAr = "",
                uiState = CommandsUiState.Idle,
                activeError = compactError,
                isErrorDetailsExpanded = false,
                modelConnectionStatus = ModelConnectionStatus.Disconnected(
                    category = classification.category,
                    exactError = classification.exactErrorMessage,
                    explanationAr = classification.explanationAr
                )
            )
        }
        speakIfEnabled(classification.category.titleAr)
    }

    private fun observeExecutionPauseState() {
        viewModelScope.launch {
            executionController.isPaused.collect { paused ->
                _state.update { it.copy(isPaused = paused) }
            }
        }
        viewModelScope.launch {
            executionController.isHandedOver.collect { handedOver ->
                _state.update { it.copy(isHandedOver = handedOver) }
            }
        }
    }

    private fun observeActiveTaskBridge() {
        viewModelScope.launch {
            _state
                .map { it.isAnyTaskRunning }
                .distinctUntilChanged()
                .collect { active ->
                    ScreenStateBridge.setTaskActive(active)
                }
        }
    }

    private fun observeInstalledApps() {
        viewModelScope.launch {
            runCatching {
                appOrganizer.installedAppsFlow.collect { loaded ->
                    cachedInstalledApps = loaded
                    _installedAppsByPackage.value = loaded.associateBy { it.packageName }
                }
            }
        }
    }

    private fun registerPackageChangeReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                packageChangeReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        }
    }

    fun refreshInstalledAppsCache(forceRefresh: Boolean = true) {
        viewModelScope.launch {
            runCatching {
                if (forceRefresh) {
                    val refreshed = appOrganizer.refreshInstalledPackagesCache()
                    cachedInstalledApps = refreshed
                    _installedAppsByPackage.value = refreshed.associateBy { it.packageName }
                } else {
                    ensureInstalledAppsLoaded()
                }
            }
        }
    }

    private suspend fun ensureInstalledAppsLoaded(): List<InstalledAppInfo> {
        if (cachedInstalledApps.isEmpty()) {
            val loaded = appOrganizer.installedAppsFlow.first()
            cachedInstalledApps = loaded
            _installedAppsByPackage.value = loaded.associateBy { it.packageName }
        }
        return cachedInstalledApps
    }

    fun updateInputText(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    fun toggleVoiceConversationMode() {
        val next = !_state.value.voiceConversationMode
        _state.update { it.copy(voiceConversationMode = next) }
        if (!next) {
            voiceManager.stopSpeaking()
        }
    }

    fun startVoiceCapture() {
        if (_state.value.isListening) {
            stopVoiceCapture()
            return
        }
        _state.update { it.copy(isListening = true, partialTranscript = "") }
        voiceManager.startListening(
            onPartialResult = { partial ->
                _state.update { it.copy(partialTranscript = partial, inputText = partial) }
            },
            onFinalTranscript = { transcript ->
                _state.update {
                    it.copy(
                        isListening = false,
                        partialTranscript = "",
                        inputText = transcript
                    )
                }
                analyzeAndPlanCommand(transcript)
            },
            onAudioRecordedFallback = { bytes, mime ->
                processRecordedAudio(bytes, mime)
            },
            onError = { err ->
                _state.update {
                    it.copy(
                        isListening = false,
                        partialTranscript = "",
                        uiState = CommandsUiState.Idle,
                        activeError = CompactErrorState(
                            titleAr = "تعذر الاستماع الصوتي",
                            summaryAr = err,
                            technicalDetails = "",
                            retryType = ErrorRetryType.VOICE
                        )
                    )
                }
            }
        )
    }

    fun stopVoiceCapture() {
        voiceManager.stopListening(
            onAudioRecordedFallback = { bytes, mime ->
                processRecordedAudio(bytes, mime)
            },
            onError = { err ->
                _state.update {
                    it.copy(
                        isListening = false,
                        uiState = CommandsUiState.Idle,
                        activeError = CompactErrorState(
                            titleAr = "تعذر إكمال التسجيل الصوتي",
                            summaryAr = err,
                            technicalDetails = "",
                            retryType = ErrorRetryType.VOICE
                        )
                    )
                }
            }
        )
        _state.update { it.copy(isListening = false) }
    }

    private fun clearCachedRecordedAudio() {
        lastRecordedAudio?.first?.fill(0)
        lastRecordedAudio = null
    }

    internal fun hasCachedRecordedAudioForTest(): Boolean = lastRecordedAudio != null

    internal fun processRecordedAudio(
        bytes: ByteArray,
        mimeType: String,
        fromMiniChat: Boolean = false
    ) {
        lastRecordedAudio = bytes to mimeType
        activeTaskJob?.cancel()
        activeTaskJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isListening = false,
                    isBusyWorking = true,
                    uiState = CommandsUiState.Planning,
                    activeError = null,
                    isErrorDetailsExpanded = false,
                    statusNoticeAr = "جارٍ تحليل التسجيل الصوتي... ${AgentPromptRules.WAIT_THINKING_NOTICE_AR}",
                    executionTruthReportAr = null
                )
            }
            try {
                if (networkConnectivityMonitor.refreshNow() == NetworkTier.OFFLINE) {
                    clearCachedRecordedAudio()
                    val offlineAudioMsg = "أنت غير متصل بالإنترنت حالياً، لذلك تعذّر تفريغ التسجيل الصوتي عبر Gemini. استخدم أوامر مثل «افتح واتساب» أو «شغل الواي فاي» عبر الكتابة أو بعد عودة الشبكة."
                    chatHistoryRepository.addAgentMessage(offlineAudioMsg, ChatMessageType.TEXT)
                    if (fromMiniChat) {
                        floatingControlManager.showMiniChatReply(offlineAudioMsg)
                    }
                    _state.update {
                        it.copy(
                            isBusyWorking = false,
                            statusNoticeAr = "",
                            lastSpokenReply = offlineAudioMsg,
                            uiState = CommandsUiState.Idle
                        )
                    }
                    speakIfEnabled(offlineAudioMsg)
                    return@launch
                }

                startGeminiProgressTimer(
                    doingNowAr = "أحلّل التسجيل الصوتي وأحوّله إلى خطة واضحة...",
                    nextStepAr = "عرض نص الأمر وخطة التنفيذ لمراجعتك",
                    waitingAr = "انتظار استجابة النموذج (يمكنك الإيقاف في أي لحظة)"
                )
                val installed = ensureInstalledAppsLoaded()
                val appLabels = installed.map { it.label }
                val audioMeta = geminiRepository.transcribeAndPlanAudioWithMeta(
                    audioBytes = bytes,
                    mimeType = mimeType,
                    installedAppNames = appLabels,
                    onStatusUpdate = { statusMsg ->
                        _state.update { it.copy(statusNoticeAr = statusMsg, liveDoingNowAr = statusMsg) }
                    }
                )
                clearCachedRecordedAudio()
                stopGeminiProgressTimer()
                val (transcript, rawPlan) = audioMeta.value
                recordModelRouteUsage(audioMeta, transcript)
                if (transcript.isNotBlank()) {
                    chatHistoryRepository.addUserMessage(transcript)
                }

                val commandPolicy = agentPolicy.evaluateCommand(transcript, appLabels)
                if (!commandPolicy.allowed) {
                    val logDetails = if (privacyPreferences.isPrivacyModeEnabled()) {
                        "السبب: ${commandPolicy.reasonAr}"
                    } else {
                        "${com.example.security.SensitiveDataScrubber.scrub(transcript)} | السبب: ${commandPolicy.reasonAr}"
                    }
                    actionLogRepository.logAction(
                        title = "رفض أمر صوتي وفق سياسة الوكيل",
                        details = logDetails,
                        status = ActionStatus.CANCELLED,
                        actionType = "POLICY_REJECTED_AUDIO"
                    )
                    recordConversationTurn(transcript, commandPolicy.reasonAr)
                    chatHistoryRepository.addAgentMessage(commandPolicy.reasonAr, ChatMessageType.TEXT)
                    if (fromMiniChat) {
                        floatingControlManager.showMiniChatReply(commandPolicy.reasonAr)
                    }
                    _state.update {
                        it.copy(
                            inputText = "",
                            isBusyWorking = false,
                            statusNoticeAr = "",
                            lastSpokenReply = commandPolicy.reasonAr,
                            uiState = CommandsUiState.Idle
                        )
                    }
                    speakIfEnabled(commandPolicy.reasonAr)
                    return@launch
                }

                val route = ChatIntentRouter.classifyIntent(transcript, appLabels)
                if (route !is ChatRouteIntent.ExecuteCommandPlan) {
                    handleNonPlanIntent(route, installed, transcript)
                    if (fromMiniChat) {
                        floatingControlManager.showMiniChatReply(_state.value.lastSpokenReply.ifBlank { "تم تنفيذ الأمر الصوتي." })
                    }
                    _state.update { it.copy(inputText = "", isBusyWorking = false, statusNoticeAr = "") }
                    return@launch
                }

                val planPolicy = agentPolicy.validatePlan(rawPlan)
                if (!planPolicy.allowed) {
                    val logDetails = if (privacyPreferences.isPrivacyModeEnabled()) {
                        "السبب: ${planPolicy.reasonAr}"
                    } else {
                        "${com.example.security.SensitiveDataScrubber.scrub(transcript)} | السبب: ${planPolicy.reasonAr}"
                    }
                    actionLogRepository.logAction(
                        title = "رفض خطة صوتية غير مطابقة للسياسة",
                        details = logDetails,
                        status = ActionStatus.CANCELLED,
                        actionType = "POLICY_REJECTED_PLAN"
                    )
                    recordConversationTurn(transcript, planPolicy.reasonAr)
                    chatHistoryRepository.addAgentMessage(planPolicy.reasonAr, ChatMessageType.TEXT)
                    if (fromMiniChat) {
                        floatingControlManager.showMiniChatReply(planPolicy.reasonAr)
                    }
                    _state.update {
                        it.copy(
                            inputText = "",
                            isBusyWorking = false,
                            statusNoticeAr = "",
                            lastSpokenReply = planPolicy.reasonAr,
                            uiState = CommandsUiState.Idle
                        )
                    }
                    speakIfEnabled(planPolicy.reasonAr)
                    return@launch
                }

                val groundedPlan = screenVerifier.groundPlanAgainstReality(rawPlan, installed)
                val planSummaryMsg = buildPlanChatSummary(groundedPlan)
                recordConversationTurn(transcript, planSummaryMsg)
                chatHistoryRepository.addAgentMessage(planSummaryMsg, ChatMessageType.PLAN)
                if (fromMiniChat) {
                    floatingControlManager.showMiniChatReply(groundedPlan.spokenReplyAr.ifBlank { planSummaryMsg })
                }

                _state.update {
                    it.copy(
                        inputText = "",
                        isBusyWorking = false,
                        statusNoticeAr = "",
                        lastSpokenReply = groundedPlan.spokenReplyAr,
                        uiState = CommandsUiState.PlanActive(groundedPlan)
                    )
                }
                speakIfEnabled(groundedPlan.spokenReplyAr)
            } catch (e: TimeoutCancellationException) {
                stopGeminiProgressTimer()
                _state.update {
                    it.copy(
                        isBusyWorking = false,
                        statusNoticeAr = "",
                        liveDoingNowAr = "",
                        liveNextStepAr = "",
                        liveWaitingForUserAr = "",
                        uiState = CommandsUiState.Idle,
                        activeError = CompactErrorState(
                            titleAr = "انتهت مهلة تحليل الصوت",
                            summaryAr = "استغرق تحليل الصوت وقتاً أطول من المهلة الإجمالية (${it.geminiTimeoutSeconds} ثانية). اضغط إعادة المحاولة.",
                            technicalDetails = e.message ?: "TimeoutCancellationException",
                            retryType = ErrorRetryType.VOICE
                        )
                    )
                }
            } catch (e: CancellationException) {
                clearCachedRecordedAudio()
                stopGeminiProgressTimer()
                throw e
            } catch (e: Exception) {
                stopGeminiProgressTimer()
                if (fromMiniChat) {
                    floatingControlManager.showMiniChatReply("تعذر تحليل الصوت حالياً، حاول مجدداً.")
                }
                handleGeminiFailure("Audio planning", e, retryType = ErrorRetryType.VOICE)
            }
        }
    }

    fun analyzeAndPlanCommand(
        commandOverride: String? = null,
        addUserBubble: Boolean = true
    ) {
        val command = (commandOverride ?: _state.value.inputText).trim()
        if (command.isEmpty()) {
            _state.update {
                it.copy(
                    activeError = CompactErrorState(
                        titleAr = "أدخل أمراً أولاً",
                        summaryAr = "اكتب رسالتك أو اضغط زر الميكروفون للتحدث.",
                        technicalDetails = "",
                        retryType = ErrorRetryType.CONNECTION_CHECK
                    )
                )
            }
            return
        }

        lastSubmittedCommand = command
        activeTaskJob?.cancel()
        executionJob?.cancel()
        gameLoopJob?.cancel()
        executionController.reset()

        activeTaskJob = viewModelScope.launch {
            if (commandOverride == null) {
                _state.update { it.copy(inputText = "") }
            }
            executeCommandInternal(
                command = command,
                addUserBubble = addUserBubble,
                allowQueueWhenOffline = true
            )
        }
    }

    internal suspend fun executeCommandInternal(
        command: String,
        addUserBubble: Boolean = true,
        allowQueueWhenOffline: Boolean = true
    ): Boolean {
        _state.update {
            it.copy(
                uiState = CommandsUiState.Idle,
                pendingApprovalStep = null,
                pendingApprovalPlan = null,
                isExecuting = false,
                isGameplayRunning = false,
                isBusyWorking = true,
                activeError = null,
                isErrorDetailsExpanded = false,
                statusNoticeAr = "يفكر الوكيل ويتحقق من الشاشة الحقيقية... ${AgentPromptRules.WAIT_THINKING_NOTICE_AR}",
                executionTruthReportAr = null
            )
        }

        return try {
            if (addUserBubble) {
                chatHistoryRepository.addUserMessage(command)
            }
            val installed = ensureInstalledAppsLoaded()
            val appLabels = installed.map { it.label }

            // 1. فحص الممنوعات الصارمة (كلمات مرور، بصمة، دفع، حذف بيانات، تحويل أموال...)
            val initialPolicy = agentPolicy.evaluateCommand(command, appLabels)
            if (!initialPolicy.allowed) {
                val reason = initialPolicy.reasonAr
                val logDetails = if (privacyPreferences.isPrivacyModeEnabled()) {
                    "السبب: $reason"
                } else {
                    "${com.example.security.SensitiveDataScrubber.scrub(command)} | السبب: $reason"
                }
                actionLogRepository.logAction(
                    title = "رفض أمر وفق سياسة الوكيل",
                    details = logDetails,
                    status = ActionStatus.CANCELLED,
                    actionType = "POLICY_REJECTED"
                )
                recordConversationTurn(command, reason)
                chatHistoryRepository.addAgentMessage(reason, ChatMessageType.TEXT)
                _state.update {
                    it.copy(
                        isBusyWorking = false,
                        statusNoticeAr = "",
                        lastSpokenReply = reason,
                        uiState = CommandsUiState.Idle
                    )
                }
                speakIfEnabled(reason)
                return false
            }

            when (val route = ChatIntentRouter.classifyIntent(command, appLabels)) {
                is ChatRouteIntent.ExecuteCommandPlan -> {
                    val currentTier = networkConnectivityMonitor.refreshNow()
                    if (currentTier == NetworkTier.OFFLINE && allowQueueWhenOffline) {
                        enqueueOfflineCommand(
                            commandText = command,
                            reasonAr = "الأوامر المحلية مثل فتح التطبيقات والمنبه والتذكير والواي فاي والاتصال تعمل فوراً بدون شبكة."
                        )
                        _state.update {
                            it.copy(
                                isBusyWorking = false,
                                statusNoticeAr = "",
                                liveDoingNowAr = "",
                                liveNextStepAr = "",
                                liveWaitingForUserAr = "",
                                uiState = CommandsUiState.Idle
                            )
                        }
                        return true
                    }

                    _state.update {
                        it.copy(
                            uiState = CommandsUiState.Planning,
                            statusNoticeAr = "أجهّز لك خطة التنفيذ خطوة بخطوة... ${AgentPromptRules.WAIT_THINKING_NOTICE_AR}"
                        )
                    }
                    startGeminiProgressTimer(
                        doingNowAr = "أفحص طلبك وأجهّز خطة العمل عبر Gemini...",
                        nextStepAr = "عرض الخطوات المرقّمة ومراجعة أي إجراء حساس",
                        waitingAr = "انتظار استجابة النموذج (يمكنك الإيقاف في أي لحظة)"
                    )
                    val rawScreenSummary = if (privacyPreferences.isPrivacyModeEnabled()) {
                        ScreenStateBridge.getService()?.captureCurrentScreen()?.toMinimalPrivacyPromptText()
                            ?: "سياق مقتضب (وضع الخصوصية مفعّل)"
                    } else {
                        ScreenStateBridge.getService()?.captureCurrentScreen()
                            ?.toCompactPromptText()
                            ?: "داخل تطبيق وكيل الهاتف (خدمة إمكانية الوصول غير مفعلة حالياً)"
                    }

                    val memoryContext = if (privacyPreferences.isPrivacyModeEnabled()) {
                        ""
                    } else {
                        userMemoryRepository.buildConciseMemoryContext(600)
                    }
                    val enrichedScreenSummary = buildString {
                        append(AgentPromptRules.enrichPlanScreenSummary(rawScreenSummary))
                        if (memoryContext.isNotBlank()) {
                            appendLine()
                            append(memoryContext)
                        }
                    }.trim()

                    val planMeta = geminiRepository.generateCommandPlanWithMeta(
                        userCommand = command,
                        installedAppNames = appLabels,
                        currentScreenSummary = enrichedScreenSummary,
                        onStatusUpdate = { statusMsg ->
                            _state.update { it.copy(statusNoticeAr = statusMsg, liveDoingNowAr = statusMsg) }
                        }
                    )
                    stopGeminiProgressTimer()
                    recordModelRouteUsage(planMeta, command)
                    val rawPlan = planMeta.value

                    val planPolicy = agentPolicy.validatePlan(rawPlan)
                    if (!planPolicy.allowed) {
                        val logDetails = if (privacyPreferences.isPrivacyModeEnabled()) {
                            "السبب: ${planPolicy.reasonAr}"
                        } else {
                            "${com.example.security.SensitiveDataScrubber.scrub(command)} | السبب: ${planPolicy.reasonAr}"
                        }
                        actionLogRepository.logAction(
                            title = "رفض خطة Gemini وفق سياسة الوكيل",
                            details = logDetails,
                            status = ActionStatus.CANCELLED,
                            actionType = "POLICY_REJECTED_PLAN"
                        )
                        recordConversationTurn(command, planPolicy.reasonAr)
                        chatHistoryRepository.addAgentMessage(planPolicy.reasonAr, ChatMessageType.TEXT)
                        _state.update {
                            it.copy(
                                isBusyWorking = false,
                                statusNoticeAr = "",
                                liveDoingNowAr = "",
                                liveNextStepAr = "",
                                liveWaitingForUserAr = "",
                                lastSpokenReply = planPolicy.reasonAr,
                                uiState = CommandsUiState.Idle
                            )
                        }
                        speakIfEnabled(planPolicy.reasonAr)
                        return false
                    }

                    val groundedPlan = screenVerifier.groundPlanAgainstReality(rawPlan, installed)
                    val planSummary = buildPlanChatSummary(groundedPlan)
                    recordConversationTurn(command, planSummary)
                    chatHistoryRepository.addAgentMessage(
                        planSummary,
                        ChatMessageType.PLAN
                    )

                    _state.update {
                        it.copy(
                            isBusyWorking = false,
                            statusNoticeAr = "",
                            liveDoingNowAr = "",
                            liveNextStepAr = "",
                            liveWaitingForUserAr = "",
                            lastSpokenReply = groundedPlan.spokenReplyAr,
                            uiState = CommandsUiState.PlanActive(groundedPlan)
                        )
                    }
                    speakIfEnabled(groundedPlan.spokenReplyAr)
                    true
                }

                else -> {
                    handleNonPlanIntent(route, installed, command, allowQueueWhenOffline = allowQueueWhenOffline)
                    _state.update {
                        it.copy(
                            isBusyWorking = false,
                            statusNoticeAr = "",
                            liveDoingNowAr = "",
                            liveNextStepAr = "",
                            liveWaitingForUserAr = ""
                        )
                    }
                    true
                }
            }
        } catch (e: TimeoutCancellationException) {
            stopGeminiProgressTimer()
            _state.update {
                it.copy(
                    isBusyWorking = false,
                    statusNoticeAr = "",
                    liveDoingNowAr = "",
                    liveNextStepAr = "",
                    liveWaitingForUserAr = "",
                    uiState = CommandsUiState.Idle,
                    activeError = CompactErrorState(
                        titleAr = "انتهت مهلة الاتصال",
                        summaryAr = "استغرقت الاستجابة وقتاً أطول من المهلة الإجمالية (${it.geminiTimeoutSeconds} ثانية). أعد المحاولة الآن.",
                        technicalDetails = e.message ?: "TimeoutCancellationException",
                        retryCommand = command,
                        retryType = ErrorRetryType.COMMAND
                    )
                )
            }
            false
        } catch (e: CancellationException) {
            stopGeminiProgressTimer()
            throw e
        } catch (e: Exception) {
            stopGeminiProgressTimer()
            handleGeminiFailure(
                contextTag = "Chat command",
                e = e,
                retryType = ErrorRetryType.COMMAND,
                retryCommand = command
            )
            false
        }
    }

    private suspend fun handleNonPlanIntent(
        route: ChatRouteIntent,
        installedApps: List<InstalledAppInfo>,
        rawCommand: String? = null,
        allowQueueWhenOffline: Boolean = true
    ) {
        val cmdText = rawCommand.orEmpty()
        when (route) {
            is ChatRouteIntent.ShowApps -> handleShowApps(route, installedApps, cmdText)
            ChatRouteIntent.ReadScreen -> handleReadScreen(installedApps, cmdText)
            ChatRouteIntent.ShowActionHistory -> handleShowActionHistory(cmdText)
            ChatRouteIntent.MorningRoutine -> handleMorningRoutine(installedApps, cmdText)
            ChatRouteIntent.SummarizeMyDay -> handleSummarizeMyDay(installedApps, cmdText)
            ChatRouteIntent.PromptReminderDetails -> handlePromptReminderDetails(cmdText)
            is ChatRouteIntent.CreateTaskOrReminder -> handleCreateTaskOrReminder(route, cmdText)
            ChatRouteIntent.ListTasksAndReminders -> handleListTasks(cmdText)
            is ChatRouteIntent.CancelTaskOrReminder -> handleCancelTaskByQuery(route.queryText, cmdText)
            is ChatRouteIntent.StartGameplay -> startGameplayGoal(route.goalText)
            is ChatRouteIntent.FileManagement -> handleFileManagement(route.queryText, cmdText)
            is ChatRouteIntent.LocalOpenApp -> handleLocalOpenApp(route, installedApps)
            is ChatRouteIntent.LocalDialPhone -> handleLocalDialPhone(route, cmdText)
            is ChatRouteIntent.LocalToggleWifi -> handleLocalToggleWifi(route, cmdText)
            is ChatRouteIntent.ConversationalChat -> handleConversationalChat(
                userMessage = route.message,
                retryCommand = rawCommand ?: route.message,
                allowQueueWhenOffline = allowQueueWhenOffline
            )
            is ChatRouteIntent.ExecuteCommandPlan -> Unit
        }
    }

    private suspend fun enqueueOfflineCommand(commandText: String, reasonAr: String) {
        queuedCommandDao.enqueue(
            QueuedCommandEntity(
                commandText = commandText,
                status = QueuedCommandStatus.PENDING.name
            )
        )
        val queueMsg = "أنت غير متصل بالإنترنت حالياً. أضفت الأمر «$commandText» إلى طابور الانتظار في القاعدة وسأنفّذه تلقائياً مع إشعارك فور عودة الشبكة.\n($reasonAr)"
        actionLogRepository.logAction(
            title = "جدولة أمر في طابور انتظار الشبكة",
            details = com.example.security.SensitiveDataScrubber.scrub(commandText),
            status = ActionStatus.PENDING_APPROVAL,
            actionType = "OFFLINE_COMMAND_QUEUED"
        )
        recordConversationTurn(commandText, queueMsg)
        chatHistoryRepository.addAgentMessage(queueMsg, ChatMessageType.TEXT)
        _state.update {
            it.copy(
                lastSpokenReply = queueMsg,
                queuedCommandsCount = it.queuedCommandsCount + 1
            )
        }
        speakIfEnabled(queueMsg)
    }

    internal suspend fun drainPendingQueuedCommands(): Int = queueDrainMutex.withLock {
        if (networkConnectivityMonitor.refreshNow() == NetworkTier.OFFLINE) {
            return@withLock 0
        }
        val pending = queuedCommandDao.getPendingCommands()
        if (pending.isEmpty()) return@withLock 0

        var processedCount = 0
        for (item in pending) {
            if (networkConnectivityMonitor.refreshNow() == NetworkTier.OFFLINE) break
            queuedCommandDao.updateStatus(
                id = item.id,
                status = QueuedCommandStatus.RUNNING.name,
                executedAt = null,
                errorMessage = null
            )
            val ok = runCatching {
                executeCommandInternal(
                    command = item.commandText,
                    addUserBubble = false,
                    allowQueueWhenOffline = false
                )
            }.getOrDefault(false)

            val now = System.currentTimeMillis()
            if (ok) {
                processedCount++
                queuedCommandDao.updateStatus(
                    id = item.id,
                    status = QueuedCommandStatus.COMPLETED.name,
                    executedAt = now,
                    errorMessage = null
                )
                val doneMsg = "✅ عاد الاتصال بالشبكة: نُفّذ الأمر المجدول «${item.commandText}» بنجاح."
                chatHistoryRepository.addAgentMessage(doneMsg, ChatMessageType.TEXT)
                showQueuedCommandNotification(item.commandText, success = true)
            } else {
                queuedCommandDao.updateStatus(
                    id = item.id,
                    status = QueuedCommandStatus.FAILED.name,
                    executedAt = now,
                    errorMessage = "تعذر التنفيذ التلقائي بعد عودة الشبكة"
                )
                showQueuedCommandNotification(item.commandText, success = false)
            }
        }
        processedCount
    }

    private fun showQueuedCommandNotification(commandText: String, success: Boolean) {
        runCatching {
            ReminderReceiver.ensureReminderChannel(appContext)
            val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            if (!canNotify) return

            val openIntent = Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val notifId = ((System.currentTimeMillis() % 100_000L).toInt() + 8000)
            val pendingIntent = android.app.PendingIntent.getActivity(
                appContext,
                notifId,
                openIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val title = if (success) "اكتمل تنفيذ أمر من الطابور" else "تعذّر تنفيذ أمر من الطابور"
            val body = if (success) {
                "عاد الاتصال وتم تنفيذ: $commandText"
            } else {
                "عاد الاتصال لكن تعذّر إكمال: $commandText"
            }
            val notification = NotificationCompat.Builder(appContext, ReminderReceiver.CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            NotificationManagerCompat.from(appContext).notify(notifId, notification)
        }
    }

    private suspend fun handleLocalOpenApp(
        route: ChatRouteIntent.LocalOpenApp,
        installedApps: List<InstalledAppInfo>
    ) {
        when (val match = AppNameMatcher.match(route.appQuery, installedApps)) {
            is AppMatchResult.SingleMatch -> {
                val app = match.app
                val launchIntent = AppOrganizer.createLaunchIntent(appContext, app.packageName)
                if (launchIntent == null) {
                    val reply = "تطبيق «${app.label}» مثبّت لكن لا يملك واجهة تشغيل مباشرة."
                    actionLogRepository.logAction(
                        title = "تعذر فتح ${app.label}",
                        details = reply,
                        status = ActionStatus.FAILED,
                        actionType = "LOCAL_OPEN_APP_NO_LAUNCHER"
                    )
                    recordConversationTurn(route.rawCommand, reply)
                    chatHistoryRepository.addAgentMessage(reply, ChatMessageType.ERROR)
                    _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = reply) }
                    speakIfEnabled(reply)
                    return
                }
                val launched = runCatching { appContext.startActivity(launchIntent) }.isSuccess
                val reply = if (launched) {
                    "تم فتح «${app.label}» محلياً دون الحاجة إلى الشبكة."
                } else {
                    "تعذّر فتح «${app.label}» من النظام حالياً."
                }
                if (launched) {
                    val cal = Calendar.getInstance()
                    runCatching {
                        userMemoryRepository.recordHabit(
                            actionName = "فتح ${app.label}",
                            hourOfDay = cal.get(Calendar.HOUR_OF_DAY),
                            dayOfWeek = HabitLearningEngine.calendarDayToIso(cal.get(Calendar.DAY_OF_WEEK))
                        )
                    }
                }
                actionLogRepository.logAction(
                    title = if (launched) "فتح محلي لتطبيق: ${app.label}" else "فشل فتح: ${app.label}",
                    details = "${app.packageName} (${match.matchReason})",
                    status = if (launched) ActionStatus.SUCCESS else ActionStatus.FAILED,
                    actionType = "LOCAL_OPEN_APP"
                )
                recordConversationTurn(route.rawCommand, reply)
                chatHistoryRepository.addAgentMessage(
                    reply,
                    if (launched) ChatMessageType.TEXT else ChatMessageType.ERROR
                )
                _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = reply) }
                speakIfEnabled(reply)
            }

            is AppMatchResult.MultipleMatches -> {
                val jsonArr = JSONArray()
                match.candidates.forEach { app ->
                    jsonArr.put(
                        JSONObject().apply {
                            put("packageName", app.packageName)
                            put("label", app.label)
                            put("folderAr", app.folder.titleAr)
                        }
                    )
                }
                val names = match.candidates.joinToString("، ") { it.label }
                val reply = "وجدت ${match.candidates.size} تطبيقات مطابقة لـ «${route.appQuery}» ($names) — أيها تريد فتحه؟ اضغط على التطبيق المطلوب:"
                recordConversationTurn(route.rawCommand, reply)
                chatHistoryRepository.addAgentMessage(
                    text = reply,
                    type = ChatMessageType.APPS_LIST,
                    payloadJson = jsonArr.toString()
                )
                _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = reply) }
                speakIfEnabled("وجدت عدة تطبيقات مطابقة لـ ${route.appQuery}، اختر التطبيق الذي تريده.")
            }

            is AppMatchResult.NoMatch -> {
                val normQ = AppNameMatcher.normalize(route.appQuery)
                if (normQ.contains("اعدادات") || normQ.contains("ضبط") || normQ.contains("settings")) {
                    val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val opened = runCatching { appContext.startActivity(settingsIntent) }.isSuccess
                    val reply = if (opened) {
                        "تم فتح إعدادات الجهاز محلياً دون الحاجة إلى الشبكة."
                    } else {
                        "تعذر فتح إعدادات الجهاز حالياً."
                    }
                    actionLogRepository.logAction(
                        title = "فتح الإعدادات محلياً",
                        details = reply,
                        status = if (opened) ActionStatus.SUCCESS else ActionStatus.FAILED,
                        actionType = "LOCAL_OPEN_SETTINGS"
                    )
                    recordConversationTurn(route.rawCommand, reply)
                    chatHistoryRepository.addAgentMessage(reply, ChatMessageType.TEXT)
                    _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = reply) }
                    speakIfEnabled(reply)
                    return
                }

                val notFoundMsg = "لم أجد أي تطبيق مثبّت يطابق «${route.appQuery}» على جهازك. تأكّد من الاسم أو قل «اعرض تطبيقاتي»."
                actionLogRepository.logAction(
                    title = "تعذر العثور على تطبيق: ${route.appQuery}",
                    details = notFoundMsg,
                    status = ActionStatus.FAILED,
                    actionType = "LOCAL_OPEN_APP_NOT_FOUND"
                )
                recordConversationTurn(route.rawCommand, notFoundMsg)
                chatHistoryRepository.addAgentMessage(notFoundMsg, ChatMessageType.ERROR)
                _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = notFoundMsg) }
                speakIfEnabled(notFoundMsg)
            }
        }
    }

    private suspend fun handleLocalDialPhone(
        route: ChatRouteIntent.LocalDialPhone,
        rawCommand: String
    ) {
        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
            if (!route.phoneNumber.isNullOrBlank()) {
                data = Uri.parse("tel:${route.phoneNumber}")
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val opened = runCatching { appContext.startActivity(dialIntent) }.isSuccess
        val reply = when {
            !opened -> "تعذر فتح تطبيق الهاتف أو شاشة الاتصال على هذا الجهاز."
            !route.phoneNumber.isNullOrBlank() ->
                "فتحت لك شاشة الاتصال بالرقم ${route.phoneNumber} محلياً (دون شبكة) — اضغط اتصال للتأكيد."
            route.targetQuery.isNotBlank() ->
                "فتحت شاشة الاتصال للطلب «${route.targetQuery}» محلياً — اختر جهة الاتصال لإتمام المكالمة."
            else -> "تم فتح شاشة الاتصال محلياً."
        }
        actionLogRepository.logAction(
            title = "فتح شاشة الاتصال محلياً",
            details = com.example.security.SensitiveDataScrubber.scrub(reply),
            status = if (opened) ActionStatus.SUCCESS else ActionStatus.FAILED,
            actionType = "LOCAL_DIAL_PHONE"
        )
        recordConversationTurn(rawCommand, reply)
        chatHistoryRepository.addAgentMessage(
            reply,
            if (opened) ChatMessageType.TEXT else ChatMessageType.ERROR
        )
        _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = reply) }
        speakIfEnabled(reply)
    }

    private suspend fun handleLocalToggleWifi(
        route: ChatRouteIntent.LocalToggleWifi,
        rawCommand: String
    ) {
        val primaryIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val openedPrimary = runCatching { appContext.startActivity(primaryIntent) }.isSuccess
        val openedFallback = if (!openedPrimary) {
            runCatching {
                appContext.startActivity(
                    Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.isSuccess
        } else {
            true
        }
        val actionLabel = when (route.enableHint) {
            true -> "تشغيل الواي فاي"
            false -> "إيقاف الواي فاي"
            null -> "إعدادات الواي فاي"
        }
        val reply = if (openedFallback) {
            "فتحت لك لوحة $actionLabel محلياً دون الحاجة إلى الشبكة لتبديله مباشرة."
        } else {
            "تعذر فتح لوحة إعدادات الواي فاي على هذا الجهاز."
        }
        actionLogRepository.logAction(
            title = "فتح لوحة الواي فاي محلياً",
            details = reply,
            status = if (openedFallback) ActionStatus.SUCCESS else ActionStatus.FAILED,
            actionType = "LOCAL_WIFI_PANEL"
        )
        recordConversationTurn(rawCommand, reply)
        chatHistoryRepository.addAgentMessage(
            reply,
            if (openedFallback) ChatMessageType.TEXT else ChatMessageType.ERROR
        )
        _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = reply) }
        speakIfEnabled(reply)
    }

    private suspend fun handleFileManagement(queryText: String, userCommand: String) {
        if (phoneFileManager.isTrashQuery(queryText)) {
            val activeTrash = phoneFileManager.listTrashItems()
            val reply = if (activeTrash.isEmpty()) {
                "سلة المهملات فارغة حالياً. تُحفظ العناصر المنقولة إليها لمدة 7 أيام قبل الحذف النهائي التلقائي."
            } else {
                "يوجد ${activeTrash.size} عنصر في سلة المهملات (تُحفظ 7 أيام). يمكنك استعادتها أو حذفها نهائياً الآن."
            }
            recordConversationTurn(userCommand, reply)
            chatHistoryRepository.addAgentMessage(reply, ChatMessageType.TEXT)
            _state.update {
                it.copy(
                    uiState = CommandsUiState.Idle,
                    lastSpokenReply = reply,
                    trashItems = activeTrash,
                    showTrashDialog = true
                )
            }
            speakIfEnabled(reply)
            return
        }

        if (!phoneFileManager.hasAllFilesPermission()) {
            val explainMsg = "لفحص تخزين الهاتف بالكامل واكتشاف المجلدات الفارغة والملفات الزائدة، أحتاج أولاً إذن «الوصول إلى كل الملفات». وإذا فضّلت عدم منحه، سأتابع الفحص داخل المجلدات المتاحة بالتخزين المحدود."
            recordConversationTurn(userCommand, explainMsg)
            chatHistoryRepository.addAgentMessage(explainMsg, ChatMessageType.TEXT)
            _state.update {
                it.copy(
                    uiState = CommandsUiState.Idle,
                    lastSpokenReply = explainMsg,
                    pendingFileQuery = queryText,
                    showFilePermissionDialog = true
                )
            }
            speakIfEnabled(explainMsg)
            return
        }

        runFileScanAndPromptBatches(queryText = queryText, useScopedFallback = false)
    }

    fun onFilePermissionSettingsReturned() {
        val pendingQuery = _state.value.pendingFileQuery ?: return
        val hasAllFiles = phoneFileManager.hasAllFilesPermission()
        _state.update { it.copy(showFilePermissionDialog = false, pendingFileQuery = null) }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isBusyWorking = true,
                    statusNoticeAr = if (hasAllFiles) {
                        "تم تفعيل صلاحية كل الملفات — جارٍ فحص التخزين..."
                    } else {
                        "لم تُمنح صلاحية كل الملفات — جارٍ الفحص بالتخزين المحدود..."
                    }
                )
            }
            try {
                runFileScanAndPromptBatches(
                    queryText = pendingQuery,
                    useScopedFallback = !hasAllFiles
                )
            } finally {
                _state.update { it.copy(isBusyWorking = false, statusNoticeAr = "") }
            }
        }
    }

    fun continueFileScanWithScopedFallback() {
        val pendingQuery = _state.value.pendingFileQuery ?: "فحص الملفات"
        _state.update { it.copy(showFilePermissionDialog = false, pendingFileQuery = null) }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isBusyWorking = true,
                    statusNoticeAr = "جارٍ الفحص بالتخزين المحدود (مع استبعاد نظام Android)..."
                )
            }
            try {
                runFileScanAndPromptBatches(queryText = pendingQuery, useScopedFallback = true)
            } finally {
                _state.update { it.copy(isBusyWorking = false, statusNoticeAr = "") }
            }
        }
    }

    fun dismissFilePermissionDialog() {
        _state.update { it.copy(showFilePermissionDialog = false, pendingFileQuery = null) }
    }

    private suspend fun runFileScanAndPromptBatches(
        queryText: String,
        useScopedFallback: Boolean
    ) {
        _state.update {
            it.copy(
                statusNoticeAr = "جارٍ فحص ملفات الهاتف واستبعاد Android/ ومجلدات النظام...",
                liveDoingNowAr = "أفحص تخزين الهاتف مع استبعاد مجلدات النظام والملفات الخاصة...",
                liveNextStepAr = "تجهيز تقرير مفصّل بالملفات والمجلدات الفارغة",
                liveWaitingForUserAr = "انتظار انتهاء الفحص"
            )
        }
        val report = phoneFileManager.scanDeviceFiles(useScopedFallback = useScopedFallback)
        val (focusCategory, focusOnly) = phoneFileManager.detectRequestedFocus(queryText)
        val batches = phoneFileManager.buildApprovalBatches(
            report = report,
            focusCategory = focusCategory,
            focusOnly = focusOnly
        )
        val summaryText = report.toArabicSummaryText()

        fileBatchRunMovedCount = 0
        fileBatchRunSkippedCount = 0
        fileBatchRunFreedBytes = 0L
        fileBatchSucceededList.clear()
        fileBatchUnverifiedList.clear()
        fileBatchFailedList.clear()

        recordConversationTurn(queryText, summaryText)
        chatHistoryRepository.addAgentMessage(summaryText, ChatMessageType.TEXT)

        val spokenSummary = if (batches.isEmpty()) {
            "انتهى فحص الهاتف. لم يُعثر على عناصر مطابقة للحذف في النطاق المفحوص."
        } else {
            "انتهى الفحص؛ عثرت على ${report.emptyFoldersCount} مجلد فارغ و${report.items.size} عنصر إجمالاً. راجع الدفعة الأولى للموافقة قبل نقل أي عنصر للسلة."
        }
        val firstBatch = batches.firstOrNull()
        _state.update {
            it.copy(
                uiState = CommandsUiState.Idle,
                latestFileReport = report,
                pendingFileBatches = batches,
                currentFileBatchIndex = 0,
                liveDoingNowAr = if (firstBatch != null) "اكتمل الفحص (${report.items.size} عنصر)" else "",
                liveNextStepAr = firstBatch?.titleAr ?: "",
                liveWaitingForUserAr = if (firstBatch != null) "موافقتك أو تخطّي الدفعة الأولى" else "",
                lastSpokenReply = spokenSummary
            )
        }
        speakIfEnabled(spokenSummary)
    }

    fun approveCurrentFileBatch() {
        val currentState = _state.value
        val batch = currentState.currentPendingFileBatch ?: return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isBusyWorking = true,
                    statusNoticeAr = "جارٍ نقل ${batch.titleAr} إلى سلة المهملات (7 أيام)...",
                    liveDoingNowAr = "أنقل عناصر ${batch.titleAr} إلى سلة المهملات الآمنة...",
                    liveNextStepAr = "تحديث حالة الدفعة وعرض الدفعة التالية",
                    liveWaitingForUserAr = ""
                )
            }
            try {
                val outcome = phoneFileManager.executeApprovedBatch(batch, approvedByUser = true)
                fileBatchRunMovedCount += outcome.movedToTrashCount
                fileBatchRunSkippedCount += outcome.skippedNonEmptyCount + outcome.skippedExcludedOrMissingCount
                fileBatchRunFreedBytes += outcome.freedBytes

                if (outcome.movedToTrashCount > 0) {
                    fileBatchSucceededList.add(
                        "${batch.titleAr}: نقل ${outcome.movedToTrashCount} عنصر للسلة (${formatFileSize(outcome.freedBytes)})"
                    )
                }
                val skippedTotal = outcome.skippedNonEmptyCount + outcome.skippedExcludedOrMissingCount
                if (skippedTotal > 0) {
                    fileBatchUnverifiedList.add(
                        "${batch.titleAr}: تم تخطي $skippedTotal عنصر (غير فارغ أو محمي أو مفقود)"
                    )
                }
                if (outcome.failedCount > 0) {
                    fileBatchFailedList.add(
                        "${batch.titleAr}: تعذّر نقل ${outcome.failedCount} عنصر"
                    )
                }

                val batchMsg = buildString {
                    append("تم تنفيذ ${batch.titleAr}: نُقل ${outcome.movedToTrashCount} عنصر إلى سلة المهملات (تُحفظ 7 أيام)")
                    if (outcome.freedBytes > 0L) {
                        append("، بمساحة ${formatFileSize(outcome.freedBytes)}")
                    }
                    if (outcome.skippedNonEmptyCount > 0) {
                        append("، وتُخطّي ${outcome.skippedNonEmptyCount} مجلد لأنها لم تعد فارغة لحظة الحذف")
                    }
                    if (outcome.failedCount > 0) {
                        append("، وتعذّر نقل ${outcome.failedCount} عنصر")
                    }
                    append(".")
                }
                chatHistoryRepository.addAgentMessage(batchMsg, ChatMessageType.TEXT)
                advanceOrFinishFileBatches(currentState.pendingFileBatches, currentState.currentFileBatchIndex)
            } finally {
                _state.update { it.copy(isBusyWorking = false, statusNoticeAr = "") }
            }
        }
    }

    fun skipOrCancelCurrentFileBatch() {
        val currentState = _state.value
        val batch = currentState.currentPendingFileBatch ?: return
        viewModelScope.launch {
            fileBatchFailedList.add("${batch.titleAr}: أُلغيت بناءً على طلبك")
            actionLogRepository.logAction(
                title = "إلغاء دفعة تنظيف ملفات",
                details = "${batch.titleAr} (${batch.items.size} عناصر)",
                status = ActionStatus.CANCELLED,
                actionType = "FILE_BATCH_CANCELLED"
            )
            chatHistoryRepository.addAgentMessage(
                "أُلغيت ${batch.titleAr} بناءً على طلبك ولم يُحذف أي عنصر منها.",
                ChatMessageType.TEXT
            )
            advanceOrFinishFileBatches(currentState.pendingFileBatches, currentState.currentFileBatchIndex)
        }
    }

    private suspend fun advanceOrFinishFileBatches(
        batches: List<FileCleanupBatch>,
        completedIndex: Int
    ) {
        val nextIndex = completedIndex + 1
        if (nextIndex < batches.size) {
            val nextBatch = batches[nextIndex]
            _state.update {
                it.copy(
                    currentFileBatchIndex = nextIndex,
                    liveDoingNowAr = "بانتظار مراجعتك لدفعة تنظيف الملفات",
                    liveNextStepAr = nextBatch.titleAr,
                    liveWaitingForUserAr = "موافقتك أو تخطّي الدفعة"
                )
            }
        } else {
            val activeTrash = phoneFileManager.listTrashItems()
            val finalMsg = "اكتملت مراجعة الدفعات: نُقل $fileBatchRunMovedCount عنصر إلى سلة المهملات (المساحة المحررة المؤقتة: ${formatFileSize(fileBatchRunFreedBytes)}، المتخطى: $fileBatchRunSkippedCount). يمكنك قول «سلة المهملات» في أي وقت للاستعادة خلال 7 أيام."
            chatHistoryRepository.addAgentMessage(finalMsg, ChatMessageType.TEXT)
            val truthReport = buildTruthReport(
                succeeded = fileBatchSucceededList,
                unverified = fileBatchUnverifiedList,
                failed = fileBatchFailedList,
                rolledBack = emptyList()
            )
            if (!truthReport.isNullOrBlank()) {
                chatHistoryRepository.addAgentMessage(truthReport, ChatMessageType.TEXT)
                recordConversationTurn("تقرير تنظيف الملفات", truthReport)
            } else {
                recordConversationTurn("تنظيف الملفات", finalMsg)
            }
            _state.update {
                it.copy(
                    pendingFileBatches = emptyList(),
                    currentFileBatchIndex = 0,
                    trashItems = activeTrash,
                    liveDoingNowAr = "",
                    liveNextStepAr = "",
                    liveWaitingForUserAr = "",
                    executionTruthReportAr = truthReport,
                    lastSpokenReply = finalMsg
                )
            }
            speakIfEnabled(finalMsg)
        }
    }

    fun restoreTrashItem(id: String) {
        viewModelScope.launch {
            val outcome = phoneFileManager.restoreTrashItem(id)
            val updated = phoneFileManager.listTrashItems()
            chatHistoryRepository.addAgentMessage(outcome.messageAr, ChatMessageType.TEXT)
            _state.update { it.copy(trashItems = updated, lastSpokenReply = outcome.messageAr) }
        }
    }

    fun permanentlyDeleteTrashItem(id: String) {
        viewModelScope.launch {
            val deleted = phoneFileManager.permanentlyDeleteTrashItem(id)
            val updated = phoneFileManager.listTrashItems()
            if (deleted) {
                chatHistoryRepository.addAgentMessage("تم الحذف النهائي للعنصر من سلة المهملات.", ChatMessageType.TEXT)
            }
            _state.update { it.copy(trashItems = updated) }
        }
    }

    fun dismissTrashDialog() {
        _state.update { it.copy(showTrashDialog = false) }
    }

    private suspend fun handleShowActionHistory(userCommand: String) {
        val recentLogs = actionLogRepository.allLogs.first().take(8)
        if (recentLogs.isEmpty()) {
            val reply = "لم أنفذ أي إجراءات مسجلة بعد. جرّب إعطائي أمراً مثل «اعرض ألعابي» أو «افتح الإعدادات»."
            recordConversationTurn(userCommand, reply)
            chatHistoryRepository.addAgentMessage(reply, ChatMessageType.TEXT)
            _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = reply) }
            speakIfEnabled(reply)
            return
        }

        val timeFmt = SimpleDateFormat("hh:mm a", Locale("ar"))
        val summary = buildString {
            appendLine("آخر ${recentLogs.size} إجراءات في سجل الوكيل:")
            recentLogs.forEach { log ->
                val status = ActionStatus.fromName(log.status)
                val badge = when (status) {
                    ActionStatus.SUCCESS -> "✅"
                    ActionStatus.UNVERIFIED -> "⚠️"
                    ActionStatus.PENDING_APPROVAL -> "⏳"
                    ActionStatus.CANCELLED -> "⏹"
                    ActionStatus.FAILED -> "❌"
                    ActionStatus.UNDONE -> "↩"
                }
                appendLine("$badge ${log.title} (${timeFmt.format(Date(log.timestamp))})")
            }
        }.trim()
        recordConversationTurn(userCommand, summary)
        chatHistoryRepository.addAgentMessage(summary, ChatMessageType.TEXT)
        _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = summary) }
        speakIfEnabled("عرضت لك آخر ${recentLogs.size} إجراءات قمت بها.")
    }

    private suspend fun handlePromptReminderDetails(userCommand: String) {
        val reply = "بماذا تريدني أن أذكّرك ومتى؟ مثال: «ذكّرني بعد 10 دقائق بشرب الماء» أو «اضبط منبهاً للساعة 7:30 صباحاً». جهّزت لك بداية العبارة في مربع الكتابة."
        recordConversationTurn(userCommand, reply)
        chatHistoryRepository.addAgentMessage(reply, ChatMessageType.TEXT)
        _state.update {
            it.copy(
                uiState = CommandsUiState.Idle,
                inputText = "ذكّرني بعد 10 دقائق بـ ",
                lastSpokenReply = reply
            )
        }
        speakIfEnabled("بماذا تريدني أن أذكّرك ومتى؟")
    }

    private suspend fun handleShowApps(
        intent: ChatRouteIntent.ShowApps,
        installedApps: List<InstalledAppInfo>,
        userCommand: String
    ) {
        val visibleApps = installedApps.filter { !it.isHidden }
        val allMatched = visibleApps.filter { app ->
            val folderOk = intent.folderFilter == null || app.folder == intent.folderFilter
            val searchOk = intent.searchQuery.isBlank() ||
                app.label.contains(intent.searchQuery, ignoreCase = true)
            folderOk && searchOk
        }
        val filtered = allMatched.take(40)

        if (filtered.isEmpty()) {
            val reply = if (intent.folderFilter != null) {
                "لم أجد تطبيقات مصنّفة ضمن مجلد «${intent.folderFilter.titleAr}» حالياً. يمكنك تجربة «اعرض تطبيقاتي» أو استخدام التصنيف التلقائي في تبويب «التطبيقات»."
            } else {
                "لم أجد تطبيقات مطابقة للعرض حالياً."
            }
            recordConversationTurn(userCommand, reply)
            chatHistoryRepository.addAgentMessage(reply, ChatMessageType.TEXT)
            _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = reply) }
            speakIfEnabled(reply)
            return
        }

        val jsonArr = JSONArray()
        filtered.forEach { app ->
            jsonArr.put(
                JSONObject().apply {
                    put("packageName", app.packageName)
                    put("label", app.label)
                    put("folderAr", app.folder.titleAr)
                }
            )
        }

        val countNote = if (allMatched.size > filtered.size) {
            " (عرض ${filtered.size} من أصل ${allMatched.size})"
        } else {
            " (${filtered.size})"
        }
        val reply = "${intent.titleAr}$countNote: اضغط على أي بطاقة لتشغيله فوراً."
        val labelsPreview = filtered.take(12).joinToString("، ") { it.label }
        recordConversationTurn(userCommand, "$reply ($labelsPreview)")
        chatHistoryRepository.addAgentMessage(
            text = reply,
            type = ChatMessageType.APPS_LIST,
            payloadJson = jsonArr.toString()
        )
        _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = reply) }
        speakIfEnabled(reply)
    }

    private suspend fun handleReadScreen(
        installedApps: List<InstalledAppInfo>,
        userCommand: String
    ) {
        val service = ScreenStateBridge.getService()
        val snapshot = if (service != null) {
            ScreenStateBridge.setScreenReadActive(true)
            try {
                service.captureCurrentScreen()
            } finally {
                ScreenStateBridge.setScreenReadActive(false)
            }
        } else {
            ScreenStateBridge.latestSnapshot.value
        }

        if (snapshot == null) {
            val msg = "لا يمكنني قراءة الشاشة الآن لأن خدمة إمكانية الوصول غير مفعّلة أو لم تُلتقط شاشة بعد. فعّلها من تبويب «الأذونات» ثم أعد المحاولة."
            recordConversationTurn(userCommand, msg)
            chatHistoryRepository.addAgentMessage(msg, ChatMessageType.SCREEN_READ)
            _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = msg) }
            speakIfEnabled(msg)
            return
        }

        if (snapshot.hasSensitiveSecurityField) {
            val msg = "رصدت حقلاً حساساً (كلمة مرور أو دفع أو بصمة) على الشاشة الحالية؛ لذلك أمتنع عن قراءة محتواها حفاظاً على خصوصيتك."
            recordConversationTurn(userCommand, msg)
            chatHistoryRepository.addAgentMessage(msg, ChatMessageType.SCREEN_READ)
            _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = msg) }
            speakIfEnabled(msg)
            return
        }

        val appLabel = installedApps.firstOrNull { it.packageName == snapshot.packageName }?.label
            ?: snapshot.packageName.ifBlank { "الشاشة الحالية" }

        val isPrivacyOn = privacyPreferences.isPrivacyModeEnabled()
        val isOffline = networkConnectivityMonitor.refreshNow() == NetworkTier.OFFLINE

        val groundedReply = if (isPrivacyOn || isOffline) {
            LocalScreenSummarizer.summarizeSnapshot(
                snapshot = snapshot,
                appLabel = appLabel,
                privacyMode = isPrivacyOn
            )
        } else {
            val factualElements = LocalScreenSummarizer.summarizeSnapshot(
                snapshot = snapshot,
                appLabel = appLabel,
                privacyMode = false
            )
            try {
                startGeminiProgressTimer(
                    doingNowAr = "أحلّل عناصر شاشة «$appLabel» وألخّصها...",
                    nextStepAr = "عرض ملخص العناصر الفعلية في الدردشة",
                    waitingAr = "انتظار استجابة النموذج"
                )
                val capturedLines = AgentPromptRules.enrichScreenReadLines(snapshot.toCompactPromptText())
                val explainMeta = geminiRepository.summarizeAppScreensWithMeta(
                    appName = appLabel,
                    packageName = snapshot.packageName,
                    capturedScreenTexts = capturedLines,
                    onStatusUpdate = { statusMsg ->
                        _state.update { it.copy(statusNoticeAr = statusMsg, liveDoingNowAr = statusMsg) }
                    }
                )
                recordModelRouteUsage(explainMeta, "قراءة شاشة $appLabel")
                "${explainMeta.value}\n\n— العناصر المرصودة فعلياً في «$appLabel»:\n$factualElements"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Screen explanation fell back to LocalScreenSummarizer", e)
                val reason = classifyModelError(e).category.titleAr
                "تعذر التلخيص الذكي عبر Gemini ($reason)، إليك الملخص المحلي المرتّب (دون إرسال بيانات):\n\n$factualElements"
            } finally {
                stopGeminiProgressTimer()
            }
        }

        recordConversationTurn(userCommand, groundedReply)
        chatHistoryRepository.addAgentMessage(groundedReply, ChatMessageType.SCREEN_READ)
        _state.update {
            it.copy(
                uiState = CommandsUiState.Idle,
                liveDoingNowAr = "",
                liveNextStepAr = "",
                liveWaitingForUserAr = "",
                lastSpokenReply = groundedReply
            )
        }
        speakIfEnabled(groundedReply)
    }

    private suspend fun handleCreateTaskOrReminder(
        intent: ChatRouteIntent.CreateTaskOrReminder,
        userCommand: String
    ) {
        val shouldScheduleAlarm = intent.taskType == AgentTaskType.REMINDER ||
            intent.taskType == AgentTaskType.ALARM ||
            intent.delayMinutes != null ||
            (intent.hour != null && intent.minute != null)

        var dueMillis: Long? = null
        var requestCode: Int? = null
        var scheduleNoteAr = ""

        if (shouldScheduleAlarm) {
            dueMillis = ChatIntentRouter.computeDueMillis(
                delayMinutes = intent.delayMinutes,
                hour = intent.hour,
                minute = intent.minute
            )
            val generatedCode = ((dueMillis / 100L) % Int.MAX_VALUE).toInt().coerceAtLeast(1001)
            val scheduledOk = scheduleExactAlarmInternal(
                requestCode = generatedCode,
                dueAtMillis = dueMillis,
                title = "${intent.taskType.titleAr}: ${intent.title}",
                message = intent.title
            )
            if (scheduledOk) {
                requestCode = generatedCode
                val formatted = SimpleDateFormat("hh:mm a", Locale("ar")).format(Date(dueMillis))
                scheduleNoteAr = " (مجدول بدقة عند الساعة $formatted)"
            } else {
                scheduleNoteAr = " (محفوظ في قائمتك مع تعذر جدولة منبه النظام)"
            }
        }

        val entity = AgentTaskEntity(
            title = intent.title,
            taskType = intent.taskType.name,
            dueAtMillis = dueMillis,
            hour = intent.hour,
            minute = intent.minute,
            alarmRequestCode = requestCode
        )
        chatHistoryRepository.createTask(entity)

        val reply = "تم حفظ ${intent.taskType.titleAr} «${intent.title}»$scheduleNoteAr."
        recordConversationTurn(userCommand, reply)
        actionLogRepository.logAction(
            title = "إنشاء ${intent.taskType.titleAr}: ${intent.title}",
            details = reply,
            status = ActionStatus.SUCCESS,
            actionType = "CHAT_${intent.taskType.name}"
        )

        val active = chatHistoryRepository.getActiveTasks()
        chatHistoryRepository.addAgentMessage(
            text = reply,
            type = ChatMessageType.TASKS_LIST,
            payloadJson = encodeTasksJson(active)
        )
        _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = reply) }
        speakIfEnabled(reply)
    }

    private suspend fun handleListTasks(userCommand: String) {
        val active = chatHistoryRepository.getActiveTasks()
        if (active.isEmpty()) {
            val reply = "لا توجد لديك تذكيرات أو مهام أو منبهات نشطة حالياً. يمكنك قول: «ذكّرني بعد 10 دقائق بشرب الماء»."
            recordConversationTurn(userCommand, reply)
            chatHistoryRepository.addAgentMessage(reply, ChatMessageType.TEXT)
            _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = reply) }
            speakIfEnabled(reply)
            return
        }

        val reply = "لديك ${active.size} عناصر في قائمة المهام والتذكيرات والمنبهات:"
        recordConversationTurn(userCommand, "$reply (${active.take(8).joinToString("، ") { it.title }})")
        chatHistoryRepository.addAgentMessage(
            text = reply,
            type = ChatMessageType.TASKS_LIST,
            payloadJson = encodeTasksJson(active)
        )
        _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = reply) }
        speakIfEnabled(reply)
    }

    private suspend fun handleCancelTaskByQuery(queryText: String, userCommand: String) {
        val active = chatHistoryRepository.getActiveTasks()
        when (val resolution = TaskCancellationResolver.resolve(queryText, active)) {
            TaskCancelResolution.NoActiveTasks -> {
                val reply = "لا توجد تذكيرات أو مهام نشطة لإلغائها حالياً."
                recordConversationTurn(userCommand, reply)
                chatHistoryRepository.addAgentMessage(reply, ChatMessageType.TEXT)
                _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = reply) }
                speakIfEnabled(reply)
            }
            is TaskCancelResolution.NeedsClarification -> {
                recordConversationTurn(userCommand, resolution.reasonAr)
                chatHistoryRepository.addAgentMessage(
                    text = resolution.reasonAr,
                    type = ChatMessageType.TASKS_LIST,
                    payloadJson = encodeTasksJson(resolution.candidateTasks)
                )
                _state.update {
                    it.copy(
                        uiState = CommandsUiState.Idle,
                        lastSpokenReply = resolution.reasonAr
                    )
                }
                speakIfEnabled(resolution.reasonAr)
            }
            is TaskCancelResolution.NeedsConfirmation -> {
                val target = resolution.targetTask
                val reply = "هل تريد بالتأكيد إلغاء «${target.title}»؟ أكّد الإلغاء من الحوار أو البطاقة."
                recordConversationTurn(userCommand, reply)
                chatHistoryRepository.addAgentMessage(
                    text = reply,
                    type = ChatMessageType.TASKS_LIST,
                    payloadJson = encodeTasksJson(listOf(target))
                )
                _state.update {
                    it.copy(
                        uiState = CommandsUiState.Idle,
                        pendingTaskCancelConfirmation = target,
                        lastSpokenReply = reply
                    )
                }
                speakIfEnabled(reply)
            }
        }
    }

    fun confirmTaskCancellation() {
        val task = _state.value.pendingTaskCancelConfirmation ?: return
        _state.update { it.copy(pendingTaskCancelConfirmation = null) }
        viewModelScope.launch {
            val alarmStopped = task.alarmRequestCode?.let { code ->
                commandExecutor.cancelScheduledReminder(code)
            }
            chatHistoryRepository.cancelTask(task.id)
            val alarmNote = when (alarmStopped) {
                true -> " وإيقاف المنبه المجدول فعلياً"
                false -> " (لم يُعثر على منبه نظام نشط لإيقافه)"
                null -> ""
            }
            val reply = "تم إلغاء «${task.title}»$alarmNote."
            recordConversationTurn("تأكيد إلغاء ${task.title}", reply)
            actionLogRepository.logAction(
                title = "إلغاء ${AgentTaskType.entries.firstOrNull { it.name == task.taskType }?.titleAr ?: "مهمة"}",
                details = reply,
                status = if (alarmStopped == false) ActionStatus.UNVERIFIED else ActionStatus.UNDONE,
                actionType = "CHAT_CANCEL_TASK"
            )
            val remaining = chatHistoryRepository.getActiveTasks()
            chatHistoryRepository.addAgentMessage(
                text = reply,
                type = if (remaining.isNotEmpty()) ChatMessageType.TASKS_LIST else ChatMessageType.TEXT,
                payloadJson = if (remaining.isNotEmpty()) encodeTasksJson(remaining) else null
            )
            _state.update { it.copy(lastSpokenReply = reply) }
            speakIfEnabled(reply)
        }
    }

    fun dismissTaskCancellationConfirmation() {
        val task = _state.value.pendingTaskCancelConfirmation
        _state.update { it.copy(pendingTaskCancelConfirmation = null) }
        if (task != null) {
            viewModelScope.launch {
                val reply = "تم الإبقاء على «${task.title}» دون إلغاء."
                chatHistoryRepository.addAgentMessage(reply, ChatMessageType.TEXT)
                _state.update { it.copy(lastSpokenReply = reply) }
            }
        }
    }

    private suspend fun handleConversationalChat(
        userMessage: String,
        retryCommand: String = userMessage,
        allowQueueWhenOffline: Boolean = true
    ) {
        val installed = ensureInstalledAppsLoaded()
        val appLabels = installed.map { it.label }

        if (ConversationMemory.isInstalledAppQuestion(userMessage)) {
            val historyText = ConversationMemory.formatHistory(conversationTurns)
            val foundApp = ConversationMemory.findAppInHistory(historyText, appLabels)
            if (foundApp != null) {
                val isInstalled = installed.any { it.label.equals(foundApp, ignoreCase = true) }
                val reply = if (isInstalled) {
                    "نعم، «$foundApp» مثبّتة لديك."
                } else {
                    "لا، «$foundApp» غير مثبّتة على هاتفك."
                }
                recordConversationTurn(userMessage, reply)
                chatHistoryRepository.addAgentMessage(reply, ChatMessageType.TEXT)
                _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = reply) }
                speakIfEnabled(reply)
                return
            }
        }

        if (networkConnectivityMonitor.refreshNow() == NetworkTier.OFFLINE && allowQueueWhenOffline) {
            enqueueOfflineCommand(
                commandText = retryCommand,
                reasonAr = "تتطلب المحادثة الذكية اتصالاً بالإنترنت؛ أو يمكنك تجربة «افتح واتساب» أو «اعرض تطبيقاتي» أو «شغل الواي فاي» محلياً."
            )
            _state.update { it.copy(uiState = CommandsUiState.Idle) }
            return
        }

        val isPrivacyOn = privacyPreferences.isPrivacyModeEnabled()
        val recentSummaries = if (isPrivacyOn) {
            emptyList()
        } else {
            userMemoryRepository.getRecentConversationSummaries(limit = 3).map { it.summaryText }
        }
        val historyText = if (isPrivacyOn) {
            ""
        } else {
            ConversationMemory.formatHistoryWithSummaries(
                summaries = recentSummaries,
                turns = synchronized(conversationTurns) { conversationTurns.toList() }
            )
        }
        val persistentMemoryContext = if (isPrivacyOn) {
            ""
        } else {
            userMemoryRepository.buildConciseMemoryContext(800)
        }
        val baseContext = buildString {
            append("تطبيقات محملة: ${cachedInstalledApps.size}. ")
            append(
                if (ScreenStateBridge.isServiceConnected.value) "إمكانية الوصول مفعلة."
                else "إمكانية الوصول غير مفعلة."
            )
            if (persistentMemoryContext.isNotBlank()) {
                appendLine()
                append(persistentMemoryContext)
            }
        }
        try {
            startGeminiProgressTimer(
                doingNowAr = "أصيغ الرد المناسب لسؤالك...",
                nextStepAr = "عرض الرد في المحادثة",
                waitingAr = "انتظار استجابة النموذج (يمكنك الإيقاف في أي لحظة)"
            )
            val modelTier = ChatIntentRouter.classifyModelTier(userMessage, appLabels)
            val meta = geminiRepository.conversationalReplyWithMeta(
                userMessage = userMessage,
                contextSummary = AgentPromptRules.enrichChatContextSummary(baseContext),
                history = historyText,
                onStatusUpdate = { statusMsg ->
                    _state.update { it.copy(statusNoticeAr = statusMsg, liveDoingNowAr = statusMsg) }
                },
                modelTier = modelTier
            )
            stopGeminiProgressTimer()
            recordModelRouteUsage(meta, userMessage)
            recordConversationTurn(userMessage, meta.value)

            chatHistoryRepository.addAgentMessage(meta.value, ChatMessageType.TEXT)
            _state.update {
                it.copy(
                    uiState = CommandsUiState.Idle,
                    liveDoingNowAr = "",
                    liveNextStepAr = "",
                    liveWaitingForUserAr = "",
                    lastSpokenReply = meta.value
                )
            }
            speakIfEnabled(meta.value)
        } catch (e: CancellationException) {
            stopGeminiProgressTimer()
            throw e
        } catch (e: Exception) {
            stopGeminiProgressTimer()
            handleGeminiFailure(
                contextTag = "Conversational chat",
                e = e,
                retryType = ErrorRetryType.COMMAND,
                retryCommand = retryCommand
            )
        }
    }

    private fun recordModelRouteUsage(
        meta: ModelResponseMetadata<*>,
        requestPreview: String
    ) {
        val fallbackNote = if (meta.usedFallback) " (تحويل احتياطي من ${meta.intendedModel})" else ""
        val traceText = "آخر استجابة: ${meta.modelUsed} • ${meta.tier.labelAr}$fallbackNote"
        Log.i(
            TAG,
            "ModelTrace: tier=${meta.tier.name}, intended=${meta.intendedModel}, actual=${meta.modelUsed}, fallback=${meta.usedFallback}"
        )
        _state.update { it.copy(lastModelTraceAr = traceText) }
    }

    fun openPlayFeatureDialog() {
        _state.update { it.copy(showPlayFeatureDialog = true) }
    }

    fun dismissPlayFeatureDialog() {
        _state.update { it.copy(showPlayFeatureDialog = false) }
    }

    fun startGameplayGoal(goal: String) {
        val resolvedGoal = goal.trim().ifBlank { return }
        lastGameplayGoal = resolvedGoal
        _state.update { it.copy(showPlayFeatureDialog = false, activeError = null, showAppPickerForGameplay = true) }
    }

    fun dismissAppPickerForGameplay() {
        _state.update { it.copy(showAppPickerForGameplay = false) }
    }

    fun selectAppForGameplay(app: InstalledAppInfo) {
        val chatGoal = lastGameplayGoal?.takeIf { it.isNotBlank() } ?: "العب لعبة"
        val effectiveGoal = "$chatGoal — التطبيق المختار: ${app.label}"
        _state.update { it.copy(showAppPickerForGameplay = false) }
        launchAppFromChat(app.packageName, app.label)
        viewModelScope.launch {
            kotlinx.coroutines.delay(1200L)
            startGameplayLoop(effectiveGoal)
        }
    }

    private fun startGameplayLoop(goalText: String, skipIntroMessage: Boolean = false) {
        activeTaskJob?.cancel()
        executionJob?.cancel()
        gameLoopJob?.cancel()
        executionController.reset()
        lastGameplayGoal = goalText

        gameLoopJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isGameplayRunning = true,
                    isPaused = false,
                    isHandedOver = false,
                    statusNoticeAr = "يتولّى الوكيل اللعب الآن... ${AgentPromptRules.WAIT_THINKING_NOTICE_AR}",
                    liveDoingNowAr = "أراقب شاشة اللعب وأحلّل الخطوة التالية...",
                    liveNextStepAr = "تحديد الحركة المناسبة على الشاشة",
                    liveWaitingForUserAr = "يمكنك الإيقاف أو تولّي اللعب بنفسك في أي لحظة",
                    uiState = CommandsUiState.Idle
                )
            }
            if (!skipIntroMessage) {
                val startMsg = "تولّى الوكيل اللعب: سأراقب الشاشة الحقيقية وأتفاعل كالإنسان خطوة بخطوة. تذكّر أنني أحتاج بضع ثوانٍ للتفكير قبل كل حركة، ويمكنك إيقافي أو إكماله بنفسك في أي لحظة."
                chatHistoryRepository.addAgentMessage(startMsg, ChatMessageType.GAME_STATUS)
                speakIfEnabled(startMsg)
            }

            try {
                val result = generalGameEngine.runObserveAndActLoop(
                    goalAr = goalText,
                    maxCycles = 10,
                    onCycleUpdate = { updateMsg ->
                        _state.update {
                            it.copy(
                                statusNoticeAr = updateMsg,
                                liveDoingNowAr = updateMsg
                            )
                        }
                        chatHistoryRepository.addAgentMessage(updateMsg, ChatMessageType.GAME_STATUS)
                    },
                    callbacks = buildFloatingControlCallbacks()
                )
                val truthReport = buildTruthReport(
                    succeeded = if (result.stepsExecuted > 0) {
                        listOf("تم تنفيذ ${result.stepsExecuted} خطوة لعب: ${result.summaryAr}")
                    } else emptyList(),
                    unverified = if (result.stepsExecuted == 0 && !result.completedNaturally) {
                        listOf(result.summaryAr)
                    } else emptyList(),
                    failed = emptyList(),
                    rolledBack = emptyList()
                )
                recordConversationTurn(goalText, truthReport ?: result.summaryAr)
                chatHistoryRepository.addAgentMessage(result.summaryAr, ChatMessageType.GAME_STATUS)
                if (!truthReport.isNullOrBlank()) {
                    chatHistoryRepository.addAgentMessage(truthReport, ChatMessageType.TEXT)
                }
                _state.update {
                    it.copy(
                        lastSpokenReply = result.summaryAr,
                        executionTruthReportAr = truthReport
                    )
                }
                speakIfEnabled(result.summaryAr)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleGeminiFailure(
                    contextTag = "Gameplay loop",
                    e = e,
                    retryType = ErrorRetryType.GAMEPLAY
                )
            } finally {
                executionController.reset()
                _state.update {
                    it.copy(
                        isGameplayRunning = false,
                        isPaused = false,
                        isHandedOver = false,
                        statusNoticeAr = "",
                        liveDoingNowAr = "",
                        liveNextStepAr = "",
                        liveWaitingForUserAr = ""
                    )
                }
            }
        }
    }

    fun launchAppFromChat(packageName: String, label: String) {
        val launchIntent = AppOrganizer.createLaunchIntent(appContext, packageName)

        if (launchIntent == null) {
            viewModelScope.launch {
                val msg = "تعذر تشغيل «$label» مباشرة من الجهاز."
                chatHistoryRepository.addAgentMessage(msg, ChatMessageType.TEXT)
            }
            return
        }

        runCatching {
            appContext.startActivity(launchIntent)
        }.onSuccess {
            viewModelScope.launch {
                actionLogRepository.logAction(
                    title = "تشغيل «$label» من الدردشة",
                    details = "package=$packageName",
                    status = ActionStatus.SUCCESS,
                    actionType = "CHAT_LAUNCH_APP"
                )
                chatHistoryRepository.addAgentMessage("تم تشغيل «$label» الآن.", ChatMessageType.TEXT)
            }
        }.onFailure { err ->
            viewModelScope.launch {
                chatHistoryRepository.addAgentMessage(
                    "تعذر فتح «$label»: ${err.localizedMessage}",
                    ChatMessageType.TEXT
                )
            }
        }
    }

    fun toggleTaskCompleted(taskId: Long, completed: Boolean) {
        viewModelScope.launch {
            chatHistoryRepository.setTaskCompleted(taskId, completed)
        }
    }

    fun cancelTaskById(taskId: Long) {
        viewModelScope.launch {
            val task = chatHistoryRepository.getTaskById(taskId)
            val alarmStopped = task?.alarmRequestCode?.let { commandExecutor.cancelScheduledReminder(it) }
            chatHistoryRepository.cancelTask(taskId)
            if (task != null) {
                val detail = when (alarmStopped) {
                    true -> "تم إلغاء المهمة/التذكير وإيقاف المنبه المجدول فعلياً."
                    false -> "أُلغيت من القائمة، ولم يُعثر على منبه نظام نشط لإيقافه."
                    null -> "تم إلغاء المهمة/التذكير من بطاقة الدردشة."
                }
                actionLogRepository.logAction(
                    title = "إلغاء «${task.title}»",
                    details = detail,
                    status = if (alarmStopped == false) ActionStatus.UNVERIFIED else ActionStatus.UNDONE,
                    actionType = "CHAT_CANCEL_TASK"
                )
            }
        }
    }

    fun clearChatHistory() {
        viewModelScope.launch {
            conversationTurns.clear()
            chatHistoryRepository.clearChatHistory()
            _state.update { it.copy(lastSpokenReply = "", executionTruthReportAr = null) }
        }
    }

    fun executePlanStepByStep(singleStepOnly: Boolean = false) {
        val currentState = _state.value.uiState
        if (currentState !is CommandsUiState.PlanActive) return
        if (_state.value.isExecuting) return

        executionController.reset()
        executionJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isExecuting = true,
                    isPaused = false,
                    isHandedOver = false,
                    statusNoticeAr = "بدأت تنفيذ الخطة خطوة بخطوة... ${AgentPromptRules.WAIT_THINKING_NOTICE_AR}",
                    liveDoingNowAr = "أجهّز تنفيذ الخطوة الأولى من الخطة...",
                    liveNextStepAr = currentState.plan.steps.firstOrNull()?.titleAr ?: "",
                    liveWaitingForUserAr = "",
                    executionTruthReportAr = null
                )
            }
            val succeededList = mutableListOf<String>()
            val unverifiedList = mutableListOf<String>()
            val failedList = mutableListOf<String>()
            val rolledBackList = mutableListOf<String>()
            var consecutiveEmpty = 0
            val overlayWatcherJob = launch {
                while (isActive) {
                    val service = ScreenStateBridge.getService()
                    if (service != null) {
                        val fgPkg = generalGameEngine.resolveForegroundPackage(service)
                        if (ForegroundAppPolicy.shouldShowFloatingOverlay(fgPkg, appContext.packageName)) {
                            consecutiveEmpty = 0
                            floatingControlManager.show(
                                initialStatusAr = _state.value.statusNoticeAr.ifBlank { "ينفّذ خطوات الخطة" },
                                isPaused = executionController.isPaused.value,
                                callbacks = buildFloatingControlCallbacks()
                            )
                            floatingControlManager.setHostVisible(true)
                        } else {
                            consecutiveEmpty++
                            if (consecutiveEmpty >= 3 && !floatingControlManager.isMiniChatOpen()) {
                                floatingControlManager.setHostVisible(false)
                            }
                        }
                    } else {
                        if (!floatingControlManager.isMiniChatOpen()) {
                            floatingControlManager.setHostVisible(false)
                        }
                    }
                    delay(350L)
                }
            }

            try {
                var plan = if (currentState.plan.isComplete && !singleStepOnly) {
                    currentState.plan.copy(
                        isComplete = false,
                        isCancelled = false,
                        steps = currentState.plan.steps.map {
                            it.copy(
                                status = StepStatus.PENDING,
                                verificationStatus = StepVerificationStatus.AWAITING_VERIFICATION,
                                verificationResultAr = null
                            )
                        }
                    )
                } else {
                    currentState.plan
                }
                val steps = plan.steps.toMutableList()
                val installed = ensureInstalledAppsLoaded()

                for (index in steps.indices) {
                    executionController.awaitIfPaused()
                    val step = steps[index]
                    if (step.status == StepStatus.COMPLETED || step.status == StepStatus.CANCELLED) {
                        if (step.status == StepStatus.COMPLETED) {
                            succeededList.add("الخطوة ${step.stepNumber}: ${step.titleAr}")
                        }
                        continue
                    }

                    val upcomingStep = steps.drop(index + 1).firstOrNull()
                    val stepNotice = "الخطوة ${step.stepNumber}/${steps.size}: ${step.titleAr}"
                    _state.update {
                        it.copy(
                            statusNoticeAr = "$stepNotice — ${AgentPromptRules.WAIT_THINKING_NOTICE_AR}",
                            liveDoingNowAr = "أنفّذ الخطوة ${step.stepNumber}/${steps.size}: ${step.titleAr}",
                            liveNextStepAr = upcomingStep?.let { next ->
                                "الخطوة ${next.stepNumber}: ${next.titleAr}"
                            } ?: "التحقق النهائي وإصدار تقرير الصدق",
                            liveWaitingForUserAr = if (step.requiresApproval) {
                                "ستُطلب موافقتك قبل التنفيذ"
                            } else ""
                        )
                    }
                    floatingControlManager.updateStatus(stepNotice, executionController.isPaused.value)

                    val stepPolicy = agentPolicy.validateStep(step)
                    if (!stepPolicy.allowed) {
                        actionLogRepository.logAction(
                            title = "إيقاف خطوة مخالفة للسياسة: ${step.titleAr}",
                            details = stepPolicy.reasonAr,
                            status = ActionStatus.CANCELLED,
                            actionType = "POLICY_BLOCKED"
                        )
                        steps[index] = step.copy(
                            status = StepStatus.CANCELLED,
                            verificationStatus = StepVerificationStatus.VERIFICATION_FAILED,
                            verificationResultAr = stepPolicy.reasonAr
                        )
                        failedList.add("الخطوة ${step.stepNumber} (${step.titleAr}): ${stepPolicy.reasonAr}")
                        plan = plan.copy(steps = steps.toList(), isCancelled = true)
                        val report = buildTruthReport(succeededList, unverifiedList, failedList, rolledBackList)
                        chatHistoryRepository.addAgentMessage(stepPolicy.reasonAr, ChatMessageType.ERROR)
                        if (!report.isNullOrBlank()) {
                            chatHistoryRepository.addAgentMessage(report, ChatMessageType.TEXT)
                            recordConversationTurn("تقرير تنفيذ: ${plan.originalCommand}", report)
                        }
                        _state.update {
                            it.copy(
                                uiState = CommandsUiState.PlanActive(plan),
                                isExecuting = false,
                                statusNoticeAr = "",
                                liveDoingNowAr = "",
                                liveNextStepAr = "",
                                liveWaitingForUserAr = "",
                                executionTruthReportAr = report
                            )
                        }
                        speakIfEnabled(stepPolicy.reasonAr)
                        return@launch
                    }

                    var stepApprovalToken: com.example.policy.GateApprovalToken? = null
                    if (sensitiveActionGate.requiresApprovalForStep(step)) {
                        steps[index] = step.copy(status = StepStatus.AWAITING_APPROVAL)
                        plan = plan.copy(steps = steps.toList())
                        _state.update {
                            it.copy(
                                uiState = CommandsUiState.PlanActive(plan),
                                liveDoingNowAr = "بانتظار مراجعتك للخطوة الحساسة ${step.stepNumber}",
                                liveWaitingForUserAr = "موافقتك الصريحة على: ${step.titleAr}"
                            )
                        }

                        val gateResult = sensitiveActionGate.authorizeStep(
                            step = steps[index],
                            plan = plan,
                            nextStep = upcomingStep
                        )
                        if (!gateResult.approved) {
                            val cancelReason = gateResult.reasonAr.ifBlank {
                                if (gateResult.timedOut) {
                                    "انتهت مهلة الموافقة (120 ثانية) على «${step.titleAr}» وأُلغي الإجراء احترازياً."
                                } else {
                                    "أُلغيت الخطوة الحساسة «${step.titleAr}» بناءً على قرارك."
                                }
                            }
                            steps[index] = step.copy(
                                status = StepStatus.CANCELLED,
                                verificationStatus = StepVerificationStatus.VERIFICATION_FAILED,
                                verificationResultAr = cancelReason
                            )
                            failedList.add("الخطوة ${step.stepNumber} (${step.titleAr}): $cancelReason")
                            plan = plan.copy(steps = steps.toList(), isCancelled = true)
                            val report = buildTruthReport(succeededList, unverifiedList, failedList, rolledBackList)
                            chatHistoryRepository.addAgentMessage(cancelReason, ChatMessageType.TEXT)
                            if (!report.isNullOrBlank()) {
                                chatHistoryRepository.addAgentMessage(report, ChatMessageType.TEXT)
                                recordConversationTurn("تقرير تنفيذ: ${plan.originalCommand}", report)
                            }
                            _state.update {
                                it.copy(
                                    uiState = CommandsUiState.PlanActive(plan),
                                    isExecuting = false,
                                    statusNoticeAr = "",
                                    liveDoingNowAr = "",
                                    liveNextStepAr = "",
                                    liveWaitingForUserAr = "",
                                    executionTruthReportAr = report
                                )
                            }
                            speakIfEnabled(cancelReason)
                            return@launch
                        }
                        stepApprovalToken = gateResult.approvalToken
                    }

                    executionController.awaitIfPaused()
                    steps[index] = steps[index].copy(
                        status = StepStatus.VERIFYING,
                        verificationStatus = StepVerificationStatus.AWAITING_VERIFICATION
                    )
                    plan = plan.copy(steps = steps.toList())
                    _state.update {
                        it.copy(
                            uiState = CommandsUiState.PlanActive(plan),
                            liveDoingNowAr = "أنفّذ وأتحقق من الخطوة ${step.stepNumber}: ${step.titleAr}",
                            liveWaitingForUserAr = ""
                        )
                    }

                    val outcome = commandExecutor.executeStep(
                        step = steps[index],
                        installedApps = installed,
                        approvalToken = stepApprovalToken
                    )

                    val resolvedStepStatus = when {
                        outcome.success -> StepStatus.COMPLETED
                        outcome.unableToVerify -> StepStatus.UNVERIFIED
                        else -> StepStatus.FAILED
                    }
                    steps[index] = steps[index].copy(
                        status = resolvedStepStatus,
                        verificationStatus = outcome.verification.status,
                        verificationResultAr = "${outcome.messageAr}\nحالة التحقق: ${outcome.verification.status.labelAr} — ${outcome.verification.explanationAr}"
                    )
                    plan = plan.copy(
                        steps = steps.toList(),
                        isComplete = steps.all { it.status == StepStatus.COMPLETED }
                    )
                    _state.update { it.copy(uiState = CommandsUiState.PlanActive(plan)) }

                    when {
                        outcome.success -> {
                            succeededList.add("الخطوة ${step.stepNumber} (${step.titleAr}): ${outcome.messageAr}")
                            speakIfEnabled(outcome.messageAr)
                        }
                        outcome.unableToVerify -> {
                            unverifiedList.add(
                                "الخطوة ${step.stepNumber} (${step.titleAr}): ${outcome.messageAr} — ${outcome.verification.explanationAr}"
                            )
                            speakIfEnabled(outcome.messageAr)
                        }
                        else -> {
                            failedList.add("الخطوة ${step.stepNumber} (${step.titleAr}): ${outcome.messageAr}")
                            speakIfEnabled(outcome.verification.explanationAr)
                        }
                    }
                    if (outcome.rolledBack) {
                        rolledBackList.add("الخطوة ${step.stepNumber} (${step.titleAr}): تم التراجع التلقائي الآمن")
                    }

                    if (!outcome.success || !outcome.verification.verified || singleStepOnly) {
                        break
                    }
                }

                val report = buildTruthReport(succeededList, unverifiedList, failedList, rolledBackList)
                if (!report.isNullOrBlank()) {
                    chatHistoryRepository.addAgentMessage(report, ChatMessageType.TEXT)
                    recordConversationTurn("تقرير تنفيذ: ${plan.originalCommand}", report)
                }
                _state.update { it.copy(executionTruthReportAr = report) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleGeminiFailure(
                    contextTag = "Step execution",
                    e = e,
                    retryType = ErrorRetryType.COMMAND,
                    retryCommand = lastSubmittedCommand
                )
            } finally {
                overlayWatcherJob.cancel()
                executionController.reset()
                if (!floatingControlManager.isMiniChatOpen()) {
                    floatingControlManager.dismiss()
                }
                _state.update {
                    it.copy(
                        isExecuting = false,
                        isPaused = false,
                        isHandedOver = false,
                        statusNoticeAr = "",
                        liveDoingNowAr = "",
                        liveNextStepAr = "",
                        liveWaitingForUserAr = ""
                    )
                }
            }
        }
    }

    fun togglePauseResume() {
        val paused = executionController.togglePaused()
        val statusText = if (paused) {
            "متوقف مؤقتاً — اضغط «استئناف» للمتابعة أو «أكمل بنفسي»"
        } else {
            "استئناف العمل... ${AgentPromptRules.WAIT_THINKING_NOTICE_AR}"
        }
        _state.update { it.copy(isPaused = paused, isHandedOver = false, statusNoticeAr = statusText) }
        viewModelScope.launch {
            floatingControlManager.updateStatus(statusText, paused)
        }
    }

    /**
     * «أكمل بنفسي»: يوقف الوكيل مؤقتاً ويسلّم التحكم للمستخدم مع طي الحبّة إلى فقاعة 56dp وزر «استئناف الوكيل».
     * لا يستدعي dismiss() أبداً — الإغلاق الكامل مخصص لزر «إيقاف» فقط.
     */
    fun handOverControlToUser() {
        executionController.setHandedOver(true)
        voiceManager.stopSpeaking()
        val statusText = "تم تسليم التحكم لك — اضغط «استئناف الوكيل» متى أردت المتابعة."
        _state.update {
            it.copy(
                isPaused = true,
                isHandedOver = true,
                statusNoticeAr = statusText,
                lastSpokenReply = statusText
            )
        }
        viewModelScope.launch {
            floatingControlManager.collapseToHandOverBubble()
            chatHistoryRepository.addAgentMessage(statusText, ChatMessageType.TEXT)
            actionLogRepository.logAction(
                title = "تسليم التحكم للمستخدم (أكمل بنفسي)",
                details = statusText,
                status = ActionStatus.SUCCESS,
                actionType = "USER_TAKEOVER"
            )
        }
    }

    fun resumeFromHandOver() {
        executionController.setHandedOver(false)
        val statusText = "تم استئناف عمل الوكيل... ${AgentPromptRules.WAIT_THINKING_NOTICE_AR}"
        _state.update {
            it.copy(
                isPaused = false,
                isHandedOver = false,
                statusNoticeAr = statusText
            )
        }
        viewModelScope.launch {
            floatingControlManager.resumeFromHandOver()
            actionLogRepository.logAction(
                title = "استئناف عمل الوكيل بعد تسليم التحكم",
                details = statusText,
                status = ActionStatus.SUCCESS,
                actionType = "AGENT_RESUME"
            )
        }
    }

    fun submitMiniChatCommand(rawCommand: String) {
        val command = rawCommand.trim()
        if (command.isEmpty()) return

        val wasGameplay = _state.value.isGameplayRunning || _state.value.isHandedOver
        val previousGoal = lastGameplayGoal

        // مقاطعة الخطة أو حلقة اللعب الجارية دون إغلاق الشريط/الحبّة العائمة
        activeTaskJob?.cancel()
        executionJob?.cancel()
        gameLoopJob?.cancel()
        executionController.reset()
        approvalDeferred?.complete(false)
        approvalDeferred = null
        voiceManager.stopSpeaking()

        activeTaskJob = viewModelScope.launch {
            floatingControlManager.show(
                initialStatusAr = "ينفّذ توجيه شريط الدردشة المصغّر…",
                isPaused = false,
                callbacks = buildFloatingControlCallbacks()
            )
            floatingControlManager.showMiniChat()

            _state.update {
                it.copy(
                    isBusyWorking = true,
                    isExecuting = false,
                    isGameplayRunning = false,
                    isPaused = false,
                    isHandedOver = false,
                    statusNoticeAr = "ينفّذ توجيه شريط الدردشة المصغّر…"
                )
            }
            chatHistoryRepository.addUserMessage(command)
            floatingControlManager.updateStatus("يفكر في توجيهك", isPaused = false)

            try {
                val miniPolicy = agentPolicy.evaluateCommand(command)
                if (!miniPolicy.allowed) {
                    val reply = miniPolicy.reasonAr.ifBlank {
                        UserMemoryRepository.formatPlainPolicyRejection(
                            actionType = "POLICY_BLOCKED_${miniPolicy.violationCategory?.name ?: "MINI_CHAT"}",
                            category = miniPolicy.violationCategory
                        )
                    }
                    recordConversationTurn(command, reply)
                    chatHistoryRepository.addAgentMessage(reply, ChatMessageType.TEXT)
                    _state.update {
                        it.copy(isBusyWorking = false, statusNoticeAr = "", lastSpokenReply = reply)
                    }
                    speakIfEnabled(reply)
                    floatingControlManager.showMiniChatReply(reply)
                    return@launch
                }

                val service = ScreenStateBridge.getService()
                if (service == null) {
                    val noAccMsg = "خدمة إمكانية الوصول غير مفعلة"
                    actionLogRepository.logAction(
                        title = "تعذّر تنفيذ توجيه الدردشة",
                        details = noAccMsg,
                        status = ActionStatus.FAILED,
                        actionType = "MINI_CHAT_NO_ACCESSIBILITY"
                    )
                    recordConversationTurn(command, noAccMsg)
                    chatHistoryRepository.addAgentMessage(noAccMsg, ChatMessageType.ERROR)
                    _state.update {
                        it.copy(isBusyWorking = false, statusNoticeAr = "", lastSpokenReply = noAccMsg)
                    }
                    speakIfEnabled(noAccMsg)
                    floatingControlManager.showMiniChatReply(noAccMsg)
                    return@launch
                }

                val observation = ScreenStateBridge.setScreenReadActive(true).let {
                    try {
                        withContext(Dispatchers.Main) {
                            service.captureDetailedScreenForGame()
                        }
                    } finally {
                        ScreenStateBridge.setScreenReadActive(false)
                    }
                }
                if (observation == null || observation.isNearEmptyTree()) {
                    val emptyTreeMsg = "لا أرى أزرار اللعبة — صف لي مكان الزر"
                    val scrubbedCmd = com.example.security.SensitiveDataScrubber.scrub(command)
                    val detailsMsg = if (privacyPreferences.isPrivacyModeEnabled()) {
                        emptyTreeMsg
                    } else {
                        "$scrubbedCmd | $emptyTreeMsg"
                    }
                    actionLogRepository.logAction(
                        title = "شاشة غير مقروءة في شريط الدردشة",
                        details = detailsMsg,
                        status = ActionStatus.FAILED,
                        actionType = "MINI_CHAT_NEAR_EMPTY_TREE"
                    )
                    recordConversationTurn(command, emptyTreeMsg)
                    chatHistoryRepository.addAgentMessage(emptyTreeMsg, ChatMessageType.TEXT)
                    _state.update {
                        it.copy(isBusyWorking = false, statusNoticeAr = "", lastSpokenReply = emptyTreeMsg)
                    }
                    speakIfEnabled(emptyTreeMsg)
                    floatingControlManager.showMiniChatReply(emptyTreeMsg)
                    return@launch
                }

                val screenSummary = observation.toPromptSummary()
                val meta = geminiRepository.analyzeMiniChatDirective(
                    userCommand = command,
                    screenSummary = AgentPromptRules.enrichGameContextSummary(screenSummary),
                    history = ConversationMemory.formatHistory(conversationTurns),
                    wasGameplayRunning = wasGameplay
                )
                recordModelRouteUsage(meta, command)
                val directive = meta.value

                if (directive.shouldAct) {
                    val gateOutcome = generalGameEngine.executeMiniChatDirectiveWithGate(
                        service = service,
                        directive = directive,
                        observation = observation
                    )
                    if (!gateOutcome.executed) {
                        val targetLabel = directive.targetText.ifBlank { command.take(30) }
                        val failMsg = if (gateOutcome.blockedByGate) {
                            gateOutcome.gateReasonAr.ifBlank {
                                "تم إيقاف الإجراء «$targetLabel» بانتظار موافقة صريحة."
                            }
                        } else {
                            "تعذّر الضغط على «$targetLabel» — هل ترى الزر على الشاشة؟"
                        }
                        if (!gateOutcome.blockedByGate) {
                            val scrubbedCmd = com.example.security.SensitiveDataScrubber.scrub(command)
                            val detailsMsg = if (privacyPreferences.isPrivacyModeEnabled()) {
                                failMsg
                            } else {
                                "$scrubbedCmd | $failMsg"
                            }
                            actionLogRepository.logAction(
                                title = "تعذّر تنفيذ الضغط من الدردشة",
                                details = detailsMsg,
                                status = ActionStatus.FAILED,
                                actionType = "MINI_CHAT_ACTION_FAILED"
                            )
                        }
                        recordConversationTurn(command, failMsg)
                        chatHistoryRepository.addAgentMessage(
                            failMsg,
                            if (gateOutcome.blockedByGate) ChatMessageType.TEXT else ChatMessageType.ERROR
                        )
                        _state.update {
                            it.copy(
                                isBusyWorking = false,
                                statusNoticeAr = "",
                                lastSpokenReply = failMsg,
                                activeError = if (gateOutcome.blockedByGate) {
                                    null
                                } else {
                                    CompactErrorState(
                                        titleAr = "تعذّر الضغط على «$targetLabel»",
                                        summaryAr = failMsg,
                                        technicalDetails = "action=${directive.action}, target=$targetLabel",
                                        retryCommand = command,
                                        retryType = ErrorRetryType.MINI_CHAT
                                    )
                                }
                            )
                        }
                        speakIfEnabled(failMsg)
                        if (gateOutcome.blockedByGate) {
                            floatingControlManager.showMiniChatReply(failMsg)
                        } else {
                            floatingControlManager.showMiniChatReply(failMsg) {
                                submitMiniChatCommand(command)
                            }
                        }
                        return@launch
                    }
                }

                val replyLine = directive.replyOneLineAr.ifBlank {
                    if (directive.shouldAct) "تم تنفيذ توجيهك على الشاشة." else "لم أستطع تنفيذ الضغط"
                }
                val isParseFailure = !directive.shouldAct && replyLine == "لم أستطع تنفيذ الضغط"
                if (isParseFailure) {
                    val scrubbedCmd = com.example.security.SensitiveDataScrubber.scrub(command)
                    val detailsMsg = if (privacyPreferences.isPrivacyModeEnabled()) {
                        replyLine
                    } else {
                        "$scrubbedCmd | $replyLine"
                    }
                    actionLogRepository.logAction(
                        title = "تعذّر فهم توجيه الدردشة",
                        details = detailsMsg,
                        status = ActionStatus.FAILED,
                        actionType = "MINI_CHAT_NON_JSON_REPLY"
                    )
                }
                recordConversationTurn(command, replyLine)
                chatHistoryRepository.addAgentMessage(
                    replyLine,
                    if (isParseFailure) ChatMessageType.ERROR else ChatMessageType.TEXT
                )
                _state.update {
                    it.copy(isBusyWorking = false, statusNoticeAr = "", lastSpokenReply = replyLine)
                }
                speakIfEnabled(replyLine)
                if (isParseFailure) {
                    floatingControlManager.showMiniChatReply(replyLine) {
                        submitMiniChatCommand(command)
                    }
                } else {
                    floatingControlManager.showMiniChatReply(replyLine)
                }

                if (directive.shouldAct && directive.continuePlaying) {
                    startGameplayLoop(
                        goalText = command.ifBlank { previousGoal ?: "تابع اللعب على الشاشة الحالية" },
                        skipIntroMessage = true
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val shortErr = classifyModelError(e).category.titleAr
                floatingControlManager.showMiniChatReply("تعذر التنفيذ: $shortErr") {
                    submitMiniChatCommand(command)
                }
                handleGeminiFailure(
                    contextTag = "Mini chat command",
                    e = e,
                    retryType = ErrorRetryType.MINI_CHAT,
                    retryCommand = command
                )
            }
        }
    }

    fun toggleMiniChatMic() {
        val granted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            viewModelScope.launch {
                floatingControlManager.showMiniChatReply("فعّل إذن الميكروفون من تبويب الأذونات أولاً.")
            }
            return
        }
        val fallbackHandler: (ByteArray, String) -> Unit = { bytes, mime ->
            viewModelScope.launch {
                floatingControlManager.setMiniChatVoiceState(isListening = false)
                floatingControlManager.showMiniChatReply("جارٍ تحليل صوتك الاحتياطي...")
            }
            processRecordedAudio(bytes, mime, fromMiniChat = true)
        }
        if (voiceManager.isCurrentlyListening()) {
            voiceManager.stopListening(
                onAudioRecordedFallback = fallbackHandler,
                onError = { err ->
                    viewModelScope.launch {
                        floatingControlManager.setMiniChatVoiceState(isListening = false)
                        floatingControlManager.showMiniChatReply(err.take(80))
                    }
                }
            )
            viewModelScope.launch {
                floatingControlManager.setMiniChatVoiceState(isListening = false)
            }
            return
        }
        viewModelScope.launch {
            floatingControlManager.setMiniChatVoiceState(isListening = true)
        }
        voiceManager.startListening(
            onPartialResult = { partial ->
                viewModelScope.launch {
                    floatingControlManager.setMiniChatVoiceState(isListening = true, partialText = partial)
                }
            },
            onFinalTranscript = { transcript ->
                viewModelScope.launch {
                    floatingControlManager.setMiniChatVoiceState(isListening = false, partialText = transcript)
                }
                submitMiniChatCommand(transcript)
            },
            onAudioRecordedFallback = fallbackHandler,
            onError = { err ->
                viewModelScope.launch {
                    floatingControlManager.setMiniChatVoiceState(isListening = false)
                    floatingControlManager.showMiniChatReply(err.take(80))
                }
            }
        )
    }

    /**
     * زر الإيقاف الفوري أثناء أي مهمة: يلغي مهمة Gemini والتنفيذ الجاري وحلقة اللعب والنطق فوراً.
     */
    fun stopCurrentTask() {
        terminateRunningJobs(
            clearPlan = false,
            chatMessage = "تم إيقاف المهمة",
            logTitle = "إيقاف فوري للمهمة",
            logActionType = "INSTANT_STOP"
        )
    }

    /**
     * إلغاء المهمة الحالية من شاشة الدردشة وإغلاق أي خطة معلقة.
     */
    fun cancelCurrentTask() {
        terminateRunningJobs(
            clearPlan = true,
            chatMessage = "تم إلغاء المهمة الحالية.",
            logTitle = "إلغاء المهمة الحالية",
            logActionType = "CANCEL_CURRENT_TASK"
        )
    }

    private fun terminateRunningJobs(
        clearPlan: Boolean,
        chatMessage: String,
        logTitle: String,
        logActionType: String
    ) {
        activeTaskJob?.cancel()
        executionJob?.cancel()
        gameLoopJob?.cancel()
        geminiTimerJob?.cancel()
        activeTaskJob = null
        executionJob = null
        gameLoopJob = null
        geminiTimerJob = null

        executionController.reset()
        approvalDeferred?.complete(false)
        approvalDeferred = null

        voiceManager.stopSpeaking()
        clearCachedRecordedAudio()
        if (_state.value.isListening) {
            runCatching {
                voiceManager.stopListening(onAudioRecordedFallback = { _, _ -> }, onError = {})
            }
        }

        val current = _state.value.uiState
        val nextUiState = if (!clearPlan && current is CommandsUiState.PlanActive) {
            val updatedSteps = current.plan.steps.map {
                if (it.status == StepStatus.PENDING || it.status == StepStatus.RUNNING ||
                    it.status == StepStatus.VERIFYING || it.status == StepStatus.AWAITING_APPROVAL
                ) {
                    it.copy(status = StepStatus.CANCELLED)
                } else it
            }
            CommandsUiState.PlanActive(current.plan.copy(steps = updatedSteps, isCancelled = true))
        } else {
            CommandsUiState.Idle
        }

        _state.update {
            it.copy(
                uiState = nextUiState,
                isBusyWorking = false,
                isExecuting = false,
                isGameplayRunning = false,
                isPaused = false,
                isHandedOver = false,
                isListening = false,
                statusNoticeAr = "",
                liveDoingNowAr = "",
                liveNextStepAr = "",
                liveWaitingForUserAr = "",
                geminiElapsedSeconds = 0,
                pendingApprovalStep = null,
                pendingApprovalPlan = null,
                pendingApprovalNextStep = null,
                pendingApprovalLogId = null,
                pendingTaskCancelConfirmation = null,
                lastSpokenReply = chatMessage
            )
        }

        viewModelScope.launch {
            floatingControlManager.dismiss()
            recordConversationTurn("إيقاف المهمة", chatMessage)
            chatHistoryRepository.addAgentMessage(chatMessage, ChatMessageType.STOPPED)
            actionLogRepository.logAction(
                title = logTitle,
                details = chatMessage,
                status = ActionStatus.CANCELLED,
                actionType = logActionType
            )
        }
    }

    private suspend fun requestUserApprovalViaUi(request: SensitiveActionRequest): Boolean {
        val displayStep = request.step ?: AgentPlanStep(
            stepNumber = 1,
            titleAr = request.titleAr,
            descriptionAr = request.descriptionAr,
            actionType = request.actionType ?: StepActionType.TAP_ELEMENT,
            targetAppName = request.targetAppName,
            targetQueryOrText = request.targetText,
            requiresApproval = true,
            safetyReasonAr = request.safetyReasonAr
        )
        val pendingLogId = actionLogRepository.logAction(
            title = "طلب موافقة على إجراء حساس: ${request.titleAr}",
            details = "المصدر: ${request.source.titleAr} | السبب: ${request.safetyReasonAr.ifBlank { request.descriptionAr }}",
            status = ActionStatus.PENDING_APPROVAL,
            actionType = (request.actionType ?: StepActionType.TAP_ELEMENT).name
        )
        val deferred = CompletableDeferred<Boolean>()
        approvalDeferred = deferred
        _state.update {
            it.copy(
                pendingApprovalStep = displayStep,
                pendingApprovalPlan = request.plan,
                pendingApprovalNextStep = request.nextStep,
                pendingApprovalLogId = pendingLogId,
                statusNoticeAr = "بانتظار موافقتك: ${request.titleAr}"
            )
        }
        val approvalNotice = "بانتظار موافقتك: ${request.titleAr}"
        floatingControlManager.updateStatus(approvalNotice, executionController.isPaused.value)
        if (request.source != SensitiveActionSource.PLAN_STEP ||
            _state.value.isGameplayRunning ||
            floatingControlManager.isMiniChatOpen()
        ) {
            floatingControlManager.showApprovalPrompt(
                titleAr = request.titleAr,
                detailsAr = request.descriptionAr
            )
        }
        speakIfEnabled("إجراء حساس: ${request.titleAr}. وافق أو ألغِ.")

        var approved = false
        var completed = false
        return try {
            approved = deferred.await()
            completed = true
            if (approved) {
                actionLogRepository.updateStatus(pendingLogId, ActionStatus.SUCCESS)
                actionLogRepository.logAction(
                    title = "موافقة صريحة من المستخدم على: ${request.titleAr}",
                    details = "وافق المستخدم بعد مراجعة تفاصيل الإجراء (${request.source.titleAr}).",
                    status = ActionStatus.SUCCESS,
                    actionType = "USER_APPROVED_SENSITIVE"
                )
            } else {
                actionLogRepository.updateStatus(pendingLogId, ActionStatus.CANCELLED)
                actionLogRepository.logAction(
                    title = "رفض المستخدم تنفيذ: ${request.titleAr}",
                    details = "أُلغي الإجراء الحساس بناءً على قرار المستخدم الصريح (${request.source.titleAr}).",
                    status = ActionStatus.CANCELLED,
                    actionType = "USER_REJECTED_SENSITIVE"
                )
            }
            approved
        } finally {
            withContext(NonCancellable) {
                if (!completed) {
                    actionLogRepository.updateStatus(pendingLogId, ActionStatus.CANCELLED)
                    actionLogRepository.logAction(
                        title = "انتهاء مهلة أو إلغاء الموافقة: ${request.titleAr}",
                        details = "انتهت مهلة الموافقة (120 ثانية) أو أُلغي الانتظار احترازياً.",
                        status = ActionStatus.CANCELLED,
                        actionType = "APPROVAL_TIMEOUT"
                    )
                }
                approvalDeferred = null
                _state.update {
                    it.copy(
                        pendingApprovalStep = null,
                        pendingApprovalPlan = null,
                        pendingApprovalNextStep = null,
                        pendingApprovalLogId = null
                    )
                }
                floatingControlManager.hideApprovalPrompt()
            }
        }
    }

    private fun buildFloatingControlCallbacks(): FloatingControlCallbacks {
        return object : FloatingControlCallbacks {
            override fun onStopImmediately() {
                stopCurrentTask()
            }

            override fun onTogglePause() {
                togglePauseResume()
            }

            override fun onTakeOverByUser() {
                handOverControlToUser()
            }

            override fun onResumeAgent() {
                resumeFromHandOver()
            }

            override fun onMiniChatSubmit(command: String) {
                submitMiniChatCommand(command)
            }

            override fun onMiniChatMicClick() {
                toggleMiniChatMic()
            }

            override fun onApproveSensitiveAction(approved: Boolean) {
                resolveApproval(approved)
            }
        }
    }

    fun resolveApproval(approved: Boolean) {
        approvalDeferred?.complete(approved)
    }

    fun cancelActivePlan() {
        cancelCurrentTask()
    }

    fun resetToIdle() {
        executionJob?.cancel()
        geminiTimerJob?.cancel()
        geminiTimerJob = null
        clearCachedRecordedAudio()
        executionController.reset()
        _state.update {
            it.copy(
                inputText = "",
                uiState = CommandsUiState.Idle,
                pendingApprovalStep = null,
                pendingApprovalPlan = null,
                pendingApprovalNextStep = null,
                isExecuting = false,
                isPaused = false,
                statusNoticeAr = "",
                liveDoingNowAr = "",
                liveNextStepAr = "",
                liveWaitingForUserAr = "",
                geminiElapsedSeconds = 0,
                executionTruthReportAr = null
            )
        }
    }

    fun reportMicPermissionDenied() {
        val msg = "إذن الميكروفون غير مفعل. يمكنك تفعيله من تبويب «الأذونات» أو كتابة أمرك نصياً."
        _state.update {
            it.copy(
                isListening = false,
                uiState = CommandsUiState.Idle,
                activeError = CompactErrorState(
                    titleAr = "إذن الميكروفون مطلوب",
                    summaryAr = msg,
                    technicalDetails = "",
                    retryType = ErrorRetryType.VOICE
                )
            )
        }
    }

    private fun speakIfEnabled(text: String) {
        if (_state.value.voiceConversationMode && text.isNotBlank()) {
            val tier = _state.value.networkTier
            val maxChars = if (tier == NetworkTier.WEAK_E_2G || tier == NetworkTier.OFFLINE) {
                VoiceAssistantManager.WEAK_NETWORK_SPEECH_MAX_CHARS
            } else {
                VoiceAssistantManager.DEFAULT_SPEECH_MAX_CHARS
            }
            voiceManager.speakArabic(text, maxChars = maxChars)
        }
    }

    private fun buildPlanChatSummary(plan: AgentPlan): String = buildString {
        appendLine("جهّزت خطة من ${plan.steps.size} خطوات: ${plan.understandingSummaryAr}")
        plan.steps.forEach { step ->
            val tag = if (step.requiresApproval) " [يتطلب موافقتك]" else ""
            appendLine("${step.stepNumber}. ${step.titleAr}$tag")
        }
        if (plan.spokenReplyAr.isNotBlank()) {
            appendLine()
            append(plan.spokenReplyAr)
        }
    }.trim()

    private fun scheduleExactAlarmInternal(
        requestCode: Int,
        dueAtMillis: Long,
        title: String,
        message: String
    ): Boolean {
        return try {
            ReminderReceiver.ensureReminderChannel(appContext)
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val receiverIntent = Intent(appContext, ReminderReceiver::class.java).apply {
                putExtra(ReminderReceiver.EXTRA_TITLE, title)
                putExtra(ReminderReceiver.EXTRA_MESSAGE, message)
                putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, requestCode)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                requestCode,
                receiverIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAtMillis, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAtMillis, pendingIntent)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exact alarm scheduling failed", e)
            false
        }
    }

    private fun encodeTasksJson(tasks: List<AgentTaskEntity>): String {
        val arr = JSONArray()
        tasks.forEach { task ->
            arr.put(
                JSONObject().apply {
                    put("id", task.id)
                    put("title", task.title)
                    put("taskType", task.taskType)
                    put("dueAtMillis", task.dueAtMillis ?: -1L)
                    put("isCompleted", task.isCompleted)
                }
            )
        }
        return arr.toString()
    }

    override fun onCleared() {
        super.onCleared()
        sensitiveActionGate.setApprovalHandler(null)
        ScreenStateBridge.setTaskActive(false)
        clearCachedRecordedAudio()
        networkConnectivityMonitor.unregister()
        runCatching { appContext.unregisterReceiver(packageChangeReceiver) }
        voiceManager.release()
    }

    // ==================== وظائف المرحلة 6 (الذاكرة، العادات، الروتين الصباحي، التلخيص اليومي، التنبؤ) ====================

    fun openMemoryHabitsDialog() {
        _state.update { it.copy(showMemoryHabitsDialog = true) }
    }

    fun dismissMemoryHabitsDialog() {
        _state.update { it.copy(showMemoryHabitsDialog = false) }
    }

    fun forgetMemoryFact(id: Long) {
        viewModelScope.launch {
            userMemoryRepository.forgetFact(id)
        }
    }

    fun clearAllUserMemory() {
        viewModelScope.launch {
            synchronized(conversationTurns) {
                conversationTurns.clear()
            }
            userMemoryRepository.clearMemory()
            memoryAndHabitPreferences.setTurnsSinceLastSummary(0)
        }
    }

    fun clearAllHabits() {
        viewModelScope.launch {
            userMemoryRepository.clearHabits()
            memoryAndHabitPreferences.clearDismissedSuggestionKeys()
            _state.update { it.copy(activePredictiveSuggestion = null) }
        }
    }

    fun setPredictiveLearningEnabled(enabled: Boolean) {
        memoryAndHabitPreferences.setPredictiveLearningEnabled(enabled)
        _state.update {
            it.copy(
                predictiveLearningEnabled = enabled,
                activePredictiveSuggestion = if (!enabled) null else it.activePredictiveSuggestion
            )
        }
        if (enabled) {
            viewModelScope.launch { refreshPredictiveSuggestion() }
        }
    }

    fun setAnonymizedHabitGeminiConsent(consented: Boolean) {
        memoryAndHabitPreferences.setAnonymizedHabitGeminiConsent(consented)
        _state.update { it.copy(anonymizedHabitGeminiConsent = consented) }
        if (consented && memoryAndHabitPreferences.isPredictiveLearningEnabled()) {
            viewModelScope.launch { refreshPredictiveSuggestion() }
        }
    }

    fun resetMorningRoutineApproval() {
        memoryAndHabitPreferences.setMorningRoutineApproved(false)
        _state.update { it.copy(morningRoutineApproved = false) }
    }

    fun dismissPredictiveSuggestion() {
        val current = _state.value.activePredictiveSuggestion ?: return
        memoryAndHabitPreferences.addDismissedSuggestionKey(current.key)
        _state.update { it.copy(activePredictiveSuggestion = null) }
    }

    /**
     * التعلّم التنبؤي لا ينفّذ تلقائياً أبداً: عند ضغط المستخدم على زر التجهيز نضع الأمر المقترح في مربع الإدخال فقط ليراجعه ويرسله بنفسه.
     */
    fun applyPredictiveSuggestion() {
        val current = _state.value.activePredictiveSuggestion ?: return
        _state.update {
            it.copy(
                inputText = current.suggestedCommand,
                activePredictiveSuggestion = null
            )
        }
    }

    suspend fun refreshPredictiveSuggestion(nowMillis: Long = System.currentTimeMillis()) {
        if (privacyPreferences.isPrivacyModeEnabled() || !memoryAndHabitPreferences.isPredictiveLearningEnabled()) {
            _state.update { it.copy(activePredictiveSuggestion = null) }
            return
        }
        val localSuggestion = habitLearningEngine.detectLocalPredictiveSuggestion(nowMillis)
        if (localSuggestion == null) {
            _state.update { it.copy(activePredictiveSuggestion = null) }
            return
        }

        // لا يُرسل إلى Gemini أي بيانات خام؛ فقط ملخص مجهول الهوية عند موافقة المستخدم المعلنة
        val finalSuggestion = if (memoryAndHabitPreferences.isAnonymizedHabitGeminiConsent()) {
            val anonymized = habitLearningEngine.getAnonymizedSummaryIfConsented(nowMillis)
            if (anonymized != null) {
                val refinedReason = runCatching {
                    geminiRepository.suggestNextHabitStepFromAnonymizedSummary(
                        anonymizedSummary = anonymized.first,
                        timeBucketAr = anonymized.second
                    ).value
                }.getOrNull()?.takeIf { it.isNotBlank() }
                if (refinedReason != null) {
                    localSuggestion.copy(
                        reasonAr = "$refinedReason\n(${localSuggestion.suggestedCommand})",
                        isFromGemini = true
                    )
                } else {
                    localSuggestion
                }
            } else {
                localSuggestion
            }
        } else {
            localSuggestion
        }

        _state.update { it.copy(activePredictiveSuggestion = finalSuggestion) }
    }

    private suspend fun handleMorningRoutine(
        installedApps: List<InstalledAppInfo>,
        userCommand: String
    ) {
        if (!memoryAndHabitPreferences.isMorningRoutineApproved()) {
            val promptMsg = "اختصار «الروتين الصباحي» جاهز! يرجى تأكيد الموافقة لمرة واحدة فقط لتشغيله بأمر واحد مستقبلاً: سأعرض لك مهامك الصباحية وتذكيرات اليوم وأبرز تطبيقاتك الصباحية دون تنفيذ أي إجراء محظور."
            recordConversationTurn(userCommand, promptMsg)
            chatHistoryRepository.addAgentMessage(promptMsg, ChatMessageType.TEXT)
            _state.update {
                it.copy(
                    uiState = CommandsUiState.Idle,
                    pendingMorningRoutineApproval = true,
                    lastSpokenReply = promptMsg
                )
            }
            speakIfEnabled(promptMsg)
            return
        }
        executeMorningRoutineSteps(installedApps, userCommand)
    }

    fun approveMorningRoutine() {
        memoryAndHabitPreferences.setMorningRoutineApproved(true)
        _state.update {
            it.copy(
                pendingMorningRoutineApproval = false,
                morningRoutineApproved = true
            )
        }
        viewModelScope.launch {
            actionLogRepository.logAction(
                title = "الموافقة الأولى على الروتين الصباحي",
                details = "اعتمد المستخدم اختصار الروتين الصباحي للتشغيل بأمر واحد مستقبلاً.",
                status = ActionStatus.SUCCESS,
                actionType = "MORNING_ROUTINE_APPROVED"
            )
            val installed = ensureInstalledAppsLoaded()
            executeMorningRoutineSteps(installed, "الروتين الصباحي")
        }
    }

    fun dismissMorningRoutineApproval() {
        _state.update { it.copy(pendingMorningRoutineApproval = false) }
        viewModelScope.launch {
            val msg = "تم إلغاء تفعيل الروتين الصباحي بناءً على طلبك."
            chatHistoryRepository.addAgentMessage(msg, ChatMessageType.TEXT)
            _state.update { it.copy(lastSpokenReply = msg) }
        }
    }

    internal suspend fun executeMorningRoutineSteps(
        installedApps: List<InstalledAppInfo>,
        userCommand: String = "الروتين الصباحي"
    ) {
        habitLearningEngine.syncHabits(installedApps)
        val activeTasks = chatHistoryRepository.getActiveTasks()
        val pendingTasks = activeTasks.filter { !it.isCompleted && !it.isCancelled }
        val habits = userMemoryRepository.getTopHabits()
        val favoriteMorningApp = HabitLearningEngine.selectMorningFavoriteApp(habits, installedApps)

        var openedAppNote = ""
        if (favoriteMorningApp != null && agentPolicy.evaluateCommand("افتح ${favoriteMorningApp.label}").allowed) {
            val launchIntent = AppOrganizer.createLaunchIntent(appContext, favoriteMorningApp.packageName)
            if (launchIntent != null) {
                val started = runCatching { appContext.startActivity(launchIntent) }.isSuccess
                if (started) {
                    openedAppNote = "• تم فتح تطبيقك الصباحي المعتاد: «${favoriteMorningApp.label}»."
                }
            }
        }

        val summary = buildString {
            appendLine("☀️ تم تنفيذ «الروتين الصباحي»:")
            if (pendingTasks.isEmpty()) {
                appendLine("• لا توجد مهام أو منبهات معلّقة حالياً.")
            } else {
                appendLine("• لديك ${pendingTasks.size} مهام/تذكيرات نشطة: ${pendingTasks.take(4).joinToString("، ") { it.title }}.")
            }
            if (openedAppNote.isNotBlank()) {
                appendLine(openedAppNote)
            } else {
                appendLine("• يمكنك قول «ذكّرني بـ...» أو «لخّص يومي» لمتابعة يومك.")
            }
        }.trim()

        val cal = Calendar.getInstance()
        userMemoryRepository.recordHabit(
            actionName = "الروتين الصباحي",
            hourOfDay = cal.get(Calendar.HOUR_OF_DAY),
            dayOfWeek = HabitLearningEngine.calendarDayToIso(cal.get(Calendar.DAY_OF_WEEK))
        )
        actionLogRepository.logAction(
            title = "تنفيذ الروتين الصباحي",
            details = summary,
            status = ActionStatus.SUCCESS,
            actionType = "MORNING_ROUTINE"
        )
        recordConversationTurn(userCommand, summary)
        chatHistoryRepository.addAgentMessage(summary, ChatMessageType.TEXT)
        _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = summary) }
        speakIfEnabled(summary)
    }

    internal suspend fun handleSummarizeMyDay(
        installedApps: List<InstalledAppInfo>,
        userCommand: String = "لخّص يومي"
    ) {
        habitLearningEngine.syncHabits(installedApps)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDayMillis = cal.timeInMillis
        val todayLogs = actionLogRepository.getLogsSince(startOfDayMillis)
        val tasks = chatHistoryRepository.getActiveTasks()
        val habits = userMemoryRepository.getTopHabits()
        val digest = HabitLearningEngine.buildDaySummaryDigest(todayLogs, tasks, habits)

        val isPrivacy = privacyPreferences.isPrivacyModeEnabled()
        val isOffline = networkConnectivityMonitor.refreshNow() == NetworkTier.OFFLINE
        val finalReply = if (isPrivacy || isOffline) {
            val label = if (isPrivacy) "📋 ملخص يومك المحلي (وضع الخصوصية مفعّل):" else "📋 ملخص يومك المحلي (دون اتصال بالشبكة):"
            "$label\n$digest"
        } else {
            runCatching {
                startGeminiProgressTimer(
                    doingNowAr = "ألخّص إنجازات يومك ومهامك...",
                    nextStepAr = "عرض ملخص اليوم في المحادثة",
                    waitingAr = "انتظار النموذج الخفيف (Lite)"
                )
                val meta = geminiRepository.summarizeDayWithLite(digest)
                recordModelRouteUsage(meta, userCommand)
                meta.value
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: "📋 ملخص يومك:\n$digest"
        }
        stopGeminiProgressTimer()

        val nowCal = Calendar.getInstance()
        userMemoryRepository.recordHabit(
            actionName = "لخّص يومي",
            hourOfDay = nowCal.get(Calendar.HOUR_OF_DAY),
            dayOfWeek = HabitLearningEngine.calendarDayToIso(nowCal.get(Calendar.DAY_OF_WEEK))
        )
        actionLogRepository.logAction(
            title = "تلخيص اليوم (لخّص يومي)",
            details = "تم تلخيص ${todayLogs.size} إجراءات و${tasks.size} مهام.",
            status = ActionStatus.SUCCESS,
            actionType = "SUMMARIZE_MY_DAY"
        )
        recordConversationTurn(userCommand, finalReply)
        chatHistoryRepository.addAgentMessage(finalReply, ChatMessageType.TEXT)
        _state.update {
            it.copy(
                uiState = CommandsUiState.Idle,
                liveDoingNowAr = "",
                liveNextStepAr = "",
                liveWaitingForUserAr = "",
                lastSpokenReply = finalReply
            )
        }
        speakIfEnabled(finalReply)
    }

    companion object {
        internal const val MAX_ACTIVE_CONVERSATION_TURNS = 30
        internal const val KEEP_AFTER_TRUNCATION_TURNS = 20

        internal fun resolveTimeoutSeconds(tier: NetworkTier): Int = when (tier) {
            NetworkTier.WEAK_E_2G -> (com.example.data.ai.WEAK_NETWORK_COMMAND_TIMEOUT_MS / 1000L).toInt()
            else -> (com.example.data.ai.TOTAL_GEMINI_COMMAND_TIMEOUT_MS / 1000L).toInt()
        }

        internal fun buildTruthReport(
            succeeded: List<String>,
            unverified: List<String> = emptyList(),
            failed: List<String> = emptyList(),
            rolledBack: List<String> = emptyList()
        ): String? {
            if (succeeded.isEmpty() && unverified.isEmpty() && failed.isEmpty() && rolledBack.isEmpty()) {
                return null
            }
            return buildString {
                var hasPrevious = false
                if (succeeded.isNotEmpty()) {
                    appendLine("✅ ما نجح فعلياً:")
                    succeeded.forEach { appendLine("• $it") }
                    hasPrevious = true
                }
                if (unverified.isNotEmpty()) {
                    if (hasPrevious) appendLine()
                    appendLine("⚠️ ما تعذّر التحقق منه:")
                    unverified.forEach { appendLine("• $it") }
                    hasPrevious = true
                }
                if (failed.isNotEmpty()) {
                    if (hasPrevious) appendLine()
                    appendLine("❌ ما فشل ولماذا:")
                    failed.forEach { appendLine("• $it") }
                    hasPrevious = true
                }
                if (rolledBack.isNotEmpty()) {
                    if (hasPrevious) appendLine()
                    appendLine("↩️ ما تم التراجع عنه:")
                    rolledBack.forEach { appendLine("• $it") }
                }
            }.trim()
        }
    }

    class Factory(
        private val geminiRepository: GeminiRepository,
        private val commandExecutor: CommandExecutor,
        private val actionLogRepository: ActionLogRepository,
        private val appOrganizer: AppOrganizer,
        private val screenVerifier: ScreenVerifier,
        private val voiceManager: VoiceAssistantManager,
        private val chatHistoryRepository: ChatHistoryRepository,
        private val generalGameEngine: GeneralGameEngine,
        private val executionController: AgentExecutionController,
        private val floatingControlManager: AgentFloatingControlManager,
        private val appContext: Context,
        private val agentPolicy: AgentPolicy = AgentPolicy(),
        private val sensitiveActionGate: SensitiveActionGate = SensitiveActionGate(agentPolicy),
        private val phoneFileManager: PhoneFileManager = PhoneFileManager(
            context = appContext,
            actionLogRepository = actionLogRepository,
            agentPolicy = agentPolicy
        ),
        private val privacyPreferences: com.example.security.PrivacyPreferences = com.example.security.PrivacyPreferences(appContext),
        private val userMemoryRepository: UserMemoryRepository? = null,
        private val memoryAndHabitPreferences: MemoryAndHabitPreferences = MemoryAndHabitPreferences(appContext),
        private val habitLearningEngine: HabitLearningEngine? = null,
        private val networkConnectivityMonitor: NetworkConnectivityMonitor? = null,
        private val queuedCommandDao: QueuedCommandDao? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db = PhoneAgentDatabase.getInstance(appContext)
            val resolvedMemoryRepo = userMemoryRepository ?: UserMemoryRepository(
                memoryDao = db.userMemoryDao(),
                habitDao = db.habitLogDao(),
                summaryDao = db.conversationSummaryDao(),
                privacyPreferences = privacyPreferences
            )
            val resolvedHabitEngine = habitLearningEngine ?: HabitLearningEngine(
                appContext = appContext,
                userMemoryRepository = resolvedMemoryRepo,
                actionLogDao = db.actionLogDao(),
                memoryPrefs = memoryAndHabitPreferences,
                privacyPreferences = privacyPreferences,
                agentPolicy = agentPolicy
            )
            val resolvedNetworkMonitor = networkConnectivityMonitor ?: NetworkConnectivityMonitor(appContext)
            val resolvedQueuedDao = queuedCommandDao ?: db.queuedCommandDao()
            return CommandsViewModel(
                geminiRepository = geminiRepository,
                commandExecutor = commandExecutor,
                actionLogRepository = actionLogRepository,
                appOrganizer = appOrganizer,
                screenVerifier = screenVerifier,
                voiceManager = voiceManager,
                chatHistoryRepository = chatHistoryRepository,
                generalGameEngine = generalGameEngine,
                executionController = executionController,
                floatingControlManager = floatingControlManager,
                appContext = appContext,
                agentPolicy = agentPolicy,
                sensitiveActionGate = sensitiveActionGate,
                phoneFileManager = phoneFileManager,
                privacyPreferences = privacyPreferences,
                userMemoryRepository = resolvedMemoryRepo,
                memoryAndHabitPreferences = memoryAndHabitPreferences,
                habitLearningEngine = resolvedHabitEngine,
                networkConnectivityMonitor = resolvedNetworkMonitor,
                queuedCommandDao = resolvedQueuedDao
            ) as T
        }
    }
}
