package dev.localapi.deepseek

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val LocalTextScale = compositionLocalOf { 1f }

enum class AppThemeMode(val id: String, val label: String) {
    SYSTEM("system", "Системная"),
    DARK("dark", "Тёмная"),
    LIGHT("light", "Светлая");

    companion object {
        fun from(raw: String?): AppThemeMode = entries.firstOrNull { it.id == raw } ?: SYSTEM
    }
}

enum class AppPalette(
    val id: String,
    val label: String,
    val swatchPrimary: Color,
    val swatchSecondary: Color,
    val swatchContainer: Color
) {
    DYNAMIC("dynamic", "Система", Color(0xFF4C8DFF), Color(0xFFAAC7FF), Color(0xFF284777)),
    BLUE("blue", "DeepSeek", Color(0xFF415F91), Color(0xFFAAC7FF), Color(0xFF284777)),
    VIOLET("violet", "Ирис", Color(0xFF665297), Color(0xFFCFBCFF), Color(0xFF4D3A7E)),
    EMERALD("emerald", "Изумруд", Color(0xFF1A6B4B), Color(0xFF8BD8B0), Color(0xFF005236)),
    ROSE("rose", "Коралл", Color(0xFFA33751), Color(0xFFFFB2BE), Color(0xFF861E3A)),
    MONO("mono", "Моно", Color(0xFF3F3F46), Color(0xFFD4D4D8), Color(0xFF27272C));

    companion object {
        fun from(raw: String?): AppPalette = entries.firstOrNull { it.id == raw } ?: DYNAMIC
    }
}

private val BlueDark = darkColorScheme(
    primary = Color(0xFFAAC7FF), onPrimary = Color(0xFF0A305F),
    primaryContainer = Color(0xFF284777), onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBEC6DC), onSecondary = Color(0xFF283141),
    secondaryContainer = Color(0xFF3E4759), onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFFDDBCE0), onTertiary = Color(0xFF402843),
    tertiaryContainer = Color(0xFF573E5C), onTertiaryContainer = Color(0xFFFAD8FD),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318), onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318), onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474E), onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099), outlineVariant = Color(0xFF44474E),
    surfaceDim = Color(0xFF111318), surfaceBright = Color(0xFF37393E),
    surfaceContainerLowest = Color(0xFF0C0E13), surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024), surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A)
)

private val BlueLight = lightColorScheme(
    primary = Color(0xFF415F91), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FF), onPrimaryContainer = Color(0xFF001B3E),
    secondary = Color(0xFF565F71), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAE2F9), onSecondaryContainer = Color(0xFF131C2B),
    tertiary = Color(0xFF705575), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFAD8FD), onTertiaryContainer = Color(0xFF28132E),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF9F9FF), onBackground = Color(0xFF191C20),
    surface = Color(0xFFF9F9FF), onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC), onSurfaceVariant = Color(0xFF44474E),
    outline = Color(0xFF74777F), outlineVariant = Color(0xFFC4C6D0),
    surfaceDim = Color(0xFFD9D9E0), surfaceBright = Color(0xFFF9F9FF),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainer = Color(0xFFEDEDF4), surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE1E2E8)
)

private val VioletDark = darkColorScheme(
    primary = Color(0xFFCFBCFF), onPrimary = Color(0xFF362266),
    primaryContainer = Color(0xFF4D3A7E), onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFFCBC2DB), onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF494358), onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8), onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48), onTertiaryContainer = Color(0xFFFFD8E4),
    background = Color(0xFF141218), onBackground = Color(0xFFE6E0E9),
    surface = Color(0xFF141218), onSurface = Color(0xFFE6E0E9),
    onSurfaceVariant = Color(0xFFCAC4D0), outline = Color(0xFF948F99), outlineVariant = Color(0xFF49454F),
    surfaceContainerLowest = Color(0xFF0F0D13), surfaceContainerLow = Color(0xFF1D1B20),
    surfaceContainer = Color(0xFF211F26), surfaceContainerHigh = Color(0xFF2B2930),
    surfaceContainerHighest = Color(0xFF36343B)
)

private val VioletLight = lightColorScheme(
    primary = Color(0xFF665297), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9DDFF), onPrimaryContainer = Color(0xFF210C4F),
    secondary = Color(0xFF625B71), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8), onSecondaryContainer = Color(0xFF1E192B),
    tertiary = Color(0xFF7D5260), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4), onTertiaryContainer = Color(0xFF31111D),
    background = Color(0xFFFEF7FF), onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFEF7FF), onSurface = Color(0xFF1D1B20),
    onSurfaceVariant = Color(0xFF49454F), outline = Color(0xFF79747E), outlineVariant = Color(0xFFCAC4D0),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainer = Color(0xFFF3EDF7), surfaceContainerHigh = Color(0xFFECE6F0),
    surfaceContainerHighest = Color(0xFFE6E0E9)
)

private val EmeraldDark = darkColorScheme(
    primary = Color(0xFF8BD8B0), onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF005236), onPrimaryContainer = Color(0xFFA7F5CB),
    secondary = Color(0xFFB4CCBD), onSecondary = Color(0xFF20352A),
    secondaryContainer = Color(0xFF364B40), onSecondaryContainer = Color(0xFFD0E8D9),
    tertiary = Color(0xFFA5CCE0), onTertiary = Color(0xFF073545),
    tertiaryContainer = Color(0xFF244C5C), onTertiaryContainer = Color(0xFFC1E8FD),
    background = Color(0xFF0F1512), onBackground = Color(0xFFDFE4DF),
    surface = Color(0xFF0F1512), onSurface = Color(0xFFDFE4DF),
    onSurfaceVariant = Color(0xFFC0C9C2), outline = Color(0xFF8A938D), outlineVariant = Color(0xFF404943),
    surfaceContainerLowest = Color(0xFF0A0F0D), surfaceContainerLow = Color(0xFF171D1A),
    surfaceContainer = Color(0xFF1B211E), surfaceContainerHigh = Color(0xFF262B28),
    surfaceContainerHighest = Color(0xFF303632)
)

private val EmeraldLight = lightColorScheme(
    primary = Color(0xFF1A6B4B), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA7F5CB), onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF4D6357), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD0E8D9), onSecondaryContainer = Color(0xFF0A1F15),
    tertiary = Color(0xFF3D6375), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC1E8FD), onTertiaryContainer = Color(0xFF001F2A),
    background = Color(0xFFF5FBF6), onBackground = Color(0xFF171D1A),
    surface = Color(0xFFF5FBF6), onSurface = Color(0xFF171D1A),
    onSurfaceVariant = Color(0xFF404943), outline = Color(0xFF707973), outlineVariant = Color(0xFFC0C9C2),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFEFF5F0),
    surfaceContainer = Color(0xFFEAEFEA), surfaceContainerHigh = Color(0xFFE4EAE5),
    surfaceContainerHighest = Color(0xFFDFE4DF)
)

private val RoseDark = darkColorScheme(
    primary = Color(0xFFFFB2BE), onPrimary = Color(0xFF660424),
    primaryContainer = Color(0xFF861E3A), onPrimaryContainer = Color(0xFFFFD9DE),
    secondary = Color(0xFFE4BDC2), onSecondary = Color(0xFF43292E),
    secondaryContainer = Color(0xFF5B3F44), onSecondaryContainer = Color(0xFFFFD9DE),
    tertiary = Color(0xFFEBB888), onTertiary = Color(0xFF462A06),
    tertiaryContainer = Color(0xFF5F401A), onTertiaryContainer = Color(0xFFFFDCBA),
    background = Color(0xFF191113), onBackground = Color(0xFFEFDFE1),
    surface = Color(0xFF191113), onSurface = Color(0xFFEFDFE1),
    onSurfaceVariant = Color(0xFFD6C2C4), outline = Color(0xFF9E8C8F), outlineVariant = Color(0xFF524345),
    surfaceContainerLowest = Color(0xFF140C0D), surfaceContainerLow = Color(0xFF22191B),
    surfaceContainer = Color(0xFF261D1F), surfaceContainerHigh = Color(0xFF312829),
    surfaceContainerHighest = Color(0xFF3C3234)
)

private val RoseLight = lightColorScheme(
    primary = Color(0xFFA33751), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9DE), onPrimaryContainer = Color(0xFF3F0013),
    secondary = Color(0xFF75565B), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD9DE), onSecondaryContainer = Color(0xFF2B1519),
    tertiary = Color(0xFF79572E), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCBA), onTertiaryContainer = Color(0xFF2A1700),
    background = Color(0xFFFFF8F8), onBackground = Color(0xFF22191B),
    surface = Color(0xFFFFF8F8), onSurface = Color(0xFF22191B),
    onSurfaceVariant = Color(0xFF524345), outline = Color(0xFF847375), outlineVariant = Color(0xFFD6C2C4),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFFF0F1),
    surfaceContainer = Color(0xFFFAEAEC), surfaceContainerHigh = Color(0xFFF4E4E6),
    surfaceContainerHighest = Color(0xFFEFDFE1)
)

private val MonoDark = darkColorScheme(
    primary = Color(0xFFD4D4D8), onPrimary = Color(0xFF18181B),
    primaryContainer = Color(0xFF3F3F46), onPrimaryContainer = Color(0xFFF4F4F5),
    secondary = Color(0xFFC4C4CC), onSecondary = Color(0xFF27272A),
    secondaryContainer = Color(0xFF3A3A40), onSecondaryContainer = Color(0xFFECECF0),
    background = Color(0xFF101012), onBackground = Color(0xFFE4E4E7),
    surface = Color(0xFF101012), onSurface = Color(0xFFE4E4E7),
    onSurfaceVariant = Color(0xFFB4B4BC), outline = Color(0xFF82828A), outlineVariant = Color(0xFF3C3C42),
    surfaceContainerLowest = Color(0xFF09090B), surfaceContainerLow = Color(0xFF18181B),
    surfaceContainer = Color(0xFF1F1F23), surfaceContainerHigh = Color(0xFF27272C),
    surfaceContainerHighest = Color(0xFF323238)
)

private val MonoLight = lightColorScheme(
    primary = Color(0xFF3F3F46), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4E4E7), onPrimaryContainer = Color(0xFF18181B),
    secondary = Color(0xFF52525B), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4E4E7), onSecondaryContainer = Color(0xFF18181B),
    background = Color(0xFFFAFAFA), onBackground = Color(0xFF18181B),
    surface = Color(0xFFFAFAFA), onSurface = Color(0xFF18181B),
    onSurfaceVariant = Color(0xFF52525B), outline = Color(0xFF71717A), outlineVariant = Color(0xFFD4D4D8),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF4F4F6),
    surfaceContainer = Color(0xFFEEEEF1), surfaceContainerHigh = Color(0xFFE8E8EC),
    surfaceContainerHighest = Color(0xFFE2E2E7)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun LocalApiTheme(
    themeMode: AppThemeMode,
    palette: AppPalette,
    textScale: Float = 1f,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> systemDark
    }
    val context = LocalContext.current
    val colors: ColorScheme = when {
        palette == AppPalette.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        palette == AppPalette.VIOLET -> if (dark) VioletDark else VioletLight
        palette == AppPalette.EMERALD -> if (dark) EmeraldDark else EmeraldLight
        palette == AppPalette.ROSE -> if (dark) RoseDark else RoseLight
        palette == AppPalette.MONO -> if (dark) MonoDark else MonoLight
        else -> if (dark) BlueDark else BlueLight
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val barColor = colors.surface.toArgb()
            window.statusBarColor = barColor
            window.navigationBarColor = barColor
            val lightIcons = !dark
            if (Build.VERSION.SDK_INT >= 30) {
                val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                window.insetsController?.setSystemBarsAppearance(if (lightIcons) mask else 0, mask)
            } else {
                @Suppress("DEPRECATION")
                var flags = window.decorView.systemUiVisibility
                flags = if (lightIcons) {
                    flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                } else {
                    flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                }
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = flags
            }
        }
    }

    CompositionLocalProvider(LocalTextScale provides textScale.coerceIn(0.85f, 1.5f)) {
        MaterialTheme(
            colorScheme = colors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
