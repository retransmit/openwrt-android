package dev.openwrtmanager.app.domain

fun interface TimeSource {
    /** Monotonic milliseconds. */
    fun nowMillis(): Long
}
