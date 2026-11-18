package dev.openwrtmanager.app.ui.kit

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.openwrtmanager.app.ui.theme.MonoFamily

/** The small, spaced, uppercase heading over a group or a value. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A value read like an instrument: monospace, with an optional smaller unit after it. */
@Composable
fun Readout(
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    color: Color = Color.Unspecified,
    size: TextUnit = 18.sp,
) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        // Steps the size down rather than clipping when large system text leaves too little room.
        BasicText(
            value,
            modifier = Modifier.weight(1f, fill = false),
            style = TextStyle(
                fontFamily = MonoFamily,
                fontWeight = FontWeight.Medium,
                fontSize = size,
                color = if (color == Color.Unspecified) LocalContentColor.current else color,
            ),
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = size),
        )
        if (unit != null) {
            Text(
                unit,
                fontFamily = MonoFamily,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 3.dp, bottom = 2.dp),
                maxLines = 1,
            )
        }
    }
}

/** Plain monospace text for data inside sentences or rows: addresses, versions, ports. */
@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    size: TextUnit = 12.sp,
    maxLines: Int = 1,
) {
    Text(
        text,
        modifier = modifier,
        fontFamily = MonoFamily,
        fontSize = size,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Marks data that updates on its own. */
@Composable
fun LiveMarker(modifier: Modifier = Modifier) {
    Label("● Live", modifier, color = MaterialTheme.colorScheme.primary)
}
