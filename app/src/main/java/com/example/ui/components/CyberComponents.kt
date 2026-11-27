package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CyberBgDeep
import com.example.ui.theme.CyberBorderGlow
import com.example.ui.theme.CyberElectricBlue
import com.example.ui.theme.CyberElectricCyan
import com.example.ui.theme.CyberOnCyan
import com.example.ui.theme.CyberSoftCyan
import com.example.ui.theme.CyberSurfaceElevated
import com.example.ui.theme.CyberSurfaceNavy
import com.example.ui.theme.CyberSurfaceVariant
import com.example.ui.theme.CyberTextPrimary
import com.example.ui.theme.CyberTextSecondary
import com.example.ui.theme.PhoneAgentTheme
import com.example.ui.theme.StatusCancelled
import com.example.ui.theme.StatusPending
import com.example.ui.theme.StatusPendingContainer
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusSuccessContainer

@Composable
fun CyberGeometricBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CyberBgDeep)
    ) {
        // توهّج أزرق سماوي هادئ للغاية بأسلوب iOS Light Mode
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        CyberElectricBlue.copy(alpha = 0.06f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.5f, 0f),
                    radius = size.width * 0.95f
                ),
                center = Offset(size.width * 0.5f, 0f),
                radius = size.width * 0.95f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        CyberElectricCyan.copy(alpha = 0.04f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.88f, size.height * 0.85f),
                    radius = size.width * 0.6f
                ),
                center = Offset(size.width * 0.88f, size.height * 0.85f),
                radius = size.width * 0.6f
            )
        }
        content()
    }
}

@Composable
fun CyberPanelCard(
    modifier: Modifier = Modifier,
    containerColor: Color = CyberSurfaceNavy,
    borderColor: Color = CyberBorderGlow,
    glowAccent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(
            width = if (glowAccent) 1.dp else 0.75.dp,
            color = if (glowAccent) CyberElectricCyan.copy(alpha = 0.55f) else borderColor.copy(alpha = 0.7f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content
    )
}

@Composable
fun AgentSafetyWarningBanner(
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .testTag("agent_safety_warning_banner"),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFFFF5CC),
        border = BorderStroke(0.85.dp, Color(0xFFE6C86E))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = "تحذير أمان",
                tint = Color(0xFF7A5200),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "تنبيه: الوكيل قد يخطئ أحياناً، راجع الأفعال الحساسة قبل الموافقة عليها",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF5C4300),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun PlayWithAgentPromoCard(
    onTakeOverPlayClick: () -> Unit,
    onExplainFeatureClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 0.85.dp,
                color = CyberBorderGlow.copy(alpha = 0.75f),
                shape = RoundedCornerShape(18.dp)
            ),
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFFFFFFF),
                            Color(0xFFF5F9FF),
                            Color(0xFFECF3FF)
                        )
                    )
                )
                .padding(if (compact) 14.dp else 16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)) {
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
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = CyberElectricCyan.copy(alpha = 0.18f),
                            border = BorderStroke(0.8.dp, CyberElectricCyan.copy(alpha = 0.45f))
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SportsEsports,
                                contentDescription = "العب مع الوكيل",
                                tint = CyberElectricCyan,
                                modifier = Modifier
                                    .padding(9.dp)
                                    .size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "العب مع الوكيل",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = CyberTextPrimary
                            )
                            Text(
                                text = "الحبّة العائمة وشريط الدردشة المصغّر",
                                style = MaterialTheme.typography.bodySmall,
                                color = CyberTextSecondary
                            )
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        color = StatusSuccessContainer,
                        border = BorderStroke(0.8.dp, StatusSuccess.copy(alpha = 0.65f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = StatusSuccess,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "مجانية",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = StatusSuccess
                            )
                        }
                    }
                }

                if (!compact) {
                    Text(
                        text = "يراقب الوكيل التطبيق الخارجي عبر إمكانية الوصول مع كبسولة تحكم عائمة أنيقة وشريط دردشة مصغّر لتوجيهه فوراً دون مغادرة التطبيق.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CyberTextSecondary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onTakeOverPlayClick,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("take_over_play_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyberElectricBlue,
                            contentColor = CyberOnCyan
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SportsEsports,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "تولّي اللعب",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = onExplainFeatureClick,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("explain_play_feature_button"),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, CyberBorderGlow)
                    ) {
                        Text(
                            text = "التفاصيل والمعاينة",
                            color = CyberElectricCyan
                        )
                    }
                }
            }
        }
    }
}

/**
 * معاينة مرئية مطابقة للتصميم الحقيقي للحبّة العائمة (Pill) بأسلوب iPhone.
 */
@Composable
fun FloatingPillShowcaseCard(
    statusText: String = "الوكيل جاهز",
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        color = Color(0xB8141418),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                modifier = Modifier.size(34.dp),
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.18f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.ChatBubbleOutline,
                        contentDescription = "فتح شريط الدردشة المصغّر",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // 3 أزرار دائرية 40dp: إيقاف مؤقت، إيقاف أحمر مصمت، يد/تسليم
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = Color(0xFF3A3A3C)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Pause,
                        contentDescription = "إيقاف مؤقت",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = Color(0xFFFF3B30)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Stop,
                        contentDescription = "إيقاف",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = Color(0xFF3A3A3C)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.PanTool,
                        contentDescription = "أكمل بنفسي",
                        tint = Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
    }
}

/**
 * معاينة مرئية مطابقة للتصميم الحقيقي لشريط الدردشة المصغّر (Mini Chat Bar) — يبقى داكناً شبه شفاف.
 */
@Composable
fun MiniChatBarShowcaseCard(
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xB8141418),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
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
                Text(
                    text = "وكيل الهاتف",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFAEAEB2),
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(modifier = Modifier.size(28.dp), shape = CircleShape, color = Color(0xFF3A3A3C)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Pause, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                    Surface(modifier = Modifier.size(28.dp), shape = CircleShape, color = Color(0xFFFF3B30)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                    Surface(modifier = Modifier.size(28.dp), shape = CircleShape, color = Color(0xFF3A3A3C)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PanTool, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }

            // الصف العلوي: رد الوكيل في فقاعة داكنة صغيرة محاذاة لليمين
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF2C2C2E)
            ) {
                Text(
                    text = "سأنفّذ خطوة «ابدأ الآن» ثم أواصل اللعب.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }

            // الصف السفلي باتجاه RTL: حقل نص مستدير داكن + ميكروفون دائري + إرسال أزرق #007AFF
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF1C1C1E),
                    border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.15f))
                ) {
                    Text(
                        text = "اكتب أمرك للوكيل…",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFAEAEB2),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }

                Surface(
                    modifier = Modifier.size(38.dp),
                    shape = CircleShape,
                    color = Color(0xFF3A3A3C)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Mic,
                            contentDescription = "تحدث بالصوت",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Surface(
                    modifier = Modifier.size(38.dp),
                    shape = CircleShape,
                    color = Color(0xFF007AFF)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "إرسال",
                            tint = Color.White,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlayFeatureInfoDialog(
    onDismiss: () -> Unit,
    onStartGameplay: () -> Unit,
    onOpenPermissions: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CyberSurfaceNavy,
        shape = RoundedCornerShape(24.dp),
        titleContentColor = CyberElectricCyan,
        textContentColor = CyberTextPrimary,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.SportsEsports,
                    contentDescription = null,
                    tint = CyberElectricCyan
                )
                Text(
                    text = "العب مع الوكيل — iOS v1.0.10",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "تظهر الحبّة العائمة فقط عند العمل فوق التطبيق الخارجي وتختفي داخل وكيل الهاتف. اضغط على جسم الحبّة لطيّها إلى فقاعة دائرية، أو افتح شريط الدردشة المصغّر لتوجيه الوكيل فوراً.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberTextPrimary
                )

                Text(
                    text = "معاينة الحبّة العائمة (Pill):",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = CyberSoftCyan
                )
                FloatingPillShowcaseCard()

                Text(
                    text = "معاينة شريط الدردشة المصغّر (Mini Chat Bar):",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = CyberSoftCyan
                )
                MiniChatBarShowcaseCard()

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = CyberBgDeep,
                border = BorderStroke(0.8.dp, CyberBorderGlow)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = "• الحبّة تحتوي 3 أزرار دائرية (إيقاف مؤقت، إيقاف أحمر، أكمل بنفسي) وتُطوى إلى فقاعة 56dp.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberTextSecondary
                    )
                    Text(
                        text = "• الضغط على «أكمل بنفسي» يسلّمك التحكم ويطويها إلى فقاعة مع زر «استئناف الوكيل» دون إنهاء المهمة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberTextSecondary
                    )
                }
            }
        }
        },
        confirmButton = {
            Button(
                onClick = onStartGameplay,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("start_gameplay_loop_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberElectricBlue,
                    contentColor = CyberOnCyan
                )
            ) {
                Text("تولّي اللعب الآن", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onOpenPermissions != null) {
                    OutlinedButton(
                        onClick = onOpenPermissions,
                        modifier = Modifier.height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, CyberBorderGlow)
                    ) {
                        Text("الأذونات", color = CyberElectricCyan)
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("إغلاق", color = CyberTextSecondary)
                }
            }
        }
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFF2F2F7, locale = "ar")
@Composable
private fun FloatingControlsShowcasePreview() {
    PhoneAgentTheme {
        Column(
            modifier = Modifier
                .background(CyberBgDeep)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            FloatingPillShowcaseCard()
            MiniChatBarShowcaseCard()
        }
    }
}
