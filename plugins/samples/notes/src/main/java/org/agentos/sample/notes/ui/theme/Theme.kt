package org.agentos.sample.notes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
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
import org.agentos.sample.notes.data.NoteColor

/*
 * 视觉风格：“纸与墨”。暖白纸色底、松绿主色、赭黄点缀；标题用衬线体（系统 Serif，中文落到宋体系），正文用无衬线体，
 * 代码用等宽。便签颜色是一组柔和的粉彩，暗色下换成同色相的深色版。
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6F5C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDEBDD),
    onPrimaryContainer = Color(0xFF07382C),
    secondary = Color(0xFF8A5A12),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF6E3B6),
    onSecondaryContainer = Color(0xFF3A2705),
    tertiary = Color(0xFF9B4B32),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF8D9CC),
    onTertiaryContainer = Color(0xFF3B1306),
    background = Color(0xFFF8F4EC),
    onBackground = Color(0xFF1E1B16),
    surface = Color(0xFFFFFDF8),
    onSurface = Color(0xFF1E1B16),
    surfaceVariant = Color(0xFFEFE8DA),
    onSurfaceVariant = Color(0xFF6A6357),
    surfaceTint = Color(0xFF1F6F5C),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBF7EF),
    surfaceContainer = Color(0xFFF3EEE2),
    surfaceContainerHigh = Color(0xFFECE6D8),
    surfaceContainerHighest = Color(0xFFE5DECE),
    outline = Color(0xFF9A9284),
    outlineVariant = Color(0xFFDDD5C5),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFADAD3),
    onErrorContainer = Color(0xFF410E0B),
    inverseSurface = Color(0xFF33302A),
    inverseOnSurface = Color(0xFFF6F0E4),
    inversePrimary = Color(0xFF7FD1B9),
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD1B9),
    onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF14513F),
    onPrimaryContainer = Color(0xFFBDF0DC),
    secondary = Color(0xFFE9C16C),
    onSecondary = Color(0xFF3E2D00),
    secondaryContainer = Color(0xFF574008),
    onSecondaryContainer = Color(0xFFFBE5B0),
    tertiary = Color(0xFFF2B7A3),
    onTertiary = Color(0xFF4B1A0B),
    tertiaryContainer = Color(0xFF6A3320),
    onTertiaryContainer = Color(0xFFFFDCCF),
    background = Color(0xFF14120E),
    onBackground = Color(0xFFECE6D9),
    surface = Color(0xFF1B1913),
    onSurface = Color(0xFFECE6D9),
    surfaceVariant = Color(0xFF302C24),
    onSurfaceVariant = Color(0xFFB4AC9C),
    surfaceTint = Color(0xFF7FD1B9),
    surfaceContainerLowest = Color(0xFF100E0B),
    surfaceContainerLow = Color(0xFF1B1913),
    surfaceContainer = Color(0xFF221F18),
    surfaceContainerHigh = Color(0xFF2C2820),
    surfaceContainerHighest = Color(0xFF37322A),
    outline = Color(0xFF7D766A),
    outlineVariant = Color(0xFF3F3A30),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    inverseSurface = Color(0xFFECE6D9),
    inverseOnSurface = Color(0xFF33302A),
    inversePrimary = Color(0xFF1F6F5C),
    scrim = Color(0xFF000000),
)

private val Serif = FontFamily.Serif
private val Sans = FontFamily.SansSerif

private val NotesTypography = Typography(
    displaySmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.2).sp),
    headlineLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 33.sp),
    headlineSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 29.sp),
    titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 26.sp, letterSpacing = 0.1.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.1.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.2.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.3.sp),
)

private val NotesShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
)

/** 便签配色：[container] 是卡片底色，[accent] 是色点 / 强调色。亮暗各一套。 */
class NotePalette(val dark: Boolean) {
    fun container(color: NoteColor, default: Color): Color {
        val pair = SWATCHES.getValue(color)
        return if (color == NoteColor.DEFAULT) default else if (dark) pair.darkContainer else pair.lightContainer
    }

    fun accent(color: NoteColor, default: Color): Color =
        if (color == NoteColor.DEFAULT) default else SWATCHES.getValue(color).accent

    private class Swatch(val lightContainer: Color, val darkContainer: Color, val accent: Color)

    private companion object {
        val SWATCHES = mapOf(
            NoteColor.DEFAULT to Swatch(Color.Unspecified, Color.Unspecified, Color(0xFF9A9284)),
            NoteColor.YELLOW to Swatch(Color(0xFFF8EBB5), Color(0xFF4D4421), Color(0xFFE0B52F)),
            NoteColor.ORANGE to Swatch(Color(0xFFF9DDC0), Color(0xFF55391D), Color(0xFFE48A36)),
            NoteColor.RED to Swatch(Color(0xFFF6D1CC), Color(0xFF5A2B28), Color(0xFFD95B52)),
            NoteColor.PURPLE to Swatch(Color(0xFFE2D7F2), Color(0xFF43355C), Color(0xFF9570CF)),
            NoteColor.BLUE to Swatch(Color(0xFFD1E3F4), Color(0xFF26425E), Color(0xFF4A89CF)),
            NoteColor.TEAL to Swatch(Color(0xFFC9EAE1), Color(0xFF1F4A44), Color(0xFF2B9D8E)),
            NoteColor.GREEN to Swatch(Color(0xFFDAEAC2), Color(0xFF3A4A22), Color(0xFF7AA93C)),
            NoteColor.GRAY to Swatch(Color(0xFFE3DFD6), Color(0xFF3A3730), Color(0xFF8C8679)),
        )
    }
}

val LocalNotePalette = staticCompositionLocalOf { NotePalette(dark = false) }

/** 当前主题下某个便签颜色的卡片底色（默认色用 surface）。 */
@Composable
@ReadOnlyComposable
fun NoteColor.containerColor(): Color = LocalNotePalette.current.container(this, MaterialTheme.colorScheme.surface)

@Composable
@ReadOnlyComposable
fun NoteColor.accentColor(): Color = LocalNotePalette.current.accent(this, MaterialTheme.colorScheme.outline)

@Composable
fun NotesTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors: ColorScheme = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(LocalNotePalette provides NotePalette(darkTheme)) {
        MaterialTheme(colorScheme = colors, typography = NotesTypography, shapes = NotesShapes, content = content)
    }
}
