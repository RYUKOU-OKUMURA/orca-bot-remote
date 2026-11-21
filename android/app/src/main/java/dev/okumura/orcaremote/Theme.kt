package dev.okumura.orcaremote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Orca 1.4.266 mobile web assets/48f50a11…js defines the palette, spacing, and radii.
private val DarkColors = darkColorScheme(
    background = Color(0xFF0A0A0A),
    surface = Color(0xFF171717),
    surfaceContainerLow = Color(0xFF1F1F1F),
    surfaceContainer = Color(0xFF262626),
    surfaceContainerHigh = Color(0xFF262626),
    secondaryContainer = Color(0xFF404040),
    onSecondaryContainer = Color(0xFFFAFAFA),
    onBackground = Color(0xFFFAFAFA),
    onSurface = Color(0xFFFAFAFA),
    onSurfaceVariant = Color(0xFFACACAC),
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0A0A0A),
    tertiary = Color(0xFFF97316),
    outline = Color(0xFF737373),
    outlineVariant = Color(0x12FFFFFF),
    error = Color(0xFFFF6568),
)

private val LightColors = lightColorScheme(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFAFAFA),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF5F5F5),
    surfaceContainerHigh = Color(0xFFF5F5F5),
    secondaryContainer = Color(0xFFE5E5E5),
    onSecondaryContainer = Color(0xFF0A0A0A),
    onBackground = Color(0xFF0A0A0A),
    onSurface = Color(0xFF0A0A0A),
    onSurfaceVariant = Color(0xFF696969),
    primary = Color(0xFF1D4ED8),
    onPrimary = Color(0xFFFFFFFF),
    tertiary = Color(0xFFC2410C),
    outline = Color(0xFF737373),
    outlineVariant = Color(0xFFE5E5E5),
    error = Color(0xFFE40014),
)

private val OrcaShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

internal val OrcaInset = 16.dp
internal val OrcaGap = 12.dp

private val OrcaType = Typography().run {
    copy(
        bodyLarge = bodyLarge.copy(fontSize = 18.sp, lineHeight = 28.sp, letterSpacing = 0.sp),
        bodyMedium = bodyMedium.copy(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
        bodySmall = bodySmall.copy(fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.sp),
        titleLarge = titleLarge.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
internal fun OrcaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = OrcaType,
        shapes = OrcaShapes,
        content = content,
    )
}

@Composable
internal fun OrcaOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
        border = BorderStroke(1.dp, if (enabled) colors.outline else colors.outlineVariant),
        content = content,
    )
}
