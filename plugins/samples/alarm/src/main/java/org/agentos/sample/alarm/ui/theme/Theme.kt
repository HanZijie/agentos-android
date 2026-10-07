package org.agentos.sample.alarm.ui.theme

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
 * 视觉风格：白天是“晨曦”——奶油底、墨色字、焦橘色强调；夜里是“午夜琥珀”——深靛蓝底、琥珀色强调。
 * 两套都不用系统动态取色，保证截图里的配色是设计过的。
 */

private val DaybreakColors = lightColorScheme(
    primary = Color(0xFFD9531E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE1CE),
    onPrimaryContainer = Color(0xFF5A1D00),
    secondary = Color(0xFF3B4A8F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E5FF),
    onSecondaryContainer = Color(0xFF111B52),
    tertiary = Color(0xFFB03A5B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E1),
    onTertiaryContainer = Color(0xFF3F0017),
    background = Color(0xFFFFF8EE),
    onBackground = Color(0xFF1F1A2E),
    surface = Color(0xFFFFF8EE),
    onSurface = Color(0xFF1F1A2E),
    surfaceVariant = Color(0xFFF3E7D6),
    onSurfaceVariant = Color(0xFF6A6277),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFBF5),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF8EDDD),
    surfaceContainerHighest = Color(0xFFF1E3CE),
    outline = Color(0xFFA79FB3),
    outlineVariant = Color(0xFFE9DDCB),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val MidnightColors = darkColorScheme(
    primary = Color(0xFFFFB45E),
    onPrimary = Color(0xFF2A1600),
    primaryContainer = Color(0xFF4B3315),
    onPrimaryContainer = Color(0xFFFFDDB5),
    secondary = Color(0xFF9DB1FF),
    onSecondary = Color(0xFF0F1B4D),
    secondaryContainer = Color(0xFF273470),
    onSecondaryContainer = Color(0xFFDDE3FF),
    tertiary = Color(0xFFFF9A8B),
    onTertiary = Color(0xFF4A0F08),
    tertiaryContainer = Color(0xFF5E2A22),
    onTertiaryContainer = Color(0xFFFFDAD4),
    background = Color(0xFF0A0E1F),
    onBackground = Color(0xFFEAEDFB),
    surface = Color(0xFF0A0E1F),
    onSurface = Color(0xFFEAEDFB),
    surfaceVariant = Color(0xFF1E2748),
    onSurfaceVariant = Color(0xFFA7AECD),
    surfaceContainerLowest = Color(0xFF070A17),
    surfaceContainerLow = Color(0xFF0F1428),
    surfaceContainer = Color(0xFF151B36),
    surfaceContainerHigh = Color(0xFF1C2444),
    surfaceContainerHighest = Color(0xFF253057),
    outline = Color(0xFF59628A),
    outlineVariant = Color(0xFF2B3459),
    error = Color(0xFFFF8E8E),
    onError = Color(0xFF4B0A0A),
    errorContainer = Color(0xFF5C1F27),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** 主题之外的几个专用颜色：下次响铃卡片的渐变、响铃页背景。 */
@Immutable
class AlarmExtras(
    val heroStart: Color,
    val heroEnd: Color,
    val onHero: Color,
    val onHeroVariant: Color,
    val success: Color,
)

private val DaybreakExtras = AlarmExtras(
    heroStart = Color(0xFFFF9A5C),
    heroEnd = Color(0xFFE8504A),
    onHero = Color(0xFFFFFFFF),
    onHeroVariant = Color(0xCCFFFFFF),
    success = Color(0xFF2E7D4F),
)

private val MidnightExtras = AlarmExtras(
    heroStart = Color(0xFF2A3A8C),
    heroEnd = Color(0xFF6A3D9A),
    onHero = Color(0xFFFFFFFF),
    onHeroVariant = Color(0xCCEAEDFB),
    success = Color(0xFF7FD1A0),
)

val LocalAlarmExtras = staticCompositionLocalOf { DaybreakExtras }

private val AlarmShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val Sans = FontFamily.SansSerif

private val AlarmTypography = Typography(
    displayLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.ExtraLight, fontSize = 96.sp, lineHeight = 100.sp, letterSpacing = (-3).sp),
    displayMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Light, fontSize = 64.sp, lineHeight = 68.sp, letterSpacing = (-2).sp),
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Light, fontSize = 48.sp, lineHeight = 52.sp, letterSpacing = (-1.5).sp),
    headlineLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
)

/** 闹钟列表里的大时间数字：细字重、等宽数字，视觉重心。 */
val TimeNumeralStyle = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.Light,
    fontSize = 52.sp,
    lineHeight = 56.sp,
    letterSpacing = (-1.5).sp,
    fontFeatureSettings = "tnum",
)

@Composable
fun AlarmTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors: ColorScheme = if (darkTheme) MidnightColors else DaybreakColors
    val extras = if (darkTheme) MidnightExtras else DaybreakExtras
    CompositionLocalProvider(LocalAlarmExtras provides extras) {
        MaterialTheme(colorScheme = colors, typography = AlarmTypography, shapes = AlarmShapes, content = content)
    }
}

/** 响铃页永远用夜间配色：半夜被叫醒时不能一片刺眼的白。 */
@Composable
fun RingTheme(content: @Composable () -> Unit) = AlarmTheme(darkTheme = true, content = content)
