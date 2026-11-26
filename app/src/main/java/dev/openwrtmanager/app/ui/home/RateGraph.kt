package dev.openwrtmanager.app.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.openwrtmanager.app.domain.Rate
import dev.openwrtmanager.app.ui.common.formatRate
import dev.openwrtmanager.app.ui.kit.MonoText
import kotlin.math.roundToInt

/** Below this many samples there is no line worth drawing yet. */
private const val MIN_SAMPLES = 3

/**
 * Rolling graph mirrored around a centre line: download fills above it, upload below, so the two
 * never overlap. Both halves share one tidy, labelled scale that eases between steps; the lines
 * glide left between samples; pressing and dragging shows the exact rates at that moment.
 */
@Composable
fun RateGraph(history: List<Rate>, slots: Int, sampleMs: Long, modifier: Modifier = Modifier) {
    val downColor = MaterialTheme.colorScheme.primary
    val upColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val peak = history.maxOfOrNull { maxOf(it.downBps, it.upBps) } ?: 0L
    val top = niceScaleTop(peak)
    val easedTop by animateFloatAsState(top.toFloat(), tween(500), label = "graph scale")
    val ready = history.size >= MIN_SAMPLES
    val description = if (!ready) "Speed graph, collecting" else "Speed graph, peak ${formatRate(peak)}"

    // Each new sample starts one step to the right and slides into place over the sample interval.
    val glide = remember { Animatable(1f) }
    LaunchedEffect(history.size, history.lastOrNull()) {
        glide.snapTo(0f)
        glide.animateTo(1f, tween(sampleMs.toInt(), easing = LinearEasing))
    }
    var touchX by remember { mutableStateOf<Float?>(null) }

    BoxWithConstraints(modifier.semantics { contentDescription = description }) {
        val widthPx = constraints.maxWidth.toFloat()
        Canvas(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .pointerInput(ready) {
                    if (!ready) return@pointerInput
                    detectTapGestures(onPress = { at ->
                        touchX = at.x
                        tryAwaitRelease()
                        touchX = null
                    })
                }
                .pointerInput(ready) {
                    if (!ready) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { touchX = it.x },
                        onDragEnd = { touchX = null },
                        onDragCancel = { touchX = null },
                    ) { change, _ -> touchX = change.position.x }
                },
        ) {
            val topY = 4.dp.toPx()
            val bottomY = size.height - 4.dp.toPx()
            val centreY = (topY + bottomY) / 2f
            val half = ((bottomY - topY) / 2f).coerceAtLeast(1f)
            val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()))
            drawLine(gridColor.copy(alpha = 0.35f), Offset(0f, topY), Offset(size.width, topY), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            drawLine(gridColor.copy(alpha = 0.35f), Offset(0f, bottomY), Offset(size.width, bottomY), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            drawLine(gridColor, Offset(0f, centreY), Offset(size.width, centreY), strokeWidth = 1.dp.toPx())
            if (!ready) return@Canvas
            val step = size.width / (slots - 1).coerceAtLeast(1)
            // While gliding the newest point sits just past the right edge, so it slides into view.
            val shift = (1f - glide.value) * step
            drawSeries(history.map { it.downBps }, step, shift, easedTop, centreY, half, above = true, downColor)
            drawSeries(history.map { it.upBps }, step, shift, easedTop, centreY, half, above = false, upColor)
            touchX?.let { x ->
                val index = sampleAt(x, size.width, slots, history.size)
                val sx = size.width - step * (history.size - 1 - index)
                drawLine(labelColor.copy(alpha = 0.6f), Offset(sx, topY), Offset(sx, bottomY), strokeWidth = 1.dp.toPx())
                val sample = history[index]
                drawCircle(downColor, radius = 4.dp.toPx(), center = Offset(sx, mirroredY(sample.downBps, easedTop, centreY, half, above = true)))
                drawCircle(upColor, radius = 4.dp.toPx(), center = Offset(sx, mirroredY(sample.upBps, easedTop, centreY, half, above = false)))
            }
        }
        // Each half's full-scale value sits at its outer edge, on the right.
        Column(Modifier.fillMaxSize().padding(end = 2.dp), horizontalAlignment = Alignment.End) {
            MonoText("\u2193 ${axisLabel(top)}", color = downColor, size = 9.sp)
            Box(Modifier.weight(1f))
            MonoText("\u2191 ${axisLabel(top)}", color = upColor, size = 9.sp)
        }
        if (!ready) {
            Text(
                "collecting…",
                Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodySmall,
                color = labelColor,
            )
        }
        val x = touchX
        if (ready && x != null) {
            val index = sampleAt(x, widthPx, slots, history.size)
            val sample = history[index]
            val cardWidth = 150.dp
            val cardPx = with(LocalDensity.current) { cardWidth.toPx() }
            // The readout sits beside the marker, flipping sides so it never leaves the graph.
            val left = (if (x > widthPx / 2) x - cardPx - 12f else x + 12f)
                .coerceIn(0f, (widthPx - cardPx).coerceAtLeast(0f))
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                tonalElevation = 2.dp,
                modifier = Modifier.width(cardWidth).offset { IntOffset(left.roundToInt(), 0) },
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ReadoutLine("↓", formatRate(sample.downBps), downColor)
                    ReadoutLine("↑", formatRate(sample.upBps), upColor)
                    MonoText(ageLabel(history.size - 1 - index, sampleMs), color = labelColor, size = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun ReadoutLine(arrow: String, value: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        MonoText(arrow, color = color, size = 12.sp)
        MonoText(value, color = MaterialTheme.colorScheme.onSurface, size = 12.sp)
    }
}

private fun DrawScope.drawSeries(
    values: List<Long>,
    step: Float,
    shift: Float,
    scale: Float,
    centreY: Float,
    half: Float,
    above: Boolean,
    color: Color,
) {
    val startX = size.width - step * (values.size - 1) + shift
    val points = values.mapIndexed { index, value ->
        Offset(startX + step * index, mirroredY(value, scale, centreY, half, above))
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
    val area = Path().apply {
        addPath(line)
        lineTo(points.last().x, centreY)
        lineTo(points.first().x, centreY)
        close()
    }
    // Each fill is strongest at its peak and fades towards the centre line.
    val edge = if (above) centreY - half else centreY + half
    drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0.04f)), startY = edge, endY = centreY))
    drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}
