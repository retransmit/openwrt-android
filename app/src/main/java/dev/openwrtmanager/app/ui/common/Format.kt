package dev.openwrtmanager.app.ui.common

import java.util.Locale

/** Bits per second as a short human string, for example "42.3 Mbps". */
fun formatRate(bps: Long): String {
    val value = bps.coerceAtLeast(0)
    return when {
        value < 1_000 -> "$value bps"
        value < 1_000_000 -> String.format(Locale.US, "%.1f kbps", value / 1_000.0)
        value < 1_000_000_000 -> String.format(Locale.US, "%.1f Mbps", value / 1_000_000.0)
        else -> String.format(Locale.US, "%.2f Gbps", value / 1_000_000_000.0)
    }
}

/** Uptime using its two largest units, for example "1d 2h". */
fun formatUptime(seconds: Long): String {
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "under 1m"
        hours < 1 -> "${minutes}m"
        days < 1 -> "${hours}h ${minutes % 60}m"
        else -> "${days}d ${hours % 24}h"
    }
}

fun memoryUsedPercent(totalBytes: Long, availableBytes: Long): Int {
    if (totalBytes <= 0) return 0
    val used = (totalBytes - availableBytes).coerceIn(0, totalBytes)
    return ((used * 100) / totalBytes).toInt()
}
