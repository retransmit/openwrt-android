package dev.openwrtmanager.app.ui.home

import java.util.Locale
import kotlin.math.roundToInt

/**
 * The graph's top value: the peak rounded up to 1, 2 or 5 times a power of ten, at least 100 kbps,
 * so gridlines land on numbers people can read and the scale only moves in clear steps.
 */
fun niceScaleTop(peakBps: Long): Long {
    var magnitude = 100_000L
    while (true) {
        for (step in longArrayOf(1, 2, 5)) {
            val top = step * magnitude
            if (top >= peakBps) return top
        }
        magnitude *= 10
    }
}

/** A short gridline label in bits per second: "500k", "2.5M", "1G". */
fun axisLabel(bps: Long): String {
    fun trim(value: Double): String {
        val text = String.format(Locale.US, "%.1f", value)
        return if (text.endsWith(".0")) text.dropLast(2) else text
    }
    return when {
        bps <= 0 -> "0"
        bps < 1_000_000 -> "${trim(bps / 1_000.0)}k"
        bps < 1_000_000_000 -> "${trim(bps / 1_000_000.0)}M"
        else -> "${trim(bps / 1_000_000_000.0)}G"
    }
}

/**
 * Which sample a touch at [x] points at, for a graph [width] wide with [slots] positions and
 * [count] samples, the newest at the right edge. -1 when there are no samples.
 */
fun sampleAt(x: Float, width: Float, slots: Int, count: Int): Int {
    if (count <= 0) return -1
    val step = width / (slots - 1).coerceAtLeast(1)
    val back = ((width - x) / step).roundToInt()
    return (count - 1 - back).coerceIn(0, count - 1)
}

/** How long ago a sample [samplesBack] places from the newest was taken. */
fun ageLabel(samplesBack: Int, sampleMs: Long): String {
    val seconds = samplesBack * sampleMs / 1_000
    if (seconds <= 0) return "now"
    if (seconds < 60) return "$seconds s ago"
    val minutes = seconds / 60
    val rest = seconds % 60
    return if (rest == 0L) "$minutes min ago" else "$minutes min $rest s ago"
}
