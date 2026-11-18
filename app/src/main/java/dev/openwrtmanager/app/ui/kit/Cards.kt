package dev.openwrtmanager.app.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.openwrtmanager.app.ui.theme.LocalExtraColors

/**
 * The kit's card: an outlined surface with an optional label header and a trailing element on the
 * header line. A glowing card gets a faint wash of the accent colour in its top corner.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    label: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    glow: Boolean = false,
    contentPadding: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tint = LocalExtraColors.current.accentTint
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier
                .then(if (glow) Modifier.background(Brush.linearGradient(0f to tint, 0.6f to Color.Transparent)) else Modifier)
                .padding(if (contentPadding) 12.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (label != null || trailing != null) {
                Row(
                    // Without content padding the rows run edge to edge, but the header still needs its inset.
                    Modifier.fillMaxWidth().then(if (contentPadding) Modifier else Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (label != null) Label(label, Modifier.weight(1f))
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

/** A grouped list: rows in one outlined card, divided by thin lines. */
@Composable
fun ListCard(modifier: Modifier = Modifier, label: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) Label(label, Modifier.padding(start = 4.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(content = content)
        }
    }
}

/** A thin divider between rows of a [ListCard]. */
@Composable
fun RowDivider() = HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)

/**
 * One row of a [ListCard]: optional icon badge, title, optional detail line (monospace when it is
 * data), and a trailing control. A clickable row with no trailing control gets a chevron.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    detail: String? = null,
    monoDetail: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) IconBadge(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                if (monoDetail) {
                    MonoText(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                } else {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
