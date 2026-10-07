package org.agentos.sample.calendar.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 视觉语言：暖纸色底 + 墨蓝文字 + 朱红强调。亮 / 暗两套，不用动态取色，保证截图和演示里风格一致。
private val LightColors = lightColorScheme(
    primary = Color(0xFFD9482B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE2D9),
    onPrimaryContainer = Color(0xFF5C1708),
    secondary = Color(0xFF2B3A55),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4E9F3),
    onSecondaryContainer = Color(0xFF121C30),
    tertiary = Color(0xFF2BA0A4),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD3F0F0),
    onTertiaryContainer = Color(0xFF00393B),
    background = Color(0xFFFBF8F3),
    onBackground = Color(0xFF1D1B18),
    surface = Color(0xFFFBF8F3),
    onSurface = Color(0xFF1D1B18),
    surfaceVariant = Color(0xFFF1EBE1),
    onSurfaceVariant = Color(0xFF6A6358),
    surfaceTint = Color(0xFFD9482B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F2E9),
    surfaceContainer = Color(0xFFF3EDE2),
    surfaceContainerHigh = Color(0xFFEDE6D9),
    surfaceContainerHighest = Color(0xFFE6DECF),
    outline = Color(0xFFC9BFB0),
    outlineVariant = Color(0xFFE6DFD2),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF32302C),
    inverseOnSurface = Color(0xFFF6F0E8),
    inversePrimary = Color(0xFFFFB4A1),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF8F72),
    onPrimary = Color(0xFF4A1000),
    primaryContainer = Color(0xFF6E2512),
    onPrimaryContainer = Color(0xFFFFDBD1),
    secondary = Color(0xFFB8C6E4),
    onSecondary = Color(0xFF223049),
    secondaryContainer = Color(0xFF39475F),
    onSecondaryContainer = Color(0xFFD9E3F9),
    tertiary = Color(0xFF6FD3D7),
    onTertiary = Color(0xFF003739),
    tertiaryContainer = Color(0xFF004F52),
    onTertiaryContainer = Color(0xFFB0F0F2),
    background = Color(0xFF131316),
    onBackground = Color(0xFFE9E5DE),
    surface = Color(0xFF131316),
    onSurface = Color(0xFFE9E5DE),
    surfaceVariant = Color(0xFF2B2A30),
    onSurfaceVariant = Color(0xFFB9B3A9),
    surfaceTint = Color(0xFFFF8F72),
    surfaceContainerLowest = Color(0xFF0E0E11),
    surfaceContainerLow = Color(0xFF19191D),
    surfaceContainer = Color(0xFF1E1E23),
    surfaceContainerHigh = Color(0xFF28282E),
    surfaceContainerHighest = Color(0xFF333339),
    outline = Color(0xFF77726A),
    outlineVariant = Color(0xFF3B3A40),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE9E5DE),
    inverseOnSurface = Color(0xFF32302C),
    inversePrimary = Color(0xFFD9482B),
)

/** 月份大标题用衬线体，其余用系统无衬线体：字重和字距拉开层级。 */
private val Display = FontFamily.Serif

private val CalendarTypography = Typography(
    displayLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-0.5).sp),
    displayMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.25).sp),
    displaySmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 34.sp),
    headlineLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.2.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
)

private val CalendarShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/** 主题之外的少量语义色（今天的强调、网格线、当前时间线）。 */
@Immutable
class ExtraColors(val gridLine: Color, val nowLine: Color, val weekend: Color)

val LocalExtraColors = staticCompositionLocalOf { ExtraColors(Color.Unspecified, Color.Unspecified, Color.Unspecified) }

@Composable
fun CalendarTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    val extra = if (darkTheme) ExtraColors(Color(0xFF2E2E34), Color(0xFFFF8F72), Color(0xFF9AA7C4)) else ExtraColors(Color(0xFFEAE3D6), Color(0xFFD9482B), Color(0xFF5B6B8C))
    androidx.compose.runtime.CompositionLocalProvider(LocalExtraColors provides extra) {
        MaterialTheme(colorScheme = colors, typography = CalendarTypography, shapes = CalendarShapes, content = content)
    }
}
