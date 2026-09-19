package com.officetracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Brand {
    val Primary = Color(0xFF0B6E99)
    val PrimaryDark = Color(0xFF7CC8EC)
    val Success = Color(0xFF1E8E5A)
    val Warning = Color(0xFFD98A00)
    val Danger = Color(0xFFC62828)
    val Muted = Color(0xFF7A8794)
    val Route = Color(0xFF1565C0)
}

private val Light = lightColorScheme(
    primary = Brand.Primary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3ECF8),
    onPrimaryContainer = Color(0xFF002A3D),
    secondary = Color(0xFF4F6070),
    secondaryContainer = Color(0xFFDDE6EE),
    onSecondaryContainer = Color(0xFF0C1D2A),
    tertiary = Brand.Success,
    tertiaryContainer = Color(0xFFD4F2E2),
    onTertiaryContainer = Color(0xFF00391F),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF151C22),
    surface = Color(0xFFF7F9FC),
    onSurface = Color(0xFF151C22),
    surfaceVariant = Color(0xFFE3E9EF),
    onSurfaceVariant = Color(0xFF444F59),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F5F9),
    surfaceContainer = Color(0xFFEBF0F5),
    surfaceContainerHigh = Color(0xFFE5EBF1),
    surfaceContainerHighest = Color(0xFFDFE6ED),
    outline = Color(0xFF75808B),
    outlineVariant = Color(0xFFC4CCD4),
    error = Brand.Danger,
    errorContainer = Color(0xFFFCDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val Dark = darkColorScheme(
    primary = Brand.PrimaryDark,
    onPrimary = Color(0xFF003549),
    primaryContainer = Color(0xFF004D69),
    onPrimaryContainer = Color(0xFFC4E7FF),
    secondary = Color(0xFFB6C9D8),
    secondaryContainer = Color(0xFF37495A),
    onSecondaryContainer = Color(0xFFD3E5F4),
    tertiary = Color(0xFF7FD9A8),
    tertiaryContainer = Color(0xFF005231),
    onTertiaryContainer = Color(0xFF9BF6C3),
    background = Color(0xFF0F1419),
    onBackground = Color(0xFFDEE3E9),
    surface = Color(0xFF0F1419),
    onSurface = Color(0xFFDEE3E9),
    surfaceVariant = Color(0xFF3F4850),
    onSurfaceVariant = Color(0xFFBFC8D1),
    surfaceContainerLowest = Color(0xFF0A0F13),
    surfaceContainerLow = Color(0xFF171C21),
    surfaceContainer = Color(0xFF1B2025),
    surfaceContainerHigh = Color(0xFF252B30),
    surfaceContainerHighest = Color(0xFF30363B),
    outline = Color(0xFF89929B),
    outlineVariant = Color(0xFF3F4850),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

val NumberStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)

@Composable
fun OfficeTrackerTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = AppTypography,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}
