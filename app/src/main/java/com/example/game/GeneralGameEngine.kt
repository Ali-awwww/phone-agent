package com.example.game

import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import com.example.data.ai.GeminiRepository
import com.example.data.local.ActionLogRepository
import com.example.data.local.ActionStatus
import com.example.executor.ChatIntentRouter
import com.example.executor.GameActionDecision
import com.example.executor.GameGestureAction
import com.example.executor.ModelRouteTier
import com.example.overlay.AgentExecutionController
import com.example.overlay.AgentFloatingControlManager
import com.example.overlay.FloatingControlCallbacks
import com.example.permissions.PermissionHelper
import com.example.policy.AgentPolicy
import com.example.policy.AgentPromptRules
import com.example.service.GameScreenObservation
import com.example.service.PhoneAgentAccessibilityService
import com.example.service.ScreenStateBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "GeneralGameEngine"

object ForegroundAppPolicy {
    fun shouldShowFloatingOverlay(foregroundPackage: String, agentPackageName: String): Boolean {
        return foregroundPackage.isNotBlank() && foregroundPackage != agentPackageName
    }
}

data class GameLoopResult(
    val completedNaturally: Boolean,
    val stepsExecuted: Int,
    val summaryAr: String
)

/**
 * محرك لعب عام غير مرتبط بأي لعبة بعينها:
 * - يتحقق من الشاشة الحقيقية عبر شجرة إمكانية الوصول دون اختراع عناصر.
 * - يستخدم التطبيق كالإنسان (نقر، سحب، تمرير، انتظار، رجوع) ويتوقف فوراً عند طلب المستخدم.
 * - يعرض الحبّة العائمة فقط عندما يكون التطبيق المستهدف الخارجي في المقدمة، ويخفيها تماماً داخل تطبيق «وكيل الهاتف».
 */
class GeneralGameEngine(
    private val context: Context,
    private val geminiRepository: GeminiRepository,
    private val actionLogRepository: ActionLogRepository,
    private val agentPolicy: AgentPolicy = AgentPolicy(),
    private val executionController: AgentExecutionController = AgentExecutionController(),
    private val floatingControlManager: AgentFloatingControlManager = AgentFloatingControlManager(context)
) {

    @Volatile
    private var currentOverlayStatusAr: String = "الوكيل جاهز"

    suspend fun resolveForegroundPackage(service: PhoneAgentAccessibilityService): String {
        val fromAccessibility = withContext(Dispatchers.Main) {
            service.getActivePackageName()
        }
        if (fromAccessibility.isNotBlank()) return fromAccessibility

        if (PermissionHelper.isUsageAccessGranted(context)) {
            return runCatching {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                    ?: return@runCatching ""
                val end = System.currentTimeMillis()
                val start = end - 15_000L
                val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
                    ?: return@runCatching ""
                stats.maxByOrNull { it.lastTimeUsed }?.packageName.orEmpty()
            }.getOrDefault("")
        }
        return ""
    }

    private suspend fun syncFloatingOverlayForCurrentForeground(
        service: PhoneAgentAccessibilityService,
        callbacks: FloatingControlCallbacks
    ): Boolean {
        val foregroundPkg = resolveForegroundPackage(service)
        val isExternal = ForegroundAppPolicy.shouldShowFloatingOverlay(foregroundPkg, context.packageName)
        if (isExternal) {
            floatingControlManager.show(
                initialStatusAr = currentOverlayStatusAr,
                isPaused = executionController.isPaused.value,
                callbacks = callbacks
            )
            floatingControlManager.setHostVisible(true)
        } else {
            floatingControlManager.setHostVisible(false)
        }
        return isExternal
    }

    suspend fun runObserveAndActLoop(
        goalAr: String,
        maxCycles: Int = 10,
        onCycleUpdate: suspend (String) -> Unit,
        callbacks: FloatingControlCallbacks
    ): GameLoopResult = coroutineScope {
        val service = ScreenStateBridge.getService()
            ?: return@coroutineScope GameLoopResult(
                completedNaturally = false,
                stepsExecuted = 0,
                summaryAr = "يتطلب وضع «العب مع الوكيل» تفعيل خدمة إمكانية الوصول أولاً ليتمكن الوكيل من مشاهدة الشاشة وتنفيذ اللمس والسحب."
            )

        executionController.reset()
        currentOverlayStatusAr = "مراقبة الشاشة..."
        syncFloatingOverlayForCurrentForeground(service, callbacks)

        var consecutiveEmpty = 0
        val visibilityWatcherJob = launch {
            while (isActive) {
                runCatching {
                    val foregroundPkg = resolveForegroundPackage(service)
                    val isExternal = ForegroundAppPolicy.shouldShowFloatingOverlay(foregroundPkg, context.packageName)
                    if (isExternal) {
                        consecutiveEmpty = 0
                        floatingControlManager.show(
                            initialStatusAr = currentOverlayStatusAr,
                            isPaused = executionController.isPaused.value,
                            callbacks = callbacks
                        )
                        floatingControlManager.setHostVisible(true)
                    } else {
                        consecutiveEmpty++
                        if (consecutiveEmpty >= 3 && !floatingControlManager.isMiniChatOpen()) {
                            floatingControlManager.setHostVisible(false)
                        }
                    }
                }
                delay(300L)
            }
        }

        val recentActions = mutableListOf<String>()
        var executedCount = 0

        try {
            for (cycle in 1..maxCycles) {
                currentCoroutineContext().ensureActive()
                executionController.awaitIfPaused()

                val isExternal = syncFloatingOverlayForCurrentForeground(service, callbacks)
                if (!isExternal) {
                    onCycleUpdate("أنت داخل «وكيل الهاتف» — تم إخفاء الحبّة العائمة، عد إلى التطبيق المستهدف للمتابعة.")
                    executionController.delayRespectingPause(650L)
                    continue
                }

                currentOverlayStatusAr = "مراقبة الشاشة والدورات..."
                floatingControlManager.updateStatus(
                    statusAr = currentOverlayStatusAr,
                    isPaused = executionController.isPaused.value
                )

                val observation = withContext(Dispatchers.Main) {
                    service.captureDetailedScreenForGame()
                }
                if (observation == null) {
                    onCycleUpdate("الدورة $cycle: لم ألتقط نافذة نشطة بعد، أنتظر استقرار الشاشة بضع ثوانٍ...")
                    executionController.delayRespectingPause(1000L)
                    continue
                }

                if (!ForegroundAppPolicy.shouldShowFloatingOverlay(observation.snapshot.packageName, context.packageName)) {
                    floatingControlManager.setHostVisible(false)
                    executionController.delayRespectingPause(650L)
                    continue
                }

                if (observation.isNearEmptyTree()) {
                    val emptyMsg = "لا أرى أزرار اللعبة — صف لي مكان الزر"
                    actionLogRepository.logAction(
                        title = "تعذّر رصد أزرار اللعبة",
                        details = emptyMsg,
                        status = ActionStatus.FAILED,
                        actionType = "GAME_NEAR_EMPTY_TREE"
                    )
                    return@coroutineScope GameLoopResult(false, executedCount, emptyMsg)
                }

                if (observation.snapshot.hasSensitiveSecurityField) {
                    val safeMsg = "تم إيقاف محرك اللعب فوراً لحمايتك بعد رصد شاشة حساسة (كلمة مرور/دفع/بصمة)."
                    actionLogRepository.logAction(
                        title = "إيقاف محرك اللعب احترازياً",
                        details = safeMsg,
                        status = ActionStatus.CANCELLED,
                        actionType = "GAME_SAFETY_STOP"
                    )
                    return@coroutineScope GameLoopResult(false, executedCount, safeMsg)
                }

                executionController.awaitIfPaused()
                currentOverlayStatusAr = "يفكر في الحركة التالية"
                floatingControlManager.updateStatus(
                    statusAr = currentOverlayStatusAr,
                    isPaused = executionController.isPaused.value
                )

                val prompt = buildGameObservationPrompt(goalAr, observation, recentActions, cycle)
                val rawDecision = geminiRepository.conversationalReply(
                    userMessage = prompt,
                    contextSummary = AgentPromptRules.enrichGameContextSummary(observation.toPromptSummary()),
                    modelTier = ModelRouteTier.FLASH
                )
                val decision = ChatIntentRouter.parseGameActionDecision(rawDecision)

                val policyCheck = agentPolicy.findForbiddenCategory(
                    "${decision.targetText} ${decision.reasonAr}"
                )
                if (policyCheck != null) {
                    val blockedMsg = "توقف اللعب وفق سياسة الأمان (${policyCheck.titleAr})."
                    return@coroutineScope GameLoopResult(false, executedCount, blockedMsg)
                }

                if (decision.action == GameGestureAction.DONE) {
                    val doneMsg = decision.reasonAr.ifBlank {
                        "أنهيت جولة المساعدة في اللعب بعد تنفيذ $executedCount حركات."
                    }
                    actionLogRepository.logAction(
                        title = "اكتمال جولة «العب مع الوكيل»",
                        details = doneMsg,
                        status = ActionStatus.SUCCESS,
                        actionType = "GAME_LOOP_DONE"
                    )
                    return@coroutineScope GameLoopResult(true, executedCount, doneMsg)
                }

                executionController.awaitIfPaused()
                if (!syncFloatingOverlayForCurrentForeground(service, callbacks)) {
                    executionController.delayRespectingPause(500L)
                    continue
                }

                currentOverlayStatusAr = decision.reasonAr.ifBlank { "ينفّذ الحركة التالية" }
                floatingControlManager.updateStatus(
                    statusAr = currentOverlayStatusAr,
                    isPaused = executionController.isPaused.value
                )

                val gestureOk = executeGameDecision(service, decision, observation)
                if (gestureOk) executedCount++
                val stepDesc = buildString {
                    append("خطوة $cycle (${decision.action.name}): ")
                    append(decision.reasonAr.ifBlank { decision.targetText.ifBlank { "تنفيذ إيماءة على الشاشة" } })
                    append(if (gestureOk) " ✓" else " (لم تستجب الشاشة أو لم أجد العنصر)")
                }
                recentActions.add(stepDesc)
                if (recentActions.size > 4) recentActions.removeAt(0)
                onCycleUpdate(stepDesc)

                executionController.delayRespectingPause(1000L)
            }

            val endSummary = "نفّذ الوكيل $executedCount حركات في حلقة المراقبة والتنفيذ. يمكنك الضغط على «تولّي اللعب» لجولة جديدة أو إرسال توجيه محدد."
            actionLogRepository.logAction(
                title = "جولة «العب مع الوكيل» ($executedCount حركات)",
                details = endSummary,
                status = ActionStatus.SUCCESS,
                actionType = "GAME_LOOP_COMPLETE"
            )
            return@coroutineScope GameLoopResult(true, executedCount, endSummary)
        } catch (e: CancellationException) {
            throw e
        } finally {
            visibilityWatcherJob.cancel()
            floatingControlManager.dismiss()
        }
    }

    internal fun resolveObservedCoordinatesForTap(
        observation: GameScreenObservation?,
        targetText: String,
        requestedX: Float,
        requestedY: Float
    ): Pair<Float, Float>? {
        val elements = observation?.elements ?: return null
        if (elements.isEmpty()) return null

        val cleanQuery = targetText.trim()
        if (cleanQuery.isNotEmpty()) {
            val matchedByText = elements.firstOrNull { el ->
                el.label.isNotBlank() &&
                    el.label != "عنصر تفاعلي" &&
                    (el.label.contains(cleanQuery, ignoreCase = true) ||
                        cleanQuery.contains(el.label, ignoreCase = true))
            }
            if (matchedByText != null) {
                return matchedByText.centerXRatio to matchedByText.centerYRatio
            }
        }

        val matchedByCoords = elements.firstOrNull { el ->
            kotlin.math.abs(el.centerXRatio - requestedX) <= 0.08f &&
                kotlin.math.abs(el.centerYRatio - requestedY) <= 0.08f
        } ?: return null
        return matchedByCoords.centerXRatio to matchedByCoords.centerYRatio
    }

    suspend fun executeMiniChatDirectiveAction(
        service: PhoneAgentAccessibilityService,
        directive: GeminiRepository.MiniChatDirectiveResult,
        observation: GameScreenObservation? = null
    ): Boolean {
        if (!directive.shouldAct) return false
        return when (directive.action) {
            "TAP_TEXT" -> {
                val clicked = withContext(Dispatchers.Main) {
                    directive.targetText.isNotBlank() && service.findAndClickHumanLike(directive.targetText)
                }
                if (clicked) {
                    true
                } else {
                    val observedCoords = resolveObservedCoordinatesForTap(
                        observation = observation,
                        targetText = directive.targetText,
                        requestedX = directive.x,
                        requestedY = directive.y
                    ) ?: return false
                    service.performTapAt(observedCoords.first, observedCoords.second)
                }
            }
            "TAP_XY" -> {
                val observedCoords = resolveObservedCoordinatesForTap(
                    observation = observation,
                    targetText = directive.targetText,
                    requestedX = directive.x,
                    requestedY = directive.y
                ) ?: return false
                service.performTapAt(observedCoords.first, observedCoords.second)
            }
            "SWIPE" -> service.performSwipe(directive.x, directive.y, directive.endX, directive.endY)
            "SCROLL" -> withContext(Dispatchers.Main) {
                service.scrollForward()
            } || service.performSwipe(0.5f, 0.78f, 0.5f, 0.28f)
            "INPUT_TEXT" -> withContext(Dispatchers.Main) {
                val textToType = directive.inputText.ifBlank { directive.targetText }
                textToType.isNotBlank() && service.findAndInputText(directive.targetText, textToType)
            }
            "BACK" -> withContext(Dispatchers.Main) {
                service.navigateBack()
            }
            else -> false
        }
    }

    private fun buildGameObservationPrompt(
        goalAr: String,
        observation: GameScreenObservation,
        recentActions: List<String>,
        cycle: Int
    ): String {
        val historyText = if (recentActions.isEmpty()) "لا يوجد" else recentActions.joinToString(" -> ")
        return """
            أنت محرك لعب عام يتصرف كالإنسان داخل أي تطبيق أو لعبة. التزم بقواعد السلوك:
            - تحقق من الشاشة الحقيقية أولاً ولا تخترع أزراراً أو نصوصاً غير موجودة.
            - نفّذ حركة واحدة تشبه تصرف الإنسان في كل دورة، وتوقف فوراً إذا طُلب منك.
            - كن صريحاً وواضحاً: إذا لم تكن متأكداً أو احتارت عليك الشاشة، اختر action="WAIT" واذكر في reasonAr أنك غير متأكد وتحتاج توضيحاً أو بضع ثوانٍ للملاحظة بدل التخمين.
            هدف المستخدم: $goalAr
            رقم الدورة: $cycle
            آخر الحركات المنفذة: $historyText
            الشاشة الحالية:
            ${observation.toPromptSummary()}

            أجب بصيغة JSON فقط بدون شرح إضافي لاختيار حركة واحدة مما يلي:
            {"action":"TAP_TEXT|TAP_XY|SWIPE|SCROLL|BACK|WAIT|DONE","text":"النص الدقيق للزر إن وجد","x":0.5,"y":0.5,"endX":0.5,"endY":0.2,"reasonAr":"شرح عربي قصير وواضح لما تفعله الآن"}
        """.trimIndent()
    }

    private suspend fun executeGameDecision(
        service: PhoneAgentAccessibilityService,
        decision: GameActionDecision,
        observation: GameScreenObservation? = null
    ): Boolean {
        return when (decision.action) {
            GameGestureAction.TAP_TEXT -> {
                val clicked = withContext(Dispatchers.Main) {
                    decision.targetText.isNotBlank() && service.findAndClickHumanLike(decision.targetText)
                }
                if (clicked) {
                    true
                } else {
                    val observedCoords = resolveObservedCoordinatesForTap(
                        observation = observation,
                        targetText = decision.targetText,
                        requestedX = decision.x,
                        requestedY = decision.y
                    ) ?: return false
                    service.performTapAt(observedCoords.first, observedCoords.second)
                }
            }
            GameGestureAction.TAP_XY -> {
                val observedCoords = resolveObservedCoordinatesForTap(
                    observation = observation,
                    targetText = decision.targetText,
                    requestedX = decision.x,
                    requestedY = decision.y
                ) ?: return false
                service.performTapAt(observedCoords.first, observedCoords.second)
            }
            GameGestureAction.SWIPE -> service.performSwipe(decision.x, decision.y, decision.endX, decision.endY)
            GameGestureAction.SCROLL -> withContext(Dispatchers.Main) {
                service.scrollForward()
            } || service.performSwipe(0.5f, 0.78f, 0.5f, 0.28f)
            GameGestureAction.BACK -> withContext(Dispatchers.Main) {
                service.navigateBack()
            }
            GameGestureAction.WAIT -> {
                executionController.delayRespectingPause(700L)
                true
            }
            GameGestureAction.DONE -> true
        }
    }
}
