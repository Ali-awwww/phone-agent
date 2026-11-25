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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.example.model.StepActionType
import com.example.model.StepStatus
import com.example.ui.viewmodel.CommandsUiState
import com.example.ui.viewmodel.CommandsViewModel

// شاشة الأوامر — متوافقة مع CommandsViewModel و CommandsUiState.
// - زر ميكروفون مدمج (72dp) ونبض خفيف، وأزرار تنفيذ 48dp.
// - الاقتراحات تُعرض في FlowRow متعدد الأسطر فلا يُقص أي نص.

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommandsScreen(
    viewModel: CommandsViewModel,
    onOpenPermissionsTab: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val isAccessibilityConnected by viewModel.isAccessibilityConnected.collectAsState()
    val plan = (state.uiState as? CommandsUiState.PlanActive)?.plan
    val isPlanning = state.uiState is CommandsUiState.Planning
    val errorMessage = (state.uiState as? CommandsUiState.Error)?.message ?: ""
    val commandText = state.inputText
    val isRecording = state.isListening
    val transcriptText = state.partialTranscript
    val isBusy = isPlanning
    val isExecutingPlan = state.isExecuting
    val executionMessage = state.executionTruthReportAr ?: ""
    val needsApprovalForStep = state.pendingApprovalStep
    val isPlanComplete = plan?.isComplete == true

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startVoiceCapture()
        } else {
            viewModel.reportMicPermissionDenied()
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
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "وكيل الهاتف",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "اطلب بالصوت أو بالكتابة، وسيحوّل الوكيل طلبك إلى خطة واضحة قابلة للمراجعة والتنفيذ خطوة بخطوة.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isAccessibilityConnected) {
                        "خدمة إمكانية الوصول: متصلة"
                    } else {
                        "خدمة إمكانية الوصول: غير مفعّلة"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isAccessibilityConnected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                TextButton(onClick = onOpenPermissionsTab) {
                    Text(text = "الأذونات", fontSize = 13.sp)
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (state.voiceConversationMode) {
                        "وضع المحادثة الصوتية: مفعّل"
                    } else {
                        "وضع المحادثة الصوتية: متوقف"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { viewModel.toggleVoiceConversationMode() }) {
                    Text(text = "تبديل", fontSize = 13.sp)
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(
                                color = if (isRecording) {
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer
                                },
                                shape = CircleShape
                            )
                            .border(
                                width = 1.5.dp,
                                color = if (isRecording) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                },
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                            contentDescription = if (isRecording) "إيقاف التسجيل" else "بدء التسجيل الصوتي",
                            tint = if (isRecording) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            if (isRecording) {
                                viewModel.stopVoiceCapture()
                            } else if (recordPermissionGranted) {
                                viewModel.startVoiceCapture()
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = !isBusy,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isRecording) {
                                "إيقاف التسجيل"
                            } else {
                                "اضغط للتحدث"
                            },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isRecording) {
                            "جارٍ التسجيل... اضغط للإيقاف"
                        } else {
                            "اضغط على الزر ثم تحدث بوضوح"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    if (transcriptText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = transcriptText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = "أو جرّب أحد هذه الأوامر:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickSuggestions.forEach { suggestion ->
                    FilterChip(
                        selected = false,
                        onClick = { viewModel.analyzeAndPlanCommand(suggestion) },
                        label = {
                            Text(
                                text = suggestion,
                                fontSize = 13.sp,
                                maxLines = 2
                            )
                        },
                        enabled = true,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = false,
                            borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        )
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = commandText,
                onValueChange = { viewModel.updateInputText(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = "اكتب أمرك هنا...") },
                placeholder = { Text(text = "مثال: افتح واتساب وأرسل رسالة") },
                singleLine = false,
                minLines = 2,
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            )
        }

        item {
            Button(
                onClick = { viewModel.analyzeAndPlanCommand() },
                enabled = commandText.isNotBlank() && !isBusy && !isExecutingPlan,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = if (isBusy) "جارٍ تحليل الأمر..." else "تحليل وتجهيز الخطة",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (state.lastSpokenReply.isNotBlank()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "رد الوكيل:",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.lastSpokenReply,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }

        if (errorMessage.isNotBlank()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Cancel,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        if (plan != null) {
            item {
                PlanSection(
                    plan = plan,
                    isExecuting = isExecutingPlan,
                    isPlanComplete = isPlanComplete,
                    executionMessage = executionMessage,
                    onNewPlan = { viewModel.resetToIdle() },
                    onExecutePlan = { viewModel.executePlanStepByStep() },
                    onCancelExecution = { viewModel.cancelActivePlan() }
                )
            }
        }
    }

    needsApprovalForStep?.let { step ->
        AlertDialog(
            onDismissRequest = { viewModel.resolveApproval(false) },
            title = {
                Text(
                    text = "موافقة مطلوبة",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = step.titleAr,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = step.descriptionAr,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (step.safetyReasonAr.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "السبب: ${step.safetyReasonAr}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.resolveApproval(true) },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(text = "تنفيذ", fontSize = 14.sp)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { viewModel.resolveApproval(false) },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(text = "إلغاء", fontSize = 14.sp)
                }
            }
        )
    }
}

@Composable
private fun PlanSection(
    plan: AgentPlan,
    isExecuting: Boolean,
    isPlanComplete: Boolean,
    executionMessage: String,
    onNewPlan: () -> Unit,
    onExecutePlan: () -> Unit,
    onCancelExecution: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "خطة التنفيذ",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${plan.steps.size} خطوات",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = plan.understandingSummaryAr,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            AnimatedVisibility(visible = isExecuting) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "جارٍ تنفيذ الخطوات...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (executionMessage.isNotBlank()) {
                Text(
                    text = executionMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            plan.steps.forEach { step ->
                StepRow(step = step, isExecuting = isExecuting)
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onExecutePlan,
                enabled = !isExecuting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isPlanComplete) "إعادة تنفيذ الخطة" else "تنفيذ الخطة",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
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
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "خطة جديدة", fontSize = 14.sp)
                }
                if (isExecuting) {
                    OutlinedButton(
                        onClick = onCancelExecution,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Cancel,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "إلغاء التنفيذ", fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: AgentPlanStep, isExecuting: Boolean) {
    val isDone = step.status == StepStatus.COMPLETED
    val isCurrent = isExecuting && step.status == StepStatus.RUNNING
    val isFailed = step.status == StepStatus.FAILED
    val stepIcon = when (step.actionType) {
        StepActionType.OPEN_APP -> Icons.Filled.Add
        StepActionType.SEARCH_IN_APP -> Icons.Filled.Add
        StepActionType.SET_ALARM -> Icons.Outlined.Schedule
        StepActionType.SET_REMINDER -> Icons.Outlined.Schedule
        StepActionType.SEND_MESSAGE -> Icons.Filled.Send
        StepActionType.CHANGE_SETTING -> Icons.Filled.Settings
        StepActionType.TAP_ELEMENT -> Icons.Filled.Add
        StepActionType.INPUT_TEXT -> Icons.Filled.Add
        StepActionType.SCROLL_SCREEN -> Icons.Outlined.Autorenew
        StepActionType.READ_SCREEN_AND_SUMMARIZE -> Icons.Outlined.PendingActions
        StepActionType.WAIT_AND_VERIFY -> Icons.Outlined.Autorenew
        StepActionType.SPEAK_ANSWER -> Icons.Filled.Mic
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = when {
            isFailed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
            isDone -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            isCurrent -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = CircleShape,
                color = when {
                    isFailed -> MaterialTheme.colorScheme.error
                    isDone -> MaterialTheme.colorScheme.primary
                    isCurrent -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.outline
                }
            ) {
                Box(
                    modifier = Modifier.size(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isDone) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    } else if (isFailed) {
                        Icon(
                            imageVector = Icons.Filled.Cancel,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            imageVector = stepIcon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${step.stepNumber}. ${step.titleAr}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = step.descriptionAr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "الحالة: ${step.status.labelAr}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isFailed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (step.targetAppName.isNotBlank() || step.targetQueryOrText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppImagePlaceholder(
                            packageName = "",
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = listOf(step.targetAppName, step.targetQueryOrText)
                                .filter { it.isNotBlank() }
                                .joinToString(" — "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (step.requiresApproval) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "تتطلب موافقتك قبل التنفيذ",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun AppImagePlaceholder(
    packageName: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.size(28.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = packageName.ifBlank { "أيقونة التطبيق" },
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}
