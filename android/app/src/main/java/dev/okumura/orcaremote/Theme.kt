package dev.okumura.orcaremote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Orca 1.4.266, out/mobile-web/assets/48f50a112c3adef7131b9cb209e7d2ac6d44e71e16a16c832b65052a67ddfe0d.js
private val DarkColors = darkColorScheme(
    background = Color(0xFF0A0A0A), // bgBase
    surface = Color(0xFF171717), // bgPanel
    surfaceContainerLow = Color(0xFF1F1F1F), // composerSurface
    surfaceContainer = Color(0xFF262626), // bgRaised for the sidebar-style lists
    surfaceContainerHigh = Color(0xFF262626), // bgRaised
    secondaryContainer = Color(0xFF404040), // Lighten bgRaised to distinguish expanded rows.
    onSecondaryContainer = Color(0xFFFAFAFA), // textPrimary
    onBackground = Color(0xFFFAFAFA), // textPrimary
    onSurface = Color(0xFFFAFAFA), // textPrimary
    onSurfaceVariant = Color(0xFFACACAC), // textSecondary
    primary = Color(0xFF3276F2), // accentBlue
    onPrimary = Color(0xFF0A0A0A), // bgBase gives 4.72:1 on accentBlue (onAccent gives 4.19:1).
    tertiary = Color(0xFFFB923C), // Lighten agentQuestion to reach 4.5:1 on expanded rows.
    outline = Color(0xFF737373), // inputBorder
    outlineVariant = Color(0x12FFFFFF), // borderSubtle, 7% alpha rounded to a byte
    error = Color(0xFFFF6568), // statusRed
)

private val LightColors = lightColorScheme(
    background = Color(0xFFFFFFFF), // bgBase
    surface = Color(0xFFFAFAFA), // bgPanel
    surfaceContainerLow = Color(0xFFFFFFFF), // composerSurface
    surfaceContainer = Color(0xFFF5F5F5), // bgRaised for the sidebar-style lists
    surfaceContainerHigh = Color(0xFFF5F5F5), // bgRaised
    secondaryContainer = Color(0xFFE5E5E5), // borderSubtle as a fill to distinguish expanded rows
    onSecondaryContainer = Color(0xFF0A0A0A), // textPrimary
    onBackground = Color(0xFF0A0A0A), // textPrimary
    onSurface = Color(0xFF0A0A0A), // textPrimary
    onSurfaceVariant = Color(0xFF636363), // Darken textSecondary to reach 4.5:1 on expanded rows.
    primary = Color(0xFF225BE3), // accentBlue
    onPrimary = Color(0xFFFFFFFF), // onAccent
    tertiary = Color(0xFFB9380A), // Darken agentQuestion to reach 4.5:1 on expanded rows.
    outline = Color(0xFF737373), // inputBorder
    outlineVariant = Color(0xFFE5E5E5), // borderSubtle
    error = Color(0xFFD90014), // Darken statusRed to reach 4.5:1 on list surfaces.
)

internal val orcaAccentText: Color
    @Composable get() = if (isSystemInDarkTheme()) {
        Color(0xFF8AB4F8) // skillToken for text since accentBlue falls below 4.5:1 on bgPanel and bgRaised.
    } else {
        MaterialTheme.colorScheme.primary // accentBlue
    }

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

@Composable
internal fun OrcaTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = orcaAccentText),
        content = content,
    )
}
