package dev.openwrtmanager.app.admin

enum class LogKind { SYSTEM, KERNEL }

private const val LINES = 300
private const val MAX_SHOWN = 1000

fun logCommand(kind: LogKind): String = when (kind) {
    LogKind.SYSTEM -> "logread -l $LINES"
    LogKind.KERNEL -> "dmesg | tail -n $LINES"
}

/**
 * Adds a fresh batch to what is on screen: the lines after the last occurrence of the last line
 * shown. When that line is gone (the log rotated, or nothing is shown yet) the batch replaces the view.
 * Keeps the newest [MAX_SHOWN] lines.
 */
fun mergeFollow(shown: List<String>, fresh: List<String>): List<String> {
    val anchor = shown.lastOrNull()
    val at = anchor?.let { fresh.lastIndexOf(it) } ?: -1
    val merged = if (at < 0) fresh else shown + fresh.drop(at + 1)
    return merged.takeLast(MAX_SHOWN)
}

fun filterLines(lines: List<String>, query: String): List<String> {
    val q = query.trim()
    return if (q.isEmpty()) lines else lines.filter { it.contains(q, ignoreCase = true) }
}
