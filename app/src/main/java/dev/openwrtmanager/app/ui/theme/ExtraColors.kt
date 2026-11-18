package dev.openwrtmanager.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colours Material has no role for. Online and warning are the two fixed exceptions to the
 * wallpaper palette; the accent tint is the wallpaper's primary, faded for backgrounds.
 */
@Immutable
data class ExtraColors(val online: Color, val warning: Color, val onWarning: Color, val accentTint: Color)

private val Online = Color(0xFF3DDC84)
private val Warning = Color(0xFFF2B04A)

fun extraColorsFor(scheme: ColorScheme, dark: Boolean) = ExtraColors(
    // A darker green and amber keep enough contrast on light surfaces.
    online = if (dark) Online else Color(0xFF1E8E4E),
    warning = if (dark) Warning else Color(0xFFB0700A),
    onWarning = Color(0xFF2B1A00),
    accentTint = scheme.primary.copy(alpha = if (dark) 0.16f else 0.12f),
)

val LocalExtraColors = staticCompositionLocalOf { ExtraColors(Online, Warning, Color(0xFF2B1A00), Color.Transparent) }
