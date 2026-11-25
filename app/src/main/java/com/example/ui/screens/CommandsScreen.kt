package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.PendingActions
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.model.AgentPlan
import com.example.model.AgentPlanStep
import com.example.model.PlanState
import com.example.model.StepActionType
import com.example.model.StepExecutionState

// شاشة الأوامر: أحجام متوازنة (v1.0.3) — زر الميكروفون 72dp بدل 104dp،
// وأزرار التنفيذ 48dp، والاقتراحات في FlowRow تلتف على عدة أسطر
// فلا يُقصّ أي نص داخل الشريحة.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommandsScreen(
    commandText: String,
    onCommandTextChange: (String) -> Unit,
    onSendCommand: () -> Unit,
    onMicPressed: () -> Unit,
    isRecording: Boolean,
    recordingSeconds: Int,
    transcriptText: String,
    isBusy: Boolean,
    isExecutingPlan: Boolean,
    plan: AgentPlan?,
    planState: PlanState,
    executionMessage: String,
    errorMessage: String,
    isCancellingExecution: Boolean,
    needsApprovalForStep: AgentPlanStep?,
    onNewPlan: () -> Unit,
    onExecutePlan: () -> Unit,
    onCancelExecution: () -> Unit,
    onApproveStep: () -> Unit,
    onRejectStep: () -> Unit,
    onSuggestionSelected: (String) -> Unit
) {
    val context = LocalContext.current
    var pendingMicAction by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingMicAction) {
            pendingMicAction = false
            onMicPressed()
        }
    }
    val recordPermissionGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
    val quickSuggestions = listOf(
        "افتح واتساب وأرسل رسالة ترحيب",
        "اضبط منبهاً للساعة 7:30 صباحاً",
        "افتح الإعدادات وابحث عن الشبكة",
        "ذكّرني بعد 10 دقائق بشرب الماء"
    )
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PendingActions,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Column {
                        Text(
                            text = "وكيل الأوامر",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "اكتب أمرك أو تحدث به، وسيحوّله الوكيل إلى خطة خطوات واضحة قبل التنفيذ.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "تحدث أو اكتب أمرك",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "اضغط على زر الميكروفون للتسجيل ثم اضغط مرة أخرى للإيقاف والإرسال.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    val infiniteTransition = rememberInfiniteTransition(label = "micPulse")
                    val pulseScale by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = if (isRecording) 1.05f else 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 750),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "micScale"
                    )
                    Button(
                        onClick = {
                            if (recordPermissionGranted) {
                                onMicPressed()
                            } else {
                                pendingMicAction = true
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier
                            .size(72.dp)
                            .scale(pulseScale),
                        shape = CircleShape,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = "تسجيل صوتي",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Text(
                        text = if (isRecording) "جارٍ التسجيل... ${recordingSeconds} ث" else "اضغط للتحدث",
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 13.sp,
                        color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    if (transcriptText.isNotBlank()) {
                        Text(
                            text = transcriptText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
        item {
            Text(
                text = "أو جرّب أحد هذه الاقتراحات:",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        item {
            // FlowRow بدل LazyRow: الاقتراحات تلتف على عدة أسطر وتظهر
            // كاملة، فلا يُقصّ نص أي شريحة مهما طال.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                quickSuggestions.forEach { suggestion ->
                    FilterChip(
                        selected = false,
                        onClick = { onSuggestionSelected(suggestion) },
                        label = {
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        )
                    )
                }
            }
        }
        item {
            OutlinedTextField(
                value = commandText,
                onValueChange = onCommandTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp),
                placeholder = {
                    Text(
                        text = "مثال: افتح واتساب وأرسل رسالة ترحيب...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                shape = RoundedCornerShape(16.dp)
            )
        }
        item {
            Button(
                onClick = onSendCommand,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                enabled = commandText.isNotBlank() && !isBusy && !isExecutingPlan,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "جارٍ تحليل الأمر...", fontSize = 15.sp)
                } else {
                    Icon(
                        imageVector = Icons.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "تحليل وتجهيز الخطة", fontSize = 15.sp)
                }
            }
        }
        if (errorMessage.isNotBlank()) {
            item {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        if (plan != null) {
            item {
                PlanSection(
                    plan = plan,
                    planState = planState,
                    executionMessage = executionMessage,
                    isExecutingPlan = isExecutingPlan,
                    isCancellingExecution = isCancellingExecution,
                    onNewPlan = onNewPlan,
                    onExecutePlan = onExecutePlan,
                    onCancelExecution = onCancelExecution
                )
            }
        }
    }
    needsApprovalForStep?.let { step ->
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(
                    text = "تأكيد الخطوة",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = step.titleAr,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = step.descriptionAr,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (step.safetyReasonAr.isNotBlank()) {
                        Text(
                            text = step.safetyReasonAr,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = onApproveStep,
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(text = "تنفيذ")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onRejectStep,
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(text = "تعديل الخطة")
                }
            }
        )
    }
}

@Composable
private fun PlanSection(
    plan: AgentPlan,
    planState: PlanState,
    executionMessage: String,
    isExecutingPlan: Boolean,
    isCancellingExecution: Boolean,
    onNewPlan: () -> Unit,
    onExecutePlan: () -> Unit,
    onCancelExecution: () -> Unit
) {
    val isExecuting = isExecutingPlan || planState == PlanState.EXECUTING
    val planSteps = plan.steps
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "خطة التنفيذ",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = plan.understandingSummaryAr,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AnimatedVisibility(visible = isExecuting) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            text = "جارٍ تنفيذ الخطوات...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (executionMessage.isNotBlank()) {
                        Text(
                            text = executionMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            planSteps.forEach { step ->
                StepRow(
                    step = step,
                    isExecuting = isExecuting
                )
            }
            Button(
                onClick = onExecutePlan,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                enabled = !isExecuting,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (planState == PlanState.COMPLETED) "إعادة تنفيذ الخطة" else "تنفيذ الخطة",
                    fontSize = 15.sp
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onNewPlan,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isExecuting
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "خطة جديدة", fontSize = 14.sp)
                }
                if (isExecuting) {
                    OutlinedButton(
                        onClick = onCancelExecution,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isCancellingExecution
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Cancel,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isCancellingExecution) "جارٍ الإيقاف..." else "إلغاء التنفيذ",
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: AgentPlanStep, isExecuting: Boolean) {
    val isDone = step.executionState == StepExecutionState.COMPLETED
    val isCurrent = isExecuting && step.executionState == StepExecutionState.RUNNING
    val isFailed = step.executionState == StepExecutionState.FAILED
    val stepIcon = when (step.actionType) {
        StepActionType.OPEN_APP -> Icons.Filled.Add
        StepActionType.SEARCH_IN_APP -> Icons.Filled.Add
        StepActionType.SET_ALARM -> Icons.Outlined.Schedule
        StepActionType.SET_REMINDER -> Icons.Outlined.Schedule
        StepActionType.SEND_MESSAGE -> Icons.Filled.Send
        StepActionType.OPEN_SETTINGS -> Icons.Filled.Settings
        StepActionType.CAPTURE_SCREEN -> Icons.Filled.Add
        StepActionType.SPEAK_ANSWER -> Icons.Filled.Mic
        StepActionType.OTHER -> Icons.Filled.Add
        StepActionType.CHANGE_SETTING -> Icons.Filled.Settings
        StepActionType.CLOSE_APP -> Icons.Filled.Delete
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isFailed -> MaterialTheme.colorScheme.errorContainer
                isDone -> MaterialTheme.colorScheme.primaryContainer
                isCurrent -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isFailed -> MaterialTheme.colorScheme.error
                            isDone -> MaterialTheme.colorScheme.primary
                            isCurrent -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isDone) Icons.Filled.CheckCircle else stepIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = Color.White
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${step.stepNumber}. ${step.titleAr}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = step.descriptionAr,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (step.targetAppName.isNotBlank() || step.targetQueryOrText.isNotBlank()) {
                    Text(
                        text = buildString {
                            if (step.targetAppName.isNotBlank()) append("التطبيق: ${step.targetAppName}")
                            if (step.targetQueryOrText.isNotBlank()) {
                                if (isNotEmpty()) append(" | ")
                                append(step.targetQueryOrText)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (step.requiresApproval) {
                    Text(
                        text = "تتطلب موافقتك قبل التنفيذ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun AppImagePlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            contentScale = ContentScale.Fit
        )
    }
}
