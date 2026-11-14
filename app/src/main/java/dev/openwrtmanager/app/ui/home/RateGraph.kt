package dev.openwrtmanager.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.openwrtmanager.app.domain.Rate
import dev.openwrtmanager.app.ui.common.formatRate

/** Rolling line graph of download (filled) and upload, scaled to the largest sample shown. */
@Composable
fun RateGraph(history: List<Rate>, slots: Int, modifier: Modifier = Modifier) {
    val downColor = MaterialTheme.colorScheme.primary
    val upColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val peak = history.maxOfOrNull { maxOf(it.downBps, it.upBps) } ?: 0L
    val description = if (history.isEmpty()) "Speed graph, waiting for data" else "Speed graph, peak ${formatRate(peak)}"

    Canvas(modifier.semantics { contentDescription = description }) {
        drawLine(gridColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        drawLine(gridColor, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1.dp.toPx())
        if (history.size < 2) return@Canvas
        // Leave headroom so the peak does not touch the top edge; never divide by zero on an idle link.
        val scale = (peak.coerceAtLeast(1_000) * 1.15f)
        drawSeries(history.map { it.downBps }, slots, scale, downColor, fill = true)
        drawSeries(history.map { it.upBps }, slots, scale, upColor, fill = false)
    }
}

private fun DrawScope.drawSeries(values: List<Long>, slots: Int, scale: Float, color: Color, fill: Boolean) {
    val step = size.width / (slots - 1).coerceAtLeast(1)
    // Newest sample sits at the right edge; older ones scroll left.
    val startX = size.width - step * (values.size - 1)
    val line = Path()
    values.forEachIndexed { index, value ->
        val x = startX + step * index
        val y = size.height - (value / scale).coerceIn(0f, 1f) * size.height
        if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
    }
    if (fill) {
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(startX, size.height)
            close()
        }
        drawPath(area, color.copy(alpha = 0.18f))
    }
    drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}
