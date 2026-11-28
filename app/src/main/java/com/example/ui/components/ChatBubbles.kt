package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.ScreenSearchDesktop
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.AgentTaskEntity
import com.example.data.local.AgentTaskType
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ChatMessageType
import com.example.data.local.ChatSender
import com.example.organizer.AppOrganizer
import com.example.organizer.InstalledAppInfo
import com.example.ui.theme.CyberBgDeep
import com.example.ui.theme.CyberBorderGlow
import com.example.ui.theme.CyberElectricBlue
import com.example.ui.theme.CyberElectricCyan
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
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatAppCardItem(
    val packageName: String,
    val label: String,
    val folderAr: String
)

object ChatTextTruncator {
    const val DEFAULT_MAX_CHARS = 380

    fun shouldTruncate(text: String, maxChars: Int = DEFAULT_MAX_CHARS): Boolean =
        text.length > maxChars

    fun truncate(text: String, maxChars: Int = DEFAULT_MAX_CHARS): String {
        if (text.length <= maxChars) return text
        val limit = maxChars.coerceAtLeast(100)
        val slice = text.take(limit)
        val lastBreak = slice.lastIndexOfAny(charArrayOf(' ', '\n', '،', '.'))
        val prefix = if (lastBreak >= (limit * 0.65).toInt()) {
            slice.substring(0, lastBreak).trimEnd()
        } else {
            slice.trimEnd()
        }
        return "$prefix…"
    }
}

data class ChatTaskCardItem(
    val id: Long,
    val title: String,
    val taskType: AgentTaskType,
    val dueAtMillis: Long?,
    val isCompleted: Boolean
)

/**
 * معالجات أزرار التحكم المدمجة داخل فقاعة التحضير/التنفيذ في المحادثة.
 */
data class ChatLiveExecutionActions(
    val onStop: () -> Unit,
    val onCancel: () -> Unit,
    val onTogglePauseResume: () -> Unit = {},
    val onTakeOver: () -> Unit = {},
    val onResumeAgent: () -> Unit = {}
)

/**
 * فقاعة حالة التحضير والتنفيذ المضغوطة داخل تدفق المحادثة نفسه.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatLiveExecutionBubble(
    statusText: String,
    isPaused: Boolean,
    canPauseOrTakeOver: Boolean,
    actions: ChatLiveExecutionActions,
    modifier: Modifier = Modifier,
    isHandedOver: Boolean = false,
    doingNowAr: String = "",
    nextStepAr: String = "",
    waitingForUserAr: String = "",
    elapsedSeconds: Int = 0,
    timeoutSeconds: Int = 65
) {
    val bubbleShape = RoundedCornerShape(
        topStart = 6.dp,
        topEnd = 16.dp,
        bottomStart = 16.dp,
        bottomEnd = 16.dp
    )
    val borderColor = when {
        isHandedOver -> CyberElectricCyan.copy(alpha = 0.65f)
        isPaused -> StatusPending.copy(alpha = 0.65f)
        else -> CyberBorderGlow
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .testTag("chat_live_execution_bubble"),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(bubbleShape),
            shape = bubbleShape,
            color = CyberSurfaceNavy,
            border = BorderStroke(0.85.dp, borderColor)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
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
                            imageVector = Icons.Rounded.SmartToy,
                            contentDescription = null,
                            tint = if (isPaused) StatusPending else CyberElectricCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = when {
                                isHandedOver -> "سلّمتك التحكم"
                                isPaused -> "متوقف مؤقتاً"
                                else -> "الحالة الحية للوكيل"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isPaused) StatusPending else CyberElectricCyan
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (elapsedSeconds > 0 && !isPaused && !isHandedOver) {
                            Text(
                                text = "${elapsedSeconds}ث / ${timeoutSeconds}ث",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberSoftCyan,
                                modifier = Modifier.testTag("live_elapsed_timer")
                            )
                        }
                        if (!isHandedOver && !isPaused) {
                            AnimatedTypingDots()
                        }
                    }
                }

                val resolvedDoingNow = doingNowAr.ifBlank { statusText }
                val resolvedNext = nextStepAr.ifBlank {
                    if (isHandedOver) "استئناف الوكيل عند ضغطك على «استئناف»"
                    else if (isPaused) "استئناف التنفيذ عند ضغطك على «استئناف»"
                    else "التحقق من النتيجة وتحديثك بالتقرير النهائي"
                }
                val resolvedWaiting = waitingForUserAr.ifBlank {
                    when {
                        isHandedOver -> "إكمالك للخطوة ثم ضغط «استئناف»"
                        isPaused -> "قرارك: «استئناف» أو «إلغاء»"
                        else -> "لا شيء حالياً — يمكنك الإيقاف أو الإلغاء في أي لحظة"
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = CyberSurfaceElevated.copy(alpha = 0.85f),
                    border = BorderStroke(0.7.dp, CyberBorderGlow.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        LiveStatusRow(label = "ماذا أفعل الآن:", value = resolvedDoingNow, accent = CyberElectricCyan)
                        LiveStatusRow(label = "الخطوة التالية:", value = resolvedNext, accent = CyberSoftCyan)
                        LiveStatusRow(label = "ماذا أنتظر منك:", value = resolvedWaiting, accent = if (isPaused) StatusPending else CyberTextSecondary)
                    }
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isHandedOver) {
                        FilledTonalButton(
                            onClick = actions.onResumeAgent,
                            modifier = Modifier
                                .defaultMinSize(minWidth = 1.dp, minHeight = 32.dp)
                                .height(32.dp)
                                .testTag("resume_agent_button"),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = CyberElectricBlue,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "استئناف الوكيل",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "استئناف",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = actions.onStop,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 1.dp, minHeight = 32.dp)
                            .height(32.dp)
                            .testTag("stop_active_task_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = StatusCancelledContainer,
                            contentColor = StatusCancelled
                        ),
                        border = BorderStroke(0.75.dp, StatusCancelled.copy(alpha = 0.45f)),
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = "إيقاف المهمة فوراً",
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = "إيقاف",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    if (canPauseOrTakeOver && !isHandedOver) {
                        OutlinedButton(
                            onClick = actions.onTogglePauseResume,
                            modifier = Modifier
                                .defaultMinSize(minWidth = 1.dp, minHeight = 32.dp)
                                .height(32.dp)
                                .testTag("pause_resume_task_button"),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                0.8.dp,
                                if (isPaused) StatusSuccess.copy(alpha = 0.7f) else CyberBorderGlow
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                contentDescription = if (isPaused) "استئناف" else "إيقاف مؤقت",
                                tint = if (isPaused) StatusSuccess else CyberElectricCyan,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = if (isPaused) "استئناف" else "مؤقت",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isPaused) StatusSuccess else CyberElectricCyan,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        OutlinedButton(
                            onClick = actions.onTakeOver,
                            modifier = Modifier
                                .defaultMinSize(minWidth = 1.dp, minHeight = 32.dp)
                                .height(32.dp)
                                .testTag("user_takeover_button"),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(0.8.dp, CyberElectricCyan.copy(alpha = 0.55f)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PanTool,
                                contentDescription = "أكمل بنفسي",
                                tint = CyberElectricCyan,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "أكمل بنفسي",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberElectricCyan,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = actions.onCancel,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 1.dp, minHeight = 32.dp)
                            .height(32.dp)
                            .testTag("cancel_current_task_button"),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(0.8.dp, StatusCancelled.copy(alpha = 0.45f)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Cancel,
                            contentDescription = "إلغاء المهمة الحالية",
                            tint = StatusCancelled,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = "إلغاء",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusCancelled,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatedTypingDots() {
    val transition = rememberInfiniteTransition(label = "agent_typing_dots")
    val dot1 by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520, delayMillis = 0),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )
    val dot2 by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520, delayMillis = 160),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )
    val dot3 by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520, delayMillis = 320),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "جارٍ الكتابة",
            style = MaterialTheme.typography.labelSmall,
            color = CyberSoftCyan
        )
        listOf(dot1, dot2, dot3).forEach { alphaVal ->
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(
                        color = CyberElectricCyan.copy(alpha = alphaVal),
                        shape = CircleShape
                    )
            )
        }
    }
}

/**
 * فقاعة محادثة بتصميم RTL:
 * - في اتجاه RTL، Arrangement.Start يضع رسالة المستخدم يميناً،
 *   و Arrangement.End يضع رد الوكيل يساراً.
 */
@Composable
fun ChatMessageBubble(
    message: ChatMessageEntity,
    installedAppsByPackage: Map<String, InstalledAppInfo>,
    liveActiveTasks: List<AgentTaskEntity>,
    onLaunchApp: (packageName: String, label: String) -> Unit,
    onToggleTaskCompleted: (taskId: Long, completed: Boolean) -> Unit,
    onCancelTask: (taskId: Long) -> Unit,
    onTakeOverPlayClick: () -> Unit,
    onExplainFeatureClick: () -> Unit
) {
    val isUser = message.sender == ChatSender.USER.name
    val msgType = remember(message.messageType) {
        ChatMessageType.entries.firstOrNull { it.name == message.messageType } ?: ChatMessageType.TEXT
    }

    val bubbleShape = if (isUser) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
    } else {
        RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
    }

    val containerColor = when {
        isUser -> CyberElectricBlue
        msgType == ChatMessageType.ERROR -> StatusCancelledContainer
        msgType == ChatMessageType.STOPPED -> StatusPendingContainer
        else -> CyberSurfaceNavy
    }

    val borderColor = when {
        isUser -> Color.Transparent
        msgType == ChatMessageType.ERROR -> StatusCancelled.copy(alpha = 0.6f)
        msgType == ChatMessageType.STOPPED -> StatusPending.copy(alpha = 0.65f)
        msgType == ChatMessageType.SCREEN_READ || msgType == ChatMessageType.PLAN -> CyberElectricCyan.copy(alpha = 0.45f)
        else -> CyberBorderGlow.copy(alpha = 0.7f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .testTag(if (isUser) "chat_bubble_user_${message.id}" else "chat_bubble_agent_${message.id}"),
        horizontalArrangement = if (isUser) Arrangement.Start else Arrangement.End
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(bubbleShape),
            shape = bubbleShape,
            color = containerColor,
            border = BorderStroke(1.dp, borderColor)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isUser) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = when (msgType) {
                                ChatMessageType.ERROR -> Icons.Rounded.ErrorOutline
                                ChatMessageType.STOPPED -> Icons.Rounded.StopCircle
                                ChatMessageType.SCREEN_READ -> Icons.Rounded.ScreenSearchDesktop
                                ChatMessageType.GAME_STATUS -> Icons.Rounded.SportsEsports
                                else -> Icons.Rounded.SmartToy
                            },
                            contentDescription = null,
                            tint = when (msgType) {
                                ChatMessageType.ERROR -> StatusCancelled
                                ChatMessageType.STOPPED -> StatusPending
                                else -> CyberElectricCyan
                            },
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = when (msgType) {
                                ChatMessageType.STOPPED -> "إيقاف المهمة"
                                ChatMessageType.SCREEN_READ -> "قراءة الشاشة الفعلية"
                                ChatMessageType.GAME_STATUS -> "محرك اللعب العام"
                                else -> "وكيل الهاتف"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = when (msgType) {
                                ChatMessageType.ERROR -> StatusCancelled
                                ChatMessageType.STOPPED -> StatusPending
                                else -> CyberElectricCyan
                            }
                        )
                    }
                }

                val shouldTruncate = remember(message.id, message.text, isUser) {
                    !isUser && ChatTextTruncator.shouldTruncate(message.text)
                }
                var isExpanded by remember(message.id) { mutableStateOf(false) }
                val displayedText = remember(message.text, shouldTruncate, isExpanded) {
                    if (shouldTruncate && !isExpanded) {
                        ChatTextTruncator.truncate(message.text)
                    } else {
                        message.text
                    }
                }

                Text(
                    text = displayedText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser) Color.White else CyberTextPrimary,
                    softWrap = true
                )

                if (shouldTruncate) {
                    TextButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("read_more_button_${message.id}"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isExpanded) "عرض أقل" else "اقرأ المزيد",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = CyberElectricCyan
                        )
                    }
                }

                if (!isUser && msgType == ChatMessageType.APPS_LIST && !message.payloadJson.isNullOrBlank()) {
                    val appCards = remember(message.payloadJson) {
                        parseAppCards(message.payloadJson)
                    }
                    if (appCards.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            appCards.forEach { item ->
                                val liveIcon = installedAppsByPackage[item.packageName]?.icon
                                ChatAppLauncherCard(
                                    item = item,
                                    icon = liveIcon,
                                    onClick = { onLaunchApp(item.packageName, item.label) }
                                )
                            }
                        }
                    }
                }

                if (!isUser && msgType == ChatMessageType.TASKS_LIST && !message.payloadJson.isNullOrBlank()) {
                    val snapshotTasks = remember(message.payloadJson) {
                        parseTaskCards(message.payloadJson)
                    }
                    val liveById = remember(liveActiveTasks) {
                        liveActiveTasks.associateBy { it.id }
                    }
                    val mergedTasks = snapshotTasks.mapNotNull { item ->
                        val live = liveById[item.id]
                        if (live != null) {
                            ChatTaskCardItem(
                                id = live.id,
                                title = live.title,
                                taskType = AgentTaskType.entries.firstOrNull { it.name == live.taskType }
                                    ?: item.taskType,
                                dueAtMillis = live.dueAtMillis,
                                isCompleted = live.isCompleted
                            )
                        } else {
                            // إذا أُلغي من القائمة الحية نُبقيه غير معروض أو نعرضه فقط إذا لا توجد مهام حية مختلفة
                            null
                        }
                    }
                    if (mergedTasks.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            mergedTasks.forEach { task ->
                                ChatTaskCardRow(
                                    task = task,
                                    onToggleComplete = { onToggleTaskCompleted(task.id, !task.isCompleted) },
                                    onCancel = { onCancelTask(task.id) }
                                )
                            }
                        }
                    }
                }

                if (!isUser && msgType == ChatMessageType.FEATURE_HIGHLIGHT) {
                    PlayWithAgentPromoCard(
                        onTakeOverPlayClick = onTakeOverPlayClick,
                        onExplainFeatureClick = onExplainFeatureClick,
                        compact = true
                    )
                }

                val timeStr = remember(message.createdAt) {
                    SimpleDateFormat("hh:mm a", Locale("ar")).format(Date(message.createdAt))
                }
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isUser) Color.White.copy(alpha = 0.78f) else CyberTextSecondary
                )
            }
        }
    }
}

@Composable
private fun ChatAppLauncherCard(
    item: ChatAppCardItem,
    icon: ImageBitmap?,
    onClick: () -> Unit
) {
    val bitmap = icon ?: AppOrganizer.getCachedIcon(item.packageName)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .testTag("chat_app_card_${item.packageName}"),
        shape = MaterialTheme.shapes.small,
        color = CyberSurfaceElevated,
        border = BorderStroke(1.dp, CyberElectricCyan.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "أيقونة ${item.label}",
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CyberBgDeep)
                        .border(1.dp, CyberBorderGlow, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Apps,
                        contentDescription = "أيقونة ${item.label}",
                        tint = CyberElectricCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = CyberTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.folderAr,
                    style = MaterialTheme.typography.labelSmall,
                    color = CyberSoftCyan
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = "تشغيل ${item.label}",
                tint = CyberElectricCyan,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ChatTaskCardRow(
    task: ChatTaskCardItem,
    onToggleComplete: () -> Unit,
    onCancel: () -> Unit
) {
    val dueText = remember(task.dueAtMillis) {
        task.dueAtMillis?.let {
            SimpleDateFormat("hh:mm a", Locale("ar")).format(Date(it))
        }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("chat_task_card_${task.id}"),
        shape = MaterialTheme.shapes.small,
        color = CyberSurfaceElevated,
        border = BorderStroke(
            1.dp,
            if (task.isCompleted) StatusSuccess.copy(alpha = 0.5f) else CyberBorderGlow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IconButton(
                onClick = onToggleComplete,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (task.isCompleted) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = if (task.isCompleted) "مكتمل" else "تحديد كمكتمل",
                    tint = if (task.isCompleted) StatusSuccess else CyberElectricCyan
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (task.isCompleted) CyberTextSecondary else CyberTextPrimary,
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = when (task.taskType) {
                            AgentTaskType.ALARM -> Icons.Rounded.Alarm
                            AgentTaskType.REMINDER -> Icons.Rounded.NotificationsActive
                            AgentTaskType.TASK -> Icons.Rounded.TaskAlt
                        },
                        contentDescription = null,
                        tint = CyberSoftCyan,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = buildString {
                            append(task.taskType.titleAr)
                            if (!dueText.isNullOrBlank()) {
                                append(" • $dueText")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberSoftCyan
                    )
                }
            }

            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.height(48.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
                border = BorderStroke(1.dp, StatusCancelled.copy(alpha = 0.55f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusCancelled)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "إلغاء ${task.title}",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(2.dp))
                Text("إلغاء", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun parseAppCards(payloadJson: String): List<ChatAppCardItem> {
    return runCatching {
        val arr = JSONArray(payloadJson)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val pkg = obj.optString("packageName")
                val label = obj.optString("label")
                val folderAr = obj.optString("folderAr", "تطبيق")
                if (pkg.isNotBlank() && label.isNotBlank()) {
                    add(ChatAppCardItem(pkg, label, folderAr))
                }
            }
        }
    }.getOrDefault(emptyList())
}

private fun parseTaskCards(payloadJson: String): List<ChatTaskCardItem> {
    return runCatching {
        val arr = JSONArray(payloadJson)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optLong("id", -1L)
                val title = obj.optString("title")
                val typeStr = obj.optString("taskType", AgentTaskType.REMINDER.name)
                val due = obj.optLong("dueAtMillis", -1L).takeIf { it > 0L }
                val completed = obj.optBoolean("isCompleted", false)
                if (id > 0L && title.isNotBlank()) {
                    add(
                        ChatTaskCardItem(
                            id = id,
                            title = title,
                            taskType = AgentTaskType.entries.firstOrNull { it.name == typeStr }
                                ?: AgentTaskType.REMINDER,
                            dueAtMillis = due,
                            isCompleted = completed
                        )
                    )
                }
            }
        }
    }.getOrDefault(emptyList())
}

@Composable
private fun LiveStatusRow(
    label: String,
    value: String,
    accent: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = accent
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = CyberTextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            softWrap = true
        )
    }
}
