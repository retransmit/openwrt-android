package dev.openwrtmanager.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.openwrtmanager.app.domain.Rate
import dev.openwrtmanager.app.ui.common.formatRate

/** Smooth rolling graph of download (filled) and upload, scaled to the largest sample shown. */
@Composable
fun RateGraph(history: List<Rate>, slots: Int, modifier: Modifier = Modifier) {
    val downColor = MaterialTheme.colorScheme.primary
    val upColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val peak = history.maxOfOrNull { maxOf(it.downBps, it.upBps) } ?: 0L
    val description = if (history.isEmpty()) "Speed graph, waiting for data" else "Speed graph, peak ${formatRate(peak)}"

    Canvas(modifier.semantics { contentDescription = description }) {
        val topPad = 4.dp.toPx()
        val bottomY = size.height - 2.dp.toPx()
        val usableHeight = (bottomY - topPad).coerceAtLeast(1f)
        val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()))
        drawLine(gridColor, Offset(0f, bottomY), Offset(size.width, bottomY), strokeWidth = 1.dp.toPx())
        drawLine(gridColor, Offset(0f, topPad + usableHeight / 2f), Offset(size.width, topPad + usableHeight / 2f), strokeWidth = 1.dp.toPx(), pathEffect = dash)
        drawLine(gridColor.copy(alpha = 0.35f), Offset(0f, topPad), Offset(size.width, topPad), strokeWidth = 1.dp.toPx(), pathEffect = dash)
        if (history.size < 2) return@Canvas
        val scale = peak.coerceAtLeast(1_000) * 1.12f
        drawSeries(history.map { it.downBps }, slots, scale, topPad, bottomY, usableHeight, downColor, fill = true)
        drawSeries(history.map { it.upBps }, slots, scale, topPad, bottomY, usableHeight, upColor, fill = false)
    }
}

private fun DrawScope.drawSeries(
    values: List<Long>,
    slots: Int,
    scale: Float,
    topPad: Float,
    bottomY: Float,
    usableHeight: Float,
    color: Color,
    fill: Boolean,
) {
    val step = size.width / (slots - 1).coerceAtLeast(1)
    val startX = size.width - step * (values.size - 1)
    val points = values.mapIndexed { index, value ->
        val x = startX + step * index
        val y = bottomY - (value / scale).coerceIn(0f, 1f) * usableHeight
        Offset(x, y)
    }
    val line = Path().apply {
        moveTo(points.first().x, points.first().y)
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            val midX = (prev.x + curr.x) / 2f
            cubicTo(midX, prev.y, midX, curr.y, curr.x, curr.y)
        }
    }
    if (fill) {
        val area = Path().apply {
            addPath(line)
            lineTo(points.last().x, bottomY)
            lineTo(points.first().x, bottomY)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.02f)), startY = topPad, endY = bottomY))
    }
    drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}
