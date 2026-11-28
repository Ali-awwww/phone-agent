package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ChatMessageType
import com.example.permissions.PermissionHelper
import com.example.policy.AgentPromptRules
import com.example.ui.components.AppPickerForGameplayDialog
import com.example.ui.components.ChatLiveExecutionActions
import com.example.ui.components.ChatLiveExecutionBubble
import com.example.ui.components.ChatMessageBubble
import com.example.ui.components.CyberPanelCard
import com.example.ui.components.CyberPlanCard
import com.example.ui.components.FileCleanupBatchApprovalDialog
import com.example.ui.components.FilePermissionExplanationDialog
import com.example.ui.components.FileTrashDialog
import com.example.ui.components.PlayFeatureInfoDialog
import com.example.ui.components.PlayWithAgentPromoCard
import com.example.ui.components.SensitiveStepApprovalDialog
import com.example.organizer.InstalledAppInfo
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
import com.example.ui.viewmodel.CompactErrorState
import com.example.ui.viewmodel.ModelConnectionStatus

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
    val hasOlderMessages by viewModel.hasOlderMessages.collectAsStateWithLifecycle()
    val activeTasks by viewModel.activeTasks.collectAsStateWithLifecycle()
    val installedAppsByPackage by viewModel.installedAppsByPackage.collectAsStateWithLifecycle()
    val isAccessibilityConnected by viewModel.isAccessibilityConnected.collectAsStateWithLifecycle()
    var showClearChatConfirmDialog by remember { mutableStateOf(false) }

    // منع تكدّس فقاعات الأخطاء المتكررة المتطابقة من السجلات القديمة
    val displayMessages = remember(messages) {
        buildList<ChatMessageEntity> {
            messages.forEach { msg ->
                val prev = lastOrNull()
                val isDuplicateError = prev != null &&
                    msg.messageType == ChatMessageType.ERROR.name &&
                    prev.messageType == ChatMessageType.ERROR.name &&
                    msg.text.trim() == prev.text.trim()
                if (!isDuplicateError) {
                    add(msg)
                }
            }
        }
    }

    val plan = (state.uiState as? CommandsUiState.PlanActive)?.plan
    val isRecording = state.isListening
    val isPaused = state.isPaused
    val isHandedOver = state.isHandedOver
    val isAnyTaskRunning = state.isAnyTaskRunning
    val canPauseOrTakeOver = state.isExecuting || state.isGameplayRunning || isHandedOver
    val showInChatExecutionBubble = isAnyTaskRunning ||
        isPaused ||
        isHandedOver ||
        (state.statusNoticeAr.isNotBlank() && state.modelConnectionStatus !is ModelConnectionStatus.Checking)
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

    val allFilesPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.onFilePermissionSettingsReturned()
    }

    val recordPermissionGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    LaunchedEffect(displayMessages.size, plan != null, showInChatExecutionBubble) {
        val layoutInfo = listState.layoutInfo
        val currentTotal = layoutInfo.totalItemsCount
        val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val isNearBottom = currentTotal == 0 || lastVisibleIndex >= currentTotal - 3

        val headerCount = if (displayMessages.isEmpty()) 2 else 1
        val totalItems = headerCount +
            displayMessages.size +
            (if (plan != null) 1 else 0) +
            (if (showInChatExecutionBubble) 1 else 0)
        if (totalItems > 0 && isNearBottom) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    val quickSuggestions = remember {
        listOf(
            "اعرض ألعابي",
            "تولّي اللعب",
            "ماذا فعلت؟",
            "ذكّرني",
            "اضبط منبهاً للساعة 7:30 صباحاً",
            "اعرض مهامي",
            "ماذا في شاشتي؟"
        )
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1) شريط علوي بأسلوب iPhone يجمع العنوان وحالة الأذونات وأزرار التحكم
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                shape = RoundedCornerShape(20.dp),
                color = CyberSurfaceNavy,
                border = BorderStroke(0.75.dp, CyberBorderGlow.copy(alpha = 0.7f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
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
                            color = CyberTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Surface(
                            modifier = Modifier
                                .heightIn(min = 34.dp)
                                .clickable(onClick = onOpenPermissionsTab),
                            shape = CircleShape,
                            color = if (isAccessibilityConnected) StatusSuccessContainer else StatusPendingContainer,
                            border = BorderStroke(
                                0.8.dp,
                                if (isAccessibilityConnected) StatusSuccess.copy(alpha = 0.75f) else StatusPending.copy(alpha = 0.75f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
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
                                    color = if (isAccessibilityConnected) StatusSuccess else StatusPending,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(6.dp))

                    Surface(
                        shape = CircleShape,
                        color = CyberSurfaceElevated,
                        border = BorderStroke(0.75.dp, CyberBorderGlow.copy(alpha = 0.6f))
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { viewModel.analyzeAndPlanCommand("العب لعبة") },
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
                                    onClick = { showClearChatConfirmDialog = true },
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
            }

            // 2) بطاقة الخطأ المدمجة (مرة واحدة فقط + زر إعادة المحاولة + التفاصيل مطوية افتراضياً) أو بطاقة اتصال النموذج
            val activeError = state.activeError
            if (activeError != null) {
                CompactErrorCard(
                    error = activeError,
                    isDetailsExpanded = state.isErrorDetailsExpanded,
                    appCheckDebugToken = state.appCheckDebugToken,
                    activeProjectId = state.activeProjectId,
                    isRetrying = state.modelConnectionStatus is ModelConnectionStatus.Checking || state.isBusyWorking,
                    onRetry = { viewModel.retryActiveError() },
                    onToggleDetails = { viewModel.toggleErrorDetailsExpanded() },
                    onDismiss = { viewModel.dismissActiveError() }
                )
            } else {
                ModelConnectionCard(
                    status = state.modelConnectionStatus,
                    statusNoticeAr = state.statusNoticeAr,
                    lastModelTraceAr = state.lastModelTraceAr,
                    appCheckDebugToken = state.appCheckDebugToken,
                    activeProjectId = state.activeProjectId,
                    isExpanded = state.isModelStatusExpanded,
                    onToggleExpand = { viewModel.toggleModelStatusExpanded() },
                    onCheckConnection = { viewModel.checkModelConnection() }
                )
            }

            // 3) منطقة فقاعات الدردشة + الخطة النشطة + فقاعة التنفيذ الحي ضمن التدفق الطبيعي
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (displayMessages.isEmpty()) {
                    item(key = "welcome_card") {
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
                                    color = CyberTextSecondary,
                                    softWrap = true
                                )
                            }
                        }
                    }
                }

                item(key = "play_promo_card") {
                    PlayWithAgentPromoCard(
                        onTakeOverPlayClick = { viewModel.analyzeAndPlanCommand("العب لعبة") },
                        onExplainFeatureClick = { viewModel.openPlayFeatureDialog() },
                        compact = displayMessages.isNotEmpty()
                    )
                }

                if (hasOlderMessages) {
                    item(key = "load_older_messages_button") {
                        OutlinedButton(
                            onClick = { viewModel.loadOlderMessages() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("load_older_messages_button"),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, CyberBorderGlow)
                        ) {
                            Text(
                                text = "تحميل رسائل أقدم",
                                color = CyberElectricCyan,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                items(displayMessages, key = { it.id }) { msg ->
                    ChatMessageBubble(
                        message = msg,
                        installedAppsByPackage = installedAppsByPackage,
                        liveActiveTasks = activeTasks,
                        onLaunchApp = { pkg, label -> viewModel.launchAppFromChat(pkg, label) },
                        onToggleTaskCompleted = { id, done -> viewModel.toggleTaskCompleted(id, done) },
                        onCancelTask = { id -> viewModel.cancelTaskById(id) },
                        onTakeOverPlayClick = { viewModel.analyzeAndPlanCommand("العب لعبة") },
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
                            onExecutePlan = { viewModel.executePlanStepByStep(singleStepOnly = false) },
                            onExecuteNextStep = { viewModel.executePlanStepByStep(singleStepOnly = true) },
                            onCancelExecution = { viewModel.cancelCurrentTask() }
                        )
                    }
                }

                if (showInChatExecutionBubble) {
                    item(key = "live_execution_bubble") {
                        val liveActions = remember(viewModel) {
                            ChatLiveExecutionActions(
                                onStop = { viewModel.stopCurrentTask() },
                                onCancel = { viewModel.cancelCurrentTask() },
                                onTogglePauseResume = { viewModel.togglePauseResume() },
                                onTakeOver = { viewModel.handOverControlToUser() },
                                onResumeAgent = { viewModel.resumeFromHandOver() }
                            )
                        }
                        ChatLiveExecutionBubble(
                            statusText = state.statusNoticeAr.ifBlank {
                                if (isHandedOver) "سلّمتك التحكم — اضغط «استئناف الوكيل» متى أردت المتابعة."
                                else if (isPaused) "المهمة متوقفة مؤقتاً — بانتظارك"
                                else AgentPromptRules.WAIT_THINKING_NOTICE_AR
                            },
                            isPaused = isPaused,
                            isHandedOver = isHandedOver,
                            canPauseOrTakeOver = canPauseOrTakeOver,
                            doingNowAr = state.liveDoingNowAr.ifBlank { state.statusNoticeAr },
                            nextStepAr = state.liveNextStepAr,
                            waitingForUserAr = state.liveWaitingForUserAr,
                            elapsedSeconds = state.geminiElapsedSeconds,
                            timeoutSeconds = state.geminiTimeoutSeconds,
                            actions = liveActions
                        )
                    }
                }
            }

            // 5) حاوية كبسولات الأوامر الجاهزة بأسلوب iPhone
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("quick_presets_container"),
                shape = RoundedCornerShape(18.dp),
                color = CyberSurfaceNavy,
                border = BorderStroke(0.75.dp, CyberBorderGlow.copy(alpha = 0.6f))
            ) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(quickSuggestions, key = { it }) { suggestion ->
                        FilterChip(
                            selected = false,
                            enabled = !isAnyTaskRunning,
                            onClick = { viewModel.analyzeAndPlanCommand(suggestion) },
                            modifier = Modifier.heightIn(min = 38.dp),
                            shape = CircleShape,
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = CyberSurfaceElevated,
                                labelColor = CyberTextPrimary,
                                disabledContainerColor = CyberSurfaceElevated.copy(alpha = 0.5f),
                                disabledLabelColor = CyberTextSecondary
                            ),
                            border = BorderStroke(0.75.dp, CyberBorderGlow.copy(alpha = 0.65f)),
                            label = {
                                Text(
                                    text = suggestion,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        )
                    }
                }
            }

            // 6) شريط إدخال الصوت والكتابة السفلي بأسلوب iMessage
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                shape = RoundedCornerShape(24.dp),
                color = CyberSurfaceNavy,
                border = BorderStroke(
                    0.9.dp,
                    if (isRecording) StatusCancelled else CyberBorderGlow.copy(alpha = 0.75f)
                )
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
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
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                contentColor = if (isRecording) StatusCancelled else CyberTextPrimary
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
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
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
                                unfocusedBorderColor = CyberBorderGlow.copy(alpha = 0.5f),
                                focusedContainerColor = CyberSurfaceElevated,
                                unfocusedContainerColor = CyberSurfaceElevated,
                                focusedTextColor = CyberTextPrimary,
                                unfocusedTextColor = CyberTextPrimary
                            ),
                            shape = RoundedCornerShape(20.dp)
                        )

                        Surface(
                            shape = CircleShape,
                            color = if (state.inputText.isNotBlank() && !isAnyTaskRunning) {
                                CyberElectricBlue
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
                                        Color.White
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
            nextStep = state.pendingApprovalNextStep,
            onApprove = { viewModel.resolveApproval(true) },
            onReject = { viewModel.resolveApproval(false) }
        )
    }

    if (showClearChatConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearChatConfirmDialog = false },
            containerColor = CyberSurfaceNavy,
            titleContentColor = StatusCancelled,
            textContentColor = CyberTextPrimary,
            title = {
                Text(
                    text = "تأكيد مسح سجل الدردشة",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "سيتم حذف جميع رسائل المحادثة الحالية نهائياً ولا يمكن استعادتها. هل تريد المتابعة؟",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearChatConfirmDialog = false
                        viewModel.clearChatHistory()
                    },
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("confirm_clear_chat_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StatusCancelled,
                        contentColor = Color.White
                    )
                ) {
                    Text("نعم، امسح الدردشة", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showClearChatConfirmDialog = false },
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("cancel_clear_chat_button"),
                    border = BorderStroke(1.dp, CyberBorderGlow)
                ) {
                    Text("إلغاء", color = CyberTextPrimary)
                }
            }
        )
    }

    state.pendingTaskCancelConfirmation?.let { taskToCancel ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissTaskCancellationConfirmation() },
            containerColor = CyberSurfaceNavy,
            titleContentColor = StatusPending,
            textContentColor = CyberTextPrimary,
            title = {
                Text(
                    text = "تأكيد إلغاء التذكير أو المهمة",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "هل تريد بالتأكيد إلغاء «${taskToCancel.title}»؟",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmTaskCancellation() },
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("confirm_cancel_task_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StatusCancelled,
                        contentColor = Color.White
                    )
                ) {
                    Text("تأكيد الإلغاء", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { viewModel.dismissTaskCancellationConfirmation() },
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("dismiss_cancel_task_button"),
                    border = BorderStroke(1.dp, CyberBorderGlow)
                ) {
                    Text("إبقاء العنصر", color = CyberTextPrimary)
                }
            }
        )
    }

    if (state.showPlayFeatureDialog) {
        PlayFeatureInfoDialog(
            onDismiss = { viewModel.dismissPlayFeatureDialog() },
            onStartGameplay = {
                viewModel.dismissPlayFeatureDialog()
                viewModel.analyzeAndPlanCommand("العب لعبة")
            },
            onOpenPermissions = {
                viewModel.dismissPlayFeatureDialog()
                onOpenPermissionsTab()
            }
        )
    }

    if (state.showAppPickerForGameplay) {
        val installedAppsList = remember(installedAppsByPackage) {
            installedAppsByPackage.values.toList().sortedBy { it.label }
        }
        AppPickerForGameplayDialog(
            installedApps = installedAppsList,
            onDismiss = { viewModel.dismissAppPickerForGameplay() },
            onAppSelected = { app ->
                viewModel.selectAppForGameplay(app)
            }
        )
    }

    if (state.showFilePermissionDialog) {
        FilePermissionExplanationDialog(
            onRequestAllFilesPermission = {
                allFilesPermissionLauncher.launch(PermissionHelper.buildAllFilesAccessIntent(context))
            },
            onContinueScopedStorage = {
                viewModel.continueFileScanWithScopedFallback()
            },
            onDismiss = {
                viewModel.dismissFilePermissionDialog()
            }
        )
    }

    state.currentPendingFileBatch?.let { batch ->
        FileCleanupBatchApprovalDialog(
            batch = batch,
            onConfirmBatch = { viewModel.approveCurrentFileBatch() },
            onCancelBatch = { viewModel.skipOrCancelCurrentFileBatch() }
        )
    }

    if (state.showTrashDialog) {
        FileTrashDialog(
            items = state.trashItems,
            onRestoreItem = { id -> viewModel.restoreTrashItem(id) },
            onPermanentDeleteItem = { id -> viewModel.permanentlyDeleteTrashItem(id) },
            onDismiss = { viewModel.dismissTrashDialog() }
        )
    }
}

@Composable
private fun CompactErrorCard(
    error: CompactErrorState,
    isDetailsExpanded: Boolean,
    appCheckDebugToken: String,
    activeProjectId: String,
    isRetrying: Boolean,
    onRetry: () -> Unit,
    onToggleDetails: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("compact_error_card"),
        shape = RoundedCornerShape(14.dp),
        color = StatusCancelledContainer,
        border = BorderStroke(1.5.dp, StatusCancelled)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = StatusCancelled,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = error.titleAr,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = StatusCancelled,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("error_dismiss_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "إغلاق التنبيه",
                        tint = CyberTextSecondary
                    )
                }
            }

            Text(
                text = error.summaryAr,
                style = MaterialTheme.typography.bodySmall,
                color = CyberTextPrimary,
                softWrap = true
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = CyberBgDeep.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, CyberBorderGlow.copy(alpha = 0.55f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onRetry,
                        enabled = !isRetrying,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("error_retry_button"),
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyberElectricCyan,
                            contentColor = CyberOnCyan
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "إعادة المحاولة",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    if (error.technicalDetails.isNotBlank() || (BuildConfig.DEBUG && error.isAppCheckDebugError)) {
                        OutlinedButton(
                            onClick = onToggleDetails,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("error_details_toggle_button"),
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, CyberBorderGlow),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Icon(
                                imageVector = if (isDetailsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                tint = CyberSoftCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (isDetailsExpanded) "إخفاء" else "التفاصيل",
                                color = CyberSoftCyan,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = isDetailsExpanded) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (error.technicalDetails.isNotBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            color = CyberBgDeep,
                            border = BorderStroke(1.dp, CyberBorderGlow)
                        ) {
                            Text(
                                text = error.technicalDetails,
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberSoftCyan,
                                softWrap = true,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    if (BuildConfig.DEBUG && error.isAppCheckDebugError && appCheckDebugToken.isNotBlank()) {
                        DebugAppCheckTokenBox(
                            appCheckDebugToken = appCheckDebugToken,
                            activeProjectId = activeProjectId
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ModelConnectionCard(
    status: ModelConnectionStatus,
    statusNoticeAr: String,
    lastModelTraceAr: String,
    appCheckDebugToken: String,
    activeProjectId: String,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onCheckConnection: () -> Unit,
    modifier: Modifier = Modifier
) {
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
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                    color = StatusSuccess,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (lastModelTraceAr.isNotBlank()) {
                                        lastModelTraceAr
                                    } else {
                                        "زمن الاستجابة: ${status.latencyMs}ms"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyberTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
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
                                text = statusNoticeAr.ifBlank { "جارٍ فحص اتصال النموذج بـ Gemini..." },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = CyberElectricCyan,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
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
                                    text = status.category.titleAr,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = StatusCancelled,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "اضغط «التفاصيل» أو أعد الفحص",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyberTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
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
                                color = CyberTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(Modifier.width(6.dp))

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = CyberBgDeep.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, CyberBorderGlow.copy(alpha = 0.6f))
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onCheckConnection,
                            enabled = status !is ModelConnectionStatus.Checking,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("check_model_connection_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "فحص اتصال النموذج",
                                tint = CyberElectricCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = onToggleExpand,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("toggle_model_connection_details_button")
                        ) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = if (isExpanded) "إخفاء التفاصيل" else "عرض التفاصيل",
                                tint = CyberTextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = isExpanded) {
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
                            color = CyberTextPrimary,
                            softWrap = true
                        )
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            color = CyberBgDeep,
                            border = BorderStroke(1.dp, CyberBorderGlow)
                        ) {
                            Text(
                                text = status.exactError,
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberSoftCyan,
                                softWrap = true,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    } else if (status is ModelConnectionStatus.Connected) {
                        Text(
                            text = status.detailsAr,
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberTextPrimary,
                            softWrap = true
                        )
                    }

                    if (lastModelTraceAr.isNotBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            color = CyberBgDeep,
                            border = BorderStroke(1.dp, CyberBorderGlow)
                        ) {
                            Text(
                                text = lastModelTraceAr,
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberSoftCyan,
                                softWrap = true,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    if (BuildConfig.DEBUG && appCheckDebugToken.isNotBlank()) {
                        DebugAppCheckTokenBox(
                            appCheckDebugToken = appCheckDebugToken,
                            activeProjectId = activeProjectId
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugAppCheckTokenBox(
    appCheckDebugToken: String,
    activeProjectId: String
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Surface(
        shape = MaterialTheme.shapes.small,
        color = CyberBgDeep,
        border = BorderStroke(1.dp, CyberBorderGlow)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                        color = CyberElectricCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(appCheckDebugToken))
                        Toast.makeText(context, "تم نسخ رمز التصحيح إلى الحافظة", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("copy_debug_token_button"),
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberElectricCyan,
                        contentColor = CyberOnCyan
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = "نسخ",
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "نسخ الرمز",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
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
                text = "لتفعيل ردود Gemini في المعاينة:\n1. افتح Firebase Console للمشروع: $displayProject\n2. اذهب إلى App Check ثم Apps ثم com.aistudio.phoneagent.wakeel\n3. اضغط «Manage debug tokens» وألصق هذا الرمز ثم احفظ.",
                style = MaterialTheme.typography.labelSmall,
                color = CyberTextSecondary,
                softWrap = true
            )
        }
    }
}
