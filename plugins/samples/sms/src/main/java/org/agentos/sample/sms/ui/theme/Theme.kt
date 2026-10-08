package org.agentos.sample.sms.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 视觉风格：白天是“薄荷”——薄荷白底、墨绿字、深青强调；夜里是“松针”——近黑的深绿底、亮薄荷强调。
 * 两套都不用系统动态取色，保证截图里的配色是设计过的。
 */

private val MintColors = lightColorScheme(
    primary = Color(0xFF0E7C66),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDF2E5),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4A5FA8),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDE3FF),
    onSecondaryContainer = Color(0xFF0B1B55),
    tertiary = Color(0xFFB4502C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCCF),
    onTertiaryContainer = Color(0xFF3A0E00),
    background = Color(0xFFF2F9F5),
    onBackground = Color(0xFF14201B),
    surface = Color(0xFFF2F9F5),
    onSurface = Color(0xFF14201B),
    surfaceVariant = Color(0xFFDBE8E1),
    onSurfaceVariant = Color(0xFF4A5A53),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FCFA),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE9F3EE),
    surfaceContainerHighest = Color(0xFFDDEAE3),
    outline = Color(0xFF77877F),
    outlineVariant = Color(0xFFD0E0D8),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val PineColors = darkColorScheme(
    primary = Color(0xFF63D9B8),
    onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF0B5444),
    onPrimaryContainer = Color(0xFFB9F4E0),
    secondary = Color(0xFFB4C3FF),
    onSecondary = Color(0xFF14267A),
    secondaryContainer = Color(0xFF2E4189),
    onSecondaryContainer = Color(0xFFDDE3FF),
    tertiary = Color(0xFFFFB59B),
    onTertiary = Color(0xFF5A1D05),
    tertiaryContainer = Color(0xFF7A3115),
    onTertiaryContainer = Color(0xFFFFDCCF),
    background = Color(0xFF09130F),
    onBackground = Color(0xFFDCE8E2),
    surface = Color(0xFF09130F),
    onSurface = Color(0xFFDCE8E2),
    surfaceVariant = Color(0xFF1C2B25),
    onSurfaceVariant = Color(0xFFA2B5AC),
    surfaceContainerLowest = Color(0xFF060E0B),
    surfaceContainerLow = Color(0xFF0E1A15),
    surfaceContainer = Color(0xFF13211B),
    surfaceContainerHigh = Color(0xFF1A2A23),
    surfaceContainerHighest = Color(0xFF23352D),
    outline = Color(0xFF55675F),
    outlineVariant = Color(0xFF263832),
    error = Color(0xFFFF8E8E),
    onError = Color(0xFF4B0A0A),
    errorContainer = Color(0xFF5C1F27),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** 主题之外的专用颜色：首页大卡片的渐变、各状态的颜色、气泡。 */
@Immutable
class SmsExtras(
    val heroStart: Color,
    val heroEnd: Color,
    val onHero: Color,
    val onHeroVariant: Color,
    val success: Color,
    val warn: Color,
    val bubbleIn: Color,
    val onBubbleIn: Color,
    val bubbleOut: Color,
    val onBubbleOut: Color,
)

private val MintExtras = SmsExtras(
    heroStart = Color(0xFF14A085),
    heroEnd = Color(0xFF0B5D6B),
    onHero = Color(0xFFFFFFFF),
    onHeroVariant = Color(0xD9FFFFFF),
    success = Color(0xFF1B7F4F),
    warn = Color(0xFFB4502C),
    bubbleIn = Color(0xFFFFFFFF),
    onBubbleIn = Color(0xFF14201B),
    bubbleOut = Color(0xFF0E7C66),
    onBubbleOut = Color(0xFFFFFFFF),
)

private val PineExtras = SmsExtras(
    heroStart = Color(0xFF136B5A),
    heroEnd = Color(0xFF123F57),
    onHero = Color(0xFFFFFFFF),
    onHeroVariant = Color(0xD9E6F6F0),
    success = Color(0xFF7FD6A4),
    warn = Color(0xFFFFB59B),
    bubbleIn = Color(0xFF1C2B25),
    onBubbleIn = Color(0xFFDCE8E2),
    bubbleOut = Color(0xFF0B5444),
    onBubbleOut = Color(0xFFD7F7EC),
)

val LocalSmsExtras = staticCompositionLocalOf { MintExtras }

private val SmsShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val Sans = FontFamily.SansSerif

private val SmsTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Light, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
)

@Composable
fun SmsTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors: ColorScheme = if (darkTheme) PineColors else MintColors
    val extras = if (darkTheme) PineExtras else MintExtras
    CompositionLocalProvider(LocalSmsExtras provides extras) {
        MaterialTheme(colorScheme = colors, typography = SmsTypography, shapes = SmsShapes, content = content)
    }
}
