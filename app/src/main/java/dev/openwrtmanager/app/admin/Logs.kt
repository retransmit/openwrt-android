package dev.openwrtmanager.app.admin

enum class LogKind { SYSTEM, KERNEL }

private const val LINES = 300
private const val MAX_SHOWN = 1000

fun logCommand(kind: LogKind): String = when (kind) {
    LogKind.SYSTEM -> "logread -l $LINES"
    LogKind.KERNEL -> "dmesg | tail -n $LINES"
}

/**
 * Adds a fresh batch to what is on screen. The anchor is the latest occurrence of the last shown line
 * whose earlier lines in the batch also match the end of what is shown, so a line that repeats (same
 * text in the same second) does not swallow the lines between. Without such an anchor (the log
 * rotated, or nothing is shown yet) the batch replaces the view. Keeps the newest [MAX_SHOWN] lines.
 */
fun mergeFollow(shown: List<String>, fresh: List<String>): List<String> {
    if (shown.isEmpty()) return fresh.takeLast(MAX_SHOWN)
    val last = shown.size - 1
    val at = fresh.indices.reversed().firstOrNull { p ->
        fresh[p] == shown[last] && (1..minOf(p, last)).all { j -> fresh[p - j] == shown[last - j] }
    } ?: -1
    val merged = if (at < 0) fresh else shown + fresh.drop(at + 1)
    return merged.takeLast(MAX_SHOWN)
}

fun filterLines(lines: List<String>, query: String): List<String> {
    val q = query.trim()
    return if (q.isEmpty()) lines else lines.filter { it.contains(q, ignoreCase = true) }
}
