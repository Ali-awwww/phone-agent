package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ai.GeminiRepository
import com.example.data.local.ActionLogRepository
import com.example.data.local.ActionStatus
import com.example.executor.CommandExecutor
import com.example.model.AgentPlan
import com.example.model.AgentPlanStep
import com.example.model.StepStatus
import com.example.model.StepVerificationStatus
import com.example.organizer.AppOrganizer
import com.example.organizer.InstalledAppInfo
import com.example.service.ScreenStateBridge
import com.example.verifier.ScreenVerifier
import com.example.voice.VoiceAssistantManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "CommandsViewModel"

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
    val executionTruthReportAr: String? = null
)

class CommandsViewModel(
    private val geminiRepository: GeminiRepository,
    private val commandExecutor: CommandExecutor,
    private val actionLogRepository: ActionLogRepository,
    private val appOrganizer: AppOrganizer,
    private val screenVerifier: ScreenVerifier,
    private val voiceManager: VoiceAssistantManager
) : ViewModel() {

    private val _state = MutableStateFlow(CommandsScreenState())
    val state: StateFlow<CommandsScreenState> = _state.asStateFlow()

    val isAccessibilityConnected: StateFlow<Boolean> = ScreenStateBridge.isServiceConnected

    private var executionJob: Job? = null
    private var approvalDeferred: CompletableDeferred<Boolean>? = null
    private var cachedInstalledApps: List<InstalledAppInfo> = emptyList()

    init {
        refreshInstalledAppsCache()
    }

    private fun refreshInstalledAppsCache() {
        viewModelScope.launch {
            runCatching {
                val saved = appOrganizer.organizationsFlow.first().associateBy { it.packageName }
                cachedInstalledApps = appOrganizer.loadInstalledApps(saved)
            }
        }
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
                        uiState = CommandsUiState.Error(err)
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
                        uiState = CommandsUiState.Error(err)
                    )
                }
            }
        )
        _state.update { it.copy(isListening = false) }
    }

    private fun processRecordedAudio(bytes: ByteArray, mimeType: String) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isListening = false,
                    uiState = CommandsUiState.Planning,
                    executionTruthReportAr = null
                )
            }
            try {
                if (cachedInstalledApps.isEmpty()) {
                    val saved = appOrganizer.organizationsFlow.first().associateBy { it.packageName }
                    cachedInstalledApps = appOrganizer.loadInstalledApps(saved)
                }
                val (transcript, rawPlan) = geminiRepository.transcribeAndPlanAudio(
                    audioBytes = bytes,
                    mimeType = mimeType,
                    installedAppNames = cachedInstalledApps.map { it.label }
                )
                val groundedPlan = screenVerifier.groundPlanAgainstReality(rawPlan, cachedInstalledApps)
                _state.update {
                    it.copy(
                        inputText = transcript,
                        lastSpokenReply = groundedPlan.spokenReplyAr,
                        uiState = CommandsUiState.PlanActive(groundedPlan)
                    )
                }
                if (_state.value.voiceConversationMode) {
                    voiceManager.speakArabic(groundedPlan.spokenReplyAr)
                }
            } catch (e: TimeoutCancellationException) {
                _state.update {
                    it.copy(uiState = CommandsUiState.Error("انتهت مهلة تحليل الصوت. يرجى المحاولة مرة أخرى."))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Audio planning failed", e)
                _state.update {
                    it.copy(uiState = CommandsUiState.Error(e.localizedMessage ?: "تعذر فهم التسجيل الصوتي."))
                }
            }
        }
    }

    fun analyzeAndPlanCommand(commandOverride: String? = null) {
        val command = (commandOverride ?: _state.value.inputText).trim()
        if (command.isEmpty()) {
            _state.update {
                it.copy(uiState = CommandsUiState.Error("يرجى كتابة أمر أو التحدث أولاً."))
            }
            return
        }

        executionJob?.cancel()
        viewModelScope.launch {
            _state.update {
                it.copy(
                    uiState = CommandsUiState.Planning,
                    pendingApprovalStep = null,
                    pendingApprovalPlan = null,
                    isExecuting = false,
                    executionTruthReportAr = null
                )
            }
            try {
                if (cachedInstalledApps.isEmpty()) {
                    val saved = appOrganizer.organizationsFlow.first().associateBy { it.packageName }
                    cachedInstalledApps = appOrganizer.loadInstalledApps(saved)
                }
                val screenSummary = ScreenStateBridge.getService()?.captureCurrentScreen()
                    ?.toCompactPromptText()
                    ?: "داخل تطبيق وكيل الهاتف (خدمة إمكانية الوصول غير مفعلة حالياً)"

                val rawPlan = geminiRepository.generateCommandPlan(
                    userCommand = command,
                    installedAppNames = cachedInstalledApps.map { it.label },
                    currentScreenSummary = screenSummary
                )
                val groundedPlan = screenVerifier.groundPlanAgainstReality(rawPlan, cachedInstalledApps)

                _state.update {
                    it.copy(
                        lastSpokenReply = groundedPlan.spokenReplyAr,
                        uiState = CommandsUiState.PlanActive(groundedPlan)
                    )
                }
                if (_state.value.voiceConversationMode) {
                    voiceManager.speakArabic(groundedPlan.spokenReplyAr)
                }
            } catch (e: TimeoutCancellationException) {
                _state.update {
                    it.copy(uiState = CommandsUiState.Error("انتهت مهلة الاتصال بالذكاء الاصطناعي. حاول مجدداً."))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Plan generation failed", e)
                _state.update {
                    it.copy(uiState = CommandsUiState.Error(e.localizedMessage ?: "تعذر إنشاء خطة التنفيذ."))
                }
            }
        }
    }

    fun executePlanStepByStep(singleStepOnly: Boolean = false) {
        val currentState = _state.value.uiState
        if (currentState !is CommandsUiState.PlanActive) return
        if (_state.value.isExecuting) return

        executionJob = viewModelScope.launch {
            _state.update { it.copy(isExecuting = true, executionTruthReportAr = null) }
            val succeededList = mutableListOf<String>()
            val failedList = mutableListOf<String>()

            try {
                var plan = currentState.plan
                val steps = plan.steps.toMutableList()
                var planApprovedForSensitive = false

                for (index in steps.indices) {
                    val step = steps[index]
                    if (step.status == StepStatus.COMPLETED || step.status == StepStatus.CANCELLED) {
                        if (step.status == StepStatus.COMPLETED) {
                            succeededList.add("الخطوة ${step.stepNumber}: ${step.titleAr}")
                        }
                        continue
                    }

                    // بوابة الأمان: عرض الخطة كاملة للمستخدم قبل أي فعل حساس وتسجيل الموافقة الصريحة
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
                        if (_state.value.voiceConversationMode) {
                            voiceManager.speakArabic("إجراء حساس: ${step.titleAr}. راجع الخطة كاملة ثم وافق أو ألغِ.")
                        }

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
                            _state.update {
                                it.copy(
                                    uiState = CommandsUiState.PlanActive(plan),
                                    isExecuting = false,
                                    executionTruthReportAr = buildTruthReport(succeededList, failedList)
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

                    // تنفيذ الخطوة مع التحقق الإجباري قبل وبعد من ScreenVerifier
                    steps[index] = steps[index].copy(
                        status = StepStatus.VERIFYING,
                        verificationStatus = StepVerificationStatus.AWAITING_VERIFICATION
                    )
                    plan = plan.copy(steps = steps.toList())
                    _state.update { it.copy(uiState = CommandsUiState.PlanActive(plan)) }

                    val outcome = commandExecutor.executeStep(steps[index], cachedInstalledApps)

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
                        if (_state.value.voiceConversationMode) {
                            voiceManager.speakArabic(outcome.messageAr)
                        }
                    } else {
                        failedList.add("الخطوة ${step.stepNumber} (${step.titleAr}): ${outcome.messageAr}")
                        if (_state.value.voiceConversationMode) {
                            voiceManager.speakArabic(outcome.verification.explanationAr)
                        }
                    }

                    if (!outcome.success || !outcome.verification.verified || singleStepOnly) {
                        break
                    }
                }

                _state.update {
                    it.copy(executionTruthReportAr = buildTruthReport(succeededList, failedList))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Step execution error", e)
                _state.update {
                    it.copy(uiState = CommandsUiState.Error(e.localizedMessage ?: "حدث خطأ أثناء التنفيذ."))
                }
            } finally {
                _state.update { it.copy(isExecuting = false) }
            }
        }
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

    fun resolveApproval(approved: Boolean) {
        approvalDeferred?.complete(approved)
    }

    fun cancelActivePlan() {
        approvalDeferred?.complete(false)
        executionJob?.cancel()
        voiceManager.stopSpeaking()
        val current = _state.value.uiState
        if (current is CommandsUiState.PlanActive) {
            val updatedSteps = current.plan.steps.map {
                if (it.status == StepStatus.PENDING || it.status == StepStatus.RUNNING ||
                    it.status == StepStatus.VERIFYING || it.status == StepStatus.AWAITING_APPROVAL
                ) {
                    it.copy(status = StepStatus.CANCELLED)
                } else it
            }
            val cancelledPlan = current.plan.copy(steps = updatedSteps, isCancelled = true)
            _state.update {
                it.copy(
                    uiState = CommandsUiState.PlanActive(cancelledPlan),
                    pendingApprovalStep = null,
                    pendingApprovalPlan = null,
                    isExecuting = false
                )
            }
            viewModelScope.launch {
                actionLogRepository.logAction(
                    title = "إلغاء الخطة: ${current.plan.originalCommand}",
                    details = "تم إيقاف تنفيذ الخطة بناءً على طلب المستخدم.",
                    status = ActionStatus.CANCELLED,
                    actionType = "CANCEL_PLAN"
                )
            }
        } else {
            _state.update { it.copy(uiState = CommandsUiState.Idle, isExecuting = false) }
        }
    }

    fun resetToIdle() {
        executionJob?.cancel()
        _state.update {
            it.copy(
                inputText = "",
                uiState = CommandsUiState.Idle,
                pendingApprovalStep = null,
                pendingApprovalPlan = null,
                isExecuting = false,
                executionTruthReportAr = null
            )
        }
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
        private val voiceManager: VoiceAssistantManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CommandsViewModel(
                geminiRepository,
                commandExecutor,
                actionLogRepository,
                appOrganizer,
                screenVerifier,
                voiceManager
            ) as T
        }
    }
}
