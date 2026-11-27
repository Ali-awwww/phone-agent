package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val IosLightColorScheme = lightColorScheme(
    primary = CyberElectricCyan,
    onPrimary = CyberOnCyan,
    primaryContainer = ColorTokens.BlueContainer,
    onPrimaryContainer = CyberSoftCyan,
    secondary = CyberElectricBlue,
    onSecondary = CyberOnBlue,
    secondaryContainer = ColorTokens.BlueContainer,
    onSecondaryContainer = CyberTextPrimary,
    tertiary = CyberSoftCyan,
    onTertiary = CyberOnCyan,
    tertiaryContainer = CyberSurfaceElevated,
    onTertiaryContainer = CyberTextPrimary,
    background = CyberBgDeep,
    onBackground = CyberTextPrimary,
    surface = CyberSurfaceNavy,
    onSurface = CyberTextPrimary,
    surfaceVariant = CyberSurfaceElevated,
    onSurfaceVariant = CyberTextSecondary,
    outline = CyberBorderGlow,
    outlineVariant = CyberSurfaceVariant,
    error = StatusCancelled,
    onError = Color.White,
    errorContainer = StatusCancelledContainer,
    onErrorContainer = StatusCancelled
)

private object ColorTokens {
    val BlueContainer = Color(0xFFE5F0FF)
}

// زوايا iPhone الدائرية الناعمة والمتناسقة (16–20dp للبطاقات)
val PhoneAgentShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun PhoneAgentTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = IosLightColorScheme,
        typography = AppTypography,
        shapes = PhoneAgentShapes,
        content = content
    )
}
