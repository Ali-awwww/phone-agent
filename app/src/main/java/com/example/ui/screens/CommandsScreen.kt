package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import android.widget.Toast
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.example.BuildConfig
import com.example.ui.viewmodel.ModelConnectionStatus
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.policy.AgentPromptRules
import com.example.ui.components.ChatMessageBubble
import com.example.ui.components.CyberPanelCard
import com.example.ui.components.CyberPlanCard
import com.example.ui.components.PlayFeatureInfoDialog
import com.example.ui.components.PlayWithAgentPromoCard
import com.example.ui.components.SensitiveStepApprovalDialog
import com.example.ui.theme.CyberBgDeep
import com.example.ui.theme.CyberBorderGlow
import com.example.ui.theme.CyberElectricBlue
import com.example.ui.theme.CyberElectricCyan
import com.example.ui.theme.CyberOnCyan
import com.example.ui.theme.CyberSoftCyan
import com.example.ui.theme.CyberSurfaceElevated
import com.example.ui.theme.CyberSurfaceNavy
import com.example.ui.theme.CyberTextPrimary
import com.example.ui.theme.CyberTextSecondary
import com.example.ui.theme.StatusCancelled
import com.example.ui.theme.StatusCancelledContainer
import com.example.ui.theme.StatusPending
import com.example.ui.theme.StatusPendingContainer
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusSuccessContainer
import com.example.ui.viewmodel.CommandsUiState
import com.example.ui.viewmodel.CommandsViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommandsScreen(
    viewModel: CommandsViewModel,
    onOpenPermissionsTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val activeTasks by viewModel.activeTasks.collectAsStateWithLifecycle()
    val installedAppsByPackage by viewModel.installedAppsByPackage.collectAsStateWithLifecycle()
    val isAccessibilityConnected by viewModel.isAccessibilityConnected.collectAsStateWithLifecycle()

    val plan = (state.uiState as? CommandsUiState.PlanActive)?.plan
    val isRecording = state.isListening
    val isAnyTaskRunning = state.isAnyTaskRunning
    val canPauseOrTakeOver = state.isExecuting || state.isGameplayRunning
    val showTaskControlStrip = isAnyTaskRunning || (plan != null && !plan.isComplete && !plan.isCancelled)
    val listState = rememberLazyListState()

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

    LaunchedEffect(messages.size, plan != null) {
        val totalItems = messages.size + (if (plan != null) 2 else 1)
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    val quickSuggestions = listOf(
        "تولّي اللعب",
        "اعرض ألعابي",
        "ماذا في شاشتي؟",
        "ذكّرني بعد 10 دقائق بشرب الماء",
        "اعرض مهامي",
        "اضبط منبهاً للساعة 7:30 صباحاً"
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .padding(horizontal = 12.dp)
        ) {
            // شريط علوي للدردشة مع حالة إمكانية الوصول والصوت وزر «تولّي اللعب»
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                shape = MaterialTheme.shapes.small,
                color = CyberSurfaceNavy,
                border = BorderStroke(1.dp, CyberBorderGlow)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "دردشة الوكيل",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = CyberElectricCyan
                        )
                        Surface(
                            modifier = Modifier.clickable(onClick = onOpenPermissionsTab),
                            shape = MaterialTheme.shapes.small,
                            color = if (isAccessibilityConnected) StatusSuccessContainer else StatusPendingContainer,
                            border = BorderStroke(
                                1.dp,
                                if (isAccessibilityConnected) StatusSuccess else StatusPending
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AccessibilityNew,
                                    contentDescription = "حالة إمكانية الوصول",
                                    tint = if (isAccessibilityConnected) StatusSuccess else StatusPending,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (isAccessibilityConnected) "متصل" else "تفعيل الأذونات",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isAccessibilityConnected) StatusSuccess else StatusPending
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { viewModel.startGameplayGoal() },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("header_take_over_play_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SportsEsports,
                                contentDescription = "تولّي اللعب",
                                tint = CyberElectricCyan
                            )
                        }
                        IconButton(
                            onClick = { viewModel.toggleVoiceConversationMode() },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("toggle_tts_button")
                        ) {
                            Icon(
                                imageVector = if (state.voiceConversationMode) {
                                    Icons.AutoMirrored.Rounded.VolumeUp
                                } else {
                                    Icons.AutoMirrored.Rounded.VolumeOff
                                },
                                contentDescription = if (state.voiceConversationMode) {
                                    "إيقاف النطق التلقائي"
                                } else {
                                    "تفعيل النطق التلقائي"
                                },
                                tint = if (state.voiceConversationMode) CyberElectricCyan else CyberTextSecondary
                            )
                        }
                        if (messages.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel.clearChatHistory() },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("clear_chat_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteSweep,
                                    contentDescription = "مسح سجل الدردشة",
                                    tint = CyberTextSecondary
                                )
                            }
                        }
                    }
                }
            }

            // بطاقة حالة اتصال النموذج بـ Gemini مع فحص الاتصال وعرض رمز التصحيح
            ModelConnectionCard(
                status = state.modelConnectionStatus,
                appCheckDebugToken = state.appCheckDebugToken,
                activeProjectId = state.activeProjectId,
                isExpanded = state.isModelStatusExpanded,
                onToggleExpand = { viewModel.toggleModelStatusExpanded() },
                onCheckConnection = { viewModel.checkModelConnection() },
                modifier = Modifier.padding(top = 6.dp)
            )

            // شريط التحكم الفوري بالوكيل داخل شاشة الدردشة (إيقاف فوري / إيقاف مؤقت-استئناف / إلغاء المهمة / أكمل بنفسي)
            AnimatedVisibility(visible = showTaskControlStrip) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    shape = MaterialTheme.shapes.small,
                    color = if (state.isPaused) StatusPendingContainer else StatusCancelledContainer,
                    border = BorderStroke(
                        1.5.dp,
                        if (state.isPaused) StatusPending else StatusCancelled
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isAnyTaskRunning && !state.isPaused) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = CyberElectricCyan
                                )
                            }
                            Text(
                                text = state.statusNoticeAr.ifBlank {
                                    if (state.isPaused) "المهمة متوقفة مؤقتاً — بانتظارك"
                                    else AgentPromptRules.WAIT_THINKING_NOTICE_AR
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = CyberTextPrimary
                            )
                        }

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Button(
                                onClick = { viewModel.stopCurrentTask() },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("stop_active_task_button"),
                                shape = MaterialTheme.shapes.small,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = StatusCancelled,
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Stop,
                                    contentDescription = "إيقاف المهمة فوراً",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(text = "إيقاف", fontWeight = FontWeight.Bold)
                            }

                            if (canPauseOrTakeOver) {
                                Button(
                                    onClick = { viewModel.togglePauseResume() },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("pause_resume_task_button"),
                                    shape = MaterialTheme.shapes.small,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (state.isPaused) StatusSuccess else CyberElectricBlue,
                                        contentColor = Color.White
                                    ),
                                    contentPadding = PaddingValues(horizontal = 14.dp)
                                ) {
                                    Icon(
                                        imageVector = if (state.isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                        contentDescription = if (state.isPaused) "استئناف" else "إيقاف مؤقت",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = if (state.isPaused) "استئناف" else "إيقاف مؤقت",
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                OutlinedButton(
                                    onClick = { viewModel.handOverControlToUser() },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("user_takeover_button"),
                                    shape = MaterialTheme.shapes.small,
                                    border = BorderStroke(1.dp, CyberElectricCyan),
                                    contentPadding = PaddingValues(horizontal = 12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PanTool,
                                        contentDescription = "أكمل بنفسي",
                                        tint = CyberElectricCyan,
                                        modifier = Modifier.size(17.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(text = "أكمل بنفسي", color = CyberElectricCyan)
                                }
                            }

                            OutlinedButton(
                                onClick = { viewModel.cancelCurrentTask() },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("cancel_current_task_button"),
                                shape = MaterialTheme.shapes.small,
                                border = BorderStroke(1.dp, StatusCancelled),
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Cancel,
                                    contentDescription = "إلغاء المهمة الحالية",
                                    tint = StatusCancelled,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(text = "إلغاء المهمة", color = StatusCancelled)
                            }
                        }
                    }
                }
            }

            // منطقة فقاعات الدردشة + الخطة النشطة
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "feature_spotlight_header") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (messages.isEmpty()) {
                            CyberPanelCard(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "أهلاً بك في دردشة وكيل الهاتف",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = CyberElectricCyan
                                    )
                                    Text(
                                        text = "جميع الميزات مجانية 100%: الدردشة النصية والصوتية، عرض ألعابك وتطبيقاتك وتشغيلها، قراءة شاشتك الحقيقية، إدارة المهام والتذكيرات والمنبهات، وتنفيذ الأوامر خطوة بخطوة. تذكّر أن الوكيل ليس لحظياً ويحتاج بضع ثوانٍ للتفكير في كل خطوة.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = CyberTextSecondary
                                    )
                                }
                            }
                        }
                        PlayWithAgentPromoCard(
                            onTakeOverPlayClick = { viewModel.startGameplayGoal() },
                            onExplainFeatureClick = { viewModel.openPlayFeatureDialog() },
                            compact = messages.isNotEmpty()
                        )
                    }
                }

                items(messages, key = { it.id }) { msg ->
                    ChatMessageBubble(
                        message = msg,
                        installedAppsByPackage = installedAppsByPackage,
                        liveActiveTasks = activeTasks,
                        onLaunchApp = { pkg, label -> viewModel.launchAppFromChat(pkg, label) },
                        onToggleTaskCompleted = { id, done -> viewModel.toggleTaskCompleted(id, done) },
                        onCancelTask = { id -> viewModel.cancelTaskById(id) },
                        onTakeOverPlayClick = { viewModel.startGameplayGoal() },
                        onExplainFeatureClick = { viewModel.openPlayFeatureDialog() }
                    )
                }

                if (plan != null) {
                    item(key = "active_plan_section") {
                        CyberPlanCard(
                            plan = plan,
                            isExecuting = state.isExecuting,
                            isPlanComplete = plan.isComplete,
                            executionMessage = state.executionTruthReportAr.orEmpty(),
                            onNewPlan = { viewModel.resetToIdle() },
                            onExecutePlan = { viewModel.executePlanStepByStep() },
                            onCancelExecution = { viewModel.cancelCurrentTask() }
                        )
                    }
                }
            }

            // اقتراحات سريعة قابلة للضغط الفوري
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(quickSuggestions) { suggestion ->
                    FilterChip(
                        selected = false,
                        onClick = { viewModel.analyzeAndPlanCommand(suggestion) },
                        shape = MaterialTheme.shapes.small,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = CyberSurfaceNavy,
                            labelColor = CyberSoftCyan
                        ),
                        border = BorderStroke(1.dp, CyberBorderGlow),
                        label = {
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        }
                    )
                }
            }

            // شريط إدخال الصوت والكتابة السفلي
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = CyberSurfaceNavy,
                border = BorderStroke(
                    1.dp,
                    if (isRecording) StatusCancelled else CyberBorderGlow
                )
            ) {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    if (isRecording) {
                        Text(
                            text = if (state.partialTranscript.isNotBlank()) {
                                "أسمعك: ${state.partialTranscript}"
                            } else {
                                "جارٍ الاستماع... اضغط زر الإيقاف لإنهاء التسجيل"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = StatusCancelled,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = {
                                if (isRecording) {
                                    viewModel.stopVoiceCapture()
                                } else if (recordPermissionGranted) {
                                    viewModel.startVoiceCapture()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            enabled = !isAnyTaskRunning || isRecording,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("voice_capture_button"),
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (isRecording) StatusCancelledContainer else CyberSurfaceElevated,
                                contentColor = if (isRecording) StatusCancelled else CyberElectricCyan
                            )
                        ) {
                            Icon(
                                imageVector = if (isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                                contentDescription = if (isRecording) "إيقاف التسجيل" else "التحدث بالصوت"
                            )
                        }

                        OutlinedTextField(
                            value = state.inputText,
                            onValueChange = { viewModel.updateInputText(it) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat_text_input"),
                            placeholder = {
                                Text(
                                    text = "اكتب رسالتك أو أمرك...",
                                    color = CyberTextSecondary,
                                    maxLines = 1
                                )
                            },
                            singleLine = false,
                            maxLines = 3,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (state.inputText.isNotBlank() && !isAnyTaskRunning) {
                                        viewModel.analyzeAndPlanCommand()
                                    }
                                }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyberElectricCyan,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = CyberBgDeep,
                                unfocusedContainerColor = CyberBgDeep,
                                focusedTextColor = CyberTextPrimary,
                                unfocusedTextColor = CyberTextPrimary
                            ),
                            shape = MaterialTheme.shapes.small
                        )

                        Surface(
                            shape = CircleShape,
                            color = if (state.inputText.isNotBlank() && !isAnyTaskRunning) {
                                CyberElectricCyan
                            } else {
                                CyberSurfaceElevated
                            }
                        ) {
                            IconButton(
                                onClick = { viewModel.analyzeAndPlanCommand() },
                                enabled = state.inputText.isNotBlank() && !isAnyTaskRunning,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("chat_send_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.Send,
                                    contentDescription = "إرسال",
                                    tint = if (state.inputText.isNotBlank() && !isAnyTaskRunning) {
                                        CyberOnCyan
                                    } else {
                                        CyberTextSecondary
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    state.pendingApprovalStep?.let { step ->
        SensitiveStepApprovalDialog(
            step = step,
            fullPlan = state.pendingApprovalPlan,
            onApprove = { viewModel.resolveApproval(true) },
            onReject = { viewModel.resolveApproval(false) }
        )
    }

    if (state.showPlayFeatureDialog) {
        PlayFeatureInfoDialog(
            onDismiss = { viewModel.dismissPlayFeatureDialog() },
            onStartGameplay = {
                viewModel.startGameplayGoal()
            },
            onOpenPermissions = {
                viewModel.dismissPlayFeatureDialog()
                onOpenPermissionsTab()
            }
        )
    }
}

@Composable
fun ModelConnectionCard(
    status: ModelConnectionStatus,
    appCheckDebugToken: String,
    activeProjectId: String,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onCheckConnection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val borderColor = when (status) {
        is ModelConnectionStatus.Connected -> StatusSuccess
        is ModelConnectionStatus.Checking -> CyberElectricCyan
        is ModelConnectionStatus.Disconnected -> StatusCancelled
        ModelConnectionStatus.NotChecked -> CyberBorderGlow
    }

    val containerColor = when (status) {
        is ModelConnectionStatus.Connected -> StatusSuccessContainer
        is ModelConnectionStatus.Checking -> CyberSurfaceNavy
        is ModelConnectionStatus.Disconnected -> StatusCancelledContainer
        ModelConnectionStatus.NotChecked -> CyberSurfaceNavy
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("model_connection_card"),
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (status) {
                        is ModelConnectionStatus.Connected -> {
                            Icon(
                                imageVector = Icons.Rounded.CloudDone,
                                contentDescription = "متصل",
                                tint = StatusSuccess,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = "النموذج متصل (Gemini 3.8 Flash)",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = StatusSuccess
                                )
                                Text(
                                    text = "زمن الاستجابة: ${status.latencyMs}ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyberTextSecondary
                                )
                            }
                        }
                        is ModelConnectionStatus.Checking -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = CyberElectricCyan
                            )
                            Text(
                                text = "جارٍ فحص اتصال النموذج بـ Gemini...",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = CyberElectricCyan
                            )
                        }
                        is ModelConnectionStatus.Disconnected -> {
                            Icon(
                                imageVector = Icons.Rounded.CloudOff,
                                contentDescription = "غير متصل",
                                tint = StatusCancelled,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = "النموذج غير متصل [${status.category.titleAr}]",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = StatusCancelled
                                )
                                Text(
                                    text = status.exactError.take(45),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyberTextSecondary,
                                    maxLines = 1
                                )
                            }
                        }
                        ModelConnectionStatus.NotChecked -> {
                            Icon(
                                imageVector = Icons.Rounded.CloudQueue,
                                contentDescription = "لم يفحص",
                                tint = CyberTextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "اتصال النموذج لم يُفحص بعد",
                                style = MaterialTheme.typography.labelMedium,
                                color = CyberTextSecondary
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onCheckConnection,
                        enabled = status !is ModelConnectionStatus.Checking,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("check_model_connection_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "فحص اتصال النموذج",
                            tint = CyberElectricCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = onToggleExpand,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("toggle_model_connection_details_button")
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = if (isExpanded) "إخفاء التفاصيل" else "عرض التفاصيل",
                            tint = CyberTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(visible = isExpanded || status is ModelConnectionStatus.Disconnected) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (status is ModelConnectionStatus.Disconnected) {
                        Text(
                            text = status.explanationAr,
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberTextPrimary
                        )
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = CyberBgDeep,
                            border = BorderStroke(1.dp, CyberBorderGlow)
                        ) {
                            Text(
                                text = status.exactError,
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberSoftCyan,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    if (BuildConfig.DEBUG && appCheckDebugToken.isNotBlank()) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = CyberBgDeep,
                            border = BorderStroke(1.dp, CyberBorderGlow)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Key,
                                            contentDescription = null,
                                            tint = CyberElectricCyan,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "رمز تصحيح App Check (المعاينة):",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = CyberElectricCyan
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(appCheckDebugToken))
                                            Toast.makeText(context, "تم نسخ رمز التصحيح إلى الحافظة", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier
                                            .height(32.dp)
                                            .testTag("copy_debug_token_button"),
                                        shape = MaterialTheme.shapes.small,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = CyberElectricCyan,
                                            contentColor = CyberOnCyan
                                        ),
                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.ContentCopy,
                                            contentDescription = "نسخ",
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "نسخ الرمز",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = CyberSurfaceNavy,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = appCheckDebugToken,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = CyberSoftCyan,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                    )
                                }

                                val displayProject = if (activeProjectId.isNotBlank() && activeProjectId != "gen-lang-client-0140553990") {
                                    "gen-lang-client-0140553990 (المشروع الحالي: $activeProjectId)"
                                } else {
                                    "gen-lang-client-0140553990"
                                }

                                Text(
                                    text = "📌 لتفعيل ردود Gemini في المعاينة:\n1. افتح Firebase Console للمشروع: $displayProject\n2. اذهب إلى App Check ⬅ Apps ⬅ com.aistudio.phoneagent.wakeel\n3. اضغط «Manage debug tokens» وألصق هذا الرمز ثم احفظ.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyberTextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
