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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.SportsEsports
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CyberBgDeep
import com.example.ui.theme.CyberBorderGlow
import com.example.ui.theme.CyberElectricBlue
import com.example.ui.theme.CyberElectricCyan
import com.example.ui.theme.CyberGridLine
import com.example.ui.theme.CyberOnCyan
import com.example.ui.theme.CyberSoftCyan
import com.example.ui.theme.CyberSurfaceElevated
import com.example.ui.theme.CyberSurfaceNavy
import com.example.ui.theme.CyberTextPrimary
import com.example.ui.theme.CyberTextSecondary
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
            .background(
                Brush.verticalGradient(
                    colors = listOf(CyberBgDeep, Color(0xFF07122B), CyberBgDeep)
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val step = 36.dp.toPx()
            val gridAlpha = 0.065f

            var x = 0f
            while (x <= size.width) {
                drawLine(
                    color = CyberGridLine.copy(alpha = gridAlpha),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f
                )
                x += step
            }

            var y = 0f
            while (y <= size.height) {
                drawLine(
                    color = CyberGridLine.copy(alpha = gridAlpha),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f
                )
                y += step
            }

            // خطوط هندسية سايبرانية خفيفة في الزوايا
            val bracketLen = 44.dp.toPx()
            val margin = 14.dp.toPx()
            val accentStroke = Stroke(width = 1.5.dp.toPx())

            val topLeftPath = Path().apply {
                moveTo(margin, margin + bracketLen)
                lineTo(margin, margin)
                lineTo(margin + bracketLen, margin)
            }
            drawPath(
                path = topLeftPath,
                color = CyberElectricCyan.copy(alpha = 0.25f),
                style = accentStroke
            )

            val bottomRightPath = Path().apply {
                moveTo(size.width - margin, size.height - margin - bracketLen)
                lineTo(size.width - margin, size.height - margin)
                lineTo(size.width - margin - bracketLen, size.height - margin)
            }
            drawPath(
                path = bottomRightPath,
                color = CyberElectricBlue.copy(alpha = 0.35f),
                style = accentStroke
            )

            // شكل هندسي قطري خفيف في الخلفية
            drawLine(
                color = CyberElectricCyan.copy(alpha = 0.08f),
                start = Offset(size.width * 0.65f, 0f),
                end = Offset(size.width, size.height * 0.32f),
                strokeWidth = 1.2.dp.toPx()
            )
            drawLine(
                color = CyberElectricBlue.copy(alpha = 0.1f),
                start = Offset(0f, size.height * 0.72f),
                end = Offset(size.width * 0.35f, size.height),
                strokeWidth = 1.2.dp.toPx()
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
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(
            width = if (glowAccent) 1.5.dp else 1.dp,
            color = if (glowAccent) CyberElectricCyan else borderColor
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
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.small,
        color = StatusPendingContainer,
        border = BorderStroke(1.dp, StatusPending.copy(alpha = 0.75f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = "تنبيه أمان",
                tint = StatusPending,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "تنبيه: الوكيل قد يخطئ أحياناً، راجع الأفعال الحساسة قبل الموافقة عليها",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = StatusPending,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
                width = 1.5.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(CyberElectricCyan, CyberElectricBlue, CyberSoftCyan)
                ),
                shape = MaterialTheme.shapes.medium
            ),
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF0A244D),
                            Color(0xFF0E1B3C),
                            Color(0xFF082E52)
                        )
                    )
                )
                .padding(if (compact) 12.dp else 16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = CyberElectricCyan.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, CyberElectricCyan.copy(alpha = 0.65f))
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SportsEsports,
                                contentDescription = "العب مع الوكيل",
                                tint = CyberElectricCyan,
                                modifier = Modifier
                                    .padding(8.dp)
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
                                text = "ميزة جديدة • اللعب فوق التطبيق كالإنسان مجاناً",
                                style = MaterialTheme.typography.bodySmall,
                                color = CyberSoftCyan
                            )
                        }
                    }

                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = StatusSuccessContainer,
                        border = BorderStroke(1.dp, StatusSuccess)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = StatusSuccess,
                                modifier = Modifier.size(14.dp)
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
                        text = "دع الوكيل يراقب شاشتك عبر إمكانية الوصول وينقر ويسحب خطوة بخطوة كالإنسان، مع أزرار تحكم عائمة (إيقاف فوري، إيقاف مؤقت، أكمل بنفسي، وتصغير). يحتاج الوكيل بضع ثوانٍ للتفكير قبل كل حركة.",
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
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyberElectricCyan,
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
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, CyberElectricCyan.copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = "التفاصيل",
                            color = CyberElectricCyan
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
                    text = "العب مع الوكيل — ميزة جديدة",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "محرك لعب عام ومجاني بالكامل لا يرتبط بلعبة معينة: يقرأ الوكيل الشاشة الحقيقية عبر إمكانية الوصول، ويتفاعل معها كالإنسان عبر النقر والسحب والتمرير دون تخمين أو اختراع أزرار.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextPrimary
                )
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = CyberSurfaceElevated,
                    border = BorderStroke(1.dp, CyberBorderGlow)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "• جميع ميزات «وكيل الهاتف» بما فيها اللعب فوق التطبيقات مجانية 100% للجميع.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberSoftCyan
                        )
                        Text(
                            text = "• الوكيل ليس لحظياً: يحتاج بضع ثوانٍ للتفكير والتحقق من الشاشة في كل خطوة.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberTextSecondary
                        )
                        Text(
                            text = "• عند منح إذن الظهور فوق التطبيقات ستظهر لك 4 أزرار عائمة: إيقاف فوري، إيقاف مؤقت/استئناف، «أكمل بنفسي»، وتصغير.",
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
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberElectricCyan,
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


