package dev.openwrtmanager.app.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.openwrtmanager.app.ui.theme.LocalExtraColors

enum class ChipTone { NEUTRAL, ACCENT, OK, WARNING, ERROR }

/** M3 filled-tonal pill chip for bands, states, and counts. */
@Composable
fun TagChip(text: String, modifier: Modifier = Modifier, tone: ChipTone = ChipTone.NEUTRAL) {
    val extra = LocalExtraColors.current
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        ChipTone.NEUTRAL -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        ChipTone.ACCENT -> scheme.primaryContainer to scheme.onPrimaryContainer
        ChipTone.OK -> extra.online.copy(alpha = 0.16f) to extra.online
        ChipTone.WARNING -> extra.warning.copy(alpha = 0.16f) to extra.warning
        ChipTone.ERROR -> scheme.errorContainer to scheme.onErrorContainer
    }
    MonoText(
        text,
        color = content,
        size = 10.sp,
        modifier = modifier
            .background(container, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** A status dot; a glowing one reads as "live and well". */
@Composable
fun StatusDot(color: Color, glow: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(8.dp)
            .drawBehind {
                if (glow) {
                    drawCircle(
                        Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent), center = Offset(size.width / 2, size.height / 2), radius = size.width * 1.4f),
                        radius = size.width * 1.4f,
                    )
                }
            }
            .background(color, CircleShape),
    )
}

/** An icon on a soft M3 tonal container square. */
@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = LocalExtraColors.current.accentTint,
) {
    Box(
        modifier.size(36.dp).background(containerColor, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(20.dp))
    }
}
