package dev.openwrtmanager.app.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

/** A small outlined tag for bands, states and counts. */
@Composable
fun TagChip(text: String, modifier: Modifier = Modifier, tone: ChipTone = ChipTone.NEUTRAL) {
    val extra = LocalExtraColors.current
    val scheme = MaterialTheme.colorScheme
    val (border, content) = when (tone) {
        ChipTone.NEUTRAL -> scheme.outlineVariant to scheme.onSurfaceVariant
        ChipTone.ACCENT -> scheme.primary to scheme.primary
        ChipTone.OK -> extra.online to extra.online
        ChipTone.WARNING -> extra.warning to extra.warning
        ChipTone.ERROR -> scheme.error to scheme.error
    }
    MonoText(
        text,
        color = content,
        size = 10.sp,
        modifier = modifier
            .border(1.dp, border, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 6.dp, vertical = 1.dp),
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
                        Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent), center = Offset(size.width / 2, size.height / 2), radius = size.width * 1.3f),
                        radius = size.width * 1.3f,
                    )
                }
            }
            .background(color, CircleShape),
    )
}

/** An icon on a tinted rounded square, in the accent colour. */
@Composable
fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier, contentDescription: String? = null) {
    Box(
        modifier.size(30.dp).background(LocalExtraColors.current.accentTint, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
    }
}
