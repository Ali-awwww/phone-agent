package com.example.ui.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val CyberDarkColorScheme = darkColorScheme(
    primary = CyberElectricCyan,
    onPrimary = CyberOnCyan,
    primaryContainer = CyberSurfaceElevated,
    onPrimaryContainer = CyberSoftCyan,
    secondary = CyberElectricBlue,
    onSecondary = CyberOnBlue,
    secondaryContainer = ColorTokens.BlueContainer,
    onSecondaryContainer = CyberTextPrimary,
    tertiary = CyberSoftCyan,
    onTertiary = CyberOnCyan,
    tertiaryContainer = CyberSurfaceVariant,
    onTertiaryContainer = CyberTextPrimary,
    background = CyberBgDeep,
    onBackground = CyberTextPrimary,
    surface = CyberSurfaceNavy,
    onSurface = CyberTextPrimary,
    surfaceVariant = CyberSurfaceVariant,
    onSurfaceVariant = CyberTextSecondary,
    outline = CyberBorderGlow,
    outlineVariant = CyberSurfaceElevated,
    error = StatusCancelled,
    onError = CyberBgDeep,
    errorContainer = StatusCancelledContainer,
    onErrorContainer = StatusCancelled
)

private object ColorTokens {
    val BlueContainer = androidx.compose.ui.graphics.Color(0xFF102A5C)
}

// زوايا هندسية حادة واضحة الهوية السايبرانية
val PhoneAgentShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp, topEnd = 2.dp, bottomStart = 2.dp),
    large = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp, topEnd = 3.dp, bottomStart = 3.dp),
    extraLarge = CutCornerShape(topStart = 18.dp, bottomEnd = 18.dp, topEnd = 4.dp, bottomStart = 4.dp)
)

@Composable
fun PhoneAgentTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CyberDarkColorScheme,
        typography = AppTypography,
        shapes = PhoneAgentShapes,
        content = content
    )
}
