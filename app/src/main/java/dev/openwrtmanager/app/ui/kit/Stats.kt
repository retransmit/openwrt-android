package dev.openwrtmanager.app.ui.kit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A usage percentage as a bar fill: no value is an empty bar, and the fill stays within the bar. */
fun usageFraction(percent: Int?): Float = (percent ?: 0).coerceIn(0, 100) / 100f

/** A tonal M3 metric tile: label, value, an optional animated usage bar, and an optional detail. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    usage: Int? = null,
    detail: String? = null,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(14.dp)) {
            Label(label)
            Readout(value, Modifier.padding(top = 6.dp), unit = unit, size = 18.sp)
            if (usage != null) UsageBar(usage, Modifier.padding(top = 10.dp))
            if (detail != null) MonoText(detail, Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, size = 10.sp, maxLines = 2)
        }
    }
}

/** A rounded M3 progress track showing how full something is. */
@Composable
fun UsageBar(percent: Int?, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(targetValue = usageFraction(percent), label = "usage")
    val barColor = when {
        (percent ?: 0) >= 90 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(6.dp)
                .clip(CircleShape)
                .background(barColor),
        )
    }
}
