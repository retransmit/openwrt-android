package dev.openwrtmanager.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatTest {
    @Test
    fun rate_picks_a_readable_unit() {
        assertEquals("0 bps", formatRate(0))
        assertEquals("950 bps", formatRate(950))
        assertEquals("1.5 kbps", formatRate(1_500))
        assertEquals("42.3 Mbps", formatRate(42_300_000))
        assertEquals("1.20 Gbps", formatRate(1_200_000_000))
    }

    @Test
    fun rate_never_shows_negative() = assertEquals("0 bps", formatRate(-5))

    @Test
    fun uptime_shows_two_largest_units() {
        assertEquals("under 1m", formatUptime(42))
        assertEquals("5m", formatUptime(300))
        assertEquals("3h 12m", formatUptime(3 * 3600 + 12 * 60 + 5))
        assertEquals("1d 2h", formatUptime(26 * 3600 + 59 * 60))
    }

    @Test
    fun memory_used_percent_is_clamped() {
        assertEquals(41, memoryUsedPercent(totalBytes = 1000, availableBytes = 590))
        assertEquals(0, memoryUsedPercent(totalBytes = 0, availableBytes = 0))
        assertEquals(0, memoryUsedPercent(totalBytes = 100, availableBytes = 500))
    }

    @Test
    fun flash_percent() {
        assertEquals(25, flashUsedPercent(8000, 2000))
        assertNull(flashUsedPercent(0, 0))
        assertNull(flashUsedPercent(null, 10))
        assertEquals(100, flashUsedPercent(100, 150))
    }

    @Test
    fun bytes_pick_a_readable_unit() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("7.8 MB", formatBytes(8_178_892))
        assertEquals("1.2 GB", formatBytes(1_288_490_189))
    }
}
