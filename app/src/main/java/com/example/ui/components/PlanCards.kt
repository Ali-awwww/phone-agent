package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import com.example.organizer.InstalledAppInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.PendingActions
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.AgentPlan
import com.example.model.AgentPlanStep
import com.example.model.StepActionType
import com.example.model.StepStatus
import com.example.ui.theme.CyberBorderGlow
import com.example.ui.theme.CyberElectricBlue
import com.example.ui.theme.CyberElectricCyan
import com.example.ui.theme.CyberOnCyan
import com.example.ui.theme.CyberSoftCyan
import com.example.ui.theme.CyberSurfaceElevated
import com.example.ui.theme.CyberSurfaceNavy
import com.example.ui.theme.CyberTextPrimary
import com.example.ui.theme.CyberTextSecondary
import com.example.ui.theme.StatusCyanInfoContainer
import com.example.ui.theme.StatusCancelled
import com.example.ui.theme.StatusCancelledContainer
import com.example.ui.theme.StatusPending
import com.example.ui.theme.StatusPendingContainer
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusSuccessContainer

@Composable
fun CyberPlanCard(
    plan: AgentPlan,
    isExecuting: Boolean,
    isPlanComplete: Boolean,
    executionMessage: String,
    onNewPlan: () -> Unit,
    onExecutePlan: () -> Unit,
    onExecuteNextStep: () -> Unit = onExecutePlan,
    onCancelExecution: () -> Unit,
    modifier: Modifier = Modifier
) {
    CyberPanelCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("active_plan_card"),
        glowAccent = true
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "خطة التنفيذ المرقّمة",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = CyberElectricCyan
                )
                Text(
                    text = "${plan.steps.size} خطوات",
                    style = MaterialTheme.typography.labelLarge,
                    color = CyberSoftCyan
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = plan.understandingSummaryAr,
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextSecondary
            )
            Spacer(modifier = Modifier.height(10.dp))

            AnimatedVisibility(visible = isExecuting) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = CyberElectricCyan
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "جارٍ التنفيذ والتحقق من الشاشة... (الوكيل ليس لحظياً ويحتاج بضع ثوانٍ لكل خطوة)",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberElectricCyan
                    )
                }
            }

            if (executionMessage.isNotBlank()) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = StatusCyanInfoContainer,
                    border = BorderStroke(1.dp, CyberBorderGlow),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                ) {
                    Text(
                        text = executionMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberTextPrimary,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            plan.steps.forEach { step ->
                CyberStepRow(step = step, isExecuting = isExecuting)
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onExecutePlan,
                enabled = !isExecuting && !plan.isCancelled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("execute_plan_button"),
                contentPadding = PaddingValues(horizontal = 16.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberElectricBlue,
                    contentColor = CyberOnCyan
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isPlanComplete) "إعادة تنفيذ الخطة" else "تنفيذ الخطة خطوة بخطوة",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (!isPlanComplete && !plan.isCancelled) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onExecuteNextStep,
                    enabled = !isExecuting && !plan.isCancelled && !isPlanComplete,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("execute_next_step_button"),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, CyberElectricCyan),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = CyberElectricCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.execute_next_button),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CyberElectricCyan
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onNewPlan,
                    enabled = !isExecuting,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, CyberBorderGlow),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = CyberTextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "إغلاق الخطة", fontSize = 14.sp, color = CyberTextPrimary)
                }

                OutlinedButton(
                    onClick = onCancelExecution,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("plan_cancel_task_button"),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, StatusCancelled),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = StatusCancelled
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Cancel,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "إلغاء المهمة", fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun CyberStepRow(step: AgentPlanStep, isExecuting: Boolean) {
    val isDone = step.status == StepStatus.COMPLETED
    val isUnverified = step.status == StepStatus.UNVERIFIED
    val isCurrent = isExecuting && (step.status == StepStatus.RUNNING || step.status == StepStatus.VERIFYING)
    val isFailed = step.status == StepStatus.FAILED || step.status == StepStatus.CANCELLED
    val stepIcon = when (step.actionType) {
        StepActionType.OPEN_APP -> Icons.Filled.Add
        StepActionType.SEARCH_IN_APP -> Icons.Filled.Add
        StepActionType.SET_ALARM, StepActionType.SET_REMINDER -> Icons.Outlined.Schedule
        StepActionType.SEND_MESSAGE -> Icons.Filled.Send
        StepActionType.CHANGE_SETTING -> Icons.Filled.Settings
        StepActionType.TAP_ELEMENT, StepActionType.INPUT_TEXT -> Icons.Filled.Add
        StepActionType.SCROLL_SCREEN, StepActionType.WAIT_AND_VERIFY -> Icons.Outlined.Autorenew
        StepActionType.READ_SCREEN_AND_SUMMARIZE -> Icons.Outlined.PendingActions
        StepActionType.SPEAK_ANSWER -> Icons.Filled.Mic
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = when {
            isFailed -> StatusCancelledContainer
            isUnverified -> StatusPendingContainer
            isDone -> StatusSuccessContainer
            isCurrent -> StatusCyanInfoContainer
            else -> CyberSurfaceElevated
        },
        border = BorderStroke(
            1.dp,
            when {
                isFailed -> StatusCancelled.copy(alpha = 0.6f)
                isUnverified -> StatusPending.copy(alpha = 0.7f)
                isDone -> StatusSuccess.copy(alpha = 0.6f)
                isCurrent -> CyberElectricCyan
                else -> CyberBorderGlow
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = CircleShape,
                color = when {
                    isFailed -> StatusCancelled
                    isUnverified -> StatusPending
                    isDone -> StatusSuccess
                    isCurrent -> CyberElectricCyan
                    else -> CyberElectricBlue
                }
            ) {
                Box(
                    modifier = Modifier.size(30.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isDone) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(17.dp)
                        )
                    } else if (isFailed) {
                        Icon(
                            imageVector = Icons.Filled.Cancel,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(17.dp)
                        )
                    } else if (isUnverified) {
                        Icon(
                            imageVector = Icons.Outlined.PendingActions,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(17.dp)
                        )
                    } else {
                        Icon(
                            imageVector = stepIcon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${step.stepNumber}. ${step.titleAr}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = CyberTextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = step.descriptionAr,
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberTextSecondary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "الحالة: ${step.status.labelAr}",
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        isFailed -> StatusCancelled
                        isUnverified -> StatusPending
                        isDone -> StatusSuccess
                        else -> CyberSoftCyan
                    }
                )
                if (!step.verificationResultAr.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = step.verificationResultAr,
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberTextSecondary
                    )
                }
                if (step.requiresApproval) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "يتطلب موافقتك الصريحة قبل التنفيذ",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = StatusPending
                    )
                }
            }
        }
    }
}

@Composable
fun SensitiveStepApprovalDialog(
    step: AgentPlanStep,
    fullPlan: AgentPlan?,
    nextStep: AgentPlanStep? = fullPlan?.steps?.firstOrNull { it.stepNumber > step.stepNumber },
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    val resolvedNextStep = nextStep ?: fullPlan?.steps?.firstOrNull { it.stepNumber > step.stepNumber }
    AlertDialog(
        onDismissRequest = onReject,
        containerColor = CyberSurfaceNavy,
        titleContentColor = StatusPending,
        textContentColor = CyberTextPrimary,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Shield,
                contentDescription = null,
                tint = StatusPending
            )
        },
        title = {
            Text(
                text = "موافقة صريحة على إجراء حساس",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "الخطوة ${step.stepNumber}: ${step.titleAr}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = CyberElectricCyan
                )
                Text(
                    text = step.descriptionAr,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (step.safetyReasonAr.isNotBlank()) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = StatusPendingContainer,
                        border = BorderStroke(1.dp, StatusPending.copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = "سبب طلب الموافقة: ${step.safetyReasonAr}",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusPending,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
                if (resolvedNextStep != null) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("approval_next_step_preview"),
                        shape = MaterialTheme.shapes.small,
                        color = StatusCyanInfoContainer,
                        border = BorderStroke(1.dp, CyberElectricCyan.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "الخطوة التالية في الخطة: ${resolvedNextStep.stepNumber}. ${resolvedNextStep.titleAr}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = CyberElectricCyan
                            )
                            if (resolvedNextStep.descriptionAr.isNotBlank()) {
                                Text(
                                    text = resolvedNextStep.descriptionAr,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CyberTextSecondary
                                )
                            }
                        }
                    }
                } else if (fullPlan != null && fullPlan.steps.isNotEmpty()) {
                    Text(
                        text = "هذه هي الخطوة الأخيرة في الخطة الحالية.",
                        style = MaterialTheme.typography.labelMedium,
                        color = CyberSoftCyan,
                        modifier = Modifier.testTag("approval_next_step_preview")
                    )
                }
                if (fullPlan != null && fullPlan.steps.isNotEmpty()) {
                    Text(
                        text = "ملخص الخطة الكاملة (${fullPlan.steps.size} خطوات):",
                        style = MaterialTheme.typography.labelLarge,
                        color = CyberSoftCyan
                    )
                    fullPlan.steps.forEach { s ->
                        Text(
                            text = "${s.stepNumber}. ${s.titleAr}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (s.stepNumber == step.stepNumber) CyberElectricCyan else CyberTextSecondary,
                            fontWeight = if (s.stepNumber == step.stepNumber) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onApprove,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("approve_sensitive_step_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberElectricCyan,
                    contentColor = CyberOnCyan
                )
            ) {
                Text(text = "موافق، نفّذ الآن", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onReject,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("reject_sensitive_step_button"),
                border = BorderStroke(1.dp, StatusCancelled)
            ) {
                Text(text = "إلغاء الإجراء", color = StatusCancelled)
            }
        }
    )
}

@Composable
fun AppPickerForGameplayDialog(
    installedApps: List<InstalledAppInfo>,
    onDismiss: () -> Unit,
    onAppSelected: (InstalledAppInfo) -> Unit
) {
    var searchQuery by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val filtered = androidx.compose.runtime.remember(installedApps, searchQuery) {
        if (searchQuery.isBlank()) installedApps
        else installedApps.filter { it.label.contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CyberSurfaceNavy,
        shape = MaterialTheme.shapes.large,
        title = {
            Text("اختر التطبيق أو اللعبة ليبدأ الوكيل اللعب والتفاعل", color = CyberElectricCyan, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("بحث عن تطبيق...", color = CyberTextSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { onAppSelected(app) },
                            color = CyberSurfaceElevated,
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, CyberBorderGlow)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = app.label, fontWeight = FontWeight.Bold, color = CyberTextPrimary)
                                    Text(text = app.folder.titleAr, style = MaterialTheme.typography.labelSmall, color = CyberSoftCyan)
                                }
                                Button(
                                    onClick = { onAppSelected(app) },
                                    colors = ButtonDefaults.buttonColors(containerColor = CyberElectricCyan, contentColor = CyberOnCyan),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text("ابدأ اللعب")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء", color = CyberElectricCyan)
            }
        }
    )
}

