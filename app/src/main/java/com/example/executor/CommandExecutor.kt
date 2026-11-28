package com.example.executor

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import com.example.data.ai.GeminiRepository
import com.example.data.local.ActionLogRepository
import com.example.data.local.ActionStatus
import com.example.model.AgentPlanStep
import com.example.model.ScreenVerificationResult
import com.example.model.StepActionType
import com.example.model.StepVerificationStatus
import com.example.organizer.AppOrganizer
import com.example.organizer.InstalledAppInfo
import com.example.policy.AgentPolicy
import com.example.policy.GateApprovalToken
import com.example.policy.SensitiveActionGate
import com.example.service.ScreenStateBridge
import com.example.verifier.ScreenVerifier
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Calendar

data class StepExecutionOutcome(
    val success: Boolean,
    val messageAr: String,
    val verification: ScreenVerificationResult,
    val logId: Long,
    val canUndo: Boolean = false,
    val undoPayload: String? = null,
    val rolledBack: Boolean = false,
    val unableToVerify: Boolean = verification.status == StepVerificationStatus.UNABLE_TO_VERIFY
)

class CommandExecutor(
    private val context: Context,
    private val actionLogRepository: ActionLogRepository,
    private val geminiRepository: GeminiRepository,
    private val screenVerifier: ScreenVerifier,
    private val agentPolicy: AgentPolicy = AgentPolicy(),
    private val sensitiveActionGate: SensitiveActionGate = SensitiveActionGate(agentPolicy),
    private val privacyPreferences: com.example.security.PrivacyPreferences? = null
) {
    fun findMatchingAppPackage(appNameOrPackage: String, installedApps: List<InstalledAppInfo>): InstalledAppInfo? {
        return screenVerifier.matchRealInstalledApp(appNameOrPackage, installedApps)
    }

    suspend fun executeStep(
        step: AgentPlanStep,
        installedApps: List<InstalledAppInfo>,
        approvalToken: GateApprovalToken? = null
    ): StepExecutionOutcome {
        // 0. فحص بوابة الموافقة والسياسة الموحّدة في طبقة التنفيذ قبل أي خطوة
        val gateResult = sensitiveActionGate.authorizeStep(
            step = step,
            existingToken = approvalToken
        )
        if (!gateResult.approved) {
            val actionTypeTag = when {
                gateResult.blockedByPolicy -> "POLICY_BLOCKED_${step.actionType.name}"
                gateResult.timedOut -> "APPROVAL_TIMEOUT_${step.actionType.name}"
                else -> "USER_REJECTED_SENSITIVE_${step.actionType.name}"
            }
            val rawReason = gateResult.reasonAr
            val reason = when {
                gateResult.blockedByPolicy && !rawReason.contains("بدلاً من ذلك") ->
                    com.example.data.local.UserMemoryRepository.formatPlainPolicyRejection(actionTypeTag)
                rawReason.isNotBlank() -> rawReason
                else -> com.example.data.local.UserMemoryRepository.formatPlainPolicyRejection(actionTypeTag)
            }
            val logId = actionLogRepository.logAction(
                title = step.titleAr,
                details = reason,
                status = ActionStatus.CANCELLED,
                actionType = actionTypeTag
            )
            return StepExecutionOutcome(
                success = false,
                messageAr = reason,
                verification = ScreenVerificationResult(
                    verified = false,
                    status = StepVerificationStatus.VERIFICATION_FAILED,
                    explanationAr = reason,
                    sensitiveUiDetected = gateResult.blockedByPolicy || gateResult.requiresApproval
                ),
                logId = logId
            )
        }

        val preSnapshot = ScreenStateBridge.getService()?.captureCurrentScreen()
            ?: ScreenStateBridge.latestSnapshot.value

        // 1. طبقة التحقق الإجبارية قبل التنفيذ: عدم الثقة العمياء بـ Gemini
        val preCheck = screenVerifier.verifyBeforeStep(step, installedApps)
        if (!preCheck.allowed) {
            val logId = actionLogRepository.logAction(
                title = step.titleAr,
                details = "رُفضت الخطوة قبل التنفيذ: ${preCheck.reasonAr}",
                status = ActionStatus.FAILED,
                actionType = step.actionType.name
            )
            return StepExecutionOutcome(
                success = false,
                messageAr = preCheck.reasonAr,
                verification = ScreenVerificationResult(
                    verified = false,
                    status = preCheck.verificationStatus,
                    explanationAr = preCheck.reasonAr
                ),
                logId = logId
            )
        }

        // 2. تنفيذ الخطوة
        var canUndo = false
        var undoPayload: String? = null
        var scheduledReminderId: Int? = null

        val (executed, messageAr) = when (step.actionType) {
            StepActionType.OPEN_APP -> executeOpenApp(step, installedApps)
            StepActionType.SEARCH_IN_APP -> executeSearchInApp(step, installedApps)
            StepActionType.SET_ALARM -> executeSetAlarm(step)
            StepActionType.SET_REMINDER -> {
                val res = executeSetReminder(step)
                canUndo = res.third != null
                undoPayload = res.third
                if (res.third != null) {
                    scheduledReminderId = runCatching {
                        JSONObject(res.third!!).optInt("reminderId", -1)
                    }.getOrNull()?.takeIf { it > 0 }
                }
                res.first to res.second
            }
            StepActionType.SEND_MESSAGE -> executeSendMessage(step, installedApps)
            StepActionType.CHANGE_SETTING -> executeChangeSetting(step)
            StepActionType.TAP_ELEMENT -> executeAccessibilityTap(step)
            StepActionType.INPUT_TEXT -> executeAccessibilityInput(step)
            StepActionType.SCROLL_SCREEN -> executeAccessibilityScroll()
            StepActionType.READ_SCREEN_AND_SUMMARIZE -> executeReadAndSummarize(step)
            StepActionType.WAIT_AND_VERIFY -> true to "تمت قراءة الشاشة الفعلية للتحقق."
            StepActionType.SPEAK_ANSWER -> true to step.descriptionAr
        }

        // 3. مهلة قصيرة لثبات الشاشة ثم التحقق الإجباري من الشاشة الفعلية بعد التنفيذ
        if (executed && step.actionType != StepActionType.SPEAK_ANSWER) {
            delay(850L)
        }
        val postSnapshot = ScreenStateBridge.getService()?.captureCurrentScreen()
            ?: ScreenStateBridge.latestSnapshot.value

        val verification = screenVerifier.verifyAfterStep(
            step = step,
            executed = executed,
            executionMessageAr = messageAr,
            preSnapshot = preSnapshot,
            postSnapshot = postSnapshot
        )

        // 4. التراجع التلقائي الآمن فقط عند فشل التنفيذ أو فشل التحقق الصريح أو رصد واجهة حساسة
        var rolledBack = false
        var rollbackNote = ""
        val isExplicitFailure = !executed ||
            verification.sensitiveUiDetected ||
            verification.status == StepVerificationStatus.VERIFICATION_FAILED ||
            verification.status == StepVerificationStatus.NOT_FOUND_ON_SCREEN
        if (isExplicitFailure) {
            if (scheduledReminderId != null) {
                rolledBack = cancelScheduledReminder(scheduledReminderId)
                if (rolledBack) {
                    rollbackNote = " | تم التراجع التلقائي عن التذكير المجدول لعدم التحقق."
                    canUndo = false
                }
            } else if (executed && step.actionType in listOf(
                    StepActionType.TAP_ELEMENT,
                    StepActionType.SCROLL_SCREEN
                ) && verification.sensitiveUiDetected
            ) {
                rolledBack = ScreenStateBridge.getService()?.navigateBack() == true
                if (rolledBack) {
                    rollbackNote = " | تم التراجع التلقائي للخلف فور رصد شاشة حساسة."
                }
            }
        }

        val isUnableToVerify = executed &&
            verification.status == StepVerificationStatus.UNABLE_TO_VERIFY &&
            !verification.sensitiveUiDetected
        val overallSuccess = executed && verification.verified && !verification.sensitiveUiDetected
        val finalStatus = when {
            rolledBack -> ActionStatus.UNDONE
            overallSuccess -> ActionStatus.SUCCESS
            verification.sensitiveUiDetected -> ActionStatus.CANCELLED
            isUnableToVerify -> ActionStatus.UNVERIFIED
            else -> ActionStatus.FAILED
        }

        val truthfulSummary = buildString {
            when {
                overallSuccess -> append("نجح: $messageAr")
                isUnableToVerify -> {
                    append("تم الإجراء: $messageAr")
                    append(" | تحذير التحقق: ${verification.explanationAr}")
                }
                else -> {
                    append("ما تم: $messageAr")
                    append(" | لماذا فشل: ${verification.explanationAr}")
                }
            }
            append(rollbackNote)
        }

        val logId = actionLogRepository.logAction(
            title = step.titleAr,
            details = truthfulSummary,
            status = finalStatus,
            actionType = step.actionType.name,
            canUndo = canUndo && (overallSuccess || isUnableToVerify),
            undoPayload = undoPayload
        )

        return StepExecutionOutcome(
            success = overallSuccess,
            messageAr = truthfulSummary,
            verification = verification,
            logId = logId,
            canUndo = canUndo && (overallSuccess || isUnableToVerify),
            undoPayload = undoPayload,
            rolledBack = rolledBack,
            unableToVerify = isUnableToVerify
        )
    }

    private fun executeOpenApp(
        step: AgentPlanStep,
        installedApps: List<InstalledAppInfo>
    ): Pair<Boolean, String> {
        val targetApp = findMatchingAppPackage(step.targetAppName, installedApps)
            ?: return false to "لم أجد التطبيق «${step.targetAppName}» مثبتاً على الجهاز."

        val launchIntent = AppOrganizer.createLaunchIntent(context, targetApp.packageName)
            ?: return false to "لا يمكن فتح التطبيق «${targetApp.label}» مباشرة."

        return try {
            context.startActivity(launchIntent)
            true to "تم فتح تطبيق «${targetApp.label}»."
        } catch (e: Exception) {
            false to "تعذر فتح «${targetApp.label}»: ${e.localizedMessage}"
        }
    }

    private suspend fun executeSearchInApp(
        step: AgentPlanStep,
        installedApps: List<InstalledAppInfo>
    ): Pair<Boolean, String> {
        val query = step.targetQueryOrText.ifBlank { step.descriptionAr }
        val targetApp = findMatchingAppPackage(step.targetAppName, installedApps)

        if (targetApp != null) {
            val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage(targetApp.packageName)
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (searchIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(searchIntent)
                return true to "تم إرسال البحث «$query» إلى تطبيق «${targetApp.label}» عبر واجهة النظام."
            }
        }

        val service = ScreenStateBridge.getService()
        if (service != null) {
            val typed = service.findAndInputText("بحث", query) ||
                service.findAndInputText("search", query)
            if (typed) {
                return true to "تم إدخال عبارة البحث «$query» داخل حقل البحث الفعلي."
            }
            val clickedSearch = service.findAndClickHumanLike("بحث") ||
                service.findAndClickHumanLike("Search")
            if (clickedSearch) {
                return true to "تم الضغط على زر البحث الفعلي داخل التطبيق."
            }
        }

        return false to ScreenVerifier.MSG_ELEMENT_NOT_FOUND
    }

    private fun executeSetAlarm(step: AgentPlanStep): Pair<Boolean, String> {
        val calendar = Calendar.getInstance()
        val hour = step.hour ?: calendar.get(Calendar.HOUR_OF_DAY)
        val minute = step.minute ?: calendar.get(Calendar.MINUTE)
        val label = step.targetQueryOrText.ifBlank { "منبه وكيل الهاتف" }

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            val timeStr = String.format("%02d:%02d", hour, minute)
            true to "فُتحت واجهة المنبه على الساعة $timeStr ($label) — أكمل الإعداد والتأكيد."
        } catch (e: Exception) {
            false to "لا يوجد تطبيق ساعة متاح لفتح واجهة المنبه: ${e.localizedMessage}"
        }
    }

    private fun executeSetReminder(step: AgentPlanStep): Triple<Boolean, String, String?> {
        ReminderReceiver.ensureReminderChannel(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val targetCal = Calendar.getInstance()
        if (step.hour != null && step.minute != null) {
            targetCal.set(Calendar.HOUR_OF_DAY, step.hour)
            targetCal.set(Calendar.MINUTE, step.minute)
            targetCal.set(Calendar.SECOND, 0)
            if (targetCal.timeInMillis <= System.currentTimeMillis()) {
                targetCal.add(Calendar.DAY_OF_YEAR, 1)
            }
        } else {
            targetCal.add(Calendar.MINUTE, 10)
        }

        val reminderId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val message = step.targetQueryOrText.ifBlank { step.descriptionAr }
        val receiverIntent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_TITLE, "تذكير: ${step.titleAr}")
            putExtra(ReminderReceiver.EXTRA_MESSAGE, message)
            putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, reminderId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminderId,
            receiverIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    targetCal.timeInMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    targetCal.timeInMillis,
                    pendingIntent
                )
            }
            val undoJson = JSONObject().apply {
                put("type", "CANCEL_REMINDER")
                put("reminderId", reminderId)
            }.toString()
            val formattedTime = String.format(
                "%02d:%02d",
                targetCal.get(Calendar.HOUR_OF_DAY),
                targetCal.get(Calendar.MINUTE)
            )
            Triple(true, "تم جدولة تذكير «$message» للساعة $formattedTime.", undoJson)
        } catch (e: Exception) {
            Triple(false, "تعذر جدولة التذكير: ${e.localizedMessage}", null)
        }
    }

    fun cancelScheduledReminder(reminderId: Int): Boolean {
        return try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return false
            val receiverIntent = Intent(context, ReminderReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                reminderId,
                receiverIntent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: return false
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun executeSendMessage(
        step: AgentPlanStep,
        installedApps: List<InstalledAppInfo>
    ): Pair<Boolean, String> {
        val messageBody = step.targetQueryOrText.ifBlank { step.descriptionAr }
        val targetApp = findMatchingAppPackage(step.targetAppName, installedApps)

        val intent = if (targetApp != null && targetApp.packageName.contains("whatsapp")) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage(targetApp.packageName)
                putExtra(Intent.EXTRA_TEXT, messageBody)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).apply {
                putExtra("sms_body", messageBody)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        return try {
            context.startActivity(intent)
            val isPrivacyOn = privacyPreferences?.isPrivacyModeEnabled() == true
            val bodyDesc = if (isPrivacyOn) {
                "(محتوى محمي في وضع الخصوصية)"
            } else {
                "بمحتوى «${com.example.security.SensitiveDataScrubber.scrub(messageBody)}»"
            }
            true to "فُتحت واجهة الرسالة $bodyDesc — راجع وأرسل يدوياً."
        } catch (e: Exception) {
            val scrubbedErr = com.example.security.SensitiveDataScrubber.scrubLogMessage(e.localizedMessage)
            false to "تعذر فتح تطبيق المراسلة: $scrubbedErr"
        }
    }

    private fun executeChangeSetting(step: AgentPlanStep): Pair<Boolean, String> {
        val hint = (step.targetQueryOrText + " " + step.descriptionAr).lowercase()
        val action = when {
            hint.contains("واي فاي") || hint.contains("wifi") || hint.contains("إنترنت") ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            hint.contains("بلوتوث") || hint.contains("bluetooth") ->
                Settings.ACTION_BLUETOOTH_SETTINGS
            hint.contains("صوت") || hint.contains("رنين") || hint.contains("volume") ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS
            hint.contains("سطوع") || hint.contains("شاشة") || hint.contains("display") ->
                Settings.ACTION_DISPLAY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return try {
            context.startActivity(intent)
            true to "فُتحت واجهة الإعدادات المطلوبة — أكمل التعديل والتأكيد."
        } catch (e: Exception) {
            val scrubbedErr = com.example.security.SensitiveDataScrubber.scrubLogMessage(e.localizedMessage)
            false to "تعذر فتح الإعدادات: $scrubbedErr"
        }
    }

    private suspend fun executeAccessibilityTap(step: AgentPlanStep): Pair<Boolean, String> {
        val service = ScreenStateBridge.getService()
            ?: return false to ScreenVerifier.MSG_ELEMENT_NOT_FOUND
        val target = step.targetQueryOrText.ifBlank { step.titleAr }
        val clicked = service.findAndClickHumanLike(target)
        val scrubbedTarget = com.example.security.SensitiveDataScrubber.scrub(target)
        return if (clicked) {
            true to "تم الضغط على «$scrubbedTarget»."
        } else {
            false to ScreenVerifier.MSG_ELEMENT_NOT_FOUND
        }
    }

    private fun executeAccessibilityInput(step: AgentPlanStep): Pair<Boolean, String> {
        val service = ScreenStateBridge.getService()
            ?: return false to ScreenVerifier.MSG_ELEMENT_NOT_FOUND
        val text = step.targetQueryOrText
        val ok = service.findAndInputText(step.targetAppName, text)
        val isPrivacyOn = privacyPreferences?.isPrivacyModeEnabled() == true
        val textDesc = if (isPrivacyOn) "نص محمي" else com.example.security.SensitiveDataScrubber.scrub(text)
        return if (ok) {
            true to "تم كتابة «$textDesc» في الحقل الفعلي."
        } else {
            false to ScreenVerifier.MSG_ELEMENT_NOT_FOUND
        }
    }

    private suspend fun executeAccessibilityScroll(): Pair<Boolean, String> {
        val service = ScreenStateBridge.getService()
            ?: return false to ScreenVerifier.MSG_ELEMENT_NOT_FOUND
        val ok = service.scrollForward() || service.performSwipe(0.5f, 0.78f, 0.5f, 0.28f)
        return if (ok) {
            true to "تم تمرير الشاشة لعرض المزيد من المحتوى."
        } else {
            false to "لا يوجد عنصر قابل للتمرير في الشاشة الحالية."
        }
    }

    private suspend fun executeReadAndSummarize(step: AgentPlanStep): Pair<Boolean, String> {
        val service = ScreenStateBridge.getService()
        val snapshot = service?.captureCurrentScreen()?.also {
            ScreenStateBridge.updateSnapshot(it)
        }
            ?: ScreenStateBridge.latestSnapshot.value
            ?: return false to "لا توجد محتويات مقروءة من الشاشة حالياً. تأكد من تفعيل خدمة إمكانية الوصول."

        val isPrivacyOn = privacyPreferences?.isPrivacyModeEnabled() == true
        if (isPrivacyOn) {
            return true to com.example.verifier.LocalScreenSummarizer.summarizeSnapshot(
                snapshot = snapshot,
                appLabel = step.targetAppName,
                privacyMode = true
            )
        }

        return try {
            val summary = geminiRepository.summarizeAppScreens(
                appName = step.targetAppName.ifBlank { snapshot.packageName },
                packageName = snapshot.packageName,
                capturedScreenTexts = listOf(snapshot.toCompactPromptText())
            )
            true to summary
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            val localFallback = com.example.verifier.LocalScreenSummarizer.summarizeSnapshot(
                snapshot = snapshot,
                appLabel = step.targetAppName,
                privacyMode = false
            )
            true to localFallback
        }
    }
}
