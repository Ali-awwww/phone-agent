package com.example.game

import android.content.Context
import android.util.Log
import com.example.data.ai.GeminiRepository
import com.example.data.local.ActionLogRepository
import com.example.data.local.ActionStatus
import com.example.executor.ChatIntentRouter
import com.example.executor.GameActionDecision
import com.example.executor.GameGestureAction
import com.example.overlay.AgentExecutionController
import com.example.overlay.AgentFloatingControlManager
import com.example.overlay.FloatingControlCallbacks
import com.example.policy.AgentPolicy
import com.example.policy.AgentPromptRules
import com.example.service.GameScreenObservation
import com.example.service.PhoneAgentAccessibilityService
import com.example.service.ScreenStateBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val TAG = "GeneralGameEngine"

data class GameLoopResult(
    val completedNaturally: Boolean,
    val stepsExecuted: Int,
    val summaryAr: String
)

/**
 * محرك لعب عام غير مرتبط بأي لعبة بعينها:
 * - يتحقق من الشاشة الحقيقية عبر شجرة إمكانية الوصول دون اختراع عناصر.
 * - يستخدم التطبيق كالإنسان (نقر، سحب، تمرير، انتظار، رجوع) ويتوقف فوراً عند طلب المستخدم.
 * - يعرض أزرار التحكم العائمة فوق التطبيق (إيقاف فوري، إيقاف مؤقت/استئناف، أكمل بنفسي، تصغير).
 */
class GeneralGameEngine(
    private val context: Context,
    private val geminiRepository: GeminiRepository,
    private val actionLogRepository: ActionLogRepository,
    private val agentPolicy: AgentPolicy = AgentPolicy(),
    private val executionController: AgentExecutionController = AgentExecutionController(),
    private val floatingControlManager: AgentFloatingControlManager = AgentFloatingControlManager(context)
) {

    suspend fun runObserveAndActLoop(
        goalAr: String,
        maxCycles: Int = 10,
        onCycleUpdate: suspend (String) -> Unit,
        callbacks: FloatingControlCallbacks
    ): GameLoopResult {
        val service = ScreenStateBridge.getService()
            ?: return GameLoopResult(
                completedNaturally = false,
                stepsExecuted = 0,
                summaryAr = "يتطلب وضع «العب مع الوكيل» تفعيل خدمة إمكانية الوصول أولاً ليتمكن الوكيل من مشاهدة الشاشة وتنفيذ اللمس والسحب."
            )

        executionController.reset()
        floatingControlManager.show(
            initialStatusAr = "الوكيل يلعب • يحتاج ثوانٍ لكل خطوة",
            isPaused = false,
            callbacks = callbacks
        )

        val recentActions = mutableListOf<String>()
        var executedCount = 0

        try {
            for (cycle in 1..maxCycles) {
                currentCoroutineContext().ensureActive()
                executionController.awaitIfPaused()

                floatingControlManager.updateStatus(
                    statusAr = "دورة $cycle/$maxCycles: قراءة الشاشة الحقيقية (ثوانٍ للتفكير)...",
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

                if (observation.snapshot.hasSensitiveSecurityField) {
                    val safeMsg = "تم إيقاف محرك اللعب فوراً لحمايتك بعد رصد شاشة حساسة (كلمة مرور/دفع/بصمة)."
                    actionLogRepository.logAction(
                        title = "إيقاف محرك اللعب احترازياً",
                        details = safeMsg,
                        status = ActionStatus.CANCELLED,
                        actionType = "GAME_SAFETY_STOP"
                    )
                    return GameLoopResult(false, executedCount, safeMsg)
                }

                executionController.awaitIfPaused()
                floatingControlManager.updateStatus(
                    statusAr = "دورة $cycle/$maxCycles: الوكيل يفكر في الحركة التالية (ثوانٍ)...",
                    isPaused = executionController.isPaused.value
                )

                val prompt = buildGameObservationPrompt(goalAr, observation, recentActions, cycle)
                val rawDecision = geminiRepository.conversationalReply(
                    userMessage = prompt,
                    contextSummary = AgentPromptRules.enrichGameContextSummary(observation.toPromptSummary())
                )
                val decision = ChatIntentRouter.parseGameActionDecision(rawDecision)

                val policyCheck = agentPolicy.findForbiddenCategory(
                    "${decision.targetText} ${decision.reasonAr}"
                )
                if (policyCheck != null) {
                    val blockedMsg = "توقف اللعب وفق سياسة الأمان (${policyCheck.titleAr})."
                    return GameLoopResult(false, executedCount, blockedMsg)
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
                    return GameLoopResult(true, executedCount, doneMsg)
                }

                executionController.awaitIfPaused()
                floatingControlManager.updateStatus(
                    statusAr = "دورة $cycle/$maxCycles: ${decision.reasonAr.ifBlank { "تنفيذ الحركة" }}",
                    isPaused = executionController.isPaused.value
                )

                val gestureOk = executeGameDecision(service, decision)
                executedCount++
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
            return GameLoopResult(true, executedCount, endSummary)
        } catch (e: CancellationException) {
            throw e
        } finally {
            floatingControlManager.dismiss()
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
        decision: GameActionDecision
    ): Boolean {
        return when (decision.action) {
            GameGestureAction.TAP_TEXT -> {
                val clicked = withContext(Dispatchers.Main) {
                    decision.targetText.isNotBlank() && service.findAndClickByText(decision.targetText)
                }
                if (clicked) true else service.performTapAt(decision.x, decision.y)
            }
            GameGestureAction.TAP_XY -> service.performTapAt(decision.x, decision.y)
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
