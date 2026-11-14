package dev.openwrtmanager.app.domain

/** Bits per second. rx is download, tx is upload. */
data class Rate(val downBps: Long, val upBps: Long)

/** Turns successive byte counter readings into a transfer rate. Not thread safe. */
class RateSampler {
    private var last: Counters? = null
    private var lastAt: Long = 0

    /** Null on the first sample. Zero rate if either counter decreased or no time has passed. */
    fun sample(counters: Counters, atMillis: Long): Rate? {
        val previous = last
        val elapsed = atMillis - lastAt
        last = counters
        lastAt = atMillis
        if (previous == null) return null
        val rx = counters.rxBytes - previous.rxBytes
        val tx = counters.txBytes - previous.txBytes
        if (elapsed <= 0 || rx < 0 || tx < 0) return Rate(0, 0)
        return Rate(rx * 8_000 / elapsed, tx * 8_000 / elapsed)
    }

    fun reset() {
        last = null
        lastAt = 0
    }
}
