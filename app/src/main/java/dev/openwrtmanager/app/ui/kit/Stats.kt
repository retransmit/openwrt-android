package dev.openwrtmanager.app.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A usage percentage as a bar fill: no value is an empty bar, and the fill stays within the bar. */
fun usageFraction(percent: Int?): Float = (percent ?: 0).coerceIn(0, 100) / 100f

/** A small outlined tile: label, value, an optional usage bar and an optional one-line detail. */
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(10.dp)) {
            Label(label)
            Readout(value, Modifier.padding(top = 4.dp), unit = unit, size = 17.sp)
            if (usage != null) UsageBar(usage, Modifier.padding(top = 8.dp))
            if (detail != null) MonoText(detail, Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, size = 10.sp, maxLines = 2)
        }
    }
}

/** A thin bar showing how full something is. */
@Composable
fun UsageBar(percent: Int?, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.extraSmall
    Box(modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.outlineVariant, shape)) {
        Box(Modifier.fillMaxWidth(usageFraction(percent)).height(4.dp).background(MaterialTheme.colorScheme.primary, shape))
    }
}
