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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.model.AgentPlan
import com.example.model.AgentPlanStep
import com.example.model.StepStatus
import com.example.model.StepVerificationStatus
import com.example.ui.components.CyberPanelCard
import com.example.ui.theme.CyberBgDeep
import com.example.ui.theme.CyberBorderGlow
import com.example.ui.theme.CyberElectricBlue
import com.example.ui.theme.CyberElectricCyan
import com.example.ui.theme.CyberOnCyan
import com.example.ui.theme.CyberSurfaceElevated
import com.example.ui.theme.CyberSurfaceNavy
import com.example.ui.theme.CyberSurfaceVariant
import com.example.ui.theme.CyberTextPrimary
import com.example.ui.theme.CyberTextSecondary
import com.example.ui.theme.StatusCancelled
import com.example.ui.theme.StatusCancelledContainer
import com.example.ui.theme.StatusCyanInfo
import com.example.ui.theme.StatusCyanInfoContainer
import com.example.ui.theme.StatusPending
import com.example.ui.theme.StatusPendingContainer
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusSuccessContainer
import com.example.ui.viewmodel.CommandsUiState
import com.example.ui.viewmodel.CommandsViewModel

@Composable
fun CommandsScreen(
    viewModel: CommandsViewModel,
    onOpenPermissionsTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isAccessibilityConnected by viewModel.isAccessibilityConnected.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startVoiceCapture()
        } else {
            viewModel.reportMicPermissionDenied()
        }
    }

    val quickSuggestions = listOf(
        "افتح الإعدادات وابحث عن الشبكة",
        "اضبط منبهاً للساعة 7:30 صباحاً",
        "ذكّرني بعد 10 دقائق بشرب الماء",
        "افتح واتساب وأرسل رسالة ترحيب"
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // بطاقة الرأس السايبرانية
            item {
                AgentHeroCard(
                    isAccessibilityConnected = isAccessibilityConnected,
                    voiceConversationMode = state.voiceConversationMode,
                    onToggleVoiceMode = { viewModel.toggleVoiceConversationMode() },
                    onOpenPermissionsTab = onOpenPermissionsTab
                )
            }

            // زر الصوت الكبير
            item {
                LargeVoiceCommandCard(
                    isListening = state.isListening,
                    partialTranscript = state.partialTranscript,
                    onVoiceClick = {
                        val hasMic = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasMic) {
                            viewModel.startVoiceCapture()
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                )
            }

            // حقل كتابة الأمر + اقتراحات سريعة
            item {
                CyberPanelCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "أو أدخل أمرك نصياً",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = CyberElectricCyan
                        )

                        OutlinedTextField(
                            value = state.inputText,
                            onValueChange = viewModel::updateInputText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("command_text_input"),
                            placeholder = {
                                Text(
                                    stringResource(R.string.command_input_hint),
                                    color = CyberTextSecondary
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyberElectricCyan,
                                unfocusedBorderColor = CyberBorderGlow,
                                focusedContainerColor = CyberBgDeep,
                                unfocusedContainerColor = CyberBgDeep,
                                focusedTextColor = CyberTextPrimary,
                                unfocusedTextColor = CyberTextPrimary
                            ),
                            minLines = 2,
                            maxLines = 4,
                            shape = MaterialTheme.shapes.small
                        )

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(quickSuggestions) { suggestion ->
                                FilterChip(
                                    selected = state.inputText == suggestion,
                                    onClick = { viewModel.updateInputText(suggestion) },
                                    shape = MaterialTheme.shapes.small,
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = CyberSurfaceElevated,
                                        labelColor = CyberTextPrimary,
                                        selectedContainerColor = CyberElectricBlue,
                                        selectedLabelColor = Color.White
                                    ),
                                    border = BorderStroke(
                                        1.dp,
                                        if (state.inputText == suggestion) CyberElectricCyan else CyberBorderGlow
                                    ),
                                    label = { Text(suggestion, style = MaterialTheme.typography.bodyMedium) }
                                )
                            }
                        }

                        Button(
                            onClick = { viewModel.analyzeAndPlanCommand() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("build_plan_button"),
                            enabled = state.uiState !is CommandsUiState.Planning && !state.isExecuting,
                            shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyberElectricCyan,
                                contentColor = CyberOnCyan
                            )
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Send,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.build_plan_button),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            when (val uiState = state.uiState) {
                CommandsUiState.Idle -> {
                    item {
                        CyberPanelCard(containerColor = CyberSurfaceElevated) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.VerifiedUser,
                                    contentDescription = null,
                                    tint = CyberElectricCyan,
                                    modifier = Modifier.size(32.dp)
                                )
                                Text(
                                    text = "تحقق صارم قبل كل خطوة: يطابق الوكيل خطة Gemini مع الشاشة الفعلية والتطبيقات المثبتة، ويرفض اختراع أي زر أو تطبيق غير موجود.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = CyberTextPrimary
                                )
                            }
                        }
                    }
                }

                CommandsUiState.Planning -> {
                    item {
                        CyberPanelCard(glowAccent = true) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    color = CyberElectricCyan
                                )
                                Column {
                                    Text(
                                        text = "جاري تحليل طلبك والتحقق منه...",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = CyberElectricCyan,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "يتم ربط الخطة بالتطبيقات الفعلية وعناصر الشاشة.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = CyberTextSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                is CommandsUiState.Error -> {
                    item {
                        CyberPanelCard(
                            containerColor = StatusCancelledContainer,
                            borderColor = StatusCancelled
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.WarningAmber,
                                        contentDescription = null,
                                        tint = StatusCancelled
                                    )
                                    Text(
                                        text = "تعذر التنفيذ",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = StatusCancelled,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = uiState.message,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = CyberTextPrimary
                                )
                                OutlinedButton(
                                    onClick = { viewModel.resetToIdle() },
                                    shape = MaterialTheme.shapes.small,
                                    border = BorderStroke(1.dp, StatusCancelled)
                                ) {
                                    Text("إعادة المحاولة", color = StatusCancelled)
                                }
                            }
                        }
                    }
                }

                is CommandsUiState.PlanActive -> {
                    if (uiState.plan.hallucinationWarnings.isNotEmpty()) {
                        item {
                            CyberPanelCard(
                                containerColor = StatusPendingContainer,
                                borderColor = StatusPending
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "رصد طبقة التحقق من الهلوسة:",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = StatusPending
                                    )
                                    uiState.plan.hallucinationWarnings.forEach { warn ->
                                        Text(
                                            text = "• $warn",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = CyberTextPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        PlanSummaryAndControlsCard(
                            plan = uiState.plan,
                            isExecuting = state.isExecuting,
                            onExecuteAll = { viewModel.executePlanStepByStep(singleStepOnly = false) },
                            onExecuteNext = { viewModel.executePlanStepByStep(singleStepOnly = true) },
                            onCancel = { viewModel.cancelActivePlan() },
                            onNewCommand = { viewModel.resetToIdle() }
                        )
                    }

                    state.executionTruthReportAr?.let { report ->
                        item {
                            CyberPanelCard(
                                containerColor = CyberSurfaceElevated,
                                borderColor = CyberElectricCyan
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.FactCheck,
                                            contentDescription = null,
                                            tint = CyberElectricCyan
                                        )
                                        Text(
                                            text = "تقرير النتيجة الفعلية",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = CyberElectricCyan
                                        )
                                    }
                                    Text(
                                        text = report,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = CyberTextPrimary
                                    )
                                }
                            }
                        }
                    }

                    items(uiState.plan.steps, key = { it.stepNumber }) { step ->
                        PlanStepCard(step = step)
                    }
                }
            }
        }
    }

    // حوار الموافقة الصريحة: يعرض الخطة كاملة قبل أي إجراء حساس
    val approvalStep = state.pendingApprovalStep
    val approvalPlan = state.pendingApprovalPlan
    if (approvalStep != null && approvalPlan != null) {
        AlertDialog(
            onDismissRequest = { viewModel.resolveApproval(false) },
            containerColor = CyberSurfaceNavy,
            shape = MaterialTheme.shapes.large,
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = StatusPending,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.approval_title),
                    fontWeight = FontWeight.Bold,
                    color = StatusPending
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "الإجراء الحساس: ${approvalStep.titleAr}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = CyberElectricCyan
                    )
                    Text(
                        text = approvalStep.safetyReasonAr.ifBlank { approvalStep.descriptionAr },
                        style = MaterialTheme.typography.bodyMedium,
                        color = CyberTextPrimary
                    )

                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = CyberBgDeep,
                        border = BorderStroke(1.dp, CyberBorderGlow)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "مراجعة الخطة كاملة (${approvalPlan.steps.size} خطوات):",
                                style = MaterialTheme.typography.labelLarge,
                                color = CyberElectricCyan,
                                fontWeight = FontWeight.Bold
                            )
                            approvalPlan.steps.forEach { s ->
                                val marker = if (s.stepNumber == approvalStep.stepNumber) "⚠️" else "•"
                                Text(
                                    text = "$marker ${s.stepNumber}. ${s.titleAr} — ${s.descriptionAr}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (s.stepNumber == approvalStep.stepNumber) StatusPending else CyberTextSecondary
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.resolveApproval(true) },
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberElectricCyan,
                        contentColor = CyberOnCyan
                    ),
                    modifier = Modifier
                        .height(50.dp)
                        .testTag("approve_step_button")
                ) {
                    Text(stringResource(R.string.approval_confirm), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { viewModel.resolveApproval(false) },
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, StatusCancelled),
                    modifier = Modifier
                        .height(50.dp)
                        .testTag("reject_step_button")
                ) {
                    Text(stringResource(R.string.approval_cancel), color = StatusCancelled)
                }
            }
        )
    }
}

@Composable
private fun AgentHeroCard(
    isAccessibilityConnected: Boolean,
    voiceConversationMode: Boolean,
    onToggleVoiceMode: () -> Unit,
    onOpenPermissionsTab: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .border(1.5.dp, CyberElectricCyan.copy(alpha = 0.7f), MaterialTheme.shapes.large)
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_cyber_banner_1795550889807),
            contentDescription = "خلفية وكيل الهاتف السايبرانية",
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize()
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xB8050B1E),
                            Color(0xE6050B1E)
                        )
                    )
                )
        )
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        color = CyberElectricCyan,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.commands_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = CyberTextPrimary
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = CyberElectricCyan,
                    modifier = Modifier.size(32.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    onClick = onOpenPermissionsTab,
                    shape = MaterialTheme.shapes.small,
                    color = if (isAccessibilityConnected) StatusSuccessContainer else StatusPendingContainer,
                    border = BorderStroke(
                        1.dp,
                        if (isAccessibilityConnected) StatusSuccess else StatusPending
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (isAccessibilityConnected) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = if (isAccessibilityConnected) StatusSuccess else StatusPending,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (isAccessibilityConnected) "خدمة الوصول مفعّلة" else "فعّل خدمة الوصول",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyberTextPrimary
                        )
                    }
                }

                Surface(
                    onClick = onToggleVoiceMode,
                    shape = MaterialTheme.shapes.small,
                    color = if (voiceConversationMode) StatusCyanInfoContainer else CyberSurfaceNavy,
                    border = BorderStroke(
                        1.dp,
                        if (voiceConversationMode) CyberElectricCyan else CyberBorderGlow
                    ),
                    modifier = Modifier.testTag("voice_mode_toggle")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (voiceConversationMode) Icons.Rounded.RecordVoiceOver else Icons.Rounded.VolumeOff,
                            contentDescription = null,
                            tint = if (voiceConversationMode) CyberElectricCyan else CyberTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (voiceConversationMode) "رد صوتي مفعّل" else "رد صامت",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyberTextPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LargeVoiceCommandCard(
    isListening: Boolean,
    partialTranscript: String,
    onVoiceClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "micPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.09f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    CyberPanelCard(
        containerColor = if (isListening) StatusCancelledContainer else CyberSurfaceNavy,
        borderColor = if (isListening) StatusCancelled else CyberElectricCyan,
        glowAccent = true
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onVoiceClick,
                shape = CircleShape,
                border = BorderStroke(2.dp, if (isListening) StatusCancelled else CyberElectricCyan),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isListening) StatusCancelled else CyberElectricBlue,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .size(104.dp)
                    .scale(pulseScale)
                    .testTag("voice_command_button"),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Rounded.GraphicEq else Icons.Rounded.Mic,
                    contentDescription = if (isListening) "إيقاف الاستماع" else "بدء الأمر الصوتي",
                    modifier = Modifier.size(46.dp)
                )
            }

            Text(
                text = if (isListening) stringResource(R.string.voice_button_listening) else stringResource(R.string.voice_button_start),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = CyberElectricCyan,
                textAlign = TextAlign.Center
            )

            AnimatedVisibility(visible = partialTranscript.isNotBlank()) {
                Text(
                    text = "«$partialTranscript»",
                    style = MaterialTheme.typography.bodyLarge,
                    color = CyberTextPrimary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun PlanSummaryAndControlsCard(
    plan: AgentPlan,
    isExecuting: Boolean,
    onExecuteAll: () -> Unit,
    onExecuteNext: () -> Unit,
    onCancel: () -> Unit,
    onNewCommand: () -> Unit
) {
    CyberPanelCard(glowAccent = true) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "خطة التنفيذ (${plan.steps.size} خطوات)",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = CyberElectricCyan
            )
            Text(
                text = plan.understandingSummaryAr,
                style = MaterialTheme.typography.bodyLarge,
                color = CyberTextPrimary
            )

            if (plan.spokenReplyAr.isNotBlank()) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = CyberBgDeep,
                    border = BorderStroke(1.dp, CyberBorderGlow)
                ) {
                    Text(
                        text = "💬 ${plan.spokenReplyAr}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CyberTextPrimary,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onExecuteAll,
                    enabled = !isExecuting && !plan.isComplete && !plan.isCancelled,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .testTag("execute_all_button"),
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberElectricCyan,
                        contentColor = CyberOnCyan
                    )
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("نفّذ الكل", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onExecuteNext,
                    enabled = !isExecuting && !plan.isComplete && !plan.isCancelled,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .testTag("execute_next_button"),
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, CyberElectricCyan)
                ) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = null, tint = CyberElectricCyan)
                    Spacer(Modifier.width(6.dp))
                    Text("الخطوة التالية", style = MaterialTheme.typography.labelLarge, color = CyberElectricCyan)
                }
            }

            if (plan.isComplete || plan.isCancelled) {
                Button(
                    onClick = onNewCommand,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("new_command_button"),
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberElectricBlue,
                        contentColor = Color.White
                    )
                ) {
                    Text("أمر جديد / مسح الخطة", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            } else {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("cancel_plan_button"),
                    border = BorderStroke(1.dp, StatusCancelled),
                    shape = MaterialTheme.shapes.small
                ) {
                    Icon(
                        imageVector = if (isExecuting) Icons.Rounded.Stop else Icons.Rounded.Cancel,
                        contentDescription = null,
                        tint = StatusCancelled
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.cancel_plan_button),
                        style = MaterialTheme.typography.labelLarge,
                        color = StatusCancelled
                    )
                }
            }
        }
    }
}

@Composable
private fun PlanStepCard(step: AgentPlanStep) {
    val (badgeBg, badgeFg) = when (step.status) {
        StepStatus.COMPLETED -> StatusSuccessContainer to StatusSuccess
        StepStatus.AWAITING_APPROVAL, StepStatus.RUNNING, StepStatus.VERIFYING ->
            StatusPendingContainer to StatusPending
        StepStatus.CANCELLED, StepStatus.FAILED ->
            StatusCancelledContainer to StatusCancelled
        StepStatus.PENDING ->
            CyberSurfaceVariant to CyberTextSecondary
    }

    val (verBg, verFg) = when (step.verificationStatus) {
        StepVerificationStatus.AWAITING_VERIFICATION ->
            StatusCyanInfoContainer to StatusCyanInfo
        StepVerificationStatus.VERIFIED_ON_SCREEN ->
            StatusSuccessContainer to StatusSuccess
        StepVerificationStatus.NOT_FOUND_ON_SCREEN ->
            StatusPendingContainer to StatusPending
        StepVerificationStatus.VERIFICATION_FAILED ->
            StatusCancelledContainer to StatusCancelled
    }

    CyberPanelCard(
        borderColor = when (step.verificationStatus) {
            StepVerificationStatus.VERIFIED_ON_SCREEN -> StatusSuccess.copy(alpha = 0.7f)
            StepVerificationStatus.NOT_FOUND_ON_SCREEN,
            StepVerificationStatus.VERIFICATION_FAILED -> StatusCancelled.copy(alpha = 0.7f)
            else -> CyberBorderGlow
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(CyberElectricBlue)
                            .border(1.dp, CyberElectricCyan, MaterialTheme.shapes.small),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${step.stepNumber}",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = step.titleAr,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = CyberTextPrimary
                    )
                }

                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = badgeBg,
                    border = BorderStroke(1.dp, badgeFg.copy(alpha = 0.6f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (step.status == StepStatus.RUNNING || step.status == StepStatus.VERIFYING) {
                            Icon(
                                imageVector = Icons.Rounded.HourglassTop,
                                contentDescription = null,
                                tint = badgeFg,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Text(
                            text = step.status.labelAr,
                            style = MaterialTheme.typography.labelMedium,
                            color = badgeFg,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // شارة حالة التحقق الإجبارية من الشاشة
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = verBg,
                    border = BorderStroke(1.dp, verFg.copy(alpha = 0.65f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = null,
                            tint = verFg,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "التحقق: ${step.verificationStatus.labelAr}",
                            style = MaterialTheme.typography.labelMedium,
                            color = verFg,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (step.requiresApproval) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = StatusPendingContainer,
                        border = BorderStroke(1.dp, StatusPending.copy(alpha = 0.7f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = StatusPending,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "يتطلب موافقة",
                                style = MaterialTheme.typography.labelMedium,
                                color = StatusPending,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            if (step.descriptionAr.isNotBlank()) {
                Text(
                    text = step.descriptionAr,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextSecondary
                )
            }

            if (!step.verificationResultAr.isNullOrBlank()) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = CyberBgDeep,
                    border = BorderStroke(1.dp, CyberBorderGlow)
                ) {
                    Text(
                        text = step.verificationResultAr,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CyberTextPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                    )
                }
            }
        }
    }
}
