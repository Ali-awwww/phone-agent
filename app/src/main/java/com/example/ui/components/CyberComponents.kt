package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CyberBgDeep
import com.example.ui.theme.CyberBorderGlow
import com.example.ui.theme.CyberElectricBlue
import com.example.ui.theme.CyberElectricCyan
import com.example.ui.theme.CyberGridLine
import com.example.ui.theme.CyberSurfaceNavy
import com.example.ui.theme.StatusPending
import com.example.ui.theme.StatusPendingContainer

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
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.small,
        color = StatusPendingContainer,
        border = BorderStroke(1.dp, StatusPending.copy(alpha = 0.75f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = "تنبيه أمان",
                tint = StatusPending,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "تنبيه: الوكيل قد يخطئ أحياناً، راجع الأفعال الحساسة قبل الموافقة عليها",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = StatusPending
            )
        }
    }
}
