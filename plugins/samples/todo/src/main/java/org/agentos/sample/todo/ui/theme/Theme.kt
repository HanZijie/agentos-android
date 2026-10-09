package org.agentos.sample.todo.ui.theme

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
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.agentos.sample.todo.data.Priority

/*
 * 视觉风格：“靛蓝与珊瑚”。冷灰蓝的纸面、靛蓝主色；优先级用三种有记忆点的颜色（珊瑚红 / 琥珀 / 天蓝）画成每行左侧的色条；
 * 完成是薄荷绿；逾期是玫红。字体全部用系统无衬线体，靠字重和字号拉开层级：大标题 Black，条目标题 SemiBold，辅助信息 Medium。
 * 亮 / 暗各一套，不用系统动态取色。
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF4353E0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE1E5FF),
    onPrimaryContainer = Color(0xFF111A6B),
    secondary = Color(0xFF5A6285),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6E9F6),
    onSecondaryContainer = Color(0xFF1B2140),
    tertiary = Color(0xFF1E9E6C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD3F3E4),
    onTertiaryContainer = Color(0xFF0A3B27),
    background = Color(0xFFF3F5FB),
    onBackground = Color(0xFF141829),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141829),
    surfaceVariant = Color(0xFFE9ECF6),
    onSurfaceVariant = Color(0xFF596079),
    surfaceTint = Color(0xFF4353E0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F9FD),
    surfaceContainer = Color(0xFFEEF0F8),
    surfaceContainerHigh = Color(0xFFE7EAF4),
    surfaceContainerHighest = Color(0xFFDFE3F0),
    outline = Color(0xFF8D93AB),
    outlineVariant = Color(0xFFD9DDEC),
    error = Color(0xFFD62F5E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE0E8),
    onErrorContainer = Color(0xFF5C0A24),
    inverseSurface = Color(0xFF282C3F),
    inverseOnSurface = Color(0xFFEFF1FA),
    inversePrimary = Color(0xFFB4BDFF),
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA2ADFF),
    onPrimary = Color(0xFF0D1558),
    primaryContainer = Color(0xFF2C3799),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFFB4BAD8),
    onSecondary = Color(0xFF23283F),
    secondaryContainer = Color(0xFF353B58),
    onSecondaryContainer = Color(0xFFDCE0F6),
    tertiary = Color(0xFF58D6A0),
    onTertiary = Color(0xFF00382A),
    tertiaryContainer = Color(0xFF145A40),
    onTertiaryContainer = Color(0xFFC6F5DD),
    background = Color(0xFF0B0E1B),
    onBackground = Color(0xFFE5E8F5),
    surface = Color(0xFF141829),
    onSurface = Color(0xFFE5E8F5),
    surfaceVariant = Color(0xFF262B42),
    onSurfaceVariant = Color(0xFFADB3CC),
    surfaceTint = Color(0xFFA2ADFF),
    surfaceContainerLowest = Color(0xFF090B16),
    surfaceContainerLow = Color(0xFF111524),
    surfaceContainer = Color(0xFF181C2E),
    surfaceContainerHigh = Color(0xFF212640),
    surfaceContainerHighest = Color(0xFF2B314D),
    outline = Color(0xFF777E9A),
    outlineVariant = Color(0xFF353B57),
    error = Color(0xFFFF8FAB),
    onError = Color(0xFF5C0A24),
    errorContainer = Color(0xFF7C1D3B),
    onErrorContainer = Color(0xFFFFD9E2),
    inverseSurface = Color(0xFFE5E8F5),
    inverseOnSurface = Color(0xFF282C3F),
    inversePrimary = Color(0xFF4353E0),
    scrim = Color(0xFF000000),
)

private val Sans = FontFamily.SansSerif

private val TodoTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 38.sp, lineHeight = 44.sp, letterSpacing = (-0.8).sp),
    headlineLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.15.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.3.sp),
)

private val TodoShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/** Material 配色之外的语义色：优先级色条、完成、逾期。 */
class TodoPalette(
    val high: Color,
    val medium: Color,
    val low: Color,
    val done: Color,
    val overdue: Color,
    val overdueContainer: Color,
    val heroStart: Color,
    val heroEnd: Color,
) {
    fun priority(priority: Priority): Color = when (priority) {
        Priority.HIGH -> high
        Priority.MEDIUM -> medium
        Priority.LOW -> low
    }
}

private val LightPalette = TodoPalette(
    high = Color(0xFFE5484D),
    medium = Color(0xFFF2A33A),
    low = Color(0xFF5B9BF0),
    done = Color(0xFF1E9E6C),
    overdue = Color(0xFFD62F5E),
    overdueContainer = Color(0xFFFFE9EF),
    heroStart = Color(0xFF4353E0),
    heroEnd = Color(0xFF7B5CF0),
)

private val DarkPalette = TodoPalette(
    high = Color(0xFFFF7A80),
    medium = Color(0xFFFFC266),
    low = Color(0xFF7DB4FF),
    done = Color(0xFF58D6A0),
    overdue = Color(0xFFFF8FAB),
    overdueContainer = Color(0xFF3B1524),
    heroStart = Color(0xFF2C3799),
    heroEnd = Color(0xFF4B3A9E),
)

val LocalTodoPalette = staticCompositionLocalOf { LightPalette }

val MaterialTheme.todo: TodoPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalTodoPalette.current

@Composable
fun TodoTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors: ColorScheme = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(LocalTodoPalette provides if (darkTheme) DarkPalette else LightPalette) {
        MaterialTheme(colorScheme = colors, typography = TodoTypography, shapes = TodoShapes, content = content)
    }
}
