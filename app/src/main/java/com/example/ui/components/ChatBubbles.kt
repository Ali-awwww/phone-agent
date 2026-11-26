package com.example.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.ScreenSearchDesktop
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.example.data.local.AgentTaskEntity
import com.example.data.local.AgentTaskType
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ChatMessageType
import com.example.data.local.ChatSender
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

data class ChatTaskCardItem(
    val id: Long,
    val title: String,
    val taskType: AgentTaskType,
    val dueAtMillis: Long?,
    val isCompleted: Boolean
)

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
        RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
    }

    val containerColor = when {
        isUser -> CyberElectricBlue.copy(alpha = 0.28f)
        msgType == ChatMessageType.ERROR -> StatusCancelledContainer
        msgType == ChatMessageType.STOPPED -> StatusPendingContainer
        else -> CyberSurfaceNavy
    }

    val borderColor = when {
        isUser -> CyberElectricCyan.copy(alpha = 0.7f)
        msgType == ChatMessageType.ERROR -> StatusCancelled.copy(alpha = 0.7f)
        msgType == ChatMessageType.STOPPED -> StatusPending.copy(alpha = 0.8f)
        msgType == ChatMessageType.SCREEN_READ || msgType == ChatMessageType.PLAN -> CyberElectricCyan.copy(alpha = 0.55f)
        else -> CyberBorderGlow
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

                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextPrimary,
                    softWrap = true
                )

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
                    color = CyberTextSecondary
                )
            }
        }
    }
}

@Composable
private fun ChatAppLauncherCard(
    item: ChatAppCardItem,
    icon: Drawable?,
    onClick: () -> Unit
) {
    val bitmap = remember(icon) {
        runCatching { icon?.toBitmap(72, 72)?.asImageBitmap() }.getOrNull()
    }
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
