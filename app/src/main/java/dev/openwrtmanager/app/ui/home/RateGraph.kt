package dev.openwrtmanager.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.unit.sp
import dev.openwrtmanager.app.domain.Rate
import dev.openwrtmanager.app.ui.common.formatRate
import dev.openwrtmanager.app.ui.kit.MonoText

/** Smooth rolling graph of download (filled) and upload, scaled to the largest sample shown. */
@Composable
fun RateGraph(history: List<Rate>, slots: Int, modifier: Modifier = Modifier) {
    val downColor = MaterialTheme.colorScheme.primary
    val upColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val peak = history.maxOfOrNull { maxOf(it.downBps, it.upBps) } ?: 0L
    val description = if (history.isEmpty()) "Speed graph, waiting for data" else "Speed graph, peak ${formatRate(peak)}"

    Box(modifier.semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxSize()) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
            drawLine(gridColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            drawLine(gridColor, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            if (history.size < 2) return@Canvas
            val scale = peak.coerceAtLeast(1_000) * 1.15f
            drawSeries(history.map { it.downBps }, slots, scale, downColor, fill = true)
            drawSeries(history.map { it.upBps }, slots, scale, upColor, fill = false)
        }
        if (history.size >= 2 && peak > 0L) {
            MonoText(
                "peak ${formatRate(peak)}",
                size = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f),
                        MaterialTheme.shapes.extraSmall,
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

private fun DrawScope.drawSeries(values: List<Long>, slots: Int, scale: Float, color: Color, fill: Boolean) {
    val step = size.width / (slots - 1).coerceAtLeast(1)
    val startX = size.width - step * (values.size - 1)
    val points = values.mapIndexed { index, value ->
        val x = startX + step * index
        val y = size.height - (value / scale).coerceIn(0f, 1f) * size.height
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
            lineTo(points.last().x, size.height)
            lineTo(points.first().x, size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0.02f))))
    }
    drawPath(line, color, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}
