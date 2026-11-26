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
import com.example.executor.ChatIntentRouter
import com.example.executor.ChatRouteIntent
import com.example.executor.CommandExecutor
import com.example.executor.ReminderReceiver
import com.example.game.GeneralGameEngine
import com.example.model.AgentPlan
import com.example.model.AgentPlanStep
import com.example.model.StepStatus
import com.example.model.StepVerificationStatus
import com.example.organizer.AppOrganizer
import com.example.organizer.InstalledAppInfo
import com.example.overlay.AgentExecutionController
import com.example.overlay.AgentFloatingControlManager
import com.example.overlay.FloatingControlCallbacks
import com.example.policy.AgentPolicy
import com.example.policy.AgentPromptRules
import com.example.service.ScreenStateBridge
import com.example.verifier.ScreenVerifier
import com.example.voice.VoiceAssistantManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
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
import com.example.BuildConfig
import com.example.security.DisconnectionCategory
import com.example.security.classifyModelError
import com.example.security.getActiveProjectId
import com.example.security.getEffectiveDebugSecret

private const val TAG = "CommandsViewModel"

enum class ErrorRetryType {
    CONNECTION_CHECK,
    COMMAND,
    VOICE,
    GAMEPLAY
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
    val pendingApprovalLogId: Long? = null,
    val isExecuting: Boolean = false,
    val isBusyWorking: Boolean = false,
    val isGameplayRunning: Boolean = false,
    val isPaused: Boolean = false,
    val statusNoticeAr: String = "",
    val showPlayFeatureDialog: Boolean = false,
    val executionTruthReportAr: String? = null,
    val modelConnectionStatus: ModelConnectionStatus = ModelConnectionStatus.NotChecked,
    val appCheckDebugToken: String = "",
    val activeProjectId: String = "",
    val isModelStatusExpanded: Boolean = false,
    val activeError: CompactErrorState? = null,
    val isErrorDetailsExpanded: Boolean = false,
    val lastModelTraceAr: String = ""
) {
    val isAnyTaskRunning: Boolean
        get() = isBusyWorking || uiState is CommandsUiState.Planning || isExecuting || isGameplayRunning
}

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
    private val agentPolicy: AgentPolicy = AgentPolicy()
) : ViewModel() {

    private val _state = MutableStateFlow(CommandsScreenState())
    val state: StateFlow<CommandsScreenState> = _state.asStateFlow()

    val isAccessibilityConnected: StateFlow<Boolean> = ScreenStateBridge.isServiceConnected

    val messages: StateFlow<List<ChatMessageEntity>> = chatHistoryRepository.messagesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val activeTasks: StateFlow<List<AgentTaskEntity>> = chatHistoryRepository.activeTasksFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _installedAppsByPackage = MutableStateFlow<Map<String, InstalledAppInfo>>(emptyMap())
    val installedAppsByPackage: StateFlow<Map<String, InstalledAppInfo>> = _installedAppsByPackage.asStateFlow()

    private var activeTaskJob: Job? = null
    private var executionJob: Job? = null
    private var gameLoopJob: Job? = null
    private var approvalDeferred: CompletableDeferred<Boolean>? = null
    private var cachedInstalledApps: List<InstalledAppInfo> = emptyList()
    private var lastSubmittedCommand: String? = null
    private var lastRecordedAudio: Pair<ByteArray, String>? = null
    private var lastGameplayGoal: String? = null

    init {
        val debugToken = if (BuildConfig.DEBUG) {
            getEffectiveDebugSecret(appContext)
        } else ""
        val projId = getActiveProjectId(appContext)
        _state.update {
            it.copy(
                appCheckDebugToken = debugToken,
                activeProjectId = projId
            )
        }
        refreshInstalledAppsCache()
        observeExecutionPauseState()
        if (BuildConfig.DEBUG) {
            checkModelConnection(isInitialSelfCheck = true)
        }
    }

    fun toggleModelStatusExpanded() {
        _state.update { it.copy(isModelStatusExpanded = !it.isModelStatusExpanded) }
    }

    fun toggleErrorDetailsExpanded() {
        _state.update { it.copy(isErrorDetailsExpanded = !it.isErrorDetailsExpanded) }
    }

    fun dismissActiveError() {
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
        dismissActiveError()
        when (error?.retryType) {
            ErrorRetryType.COMMAND -> {
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
                startGameplayGoal(lastGameplayGoal ?: "العب اللعبة أو التطبيق الظاهر على الشاشة كالإنسان خطوة بخطوة")
            }
            ErrorRetryType.CONNECTION_CHECK, null -> checkModelConnection()
        }
    }

    fun checkModelConnection(isInitialSelfCheck: Boolean = false) {
        viewModelScope.launch {
            _state.update {
                it.copy(
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
    }

    private fun refreshInstalledAppsCache() {
        viewModelScope.launch {
            runCatching {
                ensureInstalledAppsLoaded(forceRefresh = true)
            }
        }
    }

    private suspend fun ensureInstalledAppsLoaded(forceRefresh: Boolean = false): List<InstalledAppInfo> {
        if (cachedInstalledApps.isEmpty() || forceRefresh) {
            val saved = appOrganizer.organizationsFlow.first().associateBy { it.packageName }
            val loaded = appOrganizer.loadInstalledApps(saved)
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

    private fun processRecordedAudio(bytes: ByteArray, mimeType: String) {
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
                val installed = ensureInstalledAppsLoaded()
                val appLabels = installed.map { it.label }
                val audioMeta = geminiRepository.transcribeAndPlanAudioWithMeta(
                    audioBytes = bytes,
                    mimeType = mimeType,
                    installedAppNames = appLabels,
                    onStatusUpdate = { statusMsg ->
                        _state.update { it.copy(statusNoticeAr = statusMsg) }
                    }
                )
                val (transcript, rawPlan) = audioMeta.value
                recordModelRouteUsage(audioMeta, transcript)
                if (transcript.isNotBlank()) {
                    chatHistoryRepository.addUserMessage(transcript)
                }

                val commandPolicy = agentPolicy.evaluateCommand(transcript, appLabels)
                if (!commandPolicy.allowed) {
                    actionLogRepository.logAction(
                        title = "رفض أمر صوتي وفق سياسة الوكيل",
                        details = "$transcript | السبب: ${commandPolicy.reasonAr}",
                        status = ActionStatus.CANCELLED,
                        actionType = "POLICY_REJECTED_AUDIO"
                    )
                    chatHistoryRepository.addAgentMessage(commandPolicy.reasonAr, ChatMessageType.TEXT)
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
                    _state.update { it.copy(inputText = "", isBusyWorking = false, statusNoticeAr = "") }
                    return@launch
                }

                val planPolicy = agentPolicy.validatePlan(rawPlan)
                if (!planPolicy.allowed) {
                    actionLogRepository.logAction(
                        title = "رفض خطة صوتية غير مطابقة للسياسة",
                        details = "$transcript | السبب: ${planPolicy.reasonAr}",
                        status = ActionStatus.CANCELLED,
                        actionType = "POLICY_REJECTED_PLAN"
                    )
                    chatHistoryRepository.addAgentMessage(planPolicy.reasonAr, ChatMessageType.TEXT)
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
                chatHistoryRepository.addAgentMessage(planSummaryMsg, ChatMessageType.PLAN)

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
                _state.update {
                    it.copy(
                        isBusyWorking = false,
                        statusNoticeAr = "",
                        uiState = CommandsUiState.Idle,
                        activeError = CompactErrorState(
                            titleAr = "انتهت مهلة تحليل الصوت",
                            summaryAr = "استغرق تحليل الصوت وقتاً أطول من المعتاد. اضغط إعادة المحاولة.",
                            technicalDetails = e.message ?: "TimeoutCancellationException",
                            retryType = ErrorRetryType.VOICE
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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
            _state.update {
                it.copy(
                    inputText = if (commandOverride == null) "" else it.inputText,
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

            try {
                if (addUserBubble) {
                    chatHistoryRepository.addUserMessage(command)
                }
                val installed = ensureInstalledAppsLoaded(forceRefresh = true)
                val appLabels = installed.map { it.label }

                // 1. فحص الممنوعات الصارمة (كلمات مرور، بصمة، دفع، حذف بيانات، تحويل أموال...)
                val forbiddenCategory = agentPolicy.findForbiddenCategory(command)
                if (forbiddenCategory != null) {
                    val reason = "عذراً، لا أستطيع تنفيذ أي طلب يتعلق بـ«${forbiddenCategory.titleAr}» حفاظاً على أمانك وخصوصيتك. ${AgentPolicy.SUGGESTION_AR}"
                    actionLogRepository.logAction(
                        title = "رفض أمر وفق سياسة الوكيل",
                        details = "$command | السبب: $reason",
                        status = ActionStatus.CANCELLED,
                        actionType = "POLICY_REJECTED"
                    )
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
                    return@launch
                }

                when (val route = ChatIntentRouter.classifyIntent(command, appLabels)) {
                    is ChatRouteIntent.ExecuteCommandPlan -> {
                        val commandPolicy = agentPolicy.evaluateCommand(command, appLabels)
                        if (!commandPolicy.allowed) {
                            actionLogRepository.logAction(
                                title = "رفض أمر وفق سياسة الوكيل",
                                details = "$command | السبب: ${commandPolicy.reasonAr}",
                                status = ActionStatus.CANCELLED,
                                actionType = "POLICY_REJECTED"
                            )
                            chatHistoryRepository.addAgentMessage(commandPolicy.reasonAr, ChatMessageType.TEXT)
                            _state.update {
                                it.copy(
                                    isBusyWorking = false,
                                    statusNoticeAr = "",
                                    lastSpokenReply = commandPolicy.reasonAr,
                                    uiState = CommandsUiState.Idle
                                )
                            }
                            speakIfEnabled(commandPolicy.reasonAr)
                            return@launch
                        }

                        _state.update {
                            it.copy(
                                uiState = CommandsUiState.Planning,
                                statusNoticeAr = "أجهّز لك خطة التنفيذ خطوة بخطوة... ${AgentPromptRules.WAIT_THINKING_NOTICE_AR}"
                            )
                        }
                        val rawScreenSummary = ScreenStateBridge.getService()?.captureCurrentScreen()
                            ?.toCompactPromptText()
                            ?: "داخل تطبيق وكيل الهاتف (خدمة إمكانية الوصول غير مفعلة حالياً)"

                        val planMeta = geminiRepository.generateCommandPlanWithMeta(
                            userCommand = command,
                            installedAppNames = appLabels,
                            currentScreenSummary = AgentPromptRules.enrichPlanScreenSummary(rawScreenSummary),
                            onStatusUpdate = { statusMsg ->
                                _state.update { it.copy(statusNoticeAr = statusMsg) }
                            }
                        )
                        recordModelRouteUsage(planMeta, command)
                        val rawPlan = planMeta.value

                        val planPolicy = agentPolicy.validatePlan(rawPlan)
                        if (!planPolicy.allowed) {
                            actionLogRepository.logAction(
                                title = "رفض خطة Gemini وفق سياسة الوكيل",
                                details = "$command | السبب: ${planPolicy.reasonAr}",
                                status = ActionStatus.CANCELLED,
                                actionType = "POLICY_REJECTED_PLAN"
                            )
                            chatHistoryRepository.addAgentMessage(planPolicy.reasonAr, ChatMessageType.TEXT)
                            _state.update {
                                it.copy(
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
                        chatHistoryRepository.addAgentMessage(
                            buildPlanChatSummary(groundedPlan),
                            ChatMessageType.PLAN
                        )

                        _state.update {
                            it.copy(
                                isBusyWorking = false,
                                statusNoticeAr = "",
                                lastSpokenReply = groundedPlan.spokenReplyAr,
                                uiState = CommandsUiState.PlanActive(groundedPlan)
                            )
                        }
                        speakIfEnabled(groundedPlan.spokenReplyAr)
                    }

                    else -> {
                        handleNonPlanIntent(route, installed, command)
                        _state.update { it.copy(isBusyWorking = false, statusNoticeAr = "") }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                _state.update {
                    it.copy(
                        isBusyWorking = false,
                        statusNoticeAr = "",
                        uiState = CommandsUiState.Idle,
                        activeError = CompactErrorState(
                            titleAr = "انتهت مهلة الاتصال",
                            summaryAr = "استغرقت الاستجابة وقتاً طويلاً (60 ثانية). أعد المحاولة الآن.",
                            technicalDetails = e.message ?: "TimeoutCancellationException",
                            retryCommand = command,
                            retryType = ErrorRetryType.COMMAND
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleGeminiFailure(
                    contextTag = "Chat command",
                    e = e,
                    retryType = ErrorRetryType.COMMAND,
                    retryCommand = command
                )
            }
        }
    }

    private suspend fun handleNonPlanIntent(
        route: ChatRouteIntent,
        installedApps: List<InstalledAppInfo>,
        rawCommand: String? = null
    ) {
        when (route) {
            is ChatRouteIntent.ShowApps -> handleShowApps(route, installedApps)
            ChatRouteIntent.ReadScreen -> handleReadScreen(installedApps)
            ChatRouteIntent.ShowActionHistory -> handleShowActionHistory()
            ChatRouteIntent.PromptReminderDetails -> handlePromptReminderDetails()
            is ChatRouteIntent.CreateTaskOrReminder -> handleCreateTaskOrReminder(route)
            ChatRouteIntent.ListTasksAndReminders -> handleListTasks()
            is ChatRouteIntent.CancelTaskOrReminder -> handleCancelTaskByQuery(route.queryText)
            is ChatRouteIntent.StartGameplay -> startGameplayGoal(route.goalText)
            is ChatRouteIntent.ConversationalChat -> handleConversationalChat(route.message, rawCommand ?: route.message)
            is ChatRouteIntent.ExecuteCommandPlan -> Unit
        }
    }

    private suspend fun handleShowActionHistory() {
        val recentLogs = actionLogRepository.allLogs.first().take(8)
        if (recentLogs.isEmpty()) {
            val reply = "لم أنفذ أي إجراءات مسجلة بعد. جرّب إعطائي أمراً مثل «اعرض ألعابي» أو «افتح الإعدادات»."
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
                    ActionStatus.PENDING_APPROVAL -> "⏳"
                    ActionStatus.CANCELLED -> "⏹"
                    ActionStatus.FAILED -> "❌"
                    ActionStatus.UNDONE -> "↩"
                }
                appendLine("$badge ${log.title} (${timeFmt.format(Date(log.timestamp))})")
            }
        }.trim()
        chatHistoryRepository.addAgentMessage(summary, ChatMessageType.TEXT)
        _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = summary) }
        speakIfEnabled("عرضت لك آخر ${recentLogs.size} إجراءات قمت بها.")
    }

    private suspend fun handlePromptReminderDetails() {
        val reply = "بماذا تريدني أن أذكّرك ومتى؟ مثال: «ذكّرني بعد 10 دقائق بشرب الماء» أو «اضبط منبهاً للساعة 7:30 صباحاً». جهّزت لك بداية العبارة في مربع الكتابة."
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
        installedApps: List<InstalledAppInfo>
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
        chatHistoryRepository.addAgentMessage(
            text = reply,
            type = ChatMessageType.APPS_LIST,
            payloadJson = jsonArr.toString()
        )
        _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = reply) }
        speakIfEnabled(reply)
    }

    private suspend fun handleReadScreen(installedApps: List<InstalledAppInfo>) {
        val service = ScreenStateBridge.getService()
        val snapshot = service?.captureCurrentScreen() ?: ScreenStateBridge.latestSnapshot.value

        if (snapshot == null) {
            val msg = "لا يمكنني قراءة الشاشة الآن لأن خدمة إمكانية الوصول غير مفعّلة أو لم تُلتقط شاشة بعد. فعّلها من تبويب «الأذونات» ثم أعد المحاولة."
            chatHistoryRepository.addAgentMessage(msg, ChatMessageType.SCREEN_READ)
            _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = msg) }
            speakIfEnabled(msg)
            return
        }

        if (snapshot.hasSensitiveSecurityField) {
            val msg = "رصدت حقلاً حساساً (كلمة مرور أو دفع أو بصمة) على الشاشة الحالية؛ لذلك أمتنع عن قراءة محتواها حفاظاً على خصوصيتك."
            chatHistoryRepository.addAgentMessage(msg, ChatMessageType.SCREEN_READ)
            _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = msg) }
            speakIfEnabled(msg)
            return
        }

        val appLabel = installedApps.firstOrNull { it.packageName == snapshot.packageName }?.label
            ?: snapshot.packageName.ifBlank { "الشاشة الحالية" }

        val factualElements = buildString {
            appendLine("التطبيق الظاهر: $appLabel (${snapshot.packageName})")
            if (snapshot.visibleTexts.isNotEmpty()) {
                appendLine("النصوص المرصودة: ${snapshot.visibleTexts.take(18).joinToString(" | ")}")
            }
            if (snapshot.clickableLabels.isNotEmpty()) {
                appendLine("الأزرار والعناصر التفاعلية: ${snapshot.clickableLabels.take(12).joinToString(" | ")}")
            }
            if (snapshot.editableHints.isNotEmpty()) {
                appendLine("حقول الإدخال: ${snapshot.editableHints.take(6).joinToString(" | ")}")
            }
        }.trim()

        val groundedReply = try {
            val explainMeta = geminiRepository.summarizeAppScreensWithMeta(
                appName = appLabel,
                packageName = snapshot.packageName,
                capturedScreenTexts = AgentPromptRules.enrichScreenReadLines(snapshot.toCompactPromptText()),
                onStatusUpdate = { statusMsg ->
                    _state.update { it.copy(statusNoticeAr = statusMsg) }
                }
            )
            recordModelRouteUsage(explainMeta, "قراءة شاشة $appLabel")
            "${explainMeta.value}\n\n— العناصر المرصودة فعلياً في «$appLabel»:\n$factualElements"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Screen explanation fell back to factual elements", e)
            val reason = classifyModelError(e).category.titleAr
            "تعذر التلخيص الذكي عبر Gemini ($reason)، وهذه العناصر الفعلية المرصودة في شاشتك الآن:\n$factualElements"
        }

        chatHistoryRepository.addAgentMessage(groundedReply, ChatMessageType.SCREEN_READ)
        _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = groundedReply) }
        speakIfEnabled(groundedReply)
    }

    private suspend fun handleCreateTaskOrReminder(intent: ChatRouteIntent.CreateTaskOrReminder) {
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

    private suspend fun handleListTasks() {
        val active = chatHistoryRepository.getActiveTasks()
        if (active.isEmpty()) {
            val reply = "لا توجد لديك تذكيرات أو مهام أو منبهات نشطة حالياً. يمكنك قول: «ذكّرني بعد 10 دقائق بشرب الماء»."
            chatHistoryRepository.addAgentMessage(reply, ChatMessageType.TEXT)
            _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = reply) }
            speakIfEnabled(reply)
            return
        }

        val reply = "لديك ${active.size} عناصر في قائمة المهام والتذكيرات والمنبهات:"
        chatHistoryRepository.addAgentMessage(
            text = reply,
            type = ChatMessageType.TASKS_LIST,
            payloadJson = encodeTasksJson(active)
        )
        _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = reply) }
        speakIfEnabled(reply)
    }

    private suspend fun handleCancelTaskByQuery(queryText: String) {
        val active = chatHistoryRepository.getActiveTasks()
        if (active.isEmpty()) {
            val reply = "لا توجد تذكيرات أو مهام نشطة لإلغائها حالياً."
            chatHistoryRepository.addAgentMessage(reply, ChatMessageType.TEXT)
            _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = reply) }
            speakIfEnabled(reply)
            return
        }

        val normQuery = ChatIntentRouter.normalizeDigitsAndArabic(queryText).lowercase()
        val matched = if (normQuery.isBlank()) {
            active.last()
        } else {
            active.firstOrNull {
                ChatIntentRouter.normalizeDigitsAndArabic(it.title).lowercase().contains(normQuery)
            }
        }

        if (matched == null) {
            val reply = "لست متأكداً أي تذكير تقصد إلغاءه بـ «$queryText». هذه قائمتك الحالية لتختار منه بوضوح:"
            chatHistoryRepository.addAgentMessage(
                text = reply,
                type = ChatMessageType.TASKS_LIST,
                payloadJson = encodeTasksJson(active)
            )
            _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = reply) }
            speakIfEnabled(reply)
            return
        }

        matched.alarmRequestCode?.let { code ->
            commandExecutor.cancelScheduledReminder(code)
        }
        chatHistoryRepository.cancelTask(matched.id)

        val reply = "تم إلغاء «${matched.title}»${if (matched.alarmRequestCode != null) " وإيقاف المنبه المجدول له" else ""}."
        actionLogRepository.logAction(
            title = "إلغاء ${AgentTaskType.entries.firstOrNull { it.name == matched.taskType }?.titleAr ?: "مهمة"}",
            details = reply,
            status = ActionStatus.UNDONE,
            actionType = "CHAT_CANCEL_TASK"
        )

        val remaining = chatHistoryRepository.getActiveTasks()
        chatHistoryRepository.addAgentMessage(
            text = reply,
            type = if (remaining.isNotEmpty()) ChatMessageType.TASKS_LIST else ChatMessageType.TEXT,
            payloadJson = if (remaining.isNotEmpty()) encodeTasksJson(remaining) else null
        )
        _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = reply) }
        speakIfEnabled(reply)
    }

    private suspend fun handleConversationalChat(userMessage: String, retryCommand: String = userMessage) {
        val baseContext = buildString {
            append("تطبيقات محملة: ${cachedInstalledApps.size}. ")
            append(
                if (ScreenStateBridge.isServiceConnected.value) "إمكانية الوصول مفعلة."
                else "إمكانية الوصول غير مفعلة."
            )
        }
        try {
            val appLabels = cachedInstalledApps.map { it.label }
            val modelTier = ChatIntentRouter.classifyModelTier(userMessage, appLabels)
            val meta = geminiRepository.conversationalReplyWithMeta(
                userMessage = userMessage,
                contextSummary = AgentPromptRules.enrichChatContextSummary(baseContext),
                onStatusUpdate = { statusMsg ->
                    _state.update { it.copy(statusNoticeAr = statusMsg) }
                },
                modelTier = modelTier
            )
            recordModelRouteUsage(meta, userMessage)
            chatHistoryRepository.addAgentMessage(meta.value, ChatMessageType.TEXT)
            _state.update { it.copy(uiState = CommandsUiState.Idle, lastSpokenReply = meta.value) }
            speakIfEnabled(meta.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleGeminiFailure(
                contextTag = "Conversational chat",
                e = e,
                retryType = ErrorRetryType.COMMAND,
                retryCommand = retryCommand
            )
        }
    }

    private suspend fun recordModelRouteUsage(
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
        actionLogRepository.logAction(
            title = "تبديل النموذج: ${meta.modelUsed}",
            details = "${meta.tier.labelAr}$fallbackNote | الطلب: ${requestPreview.take(60)}",
            status = ActionStatus.SUCCESS,
            actionType = "MODEL_ROUTE_${meta.tier.name}"
        )
    }

    fun openPlayFeatureDialog() {
        _state.update { it.copy(showPlayFeatureDialog = true) }
    }

    fun dismissPlayFeatureDialog() {
        _state.update { it.copy(showPlayFeatureDialog = false) }
    }

    fun startGameplayGoal(goal: String = "العب اللعبة أو التطبيق الظاهر على الشاشة كالإنسان خطوة بخطوة") {
        lastGameplayGoal = goal
        _state.update { it.copy(showPlayFeatureDialog = false, activeError = null) }
        startGameplayLoop(goal)
    }

    private fun startGameplayLoop(goalText: String) {
        activeTaskJob?.cancel()
        executionJob?.cancel()
        gameLoopJob?.cancel()
        executionController.reset()

        gameLoopJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isGameplayRunning = true,
                    isPaused = false,
                    statusNoticeAr = "يتولّى الوكيل اللعب الآن... ${AgentPromptRules.WAIT_THINKING_NOTICE_AR}",
                    uiState = CommandsUiState.Idle
                )
            }
            val startMsg = "تولّى الوكيل اللعب: سأراقب الشاشة الحقيقية وأتفاعل كالإنسان خطوة بخطوة. تذكّر أنني أحتاج بضع ثوانٍ للتفكير قبل كل حركة، ويمكنك إيقافي أو إكماله بنفسك في أي لحظة."
            chatHistoryRepository.addAgentMessage(startMsg, ChatMessageType.GAME_STATUS)
            speakIfEnabled(startMsg)

            try {
                val result = generalGameEngine.runObserveAndActLoop(
                    goalAr = goalText,
                    maxCycles = 10,
                    onCycleUpdate = { updateMsg ->
                        _state.update { it.copy(statusNoticeAr = updateMsg) }
                        chatHistoryRepository.addAgentMessage(updateMsg, ChatMessageType.GAME_STATUS)
                    },
                    callbacks = buildFloatingControlCallbacks()
                )
                chatHistoryRepository.addAgentMessage(result.summaryAr, ChatMessageType.GAME_STATUS)
                _state.update { it.copy(lastSpokenReply = result.summaryAr) }
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
                    it.copy(isGameplayRunning = false, isPaused = false, statusNoticeAr = "")
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
            task?.alarmRequestCode?.let { commandExecutor.cancelScheduledReminder(it) }
            chatHistoryRepository.cancelTask(taskId)
            if (task != null) {
                actionLogRepository.logAction(
                    title = "إلغاء «${task.title}»",
                    details = "تم إلغاء المهمة/التذكير من بطاقة الدردشة.",
                    status = ActionStatus.UNDONE,
                    actionType = "CHAT_CANCEL_TASK"
                )
            }
        }
    }

    fun clearChatHistory() {
        viewModelScope.launch {
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
                    statusNoticeAr = "بدأت تنفيذ الخطة خطوة بخطوة... ${AgentPromptRules.WAIT_THINKING_NOTICE_AR}",
                    executionTruthReportAr = null
                )
            }
            val succeededList = mutableListOf<String>()
            val failedList = mutableListOf<String>()

            floatingControlManager.show(
                initialStatusAr = "ينفّذ الوكيل الخطة (ثوانٍ للتحقق في كل خطوة)",
                isPaused = false,
                callbacks = buildFloatingControlCallbacks()
            )

            try {
                var plan = currentState.plan
                val steps = plan.steps.toMutableList()
                var planApprovedForSensitive = false
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

                    val stepNotice = "الخطوة ${step.stepNumber}/${steps.size}: ${step.titleAr}"
                    _state.update { it.copy(statusNoticeAr = "$stepNotice — ${AgentPromptRules.WAIT_THINKING_NOTICE_AR}") }
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
                        val report = buildTruthReport(succeededList, failedList)
                        chatHistoryRepository.addAgentMessage(stepPolicy.reasonAr, ChatMessageType.ERROR)
                        _state.update {
                            it.copy(
                                uiState = CommandsUiState.PlanActive(plan),
                                isExecuting = false,
                                statusNoticeAr = "",
                                executionTruthReportAr = report
                            )
                        }
                        speakIfEnabled(stepPolicy.reasonAr)
                        return@launch
                    }

                    if (step.requiresApproval && !planApprovedForSensitive) {
                        steps[index] = step.copy(status = StepStatus.AWAITING_APPROVAL)
                        plan = plan.copy(steps = steps.toList())
                        val pendingLogId = actionLogRepository.logAction(
                            title = "طلب موافقة على إجراء حساس: ${step.titleAr}",
                            details = "عرض الخطة كاملة (${steps.size} خطوات) | السبب: ${step.safetyReasonAr.ifBlank { step.descriptionAr }}",
                            status = ActionStatus.PENDING_APPROVAL,
                            actionType = step.actionType.name
                        )
                        val deferred = CompletableDeferred<Boolean>()
                        approvalDeferred = deferred
                        _state.update {
                            it.copy(
                                uiState = CommandsUiState.PlanActive(plan),
                                pendingApprovalStep = steps[index],
                                pendingApprovalPlan = plan,
                                pendingApprovalLogId = pendingLogId
                            )
                        }
                        floatingControlManager.updateStatus(
                            "بانتظار موافقتك على: ${step.titleAr}",
                            executionController.isPaused.value
                        )
                        speakIfEnabled("إجراء حساس: ${step.titleAr}. راجع الخطة كاملة ثم وافق أو ألغِ.")

                        val approved = deferred.await()
                        approvalDeferred = null
                        _state.update {
                            it.copy(
                                pendingApprovalStep = null,
                                pendingApprovalPlan = null,
                                pendingApprovalLogId = null
                            )
                        }

                        if (!approved) {
                            actionLogRepository.updateStatus(pendingLogId, ActionStatus.CANCELLED)
                            actionLogRepository.logAction(
                                title = "رفض المستخدم تنفيذ: ${step.titleAr}",
                                details = "أُلغيت الخطوة الحساسة بناءً على قرار المستخدم الصريح.",
                                status = ActionStatus.CANCELLED,
                                actionType = "USER_REJECTED_SENSITIVE"
                            )
                            steps[index] = step.copy(
                                status = StepStatus.CANCELLED,
                                verificationStatus = StepVerificationStatus.VERIFICATION_FAILED,
                                verificationResultAr = "أُلغي بناءً على رفض المستخدم الصريح."
                            )
                            failedList.add("الخطوة ${step.stepNumber} (${step.titleAr}): رُفضت من المستخدم.")
                            plan = plan.copy(steps = steps.toList(), isCancelled = true)
                            val report = buildTruthReport(succeededList, failedList)
                            chatHistoryRepository.addAgentMessage(
                                "أُلغيت الخطوة الحساسة «${step.titleAr}» بناءً على قرارك.",
                                ChatMessageType.TEXT
                            )
                            _state.update {
                                it.copy(
                                    uiState = CommandsUiState.PlanActive(plan),
                                    isExecuting = false,
                                    statusNoticeAr = "",
                                    executionTruthReportAr = report
                                )
                            }
                            return@launch
                        } else {
                            planApprovedForSensitive = true
                            actionLogRepository.updateStatus(pendingLogId, ActionStatus.SUCCESS)
                            actionLogRepository.logAction(
                                title = "موافقة صريحة من المستخدم على: ${step.titleAr}",
                                details = "وافق المستخدم على الخطة الحساسة بعد مراجعتها.",
                                status = ActionStatus.SUCCESS,
                                actionType = "USER_APPROVED_SENSITIVE"
                            )
                        }
                    }

                    executionController.awaitIfPaused()
                    steps[index] = steps[index].copy(
                        status = StepStatus.VERIFYING,
                        verificationStatus = StepVerificationStatus.AWAITING_VERIFICATION
                    )
                    plan = plan.copy(steps = steps.toList())
                    _state.update { it.copy(uiState = CommandsUiState.PlanActive(plan)) }

                    val outcome = commandExecutor.executeStep(steps[index], installed)

                    steps[index] = steps[index].copy(
                        status = if (outcome.success) StepStatus.COMPLETED else StepStatus.FAILED,
                        verificationStatus = outcome.verification.status,
                        verificationResultAr = "${outcome.messageAr}\nحالة التحقق: ${outcome.verification.status.labelAr} — ${outcome.verification.explanationAr}"
                    )
                    plan = plan.copy(
                        steps = steps.toList(),
                        isComplete = steps.all { it.status == StepStatus.COMPLETED }
                    )
                    _state.update { it.copy(uiState = CommandsUiState.PlanActive(plan)) }

                    if (outcome.success) {
                        succeededList.add("الخطوة ${step.stepNumber} (${step.titleAr}): ${outcome.messageAr}")
                        speakIfEnabled(outcome.messageAr)
                    } else {
                        failedList.add("الخطوة ${step.stepNumber} (${step.titleAr}): ${outcome.messageAr}")
                        speakIfEnabled(outcome.verification.explanationAr)
                    }

                    if (!outcome.success || !outcome.verification.verified || singleStepOnly) {
                        break
                    }
                }

                val report = buildTruthReport(succeededList, failedList)
                if (!report.isNullOrBlank()) {
                    chatHistoryRepository.addAgentMessage(report, ChatMessageType.TEXT)
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
                executionController.reset()
                floatingControlManager.dismiss()
                _state.update { it.copy(isExecuting = false, isPaused = false, statusNoticeAr = "") }
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
        _state.update { it.copy(isPaused = paused, statusNoticeAr = statusText) }
        viewModelScope.launch {
            floatingControlManager.updateStatus(statusText, paused)
        }
    }

    /**
     * «أكمل بنفسي»: يتوقف الوكيل ويسلّم التحكم للمستخدم فوراً، مع إخفاء اللوحة العائمة.
     */
    fun handOverControlToUser() {
        terminateRunningJobs(
            clearPlan = false,
            chatMessage = "توقّف الوكيل وسلمك التحكم — يمكنك الإكمال بنفسك الآن.",
            logTitle = "تسليم التحكم للمستخدم (أكمل بنفسي)",
            logActionType = "USER_TAKEOVER"
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
        activeTaskJob = null
        executionJob = null
        gameLoopJob = null

        executionController.reset()
        approvalDeferred?.complete(false)
        approvalDeferred = null

        voiceManager.stopSpeaking()
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
                isListening = false,
                statusNoticeAr = "",
                pendingApprovalStep = null,
                pendingApprovalPlan = null,
                pendingApprovalLogId = null,
                lastSpokenReply = chatMessage
            )
        }

        viewModelScope.launch {
            floatingControlManager.dismiss()
            chatHistoryRepository.addAgentMessage(chatMessage, ChatMessageType.STOPPED)
            actionLogRepository.logAction(
                title = logTitle,
                details = chatMessage,
                status = ActionStatus.CANCELLED,
                actionType = logActionType
            )
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
        executionController.reset()
        _state.update {
            it.copy(
                inputText = "",
                uiState = CommandsUiState.Idle,
                pendingApprovalStep = null,
                pendingApprovalPlan = null,
                isExecuting = false,
                isPaused = false,
                statusNoticeAr = "",
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
            voiceManager.speakArabic(text)
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

    private fun buildTruthReport(succeeded: List<String>, failed: List<String>): String? {
        if (succeeded.isEmpty() && failed.isEmpty()) return null
        return buildString {
            if (succeeded.isNotEmpty()) {
                appendLine("✅ ما نجح فعلياً:")
                succeeded.forEach { appendLine("• $it") }
            }
            if (failed.isNotEmpty()) {
                if (succeeded.isNotEmpty()) appendLine()
                appendLine("❌ ما فشل ولماذا:")
                failed.forEach { appendLine("• $it") }
            }
        }.trim()
    }

    override fun onCleared() {
        super.onCleared()
        voiceManager.release()
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
        private val agentPolicy: AgentPolicy = AgentPolicy()
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
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
                agentPolicy = agentPolicy
            ) as T
        }
    }
}
