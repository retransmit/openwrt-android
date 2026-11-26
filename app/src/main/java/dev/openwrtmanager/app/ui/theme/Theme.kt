package dev.openwrtmanager.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Tuned M3 tonal palette for devices below Android 12 (without dynamic wallpaper color).
private val FallbackLight = lightColorScheme(
    primary = Color(0xFF006492),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC9E6FF),
    onPrimaryContainer = Color(0xFF001E2F),
    secondary = Color(0xFF4F606E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E5F5),
    onSecondaryContainer = Color(0xFF0C1D29),
    tertiary = Color(0xFF63597C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE9DDFF),
    onTertiaryContainer = Color(0xFF1F1635),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF181C20),
    surface = Color(0xFFF7F9FC),
    onSurface = Color(0xFF181C20),
    surfaceVariant = Color(0xFFDDE3EA),
    onSurfaceVariant = Color(0xFF41484D),
    outline = Color(0xFF71787E),
    outlineVariant = Color(0xFFC1C7CE),
    surfaceDim = Color(0xFFD7DADD),
    surfaceBright = Color(0xFFF7F9FC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F4F7),
    surfaceContainer = Color(0xFFEBEEF1),
    surfaceContainerHigh = Color(0xFFE5E8EC),
    surfaceContainerHighest = Color(0xFFDFE3E6),
)

private val FallbackDark = darkColorScheme(
    primary = Color(0xFF8BCEFF),
    onPrimary = Color(0xFF00344E),
    primaryContainer = Color(0xFF004B6F),
    onPrimaryContainer = Color(0xFFC9E6FF),
    secondary = Color(0xFFB7C9D8),
    onSecondary = Color(0xFF21323F),
    secondaryContainer = Color(0xFF384956),
    onSecondaryContainer = Color(0xFFD3E5F5),
    tertiary = Color(0xFFCDC0E9),
    onTertiary = Color(0xFF342B4B),
    tertiaryContainer = Color(0xFF4B4263),
    onTertiaryContainer = Color(0xFFE9DDFF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101417),
    onBackground = Color(0xFFE0E3E7),
    surface = Color(0xFF101417),
    onSurface = Color(0xFFE0E3E7),
    surfaceVariant = Color(0xFF41484D),
    onSurfaceVariant = Color(0xFFC1C7CE),
    outline = Color(0xFF8B9198),
    outlineVariant = Color(0xFF41484D),
    surfaceDim = Color(0xFF101417),
    surfaceBright = Color(0xFF363A3E),
    surfaceContainerLowest = Color(0xFF0B0F12),
    surfaceContainerLow = Color(0xFF181C20),
    surfaceContainer = Color(0xFF1C2024),
    surfaceContainerHigh = Color(0xFF272B2E),
    surfaceContainerHighest = Color(0xFF313539),
)

@Composable
fun OpenWrtTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> FallbackDark
        else -> FallbackLight
    }
    CompositionLocalProvider(LocalExtraColors provides extraColorsFor(scheme, dark)) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
