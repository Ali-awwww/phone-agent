package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BookmarkRemove
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.HabitLogEntity
import com.example.data.local.UserMemoryEntity
import com.example.habits.HabitLearningEngine
import com.example.habits.PredictiveSuggestion
import com.example.ui.theme.CyberBgDeep
import com.example.ui.theme.CyberBorderGlow
import com.example.ui.theme.CyberElectricBlue
import com.example.ui.theme.CyberElectricCyan
import com.example.ui.theme.CyberOnCyan
import com.example.ui.theme.CyberSurfaceElevated
import com.example.ui.theme.CyberSurfaceNavy
import com.example.ui.theme.CyberTextPrimary
import com.example.ui.theme.CyberTextSecondary
import com.example.ui.theme.StatusCancelled
import com.example.ui.theme.StatusCancelledContainer
import com.example.ui.theme.StatusCyanInfoContainer
import com.example.ui.theme.StatusPending
import com.example.ui.theme.StatusPendingContainer
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusSuccessContainer

@Composable
fun PredictiveSuggestionCard(
    suggestion: PredictiveSuggestion,
    onPrepareCommand: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("predictive_suggestion_card"),
        shape = RoundedCornerShape(16.dp),
        color = StatusCyanInfoContainer,
        border = BorderStroke(1.dp, CyberElectricCyan.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lightbulb,
                        contentDescription = null,
                        tint = CyberElectricCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = suggestion.titleAr,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = CyberElectricCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (suggestion.isFromGemini) {
                        Surface(
                            shape = CircleShape,
                            color = CyberSurfaceNavy,
                            border = BorderStroke(0.75.dp, CyberElectricCyan.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = CyberElectricCyan,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "ملخص مجهول الهوية",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyberElectricCyan
                                )
                            }
                        }
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("dismiss_predictive_suggestion_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "تجاهل الاقتراح",
                        tint = CyberTextSecondary
                    )
                }
            }

            Text(
                text = suggestion.reasonAr,
                style = MaterialTheme.typography.bodySmall,
                color = CyberTextPrimary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPrepareCommand,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("apply_predictive_suggestion_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberElectricBlue,
                        contentColor = CyberOnCyan
                    )
                ) {
                    Text(
                        text = "تجهيز الأمر",
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("ignore_predictive_suggestion_button"),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, CyberBorderGlow)
                ) {
                    Text(
                        text = "تجاهل",
                        color = CyberTextSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun MorningRoutineApprovalDialog(
    onApprove: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("morning_routine_approval_dialog"),
        containerColor = CyberSurfaceNavy,
        shape = RoundedCornerShape(22.dp),
        titleContentColor = CyberElectricCyan,
        textContentColor = CyberTextPrimary,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.WbSunny,
                    contentDescription = null,
                    tint = CyberElectricCyan
                )
                Text(
                    text = "تفعيل اختصار «الروتين الصباحي»",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "تُطلب موافقتك هذه لمرة واحدة فقط لتشغيل «الروتين الصباحي» مستقبلاً بأمر واحد:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextPrimary
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CyberBgDeep,
                    border = BorderStroke(0.75.dp, CyberBorderGlow)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "• عرض مهامك ومنبهاتك الصباحية النشطة.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberTextSecondary
                        )
                        Text(
                            text = "• مزامنة عادات الاستخدام محلياً وفتح تطبيقك الصباحي المعتاد إن وُجد.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberTextSecondary
                        )
                        Text(
                            text = "• تبقى جميع خطوات الأمان والخصوصية مفعّلة بالكامل.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberTextSecondary
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onApprove,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("approve_morning_routine_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberElectricBlue,
                    contentColor = CyberOnCyan
                )
            ) {
                Text("موافق، اعتمد الروتين الصباحي", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("cancel_morning_routine_button"),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, CyberBorderGlow)
            ) {
                Text("إلغاء", color = CyberTextSecondary)
            }
        }
    )
}

@Composable
fun MemoryAndHabitsDialog(
    memories: List<UserMemoryEntity>,
    habits: List<HabitLogEntity>,
    isPrivacyModeEnabled: Boolean,
    predictiveLearningEnabled: Boolean,
    anonymizedHabitGeminiConsent: Boolean,
    morningRoutineApproved: Boolean,
    onTogglePredictiveLearning: (Boolean) -> Unit,
    onToggleAnonymizedGeminiConsent: (Boolean) -> Unit,
    onResetMorningRoutineApproval: () -> Unit,
    onForgetMemoryFact: (Long) -> Unit,
    onClearAllMemory: () -> Unit,
    onClearAllHabits: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("memory_and_habits_dialog"),
        containerColor = CyberSurfaceNavy,
        shape = RoundedCornerShape(22.dp),
        titleContentColor = CyberElectricCyan,
        textContentColor = CyberTextPrimary,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Psychology,
                    contentDescription = null,
                    tint = CyberElectricCyan
                )
                Text(
                    text = "الذاكرة الدائمة والعادات",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                MemoryAndHabitsManagementSection(
                    memories = memories,
                    habits = habits,
                    isPrivacyModeEnabled = isPrivacyModeEnabled,
                    predictiveLearningEnabled = predictiveLearningEnabled,
                    anonymizedHabitGeminiConsent = anonymizedHabitGeminiConsent,
                    morningRoutineApproved = morningRoutineApproved,
                    onTogglePredictiveLearning = onTogglePredictiveLearning,
                    onToggleAnonymizedGeminiConsent = onToggleAnonymizedGeminiConsent,
                    onResetMorningRoutineApproval = onResetMorningRoutineApproval,
                    onForgetMemoryFact = onForgetMemoryFact,
                    onClearAllMemory = onClearAllMemory,
                    onClearAllHabits = onClearAllHabits
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("close_memory_habits_dialog_button")
            ) {
                Text("إغلاق", color = CyberElectricCyan, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun MemoryAndHabitsManagementSection(
    memories: List<UserMemoryEntity>,
    habits: List<HabitLogEntity>,
    isPrivacyModeEnabled: Boolean,
    predictiveLearningEnabled: Boolean,
    anonymizedHabitGeminiConsent: Boolean,
    morningRoutineApproved: Boolean,
    onTogglePredictiveLearning: (Boolean) -> Unit,
    onToggleAnonymizedGeminiConsent: (Boolean) -> Unit,
    onResetMorningRoutineApproval: () -> Unit,
    onForgetMemoryFact: (Long) -> Unit,
    onClearAllMemory: () -> Unit,
    onClearAllHabits: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showClearMemoryConfirmDialog by remember { mutableStateOf(false) }

    CyberPanelCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("memory_habits_management_section"),
        containerColor = CyberSurfaceNavy,
        borderColor = CyberElectricCyan.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Psychology,
                    contentDescription = null,
                    tint = CyberElectricCyan,
                    modifier = Modifier.size(26.dp)
                )
                Column {
                    Text(
                        text = "الذاكرة الدائمة والتعلّم من العادات",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = CyberTextPrimary
                    )
                    Text(
                        text = "محفوظة محلياً على جهازك ومستثناة من النسخ الاحتياطي السحابي.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberTextSecondary
                    )
                }
            }

            if (isPrivacyModeEnabled) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = StatusPendingContainer,
                    border = BorderStroke(0.8.dp, StatusPending.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "وضع الخصوصية مفعّل حالياً: يتم إيقاف حفظ الذاكرة الجديدة ومزامنة العادات والتعلّم التنبؤي.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusPending,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            // 1) مفاتيح موافقة التعلّم التنبؤي العميق
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = CyberBgDeep,
                border = BorderStroke(0.75.dp, CyberBorderGlow)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "التعلّم التنبؤي العميق (على الجهاز)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = CyberTextPrimary
                            )
                            Text(
                                text = "يلاحظ الأنماط المتكررة ويقترح الخطوة التالية كرسالة قابلة للإغلاق فقط دون أي تنفيذ تلقائي.",
                                style = MaterialTheme.typography.bodySmall,
                                color = CyberTextSecondary
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = predictiveLearningEnabled,
                            enabled = !isPrivacyModeEnabled,
                            onCheckedChange = onTogglePredictiveLearning,
                            modifier = Modifier.testTag("predictive_learning_switch")
                        )
                    }

                    HorizontalDivider(color = CyberBorderGlow.copy(alpha = 0.6f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "مشاركة ملخص عادات مجهول الهوية مع Gemini",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = CyberTextPrimary
                            )
                            Text(
                                text = "تبقى بيانات العادات الخام على الجهاز دائماً؛ لا يُرسل إلى Gemini إلا ملخص مجهول الهوية بموافقتك المعلنة.",
                                style = MaterialTheme.typography.bodySmall,
                                color = CyberTextSecondary
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = anonymizedHabitGeminiConsent,
                            enabled = predictiveLearningEnabled && !isPrivacyModeEnabled,
                            onCheckedChange = onToggleAnonymizedGeminiConsent,
                            modifier = Modifier.testTag("anonymized_gemini_consent_switch")
                        )
                    }
                }
            }

            // 2) حالة الروتين الصباحي + ملخص العادات المحلية
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = CyberBgDeep,
                border = BorderStroke(0.75.dp, CyberBorderGlow)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.WbSunny,
                                contentDescription = null,
                                tint = if (morningRoutineApproved) StatusSuccess else CyberTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = if (morningRoutineApproved) {
                                    "الروتين الصباحي: معتمد بأمر واحد"
                                } else {
                                    "الروتين الصباحي: بانتظار موافقة أول مرة"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (morningRoutineApproved) StatusSuccess else CyberTextSecondary
                            )
                        }

                        if (morningRoutineApproved) {
                            TextButton(
                                onClick = onResetMorningRoutineApproval,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("reset_morning_routine_button")
                            ) {
                                Text("إلغاء الاعتماد", color = CyberElectricCyan)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Schedule,
                                contentDescription = null,
                                tint = CyberElectricCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "العادات المسجلة محلياً: ${habits.size}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = CyberTextPrimary
                            )
                        }

                        if (habits.isNotEmpty()) {
                            TextButton(
                                onClick = onClearAllHabits,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("clear_habits_button")
                            ) {
                                Text("مسح العادات", color = StatusCancelled)
                            }
                        }
                    }

                    if (habits.isNotEmpty()) {
                        habits.take(3).forEach { habit ->
                            Text(
                                text = "• ${habit.actionName} (${HabitLearningEngine.timeBucketArabic(habit.hourOfDay)} • ${habit.frequencyCount} مرات)",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberTextSecondary
                            )
                        }
                    }
                }
            }

            // 3) قائمة الحقائق المحفوظة في user_memory + زر «انسَ هذه الحقيقة»
            Text(
                text = "الحقائق والملخصات المحفوظة (${memories.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = CyberTextPrimary
            )

            if (memories.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CyberSurfaceElevated
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = CyberTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "لا توجد حقائق محفوظة في ذاكرة الوكيل حالياً.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberTextSecondary
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    memories.forEach { item ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("memory_fact_card_${item.id}"),
                            shape = RoundedCornerShape(12.dp),
                            color = CyberBgDeep,
                            border = BorderStroke(0.75.dp, CyberBorderGlow)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = item.fact,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CyberTextPrimary
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = translateMemorySource(item.source),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CyberTextSecondary
                                    )
                                    OutlinedButton(
                                        onClick = { onForgetMemoryFact(item.id) },
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("forget_fact_button_${item.id}"),
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(0.8.dp, StatusCancelled.copy(alpha = 0.65f)),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.BookmarkRemove,
                                            contentDescription = null,
                                            tint = StatusCancelled,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "انسَ هذه الحقيقة",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = StatusCancelled,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4) زر «امسح ذاكرتي» العام
            Button(
                onClick = { showClearMemoryConfirmDialog = true },
                enabled = memories.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("clear_all_memory_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = StatusCancelledContainer,
                    contentColor = StatusCancelled,
                    disabledContainerColor = CyberSurfaceElevated,
                    disabledContentColor = CyberTextSecondary
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteForever,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "امسح ذاكرتي",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    if (showClearMemoryConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearMemoryConfirmDialog = false },
            containerColor = CyberSurfaceNavy,
            titleContentColor = StatusCancelled,
            textContentColor = CyberTextPrimary,
            title = {
                Text(
                    text = "تأكيد مسح الذاكرة الدائمة",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "سيتم حذف جميع الحقائق والملخصات المحفوظة في ذاكرة الوكيل على جهازك نهائياً. هل تريد المتابعة؟",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearMemoryConfirmDialog = false
                        onClearAllMemory()
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("confirm_clear_memory_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StatusCancelled,
                        contentColor = Color.White
                    )
                ) {
                    Text("نعم، امسح ذاكرتي", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showClearMemoryConfirmDialog = false },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("cancel_clear_memory_button"),
                    border = BorderStroke(1.dp, CyberBorderGlow)
                ) {
                    Text("إلغاء", color = CyberTextPrimary)
                }
            }
        )
    }
}

private fun translateMemorySource(source: String): String = when (source.uppercase()) {
    "PERIODIC_LITE_SUMMARY" -> "ملخص دوري (Lite)"
    "LITE_ARCHIVE_SUMMARY" -> "أرشيف محادثة (Lite)"
    "PREFERENCE" -> "تفضيل محفوظ"
    "CONVERSATION" -> "من المحادثة"
    else -> source
}
